"""角色扮演对话主流程模块。

提供 RolePlayer 类，整合角色卡解析、上下文管理、Prompt 组装和
DeepSeek API 调用，实现完整的角色扮演对话流程。

数据流：
    1. 接收用户输入
    2. 添加到短期记忆（上下文管理器）
    3. 构建 System Prompt（角色卡 + 世界书 + 记忆）
    4. 组装完整 messages
    5. 调用 DeepSeek API
    6. 将 AI 回复添加到短期记忆
    7. 返回 AI 回复

依赖：
    - src.chat_engine.card_parser: CardParser, Card
    - src.chat_engine.context_manager: ContextManager
    - src.chat_engine.prompt_builder: PromptBuilder
    - src.api_client.deepseek: DeepSeekClient, ChatResponse
    - src.utils.logger: get_logger 日志实例

使用方式:
    player = RolePlayer(deepseek_client)
    player.load_card("data/role_cards/小美.json")
    player.load_world_book("data/world_book/settings.json")
    player.inject_memories(["用户之前提到过喜欢草莓蛋糕"])
    reply = player.chat("你好呀！")
    print(reply)
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from src.api_client.deepseek import ChatResponse, DeepSeekClient
from src.chat_engine.card_parser import Card, CardParseError, CardParser
from src.chat_engine.context_manager import ContextManager
from src.chat_engine.prompt_builder import PromptBuilder
from src.chat_engine.token_presets import TokenPreset, get_preset
from src.utils.logger import get_logger


class RolePlayerError(Exception):
    """角色扮演错误。

    在角色扮演流程中发生的异常，如角色卡未加载、API 调用失败等。
    """

    def __init__(self, message: str) -> None:
        """初始化角色扮演错误。

        Args:
            message: 错误描述信息。
        """
        super().__init__(message)


class RolePlayer:
    """角色扮演对话主流程。

    整合角色卡解析、上下文管理、Prompt 组装和 API 调用，
    提供简单易用的 chat() 接口。

    Attributes:
        client: DeepSeek API 客户端实例。
        card: 当前加载的角色卡（Card 对象）。
        context: 短期对话上下文管理器。
        prompt_builder: Prompt 组装器。
        world_book_entries: 当前激活的世界书条目列表。
        memories: 注入的记忆文本列表。
    """

    def __init__(
        self,
        client: DeepSeekClient,
        max_context_tokens: int = 4000,
        temperature: float = 0.9,
        max_tokens: int = 2000,
        preset: str | TokenPreset | None = None,
        model: str | None = None,
    ) -> None:
        """初始化角色扮演对话实例。

        Args:
            client: DeepSeek API 客户端实例。
            max_context_tokens: 上下文管理器最大 token 数。
            temperature: API 采样温度 (0~2)，角色扮演建议 0.8~1.0。
            max_tokens: API 每次回复最大 token 数。
            preset: Token 预设键名 ("quality"/"balanced"/"economy") 或 TokenPreset 实例。
                    设置后将覆盖 max_context_tokens/temperature/max_tokens。
            model: 模型名称，None 表示使用预设默认模型。
        """
        self.client: DeepSeekClient = client

        # 如果提供了预设，应用预设配置
        if preset is not None:
            if isinstance(preset, str):
                preset = get_preset(preset)
            self._preset: TokenPreset = preset
            max_context_tokens = preset.context_window
            temperature = preset.temperature
            max_tokens = preset.max_tokens
            # 用户指定的模型优先于预设模型
            self.client._chat_model = model if model else preset.model
        else:
            self._preset = None
            if model:
                self.client._chat_model = model

        self.card: Card | None = None
        self.context: ContextManager = ContextManager(max_tokens=max_context_tokens)
        self.prompt_builder: PromptBuilder = self._create_prompt_builder()
        self.world_book_entries: list[str] = []
        self.memories: list[str] = []
        self.temperature: float = temperature
        self.max_tokens: int = max_tokens
        self._parser: CardParser = CardParser()
        self._log = get_logger()

        self._log.info(
            f"RolePlayer 初始化完成: temperature={temperature}, "
            f"max_context_tokens={max_context_tokens}, max_tokens={max_tokens}"
            + (f", preset={self._preset.key}" if self._preset else "")
        )

    def _create_prompt_builder(self) -> PromptBuilder:
        """根据预设配置创建 PromptBuilder。

        Returns:
            配置好的 PromptBuilder 实例。
        """
        if self._preset is not None:
            return PromptBuilder(
                max_system_prompt_chars=self._preset.system_prompt_chars,
                card_max_chars=self._preset.card_max_chars,
                world_book_max_chars=self._preset.world_book_max_chars,
                memories_max_chars=self._preset.memories_max_chars,
                guideline_max_chars=self._preset.guideline_max_chars,
                max_example_dialogues=self._preset.max_example_dialogues,
                include_guideline=self._preset.include_guideline,
                include_creator_notes=self._preset.include_creator_notes,
            )
        return PromptBuilder()

    # -------------------------------------------------------------------------
    # 角色卡加载
    # -------------------------------------------------------------------------

    def load_card(self, card_path: str | Path) -> Card:
        """加载角色卡文件。

        Args:
            card_path: 角色卡文件路径（JSON/JSON5 格式）。

        Returns:
            解析后的 Card 对象。

        Raises:
            CardParseError: 角色卡解析失败。
            FileNotFoundError: 角色卡文件不存在。
        """
        self.card = self._parser.from_file(card_path)
        self._log.info(f"角色卡已加载: {self.card.name}")
        return self.card

    # -------------------------------------------------------------------------
    # 世界书加载
    # -------------------------------------------------------------------------

    def load_world_book(self, world_book_path: str | Path) -> list[str]:
        """加载世界书文件。

        世界书文件为 JSON 格式，可以是一个条目列表或包含 "entries" 键的对象。
        每个条目可以是字符串或包含 "content" 键的字典。

        Args:
            world_book_path: 世界书文件路径。

        Returns:
            解析后的世界书条目列表。

        Raises:
            FileNotFoundError: 文件不存在时。
            CardParseError: JSON 解析失败时。
        """
        import json5 as json5_lib

        path = Path(world_book_path).resolve()
        self._log.info(f"正在加载世界书: {path}")

        if not path.exists():
            raise FileNotFoundError(f"世界书文件不存在: {path}")

        try:
            raw_text = path.read_text(encoding="utf-8")
        except Exception as e:
            raise CardParseError(f"读取世界书文件失败: {e}", str(path))

        try:
            data = json5_lib.loads(raw_text)
        except ValueError as e:
            raise CardParseError(f"世界书 JSON 解析失败: {e}", str(path))

        entries: list[str] = self._extract_world_book_entries(data)
        self.world_book_entries = entries
        self._log.info(f"世界书已加载: {len(entries)} 条")
        return entries

    @staticmethod
    def _extract_world_book_entries(data: Any) -> list[str]:
        """从世界书数据中提取条目列表。

        Args:
            data: 世界书原始数据。

        Returns:
            条目字符串列表。
        """
        # 如果是列表，提取 content 或直接使用
        if isinstance(data, list):
            entries: list[str] = []
            for item in data:
                if isinstance(item, dict):
                    content = item.get("content", "")
                    if content:
                        entries.append(str(content))
                elif isinstance(item, str):
                    entries.append(item)
            return entries

        # 如果是字典，尝试 entries 键
        if isinstance(data, dict):
            entries_list = data.get("entries", data.get("world_book", []))
            if isinstance(entries_list, list):
                result: list[str] = []
                for item in entries_list:
                    if isinstance(item, dict):
                        content = item.get("content", "")
                        if content:
                            result.append(str(content))
                    elif isinstance(item, str):
                        result.append(item)
                return result
            return []

        return []

    # -------------------------------------------------------------------------
    # 记忆注入
    # -------------------------------------------------------------------------

    def inject_memories(self, memories: list[str]) -> None:
        """注入记忆文本，用于增强 System Prompt。

        记忆会在每次构建 System Prompt 时被纳入。

        Args:
            memories: 记忆文本列表。
        """
        self.memories = memories
        self._log.debug(f"记忆已注入: {len(memories)} 条")

    def clear_memories(self) -> None:
        """清空所有注入的记忆。"""
        self.memories = []
        self._log.info("记忆已清空")

    # -------------------------------------------------------------------------
    # 对话主流程
    # -------------------------------------------------------------------------

    def chat(self, user_input: str) -> str:
        """执行一轮角色扮演对话。

        完整流程：
        1. 将用户输入添加到短期记忆
        2. 构建 System Prompt
        3. 组装 messages
        4. 调用 DeepSeek API
        5. 将 AI 回复添加到短期记忆
        6. 返回 AI 回复

        Args:
            user_input: 用户输入文本。

        Returns:
            AI 角色回复文本。

        Raises:
            RolePlayerError: 角色卡未加载时。
            APIException: API 调用失败时（由 DeepSeekClient 抛出）。
        """
        if self.card is None:
            raise RolePlayerError("角色卡未加载，请先调用 load_card()")

        user_input = user_input.strip()
        if not user_input:
            self._log.warning("用户输入为空，跳过")
            return ""

        self._log.debug(f"[对话] 收到用户输入: {user_input[:50]}...")

        # 1. 添加用户输入到上下文
        self.context.add_message("user", user_input)

        # 2. 构建 System Prompt
        system_prompt = self.prompt_builder.build_system_prompt(
            card=self.card,
            world_book_entries=self.world_book_entries if self.world_book_entries else None,
            memories=self.memories if self.memories else None,
        )

        # 3. 组装完整 messages
        messages = self.prompt_builder.build_messages(system_prompt, self.context)

        # 4. 调用 DeepSeek API
        response: ChatResponse = self.client.chat(
            messages=messages,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
        )

        ai_reply = response.content

        # 5. 将 AI 回复添加到上下文
        self.context.add_message("assistant", ai_reply)

        self._log.debug(
            f"[对话] AI 回复: {ai_reply[:50]}... "
            f"(tokens={response.usage['total_tokens']})"
        )

        return ai_reply

    # -------------------------------------------------------------------------
    # 上下文管理
    # -------------------------------------------------------------------------

    def clear_context(self) -> None:
        """清空短期对话上下文，开始新对话。"""
        self.context.clear()
        self._log.info("对话上下文已重置")

    def get_context(self) -> list[dict[str, str]]:
        """获取当前对话上下文。

        Returns:
            消息列表。
        """
        return self.context.get_context()

    def get_context_token_estimate(self) -> int:
        """获取上下文 token 估算数。

        Returns:
            估算的 token 数。
        """
        return self.context.get_token_estimate()

    # -------------------------------------------------------------------------
    # 状态查询
    # -------------------------------------------------------------------------

    def is_ready(self) -> bool:
        """检查是否准备好进行对话（角色卡已加载）。

        Returns:
            True 表示可以开始对话。
        """
        return self.card is not None

    def get_card_info(self) -> dict[str, str]:
        """获取当前加载的角色卡摘要信息。

        Returns:
            包含 name, nickname, gender 的摘要字典。

        Raises:
            RolePlayerError: 角色卡未加载时。
        """
        if self.card is None:
            raise RolePlayerError("角色卡未加载")
        return {
            "name": self.card.name,
            "nickname": self.card.nickname,
            "gender": self.card.gender,
        }
