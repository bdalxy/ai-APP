"""角色扮演对话主流程 - 整合角色卡、上下文、Prompt 和 API 调用。"""
from __future__ import annotations
from pathlib import Path
from typing import Any
from src.api_client.deepseek import ChatResponse, DeepSeekClient
from src.chat_engine.card_parser import Card, CardParseError, CardParser
from src.chat_engine.context_manager import ContextManager
from src.chat_engine.prompt_builder import PromptBuilder
from src.utils.logger import get_logger

class RolePlayerError(Exception):
    def __init__(self, message: str) -> None:
        super().__init__(message)

class RolePlayer:
    def __init__(self, client: DeepSeekClient, max_context_tokens: int = 4000, temperature: float = 0.9, max_tokens: int = 2000) -> None:
        self.client: DeepSeekClient = client
        self.card: Card | None = None
        self.context: ContextManager = ContextManager(max_tokens=max_context_tokens)
        self.prompt_builder: PromptBuilder = PromptBuilder()
        self.world_book_entries: list[str] = []
        self.memories: list[str] = []
        self.temperature: float = temperature
        self.max_tokens: int = max_tokens
        self._parser: CardParser = CardParser()
        self._log = get_logger()

    def load_card(self, card_path: str | Path) -> Card:
        self.card = self._parser.from_file(card_path)
        return self.card

    def load_world_book(self, world_book_path: str | Path) -> list[str]:
        import json5 as json5_lib
        path = Path(world_book_path).resolve()
        if not path.exists():
            raise FileNotFoundError(f"世界书文件不存在: {path}")
        raw_text = path.read_text(encoding="utf-8")
        data = json5_lib.loads(raw_text)
        entries = self._extract_world_book_entries(data)
        self.world_book_entries = entries
        return entries

    @staticmethod
    def _extract_world_book_entries(data: Any) -> list[str]:
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

    def inject_memories(self, memories: list[str]) -> None:
        self.memories = memories

    def clear_memories(self) -> None:
        self.memories = []

    def chat(self, user_input: str) -> str:
        if self.card is None:
            raise RolePlayerError("角色卡未加载")
        user_input = user_input.strip()
        if not user_input:
            return ""
        self.context.add_message("user", user_input)
        system_prompt = self.prompt_builder.build_system_prompt(card=self.card, world_book_entries=self.world_book_entries if self.world_book_entries else None, memories=self.memories if self.memories else None)
        messages = self.prompt_builder.build_messages(system_prompt, self.context)
        response: ChatResponse = self.client.chat(messages=messages, temperature=self.temperature, max_tokens=self.max_tokens)
        ai_reply = response.content
        self.context.add_message("assistant", ai_reply)
        return ai_reply

    def clear_context(self) -> None:
        self.context.clear()

    def get_context(self) -> list[dict[str, str]]:
        return self.context.get_context()

    def is_ready(self) -> bool:
        return self.card is not None

    def get_card_info(self) -> dict[str, str]:
        if self.card is None:
            raise RolePlayerError("角色卡未加载")
        return {"name": self.card.name, "nickname": self.card.nickname, "gender": self.card.gender}
