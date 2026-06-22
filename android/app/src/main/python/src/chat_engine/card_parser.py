"""角色卡解析模块。

提供 Card 数据类、CardParser 解析器和 CardParseError 异常。
支持从 JSON 文件或字典（Kotlin 桥接层传入）构建角色卡。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class CardParseError(Exception):
    """角色卡解析错误。"""

    def __init__(self, message: str, path: str = "") -> None:
        super().__init__(message)
        self.path = path


@dataclass
class Card:
    """角色卡数据模型。

    Attributes:
        name: 角色名称。
        personality: 性格描述。
        speaking_style: 说话风格。
        backstory: 背景故事。
        greeting: 开场白。
        core_traits: 核心特质（逗号分隔）。
        taboo_topics: 禁忌话题（逗号分隔）。
        role_anchor: 角色锚点（一句话定义）。
        avatar_uri: 头像路径。
    """

    name: str = ""
    personality: str = ""
    speaking_style: str = ""
    backstory: str = ""
    greeting: str = ""
    core_traits: str = ""
    taboo_topics: str = ""
    role_anchor: str = ""
    avatar_uri: str = ""

    # 兼容属性
    nickname: str = ""

    def __post_init__(self):
        if not self.nickname:
            self.nickname = self.name

    @property
    def gender(self) -> str:
        """角色性别（从 personality 推断，默认为"未知"）。"""
        personality_lower = self.personality.lower()
        if any(kw in personality_lower for kw in ["女", "她", "女性", "girl", "woman", "female"]):
            return "女"
        if any(kw in personality_lower for kw in ["男", "他", "男性", "boy", "man", "male"]):
            return "男"
        return "未知"

    def to_prompt_text(self) -> str:
        """生成用于 System Prompt 的角色卡文本。"""
        lines = []

        if self.name:
            lines.append(f"【角色名称】{self.name}")
        if self.role_anchor:
            lines.append(f"【角色定位】{self.role_anchor}")
        if self.personality:
            lines.append(f"【性格】{self.personality}")
        if self.core_traits:
            lines.append(f"【核心特质】{self.core_traits}")
        if self.speaking_style:
            lines.append(f"【说话风格】{self.speaking_style}")
        if self.backstory:
            lines.append(f"【背景故事】{self.backstory}")
        if self.taboo_topics:
            lines.append(f"【禁忌话题】{self.taboo_topics}")
        if self.greeting:
            lines.append(f"【开场白示例】{self.greeting}")

        return "\n".join(lines)


class CardParser:
    """角色卡解析器。

    支持从 JSON 文件或字典（Kotlin 桥接层传入）解析角色卡。
    """

    def from_file(self, card_path: str | Path) -> Card:
        """从 JSON 文件加载角色卡。

        Args:
            card_path: 角色卡 JSON 文件路径。

        Returns:
            解析后的 Card 对象。

        Raises:
            CardParseError: 解析失败时。
            FileNotFoundError: 文件不存在时。
        """
        path = Path(card_path).resolve()

        if not path.exists():
            raise FileNotFoundError(f"角色卡文件不存在: {path}")

        try:
            raw_text = path.read_text(encoding="utf-8")
        except Exception as e:
            raise CardParseError(f"读取角色卡文件失败: {e}", str(path))

        try:
            data = json.loads(raw_text)
        except json.JSONDecodeError as e:
            raise CardParseError(f"角色卡 JSON 解析失败: {e}", str(path))

        if not isinstance(data, dict):
            raise CardParseError("角色卡数据必须是 JSON 对象", str(path))

        return self._build_card(data)

    def from_character_data(
        self, char_data: dict[str, Any], output_dir: str | None = None
    ) -> Card:
        """从字典构建角色卡（供 Kotlin 桥接层使用）。

        Args:
            char_data: 来自 Kotlin CharacterData 的字典。
            output_dir: 可选，如果提供则同步写入 JSON 文件。

        Returns:
            构建好的 Card 对象。

        Raises:
            CardParseError: 必填字段缺失时。
        """
        if not isinstance(char_data, dict):
            raise CardParseError("角色数据必须是字典类型")

        if "name" not in char_data or not char_data["name"]:
            raise CardParseError("角色卡缺少必填字段: name")

        card = self._build_card(char_data)

        # 可选：同步写入文件
        if output_dir:
            output_path = Path(output_dir) / f"{card.name}.json"
            try:
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_text(
                    json.dumps(char_data, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
            except Exception as e:
                raise CardParseError(f"写入角色卡文件失败: {e}", str(output_path))

        return card

    @staticmethod
    def _build_card(data: dict[str, Any]) -> Card:
        """从字典构建 Card 对象。"""
        return Card(
            name=str(data.get("name", "")),
            personality=str(data.get("personality", "")),
            speaking_style=str(data.get("speaking_style", data.get("speakingStyle", ""))),
            backstory=str(data.get("backstory", "")),
            greeting=str(data.get("greeting", "")),
            core_traits=str(data.get("core_traits", data.get("coreTraits", ""))),
            taboo_topics=str(data.get("taboo_topics", data.get("tabooTopics", ""))),
            role_anchor=str(data.get("role_anchor", data.get("roleAnchor", ""))),
            avatar_uri=str(data.get("avatar_uri", data.get("avatarUri", ""))),
            nickname=str(data.get("nickname", data.get("name", ""))),
        )