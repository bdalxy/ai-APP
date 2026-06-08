"""
Chat Bridge — Android (Chaquopy) 与 Python 聊天引擎的桥接模块。

P3 阶段核心：将 RolePlayer 封装为 Chaquopy 可调用的简单接口。
Kotlin 端通过 Chaquopy 调用此模块的 init() / chat() / reset() 方法。

数据流：
    Kotlin (MainActivity)
        → Chaquopy Python.getInstance()
        → chat_bridge.set_api_key(key)   # 设置 API Key
        → chat_bridge.init(preset)       # 初始化：加载角色卡、配置 API
        → chat_bridge.chat(msg)          # 发送消息，返回 AI 回复
        → chat_bridge.reset()            # 重置对话上下文
"""

import os
from pathlib import Path
from typing import Any

from src.api_client.deepseek import DeepSeekClient
from src.chat_engine.role_player import RolePlayer, RolePlayerError
from src.config.settings import settings

# 全局单例，整个应用生命周期内复用
_player: "RolePlayer | None" = None
_current_preset: str = "balanced"

# Android 上的角色卡路径（与 Python 源码同目录的 data/role_cards/）
_BASE_DIR = Path(os.path.dirname(os.path.abspath(__file__)))
_CARD_PATH = _BASE_DIR / "data" / "role_cards" / "小美.json"


def init(preset: str = "balanced", model: str = "") -> dict:
    """初始化聊天引擎。

    加载角色卡、配置 API 客户端。首次调用时自动完成。
    如果已初始化，先关闭旧客户端再创建新实例。

    Args:
        preset: Token 预设模式 ("quality"/"balanced"/"economy")。
        model: 模型名称，空字符串表示使用预设默认模型。

    Returns:
        {"status": "ok", "card": {"name": str, "nickname": str, "gender": str}}
        或 {"status": "error", "message": str}
    """
    global _player, _current_preset

    try:
        # 关闭旧客户端，避免资源泄露
        if _player is not None:
            try:
                _player.client.close()
            except Exception:
                pass

        if not settings.DEEPSEEK_API_KEY:
            return {"status": "error", "message": "API Key 未配置，请先设置 API Key"}

        client = DeepSeekClient()
        _current_preset = preset
        _player = RolePlayer(
            client,
            preset=preset,
            model=model if model else None,
        )

        # 使用与 chat_bridge.py 同目录下的角色卡
        _player.load_card(str(_CARD_PATH))

        info = _player.get_card_info()
        info["preset"] = preset
        return {"status": "ok", "card": info}
    except FileNotFoundError as e:
        return {"status": "error", "message": f"角色卡文件不存在: {e}"}
    except RolePlayerError as e:
        return {"status": "error", "message": str(e)}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def chat(user_input: str) -> dict:
    """发送一条消息，获取 AI 角色回复。

    Args:
        user_input: 用户输入文本。

    Returns:
        {"status": "ok", "reply": str} 或 {"status": "error", "message": str}
    """
    global _player

    if _player is None:
        return {"status": "error", "message": "引擎未初始化，请先调用 init()"}

    if not user_input or not user_input.strip():
        return {"status": "ok", "reply": ""}

    try:
        reply = _player.chat(user_input.strip())
        return {"status": "ok", "reply": reply}
    except RolePlayerError as e:
        return {"status": "error", "message": str(e)}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def reset() -> dict:
    """重置对话上下文，开始新对话。

    Returns:
        {"status": "ok"}
    """
    global _player

    if _player is not None:
        _player.clear_context()
    return {"status": "ok"}


def get_card_info() -> dict:
    """获取当前角色卡信息。

    Returns:
        {"status": "ok", "card": {...}} 或 {"status": "error", "message": str}
    """
    if _player is None:
        return {"status": "error", "message": "引擎未初始化"}
    try:
        info: dict[str, Any] = dict(_player.get_card_info())
        info["preset"] = _current_preset
        return {"status": "ok", "card": info}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def set_api_key(key: str) -> dict:
    """设置 DeepSeek API Key（运行时设置，不写入文件）。

    用于 Android 端通过 SharedPreferences 等方式传入 API Key，
    避免将密钥嵌入 APK 文件。

    Args:
        key: DeepSeek API Key。

    Returns:
        {"status": "ok"}
    """
    if not key or not key.strip():
        return {"status": "error", "message": "API Key 不能为空"}
    # 同时设置到环境变量和 Settings 单例
    os.environ["DEEPSEEK_API_KEY"] = key.strip()
    settings.DEEPSEEK_API_KEY = key.strip()
    return {"status": "ok"}


def list_presets() -> dict:
    """列出所有可用的 Token 预设。

    Returns:
        {"status": "ok", "presets": {...}}
    """
    from src.chat_engine.token_presets import list_presets as _list

    return {"status": "ok", "presets": _list()}