"""
共享状态模块 — 供 chat_bridge 子模块访问的全局单例和路径。

所有状态封装在 ChatState 类中，模块级 __getattr__ 代理保证向后兼容。
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


class ChatState:
    """聊天引擎全局状态容器（单例模式）。

    所有状态封装为实例属性，避免模块级全局变量污染。
    外部通过模块级 __getattr__ 代理访问，保持向后兼容。
    """

    _DEFAULT_MAX_WORKERS: int = 2

    def __init__(self) -> None:
        self._ctx = AppContext.get_instance()
        self._MAX_WORKERS = int(os.environ.get(
            "CHAT_BRIDGE_MAX_WORKERS", str(self._DEFAULT_MAX_WORKERS)
        ))
        # 注意：shutdown_executor() 关闭后立即重建，确保后续对话可用
        self._executor = ThreadPoolExecutor(
            max_workers=self._MAX_WORKERS, thread_name_prefix="chat_bridge"
        )
        # 线程锁，保护共享状态（_cached_memories、_current_params、_turn_since_last_inject 等）
        self._lock = threading.Lock()
        # 当前生效的对话参数（由 init()/apply_params() 写入，get_current_params() 读取）
        self._current_params: dict[str, object] = {}
        # 当前角色卡信息（由 set_character_card() 写入，get_character_card() 读取）
        self._current_character: dict[str, object] = {}
        # 记忆自动注入相关
        self._memory_inject_interval: int = 1  # 每 N 轮对话更新一次记忆注入
        self._turn_since_last_inject: int = 0  # 自上次注入以来的轮数
        self._cached_memories: list[str] = []  # 缓存的记忆文本
        # Android 上的路径
        self._PYTHON_ROOT = _PYTHON_ROOT
        self._BASE_DIR = Path(_PYTHON_ROOT)
        self._CARD_DIR = self._BASE_DIR / "data" / "role_cards"


# 全局单例
_state_inst = ChatState()


# ========== 模块级 __getattr__ 代理（PEP 562, Python 3.7+）==========
# 将未在模块命名空间中定义的属性访问转发到 _state_inst，
# 保证外部代码 from ._state import _ctx / _state._lock 等调用方式不变。

def __getattr__(name: str):
    """模块级属性代理：转发到 ChatState 单例。"""
    if name == "_state_inst":
        raise AttributeError(name)
    return getattr(_state_inst, name)


# ========== 公开函数 ==========

def shutdown_executor() -> None:
    """安全关闭全局线程池并重建。

    应在 AppContext.shutdown() 中调用，防止线程泄漏。
    关闭后立即重建一个全新的 executor，确保后续对话可用。
    多次调用安全（幂等）。
    """
    try:
        _state_inst._executor.shutdown(wait=True, cancel_futures=False)
    except Exception:
        pass  # 忽略关闭异常，不影响清理流程
    finally:
        _state_inst._executor = ThreadPoolExecutor(
            max_workers=_state_inst._MAX_WORKERS,
            thread_name_prefix="chat_bridge",
        )


def set_jailbreak_params(prompt: str, level: int, preset_name: str) -> None:
    """安全地设置破限参数到 _current_params 中。

    供 jailbreak_plugin 调用，避免外部直接访问私有成员。

    Args:
        prompt: 破限 System Prompt
        level: 破限等级
        preset_name: 预设名称
    """
    with _state_inst._lock:
        _state_inst._current_params["jailbreak_prompt"] = prompt
        _state_inst._current_params["jailbreak_enabled"] = True
        _state_inst._current_params["jailbreak_level"] = level
        _state_inst._current_params["jailbreak_preset"] = preset_name


def clear_jailbreak_params() -> None:
    """安全地清除 _current_params 中的破限参数。

    供 jailbreak_plugin 调用，避免外部直接访问私有成员。
    """
    with _state_inst._lock:
        _state_inst._current_params.pop("jailbreak_prompt", None)
        _state_inst._current_params["jailbreak_enabled"] = False
        _state_inst._current_params.pop("jailbreak_level", None)
        _state_inst._current_params.pop("jailbreak_preset", None)