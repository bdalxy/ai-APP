"""
共享状态模块 — 供 chat_bridge 子模块访问的全局单例和路径。
"""
import os
import sys
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
_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="chat_bridge")

# Android 上的角色卡路径（data/ 在 python/ 根目录下）
_BASE_DIR = Path(_PYTHON_ROOT)
_CARD_PATH = _BASE_DIR / "data" / "role_cards" / "小美.json"