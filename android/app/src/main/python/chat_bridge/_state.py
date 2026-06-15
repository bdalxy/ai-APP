"""
共享状态模块 — 供 chat_bridge 子模块访问的全局单例和路径。
"""
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# Chaquopy 路径修复
# _state.py 位于 chat_bridge/ 子目录，需要将 python/ 根目录加入 sys.path
_PYTHON_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)

from src.app_context import AppContext

# 全局应用上下文单例
_ctx = AppContext.get_instance()

# 全局线程池，用于异步记忆存储等后台任务
# max_workers=2 足够（同时最多一个记忆存储 + 一个主动消息）
# 注意：shutdown_executor() 关闭后立即重建，确保后续对话可用
_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="chat_bridge")


def shutdown_executor() -> None:
    """安全关闭全局线程池并重建。

    应在 AppContext.shutdown() 中调用，防止线程泄漏。
    关闭后立即重建一个全新的 executor，确保后续对话可用。
    多次调用安全（幂等）。
    """
    global _executor
    try:
        _executor.shutdown(wait=False)
    except Exception:
        pass  # 忽略关闭异常，不影响清理流程
    finally:
        _executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="chat_bridge")

# 线程锁，保护共享状态（_cached_memories、_current_params、_turn_since_last_inject 等）
_lock = threading.Lock()

# 当前生效的对话参数（由 init()/apply_params() 写入，get_current_params() 读取）
_current_params: dict[str, object] = {}

# 当前角色卡信息（由 set_character_card() 写入，get_character_card() 读取）
_current_character: dict[str, str] = {}

# 记忆自动注入相关
_memory_inject_interval: int = 3  # 每 N 轮对话更新一次记忆注入
_turn_since_last_inject: int = 0  # 自上次注入以来的轮数
_cached_memories: list[str] = []  # 缓存的记忆文本

# Android 上的角色卡路径（data/ 在 python/ 根目录下）
_BASE_DIR = Path(_PYTHON_ROOT)
_CARD_PATH = _BASE_DIR / "data" / "role_cards" / "小美.json"