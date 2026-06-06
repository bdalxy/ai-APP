"""角色卡解析器模块 - 解析 JSON/JSON5 格式角色卡文件。"""
from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import json5
from src.utils.logger import get_logger

@dataclass
class Card:
    version: str = "1.0"
    name: str = ""
    nickname: str = ""
    age: str = ""
    gender: str = ""
    appearance: str = ""
    personality: str = ""
    background: str = ""
    speaking_style: str = ""
    likes: list[str] = field(default_factory=list)
    dislikes: list[str] = field(default_factory=list)
    example_dialogues: list[dict[str, str]] = field(default_factory=list)
    creator_notes: str = ""
    tags: list[str] = field(default_factory=list)

    def to_prompt_text(self) -> str:
        lines: list[str] = []
        lines.append("## 角色信息\n")
        lines.append(f"- 姓名：{self.name}")
        if self.nickname:
            lines.append(f"- 昵称：{self.nickname}")
        if self.gender:
            lines.append(f"- 性别：{self.gender}")
        if self.age:
            lines.append(f"- 年龄：{self.age}")
        if self.appearance:
            lines.append(f"- 外貌：{self.appearance}")
        if self.personality:
            lines.append(f"\n### 性格\n{self.personality}")
        if self.background:
            lines.append(f"\n### 背景\n{self.background}")
        if self.likes:
            lines.append(f"\n### 喜好\n- " + "\n- ".join(self.likes))
        if self.dislikes:
            lines.append(f"\n### 厌恶\n- " + "\n- ".join(self.dislikes))
        if self.speaking_style:
            lines.append(f"\n### 说话风格\n{self.speaking_style}")
        if self.example_dialogues:
            lines.append("\n### 示例对话")
            for i, dialogue in enumerate(self.example_dialogues, 1):
                user_text = dialogue.get("user", "")
                char_text = dialogue.get("character", "")
                lines.append(f"{i}. 用户：{user_text}")
                lines.append(f"   {self.name}：{char_text}")
        if self.creator_notes:
            lines.append(f"\n### 创作者备注\n{self.creator_notes}")
        return "\n".join(lines)

_REQUIRED_FIELDS: tuple[str, ...] = ("name", "personality", "background", "speaking_style")

class CardParseError(Exception):
    def __init__(self, message: str, file_path: str | None = None) -> None:
        prefix = f"[{file_path}] " if file_path else ""
        super().__init__(f"{prefix}{message}")
        self.file_path = file_path

class CardParser:
    def __init__(self) -> None:
        self._log = get_logger()

    def from_file(self, file_path: str | Path) -> Card:
        path = Path(file_path).resolve()
        if not path.exists():
            raise FileNotFoundError(f"角色卡文件不存在: {path}")
        try:
            raw_text = path.read_text(encoding="utf-8")
        except Exception as e:
            raise CardParseError(f"读取文件失败: {e}", str(path))
        try:
            data = json5.loads(raw_text)
        except ValueError as e:
            raise CardParseError(f"JSON 解析失败: {e}", str(path))
        if not isinstance(data, dict):
            raise CardParseError("角色卡根元素必须是一个 JSON 对象", str(path))
        return self.from_dict(data, str(path))

    def from_dict(self, data: dict[str, Any], source: str = "dict") -> Card:
        card_data = data.get("card", data)
        if "card" in data and isinstance(data.get("card"), dict):
            version = data.get("version", "1.0")
            card_data = data["card"]
        else:
            version = data.get("version", "1.0")
        self._validate_required_fields(card_data, source)
        return Card(version=str(version), name=str(card_data.get("name", "")), nickname=str(card_data.get("nickname", "")), age=str(card_data.get("age", "")), gender=str(card_data.get("gender", "")), appearance=str(card_data.get("appearance", "")), personality=str(card_data.get("personality", "")), background=str(card_data.get("background", "")), speaking_style=str(card_data.get("speaking_style", "")), likes=self._parse_string_list(card_data.get("likes", [])), dislikes=self._parse_string_list(card_data.get("dislikes", [])), example_dialogues=self._parse_dialogues(card_data.get("example_dialogues", [])), creator_notes=str(card_data.get("creator_notes", "")), tags=self._parse_string_list(card_data.get("tags", [])))

    def _validate_required_fields(self, card_data: dict[str, Any], source: str) -> None:
        missing: list[str] = []
        for field in _REQUIRED_FIELDS:
            value = card_data.get(field, "")
            if not value or not str(value).strip():
                missing.append(field)
        if missing:
            raise CardParseError(f"缺少必填字段: {', '.join(missing)}", source)

    @staticmethod
    def _parse_string_list(value: Any) -> list[str]:
        if not isinstance(value, list):
            return []
        return [str(item).strip() for item in value if str(item).strip()]

    @staticmethod
    def _parse_dialogues(value: Any) -> list[dict[str, str]]:
        if not isinstance(value, list):
            return []
        result: list[dict[str, str]] = []
        for item in value:
            if isinstance(item, dict):
                dialogue: dict[str, str] = {"user": str(item.get("user", "")), "character": str(item.get("character", ""))}
                if dialogue["user"].strip() and dialogue["character"].strip():
                    result.append(dialogue)
        return result
