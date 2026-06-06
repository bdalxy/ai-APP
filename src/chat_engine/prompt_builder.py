"""Prompt 组装器 - 组装 System Prompt 和 messages。"""
from __future__ import annotations
from src.chat_engine.card_parser import Card
from src.chat_engine.context_manager import ContextManager
from src.utils.logger import get_logger

class PromptBuilder:
    MAX_SYSTEM_PROMPT_CHARS: int = 2000
    _CARD_MAX_CHARS: int = 1200
    _WORLD_BOOK_MAX_CHARS: int = 400
    _MEMORIES_MAX_CHARS: int = 300
    _GUIDELINE_MAX_CHARS: int = 200

    def __init__(self) -> None:
        self._log = get_logger()

    def build_system_prompt(self, card: Card, world_book_entries: list[str] | None = None, memories: list[str] | None = None) -> str:
        sections: list[tuple[str, str]] = []
        card_text = card.to_prompt_text()
        sections.append(("card", self._truncate(card_text, self._CARD_MAX_CHARS)))
        if world_book_entries:
            world_text = "## 世界设定\n" + "\n".join(f"{i}. {entry}" for i, entry in enumerate(world_book_entries, 1))
            sections.append(("world_book", self._truncate(world_text, self._WORLD_BOOK_MAX_CHARS)))
        if memories:
            memory_text = "## 相关记忆\n" + "\n".join(f"- {m}" for m in memories)
            sections.append(("memories", self._truncate(memory_text, self._MEMORIES_MAX_CHARS)))
        guideline_text = "## 对话指引\n" + "\n".join(["1. 严格遵循角色设定，以第一人称进行对话。", "2. 保持角色性格和说话风格的一致性。", "3. 回应要自然、有情感，不要机械或过于正式。", "4. 不要打破角色设定。", "5. 回复长度适中，根据上下文灵活调整。"])
        sections.append(("guideline", self._truncate(guideline_text, self._GUIDELINE_MAX_CHARS)))
        system_prompt = "\n\n".join(text for _, text in sections)
        return self._truncate(system_prompt, self.MAX_SYSTEM_PROMPT_CHARS)

    def build_messages(self, system_prompt: str, context: ContextManager | list[dict[str, str]]) -> list[dict[str, str]]:
        messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]
        if isinstance(context, ContextManager):
            history = context.get_context()
        else:
            history = context
        messages.extend(history)
        return messages

    @staticmethod
    def _truncate(text: str, max_chars: int) -> str:
        if len(text) <= max_chars:
            return text
        truncated = text[:max_chars]
        last_newline = truncated.rfind("\n")
        if last_newline > max_chars * 0.7:
            return truncated[:last_newline].rstrip()
        return truncated.rstrip()
