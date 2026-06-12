"""
核心聊天引擎桥接模块 — init / chat / reset / apply_params 等。
"""
import json
import os

from src.chat_engine.role_player import RolePlayerError
from src.config.settings import settings
from src.utils.logger import get_logger

from ._state import _ctx, _CARD_PATH, _executor

_log = get_logger()


def init(
    context_size: int = 2000,
    temperature: float = 0.7,
    max_tokens: int = 1000,
    example_dialogues: int = 1,
    model: str = "",
) -> dict:
    """初始化聊天引擎。

    加载角色卡、配置 API 客户端。首次调用时自动完成。
    如果已初始化，先通过 AppContext.shutdown() 清理旧资源再创建新实例。

    Args:
        context_size: 上下文窗口大小（1000/2000/4000）。
        temperature: 创意度/温度（0.5/0.7/0.9）。
        max_tokens: 回复最大 token 数（500/1000/2000）。
        example_dialogues: 示例对话条数（0/1/2/3）。
        model: 模型名称，空字符串默认 deepseek-v4-flash。

    Returns:
        {"status": "ok", "card": {"name": str, "nickname": str, "gender": str}}
        或 {"status": "error", "message": str}
    """
    try:
        if not settings.DEEPSEEK_API_KEY:
            return json.dumps({"status": "error", "message": "API Key 未配置，请先设置 API Key"})

        from src.chat_engine.token_presets import TokenPreset
        custom_preset = TokenPreset(
            key="custom",
            label="自定义",
            description="手动调整",
            model=model if model else "deepseek-v4-flash",
            temperature=temperature,
            max_tokens=max_tokens,
            context_window=context_size,
            system_prompt_chars=int(context_size * 0.6),
            card_max_chars=int(context_size * 0.35),
            world_book_max_chars=int(context_size * 0.15),
            memories_max_chars=int(context_size * 0.1),
            guideline_max_chars=int(context_size * 0.06),
            max_example_dialogues=example_dialogues,
            include_guideline=True,
            include_creator_notes=False,
        )

        player = _ctx.initialize(preset=custom_preset, model=model if model else "")
        player.load_card(str(_CARD_PATH))

        info = player.get_card_info()
        info["context_size"] = context_size
        info["temperature"] = temperature
        info["max_tokens"] = max_tokens
        info["example_dialogues"] = example_dialogues
        return json.dumps({"status": "ok", "card": info})
    except FileNotFoundError as e:
        return json.dumps({"status": "error", "message": f"角色卡文件不存在: {e}"})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def chat(user_input: str) -> dict:
    """发送一条消息，获取 AI 角色回复。

    Args:
        user_input: 用户输入文本。

    Returns:
        {"status": "ok", "reply": str} 或 {"status": "error", "message": str}
    """
    player = _ctx.player
    orchestrator = _ctx.orchestrator

    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化，请先调用 init()"})

    if not user_input or not user_input.strip():
        return json.dumps({"status": "error", "message": "消息不能为空"})

    try:
        user_input = user_input.strip()
        reply = player.chat(user_input)

        # 记忆存储：使用线程池异步执行，不阻塞对话回复
        if orchestrator is not None and reply:
            _executor.submit(_auto_remember, user_input, reply)

        return json.dumps({"status": "ok", "reply": reply})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def _auto_remember(user_input: str, ai_reply: str) -> None:
    """内部函数：对话完成后自动存储记忆。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return

    try:
        from src.utils.time_utils import format_timestamp_iso
        turn_num = _ctx.increment_turn()
        turn_id = f"turn_{turn_num}_{format_timestamp_iso()}"
        orchestrator.remember(turn_id, user_input, ai_reply)
    except Exception as e:
        from src.utils.logger import get_logger as _get_logger
        _get_logger().warning(f"[自动记忆] 存储失败（对话不受影响）: {e}")


def reset() -> dict:
    """重置对话上下文，开始新对话。"""
    player = _ctx.player
    if player is not None:
        player.clear_context()
    return json.dumps({"status": "ok"})


def get_card_info() -> dict:
    """获取当前角色卡信息。"""
    player = _ctx.player
    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化"})
    try:
        from typing import Any
        info: dict[str, Any] = dict(player.get_card_info())
        info["preset"] = _ctx.current_preset
        return json.dumps({"status": "ok", "card": info})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_api_key(key: str) -> dict:
    """设置 DeepSeek API Key（运行时设置，不写入文件）。"""
    if not key or not key.strip():
        return json.dumps({"status": "error", "message": "API Key 不能为空"})
    key = key.strip()
    os.environ["DEEPSEEK_API_KEY"] = key
    settings.DEEPSEEK_API_KEY = key
    client = _ctx.client
    if client is not None:
        client.update_api_key(key)
    return json.dumps({"status": "ok"})


def list_presets() -> dict:
    """列出所有可用的 Token 预设。"""
    from src.chat_engine.token_presets import list_presets as _list
    return json.dumps({"status": "ok", "presets": _list()})


def apply_params(
    context_size: int = 2000,
    temperature: float = 0.7,
    max_tokens: int = 1000,
    example_dialogues: int = 1,
    model: str = "",
) -> dict:
    """运行时应用对话参数（重新初始化聊天引擎）。"""
    try:
        if not settings.DEEPSEEK_API_KEY:
            return json.dumps({"status": "error", "message": "API Key 未配置，请先设置 API Key"})

        from src.chat_engine.token_presets import TokenPreset
        custom_preset = TokenPreset(
            key="custom",
            label="自定义",
            description="手动调整",
            model=model if model else "deepseek-v4-flash",
            temperature=temperature,
            max_tokens=max_tokens,
            context_window=context_size,
            system_prompt_chars=int(context_size * 0.6),
            card_max_chars=int(context_size * 0.35),
            world_book_max_chars=int(context_size * 0.15),
            memories_max_chars=int(context_size * 0.1),
            guideline_max_chars=int(context_size * 0.06),
            max_example_dialogues=example_dialogues,
            include_guideline=True,
            include_creator_notes=False,
        )

        player = _ctx.initialize(preset=custom_preset, model=model if model else "")
        player.load_card(str(_CARD_PATH))
        _log.info(
            f"[参数应用] context={context_size}, temp={temperature}, "
            f"max_tokens={max_tokens}, dialogues={example_dialogues}, model={model or '默认'}"
        )
        return json.dumps({
            "status": "ok",
            "params": {
                "context_size": context_size,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "example_dialogues": example_dialogues,
                "model": model or "deepseek-v4-flash",
            },
        })
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})