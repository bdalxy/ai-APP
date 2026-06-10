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
import sys
import threading
from pathlib import Path
from typing import Any

# =============================================================================
# Chaquopy 路径修复
# 确保当前目录（src/main/python/）在 sys.path 中，
# 这样 from src.xxx 的绝对导入才能正确解析。
# =============================================================================
_CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if _CURRENT_DIR not in sys.path:
    sys.path.insert(0, _CURRENT_DIR)

from src.app_context import AppContext
from src.chat_engine.role_player import RolePlayerError
from src.config.settings import settings
from src.utils.time_utils import format_timestamp_iso
from src.utils.logger import get_logger

_log = get_logger(__name__)

# 全局应用上下文单例，统一管理 client/player/orchestrator 生命周期
_ctx = AppContext.get_instance()

# Android 上的角色卡路径（与 Python 源码同目录的 data/role_cards/）
_BASE_DIR = Path(os.path.dirname(os.path.abspath(__file__)))
_CARD_PATH = _BASE_DIR / "data" / "role_cards" / "小美.json"


def init(preset: str = "balanced", model: str = "") -> dict:
    """初始化聊天引擎。

    加载角色卡、配置 API 客户端。首次调用时自动完成。
    如果已初始化，先通过 AppContext.shutdown() 清理旧资源再创建新实例。

    Args:
        preset: Token 预设模式 ("quality"/"balanced"/"economy")。
        model: 模型名称，空字符串表示使用预设默认模型。

    Returns:
        {"status": "ok", "card": {"name": str, "nickname": str, "gender": str}}
        或 {"status": "error", "message": str}
    """
    try:
        if not settings.DEEPSEEK_API_KEY:
            return {"status": "error", "message": "API Key 未配置，请先设置 API Key"}

        # AppContext.initialize() 会先调用 shutdown() 清理旧资源（包括
        # orchestrator），再创建新的 client 和 player，从根本上解决切换
        # 预设时 orchestrator 持有旧 client 引用的问题。
        player = _ctx.initialize(preset=preset, model=model if model else "")

        # 加载角色卡
        player.load_card(str(_CARD_PATH))

        info = player.get_card_info()
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
    player = _ctx.player
    orchestrator = _ctx.orchestrator

    if player is None:
        return {"status": "error", "message": "引擎未初始化，请先调用 init()"}

    if not user_input or not user_input.strip():
        return {"status": "error", "message": "消息不能为空"}

    try:
        user_input = user_input.strip()
        reply = player.chat(user_input)

        # 记忆存储：异步执行，不阻塞对话回复
        # 使用 daemon 线程，主进程退出时自动结束
        # 记忆存储失败不影响对话，仅在 orchestrator 已初始化时执行
        if orchestrator is not None and reply:
            threading.Thread(
                target=_auto_remember,
                args=(user_input, reply),
                daemon=True,
            ).start()

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
    player = _ctx.player
    if player is not None:
        player.clear_context()
    return {"status": "ok"}


def get_card_info() -> dict:
    """获取当前角色卡信息。

    Returns:
        {"status": "ok", "card": {...}} 或 {"status": "error", "message": str}
    """
    player = _ctx.player
    if player is None:
        return {"status": "error", "message": "引擎未初始化"}
    try:
        info: dict[str, Any] = dict(player.get_card_info())
        info["preset"] = _ctx.current_preset
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
    key = key.strip()
    # 同时设置到环境变量和 Settings 单例
    os.environ["DEEPSEEK_API_KEY"] = key
    settings.DEEPSEEK_API_KEY = key
    # 如果已有活跃的 DeepSeekClient，同步更新其 session header
    client = _ctx.client
    if client is not None:
        client.update_api_key(key)
    return {"status": "ok"}


def list_presets() -> dict:
    """列出所有可用的 Token 预设。

    Returns:
        {"status": "ok", "presets": {...}}
    """
    from src.chat_engine.token_presets import list_presets as _list

    return {"status": "ok", "presets": _list()}


# =============================================================================
# 记忆系统接口
# =============================================================================


def _auto_remember(user_input: str, ai_reply: str) -> None:
    """内部函数：对话完成后自动存储记忆。

    生成递增的轮次 ID，调用 orchestrator.remember()。
    所有异常静默处理，记忆存储失败不影响对话。

    Args:
        user_input: 用户输入文本。
        ai_reply: AI 回复文本。
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return

    try:
        turn_num = _ctx.increment_turn()
        turn_id = f"turn_{turn_num}_{format_timestamp_iso()}"
        orchestrator.remember(turn_id, user_input, ai_reply)
    except Exception as e:
        # 记忆存储失败不阻断对话，但记录日志便于排查
        from src.utils.logger import get_logger as _get_logger
        _get_logger().warning(f"[自动记忆] 存储失败（对话不受影响）: {e}")


def init_memory(db_path: str) -> dict:
    """初始化记忆系统。

    从 Android 端接收 filesDir 路径（如 /data/data/xxx/files），
    在该目录下创建 memories.db，并通过 AppContext.init_memory()
    构建 VectorStore 和 MemoryOrchestrator。

    调用前需要先初始化聊天引擎（init()），否则返回错误。

    Args:
        db_path: Android 端可写文件目录路径（通常是 context.filesDir）。

    Returns:
        {"status": "ok", "memory_count": int}
        或 {"status": "error", "message": str}
    """
    if _ctx.player is None:
        return {"status": "error", "message": "聊天引擎未初始化，请先调用 init()"}

    try:
        orchestrator = _ctx.init_memory(db_path)
        memory_count = orchestrator.vector_store.count()
        return {"status": "ok", "memory_count": memory_count}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_memory_stats() -> dict:
    """获取记忆统计信息。

    Returns:
        {"status": "ok", "total": int, "by_type": dict, "turn_count": int}
        或 {"status": "error", "message": str}（当记忆系统未初始化时）。
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    try:
        stats = orchestrator.get_stats()
        return {"status": "ok", **stats}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def reset_memories() -> dict:
    """清除所有记忆（调试用）。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化"}
    try:
        count = orchestrator.vector_store.count()
        orchestrator.vector_store.clear()
        _ctx.reset_turn_counter()
        return {"status": "ok", "deleted": count}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def inject_memories(query_text: str = "") -> dict:
    """检索相关记忆并注入到 RolePlayer 的 System Prompt。

    使用用户当前输入作为检索查询，检索到的记忆会自动纳入
    下一次 chat() 调用的 System Prompt 中。

    Args:
        query_text: 用于检索记忆的查询文本（通常为用户最新输入）。
                    如果为空，不做任何操作。

    Returns:
        {"status": "ok", "count": int, "memories": list[str]}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    player = _ctx.player

    if orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    if player is None:
        return {"status": "error", "message": "聊天引擎未初始化，请先调用 init()"}

    if not query_text or not query_text.strip():
        return {"status": "ok", "count": 0, "memories": []}

    try:
        # 从记忆库检索
        memories = orchestrator.recall(query_text.strip())

        # 注入到 RolePlayer
        player.inject_memories(memories)

        return {"status": "ok", "count": len(memories), "memories": memories}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def remember_turn(turn_id: str = "", user_msg: str = "", ai_reply: str = "") -> dict:
    """手动存储一轮对话的记忆。

    通常记忆存储会在 chat() 中自动完成，此函数用于特殊场景：
    - 需要手动控制记忆存储时机
    - 需要补录历史对话

    Args:
        turn_id: 对话轮次 ID。为空时自动生成。
        user_msg: 用户输入消息。
        ai_reply: AI 回复消息。

    Returns:
        {"status": "ok", "stored_count": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    if not user_msg or not ai_reply:
        return {"status": "error", "message": "user_msg 和 ai_reply 不能为空"}

    try:
        if not turn_id:
            turn_num = _ctx.increment_turn()
            turn_id = f"turn_{turn_num}_{format_timestamp_iso()}"

        stored_count = orchestrator.remember(turn_id, user_msg.strip(), ai_reply.strip())
        return {"status": "ok", "stored_count": stored_count}
    except Exception as e:
        return {"status": "error", "message": str(e)}