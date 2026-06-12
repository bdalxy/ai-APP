"""
角色卡管理桥接模块 — set_character_card / get_character_card。
"""
import json

from src.config.settings import settings


def set_character_card(name: str, personality: str, speaking_style: str, backstory: str) -> str:
    """设置当前使用的角色卡信息（运行时覆盖默认值）。"""
    try:
        settings.CHARACTER_NAME = name
        settings.CHARACTER_PERSONALITY = personality
        settings.CHARACTER_SPEAKING_STYLE = speaking_style
        settings.CHARACTER_BACKSTORY = backstory
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_character_card() -> str:
    """获取当前角色卡信息。"""
    try:
        return json.dumps({
            "status": "ok",
            "name": getattr(settings, 'CHARACTER_NAME', '小美'),
            "personality": getattr(settings, 'CHARACTER_PERSONALITY', ''),
            "speaking_style": getattr(settings, 'CHARACTER_SPEAKING_STYLE', ''),
            "backstory": getattr(settings, 'CHARACTER_BACKSTORY', ''),
        }, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})