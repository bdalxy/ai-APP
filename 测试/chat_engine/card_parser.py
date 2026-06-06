# -*- coding: utf-8 -*-
"""
角色卡解析器
支持 Tavo 和酒馆（Sil Tavern）格式的角色卡导入导出
"""

import os
import json
import logging
from typing import Dict, Any, Optional, List
from pathlib import Path

logger = logging.getLogger(__name__)

class CharacterCard:
    """角色卡数据类"""
    
    def __init__(
        self,
        name: str = "",
        personality: str = "",
        background: str = "",
        dialogue_style: str = "",
        description: str = "",
        scenario: str = "",
        example_dialogues: List[Dict] = None,
        metadata: Dict = None,
        source_format: str = "custom"
    ):
        self.name = name
        self.personality = personality
        self.background = background
        self.dialogue_style = dialogue_style
        self.description = description
        self.scenario = scenario
        self.example_dialogues = example_dialogues or []
        self.metadata = metadata or {}
        self.source_format = source_format
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {k: v for k, v in self.__dict__.items() if not k.startswith('_')}
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'CharacterCard':
        """从字典创建"""
        return cls(**{k: v for k, v in data.items() if k in cls.__init__.__code__.co_varnames})
    
    def build_system_prompt(self, include_examples: bool = True) -> str:
        """构建系统提示词"""
        parts = []
        for field in ["name", "description", "personality", "background", "dialogue_style", "scenario"]:
            if getattr(self, field, None):
                parts.append(f"【{field}】{getattr(self, field)}")
        
        prompt = "\n".join(parts)
        
        if include_examples and self.example_dialogues:
            examples = []
            for ex in self.example_dialogues:
                user_msg = ex.get("user", ex.get("speaker", ""))
                bot_msg = ex.get("bot", ex.get("assistant", ""))
                if user_msg and bot_msg:
                    examples.append(f"用户: {user_msg}\n{self.name}: {bot_msg}")
            if examples:
                prompt += "\n\n【示例对话】\n" + "\n\n".join(examples)
        
        return prompt
    
    def validate(self) -> List[str]:
        """验证角色卡数据"""
        errors = []
        if not self.name:
            errors.append("角色名称不能为空")
        if len(self.name) > 50:
            errors.append("角色名称过长（最大50字符）")
        if self.personality and len(self.personality) > 500:
            errors.append("性格描述过长（最大500字符）")
        if self.background and len(self.background) > 2000:
            errors.append("背景故事过长（最大2000字符）")
        if self.dialogue_style and len(self.dialogue_style) > 500:
            errors.append("对话风格描述过长（最大500字符）")
        return errors
    
    def __str__(self):
        return f"CharacterCard(name={self.name}, format={self.source_format})"

class CardParser:
    """角色卡解析器"""
    
    SUPPORTED_FORMATS = ["tavo", "siltavern", "custom"]
    
    TAVO_FIELD_MAP = {"character_name": "name", "character_persona": "personality", 
                     "world_scenario": "scenario", "example_messages": "example_dialogues"}
    
    SILTAVERN_FIELD_MAP = {"name": "name", "description": "description", 
                          "personality": "personality", "background": "background", 
                          "dialogue_style": "dialogue_style", "sample_dialogues": "example_dialogues"}
    
    @classmethod
    def load_from_file(cls, file_path: str) -> Optional[CharacterCard]:
        """从文件加载角色卡"""
        path = Path(file_path)
        if not path.exists():
            logger.error(f"角色卡文件不存在: {file_path}")
            return None
        
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            return cls.parse(data, file_path)
        except json.JSONDecodeError as e:
            logger.error(f"角色卡 JSON 解析失败: {e}")
            return None
        except Exception as e:
            logger.error(f"加载角色卡失败: {e}")
            return None
    
    @classmethod
    def parse(cls, data: Dict[str, Any], source: str = "unknown") -> Optional[CharacterCard]:
        """解析角色卡数据"""
        if not isinstance(data, dict):
            logger.error(f"角色卡数据格式错误: {type(data)}")
            return None
        
        format_type = cls.detect_format(data)
        logger.info(f"检测到角色卡格式: {format_type}")
        
        try:
            if format_type == "tavo":
                card = cls._parse_tavo(data)
            elif format_type == "siltavern":
                card = cls._parse_siltavern(data)
            else:
                card = cls._parse_custom(data)
            
            card.source_format = format_type
            errors = card.validate()
            if errors:
                logger.warning(f"角色卡验证警告: {errors}")
            
            return card
        except Exception as e:
            logger.error(f"解析角色卡失败: {e}")
            return None
    
    @classmethod
    def detect_format(cls, data: Dict[str, Any]) -> str:
        """检测角色卡格式"""
        if any(field in data for field in ["character_name", "character_persona"]):
            return "tavo"
        if all(field in data for field in ["name", "personality"]):
            return "siltavern"
        return "custom"
    
    @classmethod
    def _parse_tavo(cls, data: Dict[str, Any]) -> CharacterCard:
        """解析 Tavo 格式"""
        card = CharacterCard()
        for tavo_field, our_field in cls.TAVO_FIELD_MAP.items():
            if tavo_field in data:
                setattr(card, our_field, data[tavo_field])
        
        if "example_messages" in data:
            examples = []
            for msg in data["example_messages"]:
                if isinstance(msg, dict):
                    speaker, text = msg.get("speaker", ""), msg.get("text", "")
                    if speaker == "user":
                        examples.append({"user": text})
                    elif speaker == "assistant":
                        examples.append({"bot": text})
            card.example_dialogues = examples
        return card
    
    @classmethod
    def _parse_siltavern(cls, data: Dict[str, Any]) -> CharacterCard:
        """解析酒馆格式"""
        card = CharacterCard()
        for siltavern_field, our_field in cls.SILTAVERN_FIELD_MAP.items():
            if siltavern_field in data:
                setattr(card, our_field, data[siltavern_field])
        
        if "sample_dialogues" in data:
            card.example_dialogues = [{"user": d.get("user", ""), "bot": d.get("bot", "")} 
                                    for d in data["sample_dialogues"] if isinstance(d, dict)]
        return card
    
    @classmethod
    def _parse_custom(cls, data: Dict[str, Any]) -> CharacterCard:
        """解析自定义格式"""
        return CharacterCard.from_dict(data)
    
    @classmethod
    def export_to_tavo(cls, card: CharacterCard) -> Dict[str, Any]:
        """导出为 Tavo 格式"""
        reverse_map = {v: k for k, v in cls.TAVO_FIELD_MAP.items()}
        data = {reverse_map[k]: getattr(card, k, None) for k in reverse_map}
        if card.example_dialogues:
            examples = []
            for ex in card.example_dialogues:
                if isinstance(ex, dict):
                    if "user" in ex:
                        examples.append({"speaker": "user", "text": ex["user"]})
                    if "bot" in ex:
                        examples.append({"speaker": "assistant", "text": ex["bot"]})
            data["example_messages"] = examples
        return {k: v for k, v in data.items() if v is not None}
    
    @classmethod
    def export_to_siltavern(cls, card: CharacterCard) -> Dict[str, Any]:
        """导出为酒馆格式"""
        data = {siltavern_field: getattr(card, our_field, None) 
                for siltavern_field, our_field in cls.SILTAVERN_FIELD_MAP.items()}
        if card.example_dialogues:
            data["sample_dialogues"] = [{"user": d.get("user", ""), "bot": d.get("bot", "")} 
                                      for d in card.example_dialogues if isinstance(d, dict)]
        return {k: v for k, v in data.items() if v is not None}
    
    @classmethod
    def export_to_file(
        cls,
        card: CharacterCard,
        file_path: str,
        format_type: str = "custom"
    ):
        """导出角色卡到文件"""
        if format_type == "tavo":
            data = cls.export_to_tavo(card)
        elif format_type == "siltavern":
            data = cls.export_to_siltavern(card)
        else:
            data = card.to_dict()
        
        path = Path(file_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        logger.info(f"角色卡已导出到: {file_path}")

class CardManager:
    """角色卡管理器"""
    
    def __init__(self, cards_dir: str = "./cards"):
        # 安全检查：确保路径是相对路径，防止路径遍历
        self.cards_dir = Path(cards_dir).resolve()
        if ".." in str(self.cards_dir):
            raise ValueError("无效的角色卡目录路径")
        self.cards_dir.mkdir(parents=True, exist_ok=True)
        self._cards_cache: Dict[str, CharacterCard] = {}
        logger.info(f"角色卡管理器初始化，目录: {self.cards_dir}")
    
    def _validate_card_name(self, name: str) -> str:
        """验证并清理角色卡名称"""
        if not name:
            return ""
        # 只允许字母、数字、中文和下划线
        import re
        cleaned = re.sub(r'[^\w\u4e00-\u9fa5]', '_', name)
        return cleaned[:50]  # 限制长度
    
    def _validate_file_path(self, file_path: Path) -> bool:
        """验证文件路径安全性"""
        try:
            resolved = file_path.resolve()
            # 确保文件在允许的目录内
            return str(resolved).startswith(str(self.cards_dir))
        except Exception:
            return False
    
    def load_all(self) -> Dict[str, CharacterCard]:
        """加载所有角色卡"""
        self._cards_cache.clear()
        if not self.cards_dir.exists():
            logger.warning(f"角色卡目录不存在: {self.cards_dir}")
            return {}
        
        for file_path in self.cards_dir.glob("*.json"):
            try:
                card = CardParser.load_from_file(str(file_path))
                if card and card.name:
                    key = file_path.stem
                    self._cards_cache[key] = card
                    logger.info(f"加载角色卡: {card.name}")
            except Exception as e:
                logger.error(f"加载角色卡失败 {file_path}: {e}")
        
        logger.info(f"共加载 {len(self._cards_cache)} 个角色卡")
        return self._cards_cache
    
    def get_card(self, name: str) -> Optional[CharacterCard]:
        """获取角色卡"""
        if name in self._cards_cache:
            return self._cards_cache[name]
        for key, card in self._cards_cache.items():
            if card.name == name or key == name:
                return card
        return None
    
    def save_card(
        self,
        card: CharacterCard,
        file_name: str = None,
        format_type: str = "custom"
    ) -> str:
        """保存角色卡"""
        if not file_name:
            file_name = "".join(c if c.isalnum() or c in ('_', '-') else '_' for c in card.name)
        
        file_path = self.cards_dir / f"{file_name}.json"
        CardParser.export_to_file(card, str(file_path), format_type)
        self._cards_cache[file_name] = card
        return str(file_path)
    
    def delete_card(self, name: str) -> bool:
        """删除角色卡"""
        card = self.get_card(name)
        if not card:
            return False
        
        for file_path in self.cards_dir.glob("*.json"):
            try:
                test_card = CardParser.load_from_file(str(file_path))
                if test_card and test_card.name == card.name:
                    file_path.unlink()
                    for key, c in list(self._cards_cache.items()):
                        if c.name == card.name:
                            del self._cards_cache[key]
                    logger.info(f"删除角色卡: {card.name}")
                    return True
            except Exception:
                continue
        return False
    
    def list_cards(self) -> List[CharacterCard]:
        """列出所有角色卡"""
        return list(self._cards_cache.values())
    
    def import_card(
        self,
        source_path: str,
        target_name: str = None,
        target_format: str = "custom"
    ) -> Optional[CharacterCard]:
        """导入角色卡"""
        card = CardParser.load_from_file(source_path)
        if card:
            self.save_card(card, target_name, target_format)
        return card
    
    def export_card(
        self,
        card_name: str,
        target_path: str,
        target_format: str = "custom"
    ) -> bool:
        """导出角色卡"""
        card = self.get_card(card_name)
        if card:
            CardParser.export_to_file(card, target_path, target_format)
            return True
        return False
