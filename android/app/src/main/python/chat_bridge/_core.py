"""
核心聊天引擎桥接模块 — init / chat / reset / apply_params 等。
"""
import json
import os
import queue
import threading
import traceback
import uuid

from src.chat_engine.role_player import RolePlayerError
from src.config.settings import settings
from src.plugins.plugin_manager import get_plugin_manager
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso

from . import _state
from ._state import _ctx, _CARD_PATH, _current_params

_log = get_logger()

# 插件管理器（全局单例）
_plugin_manager = get_plugin_manager()

# 流式对话会话管理（队列+轮询方案，解决 Chaquopy 无法迭代 Python 生成器的问题）
_streams: dict[str, dict] = {}
_streams_lock = threading.Lock()


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

        # 加载插件
        plugin_count = _plugin_manager.load_all()
        _log.info(f"已加载 {plugin_count} 个插件")

        return json.dumps({"status": "ok", "card": info, "params": params})
    except FileNotFoundError as e:
        return json.dumps({"status": "error", "message": f"角色卡文件不存在: {e}"})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def _inject_context(player, user_input: str, orchestrator) -> None:
    """注入记忆和世界书上下文到 Player 实例。

    提取 chat() 和 chat_stream() 共用的注入逻辑，避免重复代码。

    Args:
        player: RolePlayer 实例
        user_input: 用户输入文本
        orchestrator: MemoryOrchestrator 实例（可为 None）
    """
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

        # 插件管道：预处理用户输入
        user_input = _plugin_manager.pre_process(user_input)

        # 注入记忆和世界书上下文
        _inject_context(player, user_input, orchestrator)

        reply = player.chat(user_input)

        # 插件管道：后处理 AI 回复
        reply = _plugin_manager.post_process(reply)
        _plugin_manager.on_turn_end(user_input, reply)

        # 记忆存储：使用线程池异步执行，不阻塞对话回复
        if orchestrator is not None and reply:
            _state._executor.submit(_auto_remember, user_input, reply)

        return json.dumps({"status": "ok", "reply": reply})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def chat_stream_start(user_input: str) -> str:
    """启动流式对话，返回 stream_id。

    在后台线程中执行 AI 生成，通过 chat_stream_poll(stream_id) 获取 token。
    这是队列+轮询方案的核心入口，解决 Chaquopy 17.0.0 无法正确迭代
    Python 生成器的问题（PyObject 无法转为 Java String）。

    Args:
        user_input: 用户输入的消息文本。

    Returns:
        stream_id 字符串（UUID），用于后续 chat_stream_poll() 调用。
    """
    player = _ctx.player
    orchestrator = _ctx.orchestrator

    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化，请先调用 init()"})

    if not user_input or not user_input.strip():
        return json.dumps({"status": "error", "message": "消息不能为空"})

    user_input = user_input.strip()

    # 插件管道：预处理用户输入
    user_input = _plugin_manager.pre_process(user_input)

    # 注入记忆和世界书上下文
    _inject_context(player, user_input, orchestrator)

    stream_id = str(uuid.uuid4())
    token_queue = queue.Queue()

    with _streams_lock:
        _streams[stream_id] = {
            "queue": token_queue,
            "done": False,
            "error": None,
            "full_reply": "",
        }

    def _run():
        stream = _streams.get(stream_id)
        if not stream:
            return
        try:
            gen = player.chat_stream(user_input)
            full_reply = ""
            for token in gen:
                if token.startswith("__DONE__:"):
                    full_reply = token[len("__DONE__:"):]
                elif token.startswith("__ERROR__:"):
                    error_msg = token[len("__ERROR__:"):]
                    _log.error(f"[流式对话] 角色扮演器返回错误: {error_msg}")
                    stream["error"] = error_msg
                    token_queue.put(json.dumps({"status": "error", "message": error_msg}))
                    return
                else:
                    token_queue.put(json.dumps({"status": "streaming", "token": token}))

            # 记忆存储：使用线程池异步执行，不阻塞对话回复
            if orchestrator is not None and full_reply:
                _state._executor.submit(_auto_remember, user_input, full_reply)

            # 插件管道：后处理 AI 回复（流式对话）
            full_reply = _plugin_manager.post_process(full_reply)
            _plugin_manager.on_turn_end(user_input, full_reply)

            stream["full_reply"] = full_reply
            token_queue.put(json.dumps({"status": "done", "reply": full_reply}))
            _log.debug(f"[流式对话] 线程完成: reply_len={len(full_reply)}, error={stream.get('error')}")
        except RolePlayerError as e:
            _log.error(f"[流式对话] RolePlayerError: {e}")
            stream["error"] = str(e)
            token_queue.put(json.dumps({"status": "error", "message": str(e)}))
        except Exception as e:
            _log.error(f"[流式对话] 后台线程未知错误: {e}")
            _log.error(f"[流式对话] 异常详情: {traceback.format_exc()}")
            stream["error"] = str(e)
            token_queue.put(json.dumps({"status": "error", "message": f"内部错误: {e}"}))
        finally:
            stream["done"] = True
            _log.debug(f"[流式对话] 流结束: done={stream['done']}, error={stream.get('error')}")

    t = threading.Thread(target=_run, daemon=True)
    t.start()
    return stream_id


def chat_stream_poll(stream_id: str) -> str:
    """获取流式对话的可用 token（批量返回）。

    一次性返回队列中所有已生成的 token，减少 Chaquopy 跨语言调用次数。
    当队列为空且流未结束时返回 {"status": "waiting"}。

    Args:
        stream_id: chat_stream_start() 返回的会话 ID。

    Returns:
        JSON 字符串，格式如下：
            {"status": "batch", "events": [{"status": "streaming", "token": "..."}, ...]}
            {"status": "done", "reply": "完整回复"}
            {"status": "error", "message": "错误信息"}
            {"status": "waiting"}  — 暂无新 token，流未结束
            {"status": "error", "message": "无效的 stream_id"}
    """
    stream = _streams.get(stream_id)
    if not stream:
        return json.dumps({"status": "error", "message": "无效的 stream_id"})

    # 批量取出所有可用 token
    events = []
    while True:
        try:
            events.append(stream["queue"].get_nowait())
        except queue.Empty:
            break

    if events:
        return json.dumps({"status": "batch", "events": events})

    if stream["done"]:
        # 清理已结束的流会话
        with _streams_lock:
            _streams.pop(stream_id, None)
        if stream["error"]:
            return json.dumps({"status": "error", "message": stream["error"]})
        return json.dumps({"status": "done", "reply": stream["full_reply"]})

    return json.dumps({"status": "waiting"})


def chat_stream(user_input: str):
    """流式对话（向后兼容接口）。

    内部使用 chat_stream_start() + chat_stream_poll() 实现，
    但收集所有 token 后返回列表（与旧接口行为一致）。

    新代码请使用 chat_stream_start() + chat_stream_poll() 实现真正的流式输出。

    每个事件是一个 JSON 字符串：
        {"status": "streaming", "token": "..."}
        {"status": "done", "reply": "完整回复"}
        {"status": "error", "message": "错误信息"}
    """
    results = []

    stream_id = chat_stream_start(user_input)

    # 检查是否返回了错误（stream_id 可能是 JSON 字符串）
    if stream_id.startswith("{"):
        results.append(stream_id)
        return results

    import time
    while True:
        poll_result = chat_stream_poll(stream_id)
        poll_json = json.loads(poll_result)
        status = poll_json.get("status")

        if status == "batch":
            for event_str in poll_json.get("events", []):
                results.append(event_str)
        elif status in ("done", "error"):
            results.append(poll_result)
            break
        elif status == "waiting":
            time.sleep(0.01)  # 10ms 轮询间隔

    return results


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


def search_conversation(keyword: str, conversation_json: str) -> str:
    """搜索对话历史，返回匹配的消息及上下文。

    Args:
        keyword: 搜索关键词（不区分大小写）
        conversation_json: 对话历史的 JSON 字符串
            格式: [{"content":"...","isUser":true/false,"timestamp":...},...]

    Returns:
        JSON 字符串，包含 status 和匹配的消息列表（含上下文）。
    """
    try:
        messages = json.loads(conversation_json)
    except Exception:
        return json.dumps({"status": "error", "message": "无效的对话数据格式"})

    if not keyword or not keyword.strip():
        return json.dumps({"status": "ok", "matches": [], "total": 0})

    keyword_lower = keyword.strip().lower()
    matches = []

    for i, msg in enumerate(messages):
        content = msg.get("content", "")
        if keyword_lower in content.lower():
            # 获取上下文（前后各1条消息）
            context_before = messages[i - 1]["content"] if i > 0 else ""
            context_after = messages[i + 1]["content"] if i < len(messages) - 1 else ""
            matches.append({
                "index": i,
                "content": content,
                "isUser": msg.get("isUser", False),
                "timestamp": msg.get("timestamp", 0),
                "context_before": context_before,
                "context_after": context_after,
            })

    return json.dumps({"status": "ok", "matches": matches, "total": len(matches)})


def export_history(format: str = "json") -> dict:
    """导出当前对话历史。

    将当前对话上下文导出为 JSON 或 TXT 格式。

    Args:
        format: 导出格式，"json" 或 "txt"。

    Returns:
        JSON 字符串，包含状态和导出的对话历史。
    """
    player = _ctx.player
    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化，没有对话历史"})

    try:
        context = player.get_context()
        if not context:
            return json.dumps({"status": "error", "message": "对话历史为空"})

        card_info = player.get_card_info()
        card_name = card_info.get("name", "未知角色")

        if format == "txt":
            lines = [f"AI 角色扮演对话记录", f"角色: {card_name}", f"导出时间: {__import__('src.utils.time_utils', fromlist=['format_timestamp_iso']).format_timestamp_iso()}", "=" * 40, ""]
            for msg in context:
                role_name = "用户" if msg["role"] == "user" else card_name
                lines.append(f"[{role_name}]")
                lines.append(msg["content"])
                lines.append("")
            content = "\n".join(lines)
            return json.dumps({
                "status": "ok",
                "format": "txt",
                "content": content,
                "filename": f"对话记录_{card_name}_{__import__('src.utils.time_utils', fromlist=['format_timestamp_iso']).format_timestamp_iso()[:10]}.txt",
            })
        else:
            # JSON 格式
            data = {
                "card": card_name,
                "exported_at": format_timestamp_iso(),
                "messages": context,
            }
            content = json.dumps(data, ensure_ascii=False, indent=2)
            return json.dumps({
                "status": "ok",
                "format": "json",
                "content": content,
                "filename": f"对话记录_{card_name}_{format_timestamp_iso()[:10]}.json",
            })
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})