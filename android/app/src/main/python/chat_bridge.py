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

from src.api_client.deepseek import DeepSeekClient
from src.chat_engine.role_player import RolePlayer, RolePlayerError
from src.config.settings import settings
from src.memory.vector_store import VectorStore
from src.memory.orchestrator import MemoryOrchestrator
from src.utils.time_utils import format_timestamp_iso

# 全局单例，整个应用生命周期内复用
_player: "RolePlayer | None" = None
_current_preset: str = "balanced"

# 记忆系统全局单例
_orchestrator: "MemoryOrchestrator | None" = None
_turn_counter: int = 0

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
        user_input = user_input.strip()
        reply = _player.chat(user_input)

        # 记忆存储：对话完成后自动提取并存储本轮记忆
        # 记忆存储失败不影响对话，仅在 orchestrator 已初始化时执行
        if _orchestrator is not None and reply:
            _auto_remember(user_input, reply)

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
    global _turn_counter

    try:
        _turn_counter += 1
        turn_id = f"turn_{_turn_counter}_{format_timestamp_iso()}"
        _orchestrator.remember(turn_id, user_input, ai_reply)
    except Exception as e:
        # 静默处理，记忆存储失败不阻断对话
        pass


def init_memory(db_path: str) -> dict:
    """初始化记忆系统。

    从 Android 端接收 filesDir 路径（如 /data/data/xxx/files），
    在该目录下创建 memories.db，并构建 VectorStore 和 MemoryOrchestrator。

    调用前需要先初始化聊天引擎（init()），否则返回错误。

    Args:
        db_path: Android 端可写文件目录路径（通常是 context.filesDir）。

    Returns:
        {"status": "ok", "memory_count": int}
        或 {"status": "error", "message": str}
    """
    global _orchestrator, _turn_counter

    if _player is None:
        return {"status": "error", "message": "聊天引擎未初始化，请先调用 init()"}

    try:
        # 关闭旧的 orchestrator，避免资源泄露
        if _orchestrator is not None:
            try:
                _orchestrator.close()
            except Exception:
                pass

        # 构建数据库文件路径
        db_file = os.path.join(db_path.rstrip("/").rstrip("\\"), "memories.db")

        # 创建 VectorStore（使用_p player 共享的 DeepSeekClient）
        vector_store = VectorStore(db_file)

        # 创建 MemoryOrchestrator
        _orchestrator = MemoryOrchestrator(
            vector_store=vector_store,
            deepseek_client=_player.client,
        )
        _turn_counter = 0

        memory_count = vector_store.count()
        return {"status": "ok", "memory_count": memory_count}
    except Exception as e:
        return {"status": "error", "message": f"记忆系统初始化失败: {e}"}


def get_memory_stats() -> dict:
    """获取记忆统计信息。

    Returns:
        {"status": "ok", "total": int, "by_type": dict, "turn_count": int}
        或 {"status": "error", "message": str}（当记忆系统未初始化时）。
    """
    if _orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    try:
        stats = _orchestrator.get_stats()
        return {"status": "ok", **stats}
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
    if _orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    if _player is None:
        return {"status": "error", "message": "聊天引擎未初始化，请先调用 init()"}

    if not query_text or not query_text.strip():
        return {"status": "ok", "count": 0, "memories": []}

    try:
        # 从记忆库检索
        memories = _orchestrator.recall(query_text.strip())

        # 注入到 RolePlayer
        _player.inject_memories(memories)

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
    if _orchestrator is None:
        return {"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"}

    if not user_msg or not ai_reply:
        return {"status": "error", "message": "user_msg 和 ai_reply 不能为空"}

    global _turn_counter

    try:
        if not turn_id:
            _turn_counter += 1
            turn_id = f"turn_{_turn_counter}_{format_timestamp_iso()}"

        stored_count = _orchestrator.remember(turn_id, user_msg.strip(), ai_reply.strip())
        return {"status": "ok", "stored_count": stored_count}
    except Exception as e:
        return {"status": "error", "message": str(e)}