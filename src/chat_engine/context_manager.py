"""上下文管理器模块 - FIFO 队列管理短期对话历史。"""
from __future__ import annotations
from collections import deque
from src.utils.logger import get_logger

class ContextManager:
    _CHARS_PER_TOKEN: float = 2.5
    _SAFETY_MARGIN: float = 0.8

    def __init__(self, max_tokens: int = 4000) -> None:
        self.max_tokens: int = max_tokens
        self._history: deque[dict[str, str]] = deque()
        self._log = get_logger()

    def add_message(self, role: str, content: str) -> None:
        if not role:
            raise ValueError("消息角色 role 不能为空")
        if not content:
            self._log.warning(f"添加了空的 {role} 消息")
            return
        self._history.append({"role": role, "content": content})
        self._auto_trim()

    def get_context(self) -> list[dict[str, str]]:
        return list(self._history)

    def clear(self) -> None:
        self._history.clear()

    def get_token_estimate(self) -> int:
        total_chars = sum(len(msg.get("content", "")) for msg in self._history)
        return int(total_chars / self._CHARS_PER_TOKEN)

    def count(self) -> int:
        return len(self._history)

    def _auto_trim(self) -> None:
        if len(self._history) <= 1:
            return
        current_tokens = self.get_token_estimate()
        if current_tokens <= self.max_tokens:
            return
        target_tokens = int(self.max_tokens * self._SAFETY_MARGIN)
        while len(self._history) > 1 and self.get_token_estimate() > target_tokens:
            self._history.popleft()
