"""角色卡解析器模块。

负责解析 JSON/JSON5 格式的角色卡文件，验证必填字段，
将解析结果封装为 Card 对象，并支持生成用于 System Prompt 的角色描述文本。

依赖：
    - json5: 支持 JSON5 格式（尾逗号、注释等）的 JSON 解析库
    - src.utils.logger: get_logger 日志实例

使用方式:
    parser = CardParser()
    card = parser.from_file("data/role_cards/小美.json")
    prompt_text = card.to_prompt_text()
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import json

from src.utils.logger import get_logger


# =============================================================================
# Card 数据结构
# =============================================================================


@dataclass
class Card:
    """角色卡数据类。

    存储角色卡的完整解析结果，提供 to_prompt_text() 方法
    将角色信息格式化为可直接注入 System Prompt 的文本。

    Attributes:
        version: 角色卡格式版本号，如 "1.0"。
        name: 角色名（必填）。
        nickname: 昵称，默认为空字符串。
        age: 年龄，默认为空字符串。
        gender: 性别，默认为空字符串。
        appearance: 外貌描述，默认为空字符串。
        personality: 性格描述（必填）。
        background: 背景故事（必填）。
        speaking_style: 说话风格（必填）。
        likes: 喜好列表。
        dislikes: 厌恶列表。
        example_dialogues: 示例对话列表，每项为 {"user": "...", "character": "..."}。
        creator_notes: 创作者备注，默认为空字符串。
        greeting: 开场白/问候语（Kotlin 侧 CharacterData 传入）。
        tags: 标签列表。
    """

    version: str = "1.0"
    name: str = ""
    nickname: str = ""
    age: str = ""
    gender: str = ""
    appearance: str = ""
    personality: str = ""
    background: str = ""
    speaking_style: str = ""
    greeting: str = ""  # 开场白，Kotlin 侧 CharacterData 传入
    likes: list[str] = field(default_factory=list)
    dislikes: list[str] = field(default_factory=list)
    example_dialogues: list[dict[str, str]] = field(default_factory=list)
    creator_notes: str = ""
    tags: list[str] = field(default_factory=list)

    def to_prompt_text(self) -> str:
        """将角色卡信息格式化为 System Prompt 可用的角色描述文本。

        Returns:
            格式化的角色描述字符串，包含姓名、性别、年龄、性格、
            背景、说话风格、喜好、厌恶和示例对话。
        """
        lines: list[str] = []

        # === 基本信息 ===
        lines.append(f"## 角色信息\n")
        lines.append(f"- 姓名：{self.name}")
        if self.nickname:
            lines.append(f"- 昵称：{self.nickname}")
        if self.gender:
            lines.append(f"- 性别：{self.gender}")
        if self.age:
            lines.append(f"- 年龄：{self.age}")
        if self.appearance:
            lines.append(f"- 外貌：{self.appearance}")

        # === 性格 ===
        if self.personality:
            lines.append(f"\n### 性格\n{self.personality}")

        # === 背景 ===
        if self.background:
            lines.append(f"\n### 背景\n{self.background}")

        # === 喜好与厌恶 ===
        if self.likes:
            lines.append(f"\n### 喜好\n- " + "\n- ".join(self.likes))
        if self.dislikes:
            lines.append(f"\n### 厌恶\n- " + "\n- ".join(self.dislikes))

        # === 说话风格 ===
        if self.speaking_style:
            lines.append(f"\n### 说话风格\n{self.speaking_style}")

        # === 示例对话 ===
        if self.example_dialogues:
            lines.append(f"\n### 示例对话")
            for i, dialogue in enumerate(self.example_dialogues, 1):
                user_text = dialogue.get("user", "")
                char_text = dialogue.get("character", "")
                lines.append(f"{i}. 用户：{user_text}")
                lines.append(f"   {self.name}：{char_text}")

        # === 创作者备注 ===
        if self.creator_notes:
            lines.append(f"\n### 创作者备注\n{self.creator_notes}")

        return "\n".join(lines)


# =============================================================================
# CardParser 解析器
# =============================================================================

# 必填字段列表
_REQUIRED_FIELDS: tuple[str, ...] = ("name", "personality", "background", "speaking_style")


class CardParseError(Exception):
    """角色卡解析异常。

    当 JSON 解析失败、必填字段缺失或数据格式不正确时抛出。
    """

    def __init__(self, message: str, file_path: str | None = None) -> None:
        """初始化角色卡解析异常。

        Args:
            message: 错误描述信息。
            file_path: 出错的角色卡文件路径（可选）。
        """
        prefix = f"[{file_path}] " if file_path else ""
        super().__init__(f"{prefix}{message}")
        self.file_path = file_path


class CardParser:
    """角色卡解析器。

    支持从 JSON/JSON5 文件或字典解析角色卡，
    自动验证必填字段，返回 Card 实例。

    使用方式:
        parser = CardParser()
        card = parser.from_file("小美.json")
        card = parser.from_dict(data_dict)
    """

    def __init__(self) -> None:
        """初始化角色卡解析器。"""
        self._log = get_logger()

    # -------------------------------------------------------------------------
    # 公开方法
    # -------------------------------------------------------------------------

    def from_file(self, file_path: str | Path) -> Card:
        """从 JSON/JSON5 文件解析角色卡。

        Args:
            file_path: 角色卡文件路径（字符串或 Path 对象）。

        Returns:
            Card: 解析后的角色卡对象。

        Raises:
            CardParseError: 文件读取失败、JSON 解析失败或必填字段缺失时。
            FileNotFoundError: 文件不存在时。
        """
        path = Path(file_path).resolve()
        self._log.info(f"正在加载角色卡: {path}")

        if not path.exists():
            raise FileNotFoundError(f"角色卡文件不存在: {path}")

        try:
            raw_text = path.read_text(encoding="utf-8")
        except Exception as e:
            raise CardParseError(f"读取文件失败: {e}", str(path))

        try:
            data = json.loads(raw_text)
        except ValueError:
            # JSON 解析失败时尝试 JSON5（支持尾逗号、注释等）
            try:
                import json5 as json5_lib
                data = json5_lib.loads(raw_text)
            except (ImportError, ValueError) as e2:
                raise CardParseError(f"JSON/JSON5 解析失败: {e2}", str(path))

        if not isinstance(data, dict):
            raise CardParseError("角色卡根元素必须是一个 JSON 对象", str(path))

        return self.from_dict(data, str(path))

    def from_dict(self, data: dict[str, Any], source: str = "dict") -> Card:
        """从字典解析角色卡。

        Args:
            data: 包含角色卡数据的字典。
            source: 数据来源标识（用于错误消息，如文件路径或 "dict"）。

        Returns:
            Card: 解析后的角色卡对象。

        Raises:
            CardParseError: 必填字段缺失时。
        """
        # 提取 card 子对象（如果有外层包装）
        card_data = data.get("card", data)

        # 如果 card 字段不存在，但 data 中可能直接就是 card 数据
        if "card" in data and isinstance(data.get("card"), dict):
            version = data.get("version", "1.0")
            card_data = data["card"]
        else:
            version = data.get("version", "1.0")

        self._validate_required_fields(card_data, source)

        return Card(
            version=str(version),
            name=str(card_data.get("name", "")),
            nickname=str(card_data.get("nickname", "")),
            age=str(card_data.get("age", "")),
            gender=str(card_data.get("gender", "")),
            appearance=str(card_data.get("appearance", "")),
            personality=str(card_data.get("personality", "")),
            background=str(card_data.get("background", "")),
            speaking_style=str(card_data.get("speaking_style", "")),
            greeting=str(card_data.get("greeting", "")),
            likes=self._parse_string_list(card_data.get("likes", [])),
            dislikes=self._parse_string_list(card_data.get("dislikes", [])),
            example_dialogues=self._parse_dialogues(card_data.get("example_dialogues", [])),
            creator_notes=str(card_data.get("creator_notes", "")),
            tags=self._parse_string_list(card_data.get("tags", [])),
        )

    def from_character_data(self, char_data: dict[str, Any], output_dir: str | Path | None = None) -> Card:
        """从 Kotlin CharacterData 字典构建 Card 并可选持久化为 JSON 文件。

        这是 Kotlin 侧角色数据与 Python 侧 Card 模型的核心桥接方法。
        将 CharacterData 的扁平结构映射到 Card 的完整字段，
        缺失的扩展字段（nickname/age/gender/appearance/likes/dislikes/example_dialogues）
        使用合理的空默认值，确保 Card 始终可用。

        Args:
            char_data: 来自 Kotlin CharacterData 的字典，必须包含 name/personality/
                       speaking_style/backstory/greeting 等字段。
            output_dir: 可选，如果提供则将 Card 序列化为 JSON 写入此目录，
                        文件名为 "{name}.json"。用于覆盖 Python 侧默认角色卡文件。

        Returns:
            构建好的 Card 对象。

        Raises:
            CardParseError: 必填字段缺失时。
        """
        # 字段映射：CharacterData -> Card
        # CharacterData: name, personality, speakingStyle, backstory, greeting, ...
        # Card: name, personality, speaking_style, background, greeting, ...
        card_dict = {
            "version": "1.0",
            "name": char_data.get("name", ""),
            "nickname": char_data.get("nickname", char_data.get("name", "")),
            "age": char_data.get("age", ""),
            "gender": char_data.get("gender", ""),
            "appearance": char_data.get("appearance", ""),
            "personality": char_data.get("personality", ""),
            "background": char_data.get("backstory", char_data.get("background", "")),
            "speaking_style": char_data.get("speaking_style", char_data.get("speakingStyle", "")),
            "greeting": char_data.get("greeting", ""),
            "likes": char_data.get("likes", []),
            "dislikes": char_data.get("dislikes", []),
            "example_dialogues": char_data.get("example_dialogues", []),
            "creator_notes": char_data.get("creator_notes", ""),
            "tags": char_data.get("tags", []),
        }

        card = self.from_dict(card_dict, source=f"CharacterData:{card_dict['name']}")

        # 持久化到 Python 角色卡目录（如果提供了 output_dir）
        if output_dir is not None:
            self._save_card_to_file(card, Path(output_dir))

        extra_count = sum(1 for f in ['nickname', 'age', 'gender', 'appearance'] if getattr(card, f))
        self._log.info(
            f"从 CharacterData 构建 Card 完成: name={card.name}, "
            f"greeting={'有' if card.greeting else '无'}, "
            f"extra_fields={extra_count} 个扩展字段"
        )
        return card

    @staticmethod
    def _save_card_to_file(card: Card, output_dir: Path) -> None:
        """将 Card 序列化保存为 JSON 文件（{name}.json）。

        写入格式与 CardParser.from_file() 兼容，即包含 version 和 card 包装。

        Args:
            card: 要保存的 Card 对象。
            output_dir: 输出目录路径。
        """
        import json as json_lib  # 避免与 top-level import 冲突

        output_dir.mkdir(parents=True, exist_ok=True)
        file_path = output_dir / f"{card.name}.json"

        data: dict[str, Any] = {
            "version": card.version,
            "card": {
                "name": card.name,
                "nickname": card.nickname,
                "age": card.age,
                "gender": card.gender,
                "appearance": card.appearance,
                "personality": card.personality,
                "background": card.background,
                "speaking_style": card.speaking_style,
                "greeting": card.greeting,
                "likes": card.likes,
                "dislikes": card.dislikes,
                "example_dialogues": card.example_dialogues,
                "creator_notes": card.creator_notes,
                "tags": card.tags,
            },
        }
        file_path.write_text(
            json_lib.dumps(data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    # -------------------------------------------------------------------------
    # 内部验证与解析方法
    # -------------------------------------------------------------------------

    def _validate_required_fields(
        self, card_data: dict[str, Any], source: str
    ) -> None:
        """验证角色卡必填字段是否完整。

        Args:
            card_data: 角色卡数据字典。
            source: 数据来源标识。

        Raises:
            CardParseError: 必填字段缺失时。
        """
        missing: list[str] = []
        for field in _REQUIRED_FIELDS:
            value = card_data.get(field, "")
            if not value or not str(value).strip():
                missing.append(field)

        if missing:
            raise CardParseError(
                f"缺少必填字段: {', '.join(missing)}", source
            )

        self._log.debug(f"角色卡必填字段验证通过: {source}")

    @staticmethod
    def _parse_string_list(value: Any) -> list[str]:
        """将输入解析为字符串列表，过滤空值。

        Args:
            value: 可能是 list 或其他类型的输入。

        Returns:
            过滤后的字符串列表。
        """
        if not isinstance(value, list):
            return []
        result: list[str] = []
        for item in value:
            s = str(item).strip()
            if s:
                result.append(s)
        return result

    @staticmethod
    def _parse_dialogues(value: Any) -> list[dict[str, str]]:
        """解析并验证示例对话列表。

        Args:
            value: 示例对话原始数据。

        Returns:
            验证后的示例对话列表，每项包含 "user" 和 "character" 键。
        """
        if not isinstance(value, list):
            return []
        result: list[dict[str, str]] = []
        for item in value:
            if isinstance(item, dict):
                dialogue: dict[str, str] = {
                    "user": str(item.get("user", "")),
                    "character": str(item.get("character", "")),
                }
                # 只保留 user 和 character 都有内容的对话
                if dialogue["user"].strip() and dialogue["character"].strip():
                    result.append(dialogue)
        return result
