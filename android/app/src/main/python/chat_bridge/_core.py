"""
核心聊天引擎桥接模块 — init / chat / reset / apply_params 等。
"""
import json
import os

from src.chat_engine.role_player import RolePlayerError
from src.config.settings import settings
from src.utils.logger import get_logger

from . import _state
from ._state import _ctx, _CARD_PATH, _executor, _current_params

_log = get_logger()


def _build_custom_preset(
    context_size: int,
    temperature: float,
    max_tokens: int,
    example_dialogues: int,
    model: str = "",
):
    """构建自定义 TokenPreset，init() 和 apply_params() 共用。"""
    from src.chat_engine.token_presets import TokenPreset
    return TokenPreset(
        key="custom",
        label="自定义",
        description="手动调整",
        model=model or "deepseek-v4-flash",
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


def _record_params(context_size: int, temperature: float, max_tokens: int,
                   example_dialogues: int, model: str) -> dict:
    """记录当前生效的参数，并返回确认后的参数字典。"""
    actual_model = model or "deepseek-v4-flash"
    params = {
        "context_size": context_size,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "example_dialogues": example_dialogues,
        "model": actual_model,
    }
    with _state._lock:
        _current_params.clear()
        _current_params.update(params)
    return params


def init(
    context_size: int = 2000,
    temperature: float = 0.7,
    max_tokens: int = 1000,
    example_dialogues: int = 1,
    model: str = "",
) -> dict:
    """初始化聊天引擎。"""
    try:
        if not settings.DEEPSEEK_API_KEY:
            return json.dumps({"status": "error", "message": "API Key 未配置，请先设置 API Key"})

        custom_preset = _build_custom_preset(context_size, temperature, max_tokens, example_dialogues, model)
        player = _ctx.initialize(preset=custom_preset, model=model if model else "")
        player.load_card(str(_CARD_PATH))

        info = player.get_card_info()
        params = _record_params(context_size, temperature, max_tokens, example_dialogues, model)
        info.update(params)
        return json.dumps({"status": "ok", "card": info, "params": params})
    except FileNotFoundError as e:
        return json.dumps({"status": "error", "message": f"角色卡文件不存在: {e}"})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def chat(user_input: str) -> dict:
    """发送一条消息，获取 AI 角色回复。

    自动注入相关记忆：每 N 轮（默认3轮）检索一次记忆并注入到 System Prompt。
    自动注入世界书：每次对话时对所有已启用的世界书执行关键词匹配注入。
    """
    player = _ctx.player
    orchestrator = _ctx.orchestrator

    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化，请先调用 init()"})

    if not user_input or not user_input.strip():
        return json.dumps({"status": "error", "message": "消息不能为空"})

    try:
        user_input = user_input.strip()

        # 自动记忆注入：每 N 轮检索一次相关记忆
        with _state._lock:
            _state._turn_since_last_inject += 1
            need_inject = (
                orchestrator is not None
                and _state._turn_since_last_inject >= _state._memory_inject_interval
            )
        if need_inject:
            try:
                new_memories = orchestrator.recall(user_input)
                with _state._lock:
                    _state._cached_memories = new_memories
                    _state._turn_since_last_inject = 0
                _log.debug(f"[记忆注入] 检索到 {len(_state._cached_memories)} 条相关记忆")
            except Exception as e:
                _log.warning(f"[记忆注入] 检索失败: {e}")

        with _state._lock:
            memories = list(_state._cached_memories)
        if memories:
            player.inject_memories(memories)

        # 世界书注入：对所有已启用的世界书执行关键词匹配
        try:
            from ._world_book import _match_and_inject_for_all
            world_book_context = _match_and_inject_for_all(user_input)
            if world_book_context:
                player.world_book_entries = [world_book_context]
                _log.debug(f"[世界书] 注入 {len(world_book_context)} 字符")
            else:
                player.world_book_entries = []
        except Exception as e:
            _log.warning(f"[世界书] 注入失败: {e}")

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

    # 重置世界书轮次计数
    try:
        from ._world_book import _reset_round_for_all
        _reset_round_for_all()
    except Exception as e:
        _log.warning(f"[世界书] 重置轮次计数失败: {e}")

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

        custom_preset = _build_custom_preset(context_size, temperature, max_tokens, example_dialogues, model)
        player = _ctx.initialize(preset=custom_preset, model=model if model else "")
        player.load_card(str(_CARD_PATH))

        params = _record_params(context_size, temperature, max_tokens, example_dialogues, model)
        _log.info(
            f"[参数应用] context={context_size}, temp={temperature}, "
            f"max_tokens={max_tokens}, dialogues={example_dialogues}, model={model or '默认'}"
        )
        return json.dumps({"status": "ok", "params": params})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_current_params() -> dict:
    """获取当前生效的对话参数（供 Kotlin 端验证配置同步）。"""
    with _state._lock:
        params_copy = dict(_current_params)
    return json.dumps({"status": "ok", "params": params_copy})