"""Prompt 组装器模块。

负责将角色卡、世界书条目、检索记忆和对话风格指引组装为
完整的 System Prompt，并拼接上下文消息列表为 API 可用的 messages 格式。

System Prompt 结构：
    1. 角色卡信息（角色设定）
    2. 世界书内容（世界观/场景补充）
    3. 检索到的相关记忆
    4. 对话风格指引

依赖：
    - src.chat_engine.card_parser: Card 类
    - src.chat_engine.context_manager: ContextManager 类
    - src.utils.logger: get_logger 日志实例

使用方式:
    builder = PromptBuilder()
    system_prompt = builder.build_system_prompt(card, world_book_entries, memories)
    messages = builder.build_messages(system_prompt, context_manager)
"""

from __future__ import annotations

from src.chat_engine.card_parser import Card
from src.chat_engine.context_manager import ContextManager
from src.utils.logger import get_logger


class PromptBuilder:
    """Prompt 组装器。

    将角色卡、世界书、记忆和对话上下文组装为 DeepSeek API 可用的 messages 列表。
    严格控制 System Prompt 长度，避免超出 context window。

    Attributes:
        max_system_prompt_chars: System Prompt 最大字符数限制（默认 2000）。
        max_example_dialogues: 最多包含的示例对话条数（默认 2）。
        include_guideline: 是否包含对话指引（默认 True）。
        include_creator_notes: 是否包含创作者备注（默认 False）。
        _log: 日志实例。
    """

    # System Prompt 最大字符数限制
    MAX_SYSTEM_PROMPT_CHARS: int = 2000

    # 各部分的字符数上限
    _CARD_MAX_CHARS: int = 1200
    _WORLD_BOOK_MAX_CHARS: int = 400
    _MEMORIES_MAX_CHARS: int = 300
    _GUIDELINE_MAX_CHARS: int = 200

    def __init__(
        self,
        max_system_prompt_chars: int = 2000,
        card_max_chars: int = 1200,
        world_book_max_chars: int = 400,
        memories_max_chars: int = 300,
        guideline_max_chars: int = 200,
        max_example_dialogues: int = 2,
        include_guideline: bool = True,
        include_creator_notes: bool = False,
    ) -> None:
        """初始化 Prompt 组装器。

        Args:
            max_system_prompt_chars: System Prompt 最大字符数。
            card_max_chars: 角色卡部分最大字符数。
            world_book_max_chars: 世界书部分最大字符数。
            memories_max_chars: 记忆部分最大字符数。
            guideline_max_chars: 对话指引部分最大字符数。
            max_example_dialogues: 最多包含的示例对话条数。
            include_guideline: 是否包含对话指引。
            include_creator_notes: 是否包含创作者备注。
        """
        self.max_system_prompt_chars = max_system_prompt_chars
        self._card_max_chars = card_max_chars
        self._world_book_max_chars = world_book_max_chars
        self._memories_max_chars = memories_max_chars
        self._guideline_max_chars = guideline_max_chars
        self.max_example_dialogues = max_example_dialogues
        self.include_guideline = include_guideline
        self.include_creator_notes = include_creator_notes
        self._log = get_logger()
        self._log.debug(
            f"PromptBuilder 初始化: system={max_system_prompt_chars}chars, "
            f"card={card_max_chars}chars, example_dialogues<={max_example_dialogues}"
        )

    # -------------------------------------------------------------------------
    # 公开方法
    # -------------------------------------------------------------------------

    def build_system_prompt(
        self,
        card: Card,
        world_book_entries: list[str] | None = None,
        memories: list[str] | None = None,
    ) -> str:
        """构建完整的 System Prompt。

        按优先级组装各部分内容，超出限制时自动截断低优先级部分。

        Args:
            card: 角色卡对象（必须提供）。
            world_book_entries: 激活的世界书条目列表，每条为一个字符串。
            memories: 检索到的记忆文本列表，每条为一个字符串。

        Returns:
            完整的 System Prompt 字符串。
        """
        sections: list[tuple[str, str]] = []

        # 1. 角色卡信息（最高优先级），限制示例对话数量
        card_text = card.to_prompt_text(
            max_example_dialogues=self.max_example_dialogues,
            include_creator_notes=self.include_creator_notes,
        )
        sections.append(("card", self._truncate(card_text, self._card_max_chars)))

        # 2. 世界书内容
        if world_book_entries:
            world_text = self._build_world_book_section(world_book_entries)
            sections.append(
                ("world_book", self._truncate(world_text, self._world_book_max_chars))
            )

        # 3. 检索到的记忆
        if memories:
            memory_text = self._build_memories_section(memories)
            sections.append(
                ("memories", self._truncate(memory_text, self._memories_max_chars))
            )

        # 4. 对话风格指引（可选）
        if self.include_guideline:
            guideline_text = self._build_guideline_section()
            sections.append(
                ("guideline", self._truncate(guideline_text, self._guideline_max_chars))
            )

        # 组装并做最终裁剪
        system_prompt = "\n\n".join(text for _, text in sections)
        system_prompt = self._truncate(system_prompt, self.max_system_prompt_chars)

        self._log.debug(
            f"System Prompt 构建完成: {len(system_prompt)} 字符, "
            f"约 {len(system_prompt) // 2} tokens"
        )
        return system_prompt

    def build_messages(
        self,
        system_prompt: str,
        context: ContextManager | list[dict[str, str]],
    ) -> list[dict[str, str]]:
        """组装完整的 messages 列表（system + 对话历史）。

        Args:
            system_prompt: System Prompt 文本。
            context: ContextManager 实例或消息列表。

        Returns:
            [{"role": "system", "content": system_prompt}, ...messages]
            格式的消息列表，可直接传入 DeepSeekClient.chat()。
        """
        messages: list[dict[str, str]] = [
            {"role": "system", "content": system_prompt}
        ]

        # 获取对话历史
        if isinstance(context, ContextManager):
            history = context.get_context()
        else:
            history = context

        messages.extend(history)
        self._log.debug(f"Messages 组装完成: 共 {len(messages)} 条消息")
        return messages

    # -------------------------------------------------------------------------
    # 内部方法
    # -------------------------------------------------------------------------

    @staticmethod
    def _build_world_book_section(entries: list[str]) -> str:
        """构建世界书部分文本。

        Args:
            entries: 世界书条目列表。

        Returns:
            格式化的世界书文本。
        """
        lines = ["## 世界设定\n"]
        for i, entry in enumerate(entries, 1):
            lines.append(f"{i}. {entry}")
        return "\n".join(lines)

    @staticmethod
    def _build_memories_section(memories: list[str]) -> str:
        """构建记忆部分文本。

        Args:
            memories: 记忆文本列表。

        Returns:
            格式化的记忆文本。
        """
        lines = [
            "## 关于对方的已知信息\n",
            "以下是你之前了解到的关于对方的信息。请在对话中自然地运用，",
            "不要逐条机械复述，也不要说[根据我的记忆]之类的话——",
            "就像真正的朋友一样，在不经意间自然地提及。\n",
        ]
        for i, memory in enumerate(memories, 1):
            lines.append(f"- {memory}")
        return "\n".join(lines)

    @staticmethod
    def _build_guideline_section() -> str:
        """构建对话风格指引部分。

        Returns:
            对话风格指引文本。
        """
        lines = [
            "## 对话指引\n",
            "1. 严格遵循角色设定，以第一人称进行对话。",
            "2. 保持角色性格和说话风格的一致性。",
            "3. 回应要自然、有情感，不要机械或过于正式。",
            "4. 不要打破角色设定或提及角色设定本身，用角色的方式自然回应。",
            "5. 回复长度适中，根据上下文灵活调整。",
        ]
        return "\n".join(lines)

    @staticmethod
    def _truncate(text: str, max_chars: int) -> str:
        """按最大字符数截断文本。

        尽可能在换行符处截断，避免截断在句子中间。

        Args:
            text: 原始文本。
            max_chars: 最大字符数。

        Returns:
            截断后的文本。如果原始文本未超限，返回原文本。
        """
        if len(text) <= max_chars:
            return text

        # 在 max_chars 范围内找最后一个换行符
        truncated = text[:max_chars]
        last_newline = truncated.rfind("\n")
        if last_newline > max_chars * 0.7:
            return truncated[:last_newline].rstrip()

        return truncated.rstrip()
