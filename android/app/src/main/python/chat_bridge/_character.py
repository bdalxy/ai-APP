"""
角色卡管理桥接模块 — set_character_card / get_character_card / reload_card。

数据流：
    Kotlin CharacterData → JSON 字符串 → set_character_card(json_str)
        → 解析为 Card → 更新 _current_character → 同步到 settings
        → 调用 RolePlayer.load_card_from_dict() 重新加载角色卡
"""
import json
from typing import Any

from src.config.settings import settings
from src.utils.logger import get_logger

from ._state import _current_character, _CARD_DIR, _ctx

_log = get_logger()


def set_character_card(char_json: str) -> str:
    """设置当前使用的角色卡（接收完整 JSON 字符串）。

    从 Kotlin 侧接收 CharacterData 的完整 JSON 表示，
    解析后同步更新 settings、_current_character 和 RolePlayer.card。

    JSON 格式示例:
        {
            "name": "小美",
            "personality": "温柔、活泼、善解人意",
            "speaking_style": "语气轻柔...",
            "backstory": "乐于助人的AI助手...",
            "greeting": "你好呀~今天过得怎么样？",
            "nickname": "美美",
            "age": "22",
            "gender": "女",
            ...
        }

    Args:
        char_json: CharacterData 的 JSON 字符串。

    Returns:
        JSON 字符串，包含 status 和当前角色卡信息。
    """
    try:
        data: dict[str, Any] = json.loads(char_json)
    except json.JSONDecodeError as e:
        return json.dumps({"status": "error", "message": f"无效的 JSON 格式: {e}"})

    if not isinstance(data, dict):
        return json.dumps({"status": "error", "message": "角色数据必须是一个 JSON 对象"})

    name = data.get("name", "")
    if not name:
        return json.dumps({"status": "error", "message": "角色名不能为空"})

    try:
        # 1. 同步到 settings（兼容旧代码）
        settings.CHARACTER_NAME = name
        settings.CHARACTER_PERSONALITY = data.get("personality", "")
        settings.CHARACTER_SPEAKING_STYLE = data.get("speaking_style", data.get("speakingStyle", ""))
        settings.CHARACTER_BACKSTORY = data.get("backstory", data.get("background", ""))

        # 2. 更新 _current_character（完整字段，不只是 4 个）
        _current_character.clear()
        _current_character.update({
            "name": name,
            "personality": data.get("personality", ""),
            "speaking_style": data.get("speaking_style", data.get("speakingStyle", "")),
            "backstory": data.get("backstory", data.get("background", "")),
            "greeting": data.get("greeting", ""),
            "nickname": data.get("nickname", name),
            "age": data.get("age", ""),
            "gender": data.get("gender", ""),
            "appearance": data.get("appearance", ""),
            "likes": data.get("likes", []),
            "dislikes": data.get("dislikes", []),
            "example_dialogues": data.get("example_dialogues", []),
            "creator_notes": data.get("creator_notes", ""),
            "tags": data.get("tags", []),
        })

        # 3. 同步到 RolePlayer（关键：让 chat 流程使用最新的 Card）
        player = _ctx.player
        if player is not None:
            # 写入角色卡目录，确保 init() 重新加载时也能读到
            player.load_card_from_dict(data, output_dir=str(_CARD_DIR))
            _log.info(f"角色卡已同步到 RolePlayer: {name}")
        else:
            # 引擎未初始化，只写入文件，后续 init() 会加载
            from src.chat_engine.card_parser import CardParser
            parser = CardParser()
            parser.from_character_data(data, output_dir=str(_CARD_DIR))
            _log.info(f"角色卡已写入文件（引擎未初始化）: {name}")

        return json.dumps(
            {"status": "ok", "character": dict(_current_character)},
            ensure_ascii=False,
        )
    except Exception as e:
        _log.error(f"设置角色卡失败: {e}")
        return json.dumps({"status": "error", "message": str(e)})


def set_character_card_legacy(name: str, personality: str, speaking_style: str, backstory: str) -> str:
    """旧版兼容接口：接收 4 个独立参数。

    仅用于 Kotlin 侧未升级时的临时兼容，新代码请使用 set_character_card(json_str)。

    Args:
        name: 角色名。
        personality: 性格描述。
        speaking_style: 说话风格。
        backstory: 背景故事。

    Returns:
        JSON 字符串，包含 status。
    """
    data = {
        "name": name,
        "personality": personality,
        "speaking_style": speaking_style,
        "backstory": backstory,
    }
    return set_character_card(json.dumps(data, ensure_ascii=False))


def get_character_card() -> str:
    """获取当前角色卡信息（优先从 _current_character 返回，兜底从 settings）。"""
    try:
        if _current_character:
            char = dict(_current_character)
        else:
            char = {
                "name": getattr(settings, 'CHARACTER_NAME', '小美'),
                "personality": getattr(settings, 'CHARACTER_PERSONALITY', ''),
                "speaking_style": getattr(settings, 'CHARACTER_SPEAKING_STYLE', ''),
                "backstory": getattr(settings, 'CHARACTER_BACKSTORY', ''),
            }
        return json.dumps({"status": "ok", "character": char}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def reload_card() -> str:
    """强制重新加载角色卡到 RolePlayer（角色切换后调用）。

    从 _current_character 重新构建 Card 并加载到 RolePlayer，
    同时清除对话上下文（角色切换后不应保留旧对话）。

    使用场景：Kotlin 侧角色切换后调用此方法，确保 RolePlayer 使用新角色。

    Returns:
        JSON 字符串，包含 status 和操作结果。
    """
    player = _ctx.player
    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化"})

    if not _current_character:
        return json.dumps({"status": "error", "message": "没有设置角色卡"})

    try:
        # 重新加载角色卡
        player.load_card_from_dict(dict(_current_character), output_dir=str(_CARD_DIR))
        # 清除旧对话上下文（角色切换后不应保留）
        player.clear_context()
        _log.info(f"角色卡已重新加载: {_current_character.get('name', '未知')}")
        return json.dumps({
            "status": "ok",
            "character": dict(_current_character),
            "message": "角色卡已重新加载，对话上下文已清除",
        })
    except Exception as e:
        _log.error(f"重新加载角色卡失败: {e}")
        return json.dumps({"status": "error", "message": str(e)})