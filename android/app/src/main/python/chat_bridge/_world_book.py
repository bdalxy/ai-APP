"""
世界书桥接模块 — 封装 WorldBookEngine 为 Chaquopy 可调用的简单接口。

支持多世界书同时启用（勾选机制）：
- enable_world_book(name) / disable_world_book(name)
- 每次 chat() 调用时对所有已启用的世界书执行 match_and_inject
"""

import json
import os
import sys
from pathlib import Path

# 路径修复（与 _state.py 一致）
_PYTHON_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)

from src.world_book.world_book import WorldBookEngine
from src.utils.logger import get_logger

_log = get_logger()

# 惰性初始化的引擎单例
_wb_engine: WorldBookEngine | None = None
# 已启用的世界书名称集合
_enabled_books: set[str] = set()


def _get_engine() -> WorldBookEngine:
    """获取或初始化 WorldBookEngine 单例。"""
    global _wb_engine
    if _wb_engine is None:
        world_books_dir = str(Path(_PYTHON_ROOT) / "data" / "world_books")
        _log.info(f"[世界书] 初始化引擎, 数据目录: {world_books_dir}")
        _wb_engine = WorldBookEngine(books_dir=world_books_dir)
    return _wb_engine


def list_world_books() -> str:
    """返回所有可用世界书列表。

    Returns:
        JSON: {"status": "ok", "books": [{"name": "...", "description": "...", "entries": N}, ...]}
    """
    try:
        engine = _get_engine()
        books = engine.list_books()
        return json.dumps({"status": "ok", "books": books}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] list_world_books 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def enable_world_book(name: str) -> str:
    """启用指定世界书。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "name": "...", "enabled_count": N}
    """
    try:
        engine = _get_engine()

        # 检查世界书是否存在
        if not engine.set_active(name):
            available = [b["name"] for b in engine.list_books()]
            return json.dumps({
                "status": "error",
                "message": f"世界书 '{name}' 不存在",
                "available": available,
            }, ensure_ascii=False)

        _enabled_books.add(name)
        _log.info(f"[世界书] 已启用: {name} (当前启用: {len(_enabled_books)} 本)")
        return json.dumps({
            "status": "ok",
            "name": name,
            "enabled_count": len(_enabled_books),
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] enable_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def disable_world_book(name: str) -> str:
    """禁用指定世界书。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "name": "...", "enabled_count": N}
    """
    try:
        _enabled_books.discard(name)
        _log.info(f"[世界书] 已禁用: {name} (当前启用: {len(_enabled_books)} 本)")
        return json.dumps({
            "status": "ok",
            "name": name,
            "enabled_count": len(_enabled_books),
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] disable_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def get_enabled_world_books() -> str:
    """返回当前已启用的世界书列表。

    Returns:
        JSON: {"status": "ok", "enabled": ["name1", "name2", ...]}
    """
    return json.dumps({
        "status": "ok",
        "enabled": list(_enabled_books),
    }, ensure_ascii=False)


def _match_and_inject_for_all(user_input: str) -> str:
    """对所有已启用的世界书执行匹配注入，合并结果。

    Args:
        user_input: 用户输入文本

    Returns:
        合并后的世界书上下文文本（空字符串表示无匹配）
    """
    if not _enabled_books:
        return ""

    engine = _get_engine()
    results = []

    for name in sorted(_enabled_books):
        try:
            if engine.set_active(name):
                injected = engine.match_and_inject(user_input)
                if injected.strip():
                    results.append(injected)
                    _log.debug(f"[世界书] {name} 匹配注入: {len(injected)} 字符")
        except Exception as e:
            _log.warning(f"[世界书] {name} 匹配注入失败: {e}")

    return "\n\n".join(results) if results else ""


def _reset_round_for_all() -> None:
    """重置所有世界书的轮次计数（开始新对话时调用）。"""
    engine = _get_engine()
    engine.reset_round()
    _log.debug("[世界书] 已重置所有世界书轮次计数")