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

模块拆分（v1.1）：
    _core.py      — 核心聊天引擎桥接（init/chat/reset/apply_params）【冷启动必需】
    _memory.py    — 记忆系统桥接（CRUD/检索/导出导入）【延迟加载】
    _character.py — 角色卡管理（set/get）【延迟加载】
    _proactive.py — 主动消息生成【延迟加载】
    _state.py     — 共享全局状态（_ctx/CARD_PATH）【冷启动必需】

冷启动优化（v1.2）：
    非核心模块（_memory, _character, _proactive, _world_book, _plugins）
    改为延迟加载：首次通过 module.callAttr() 访问时才导入，
    减少 getModule("chat_bridge") 的初始化耗时。
"""

import importlib
import threading

# ===== 核心模块（冷启动必需，立即加载） =====
from . import _core as core

# 从 _core 导出核心聊天引擎函数（冷启动必需）
from ._core import (
    init,
    chat,
    chat_stream,
    chat_stream_start,
    chat_stream_poll,
    chat_stream_cancel,
    cleanup_all_streams,
    reset,
    restore_history,
    get_card_info,
    set_api_key,
    list_presets,
    apply_params,
    get_current_params,
    export_history,
    search_conversation,
    _plugin_manager,
)

# ===== 构建类型设置（日志级别控制）—— 冷启动必需 =====
from src.config.settings import settings


def set_build_type(build_type: str) -> str:
    """由 Kotlin 侧调用，设置构建类型以控制日志级别。

    Release 构建时应传入 "release"，将日志级别降为 WARNING，
    避免 logcat 泄露用户对话内容。

    Args:
        build_type: "debug" 或 "release"

    Returns:
        "ok"
    """
    settings.set_build_type(build_type)
    return "ok"


# ===== 非核心模块延迟加载机制 =====
# 延迟加载的模块缓存字典
_lazy_modules: dict[str, object] = {}
_lazy_modules_lock = threading.Lock()  # 保护 _lazy_modules 的并发访问

# 每个函数名到其所属子模块的映射，用于 __getattr__ 拦截
# 格式：{函数名: 子模块名}
_FUNC_TO_SUBMODULE: dict[str, str] = {}

# 子模块名列表（用于子模块命名空间访问 chat_bridge.memory 等）
_SUBMODULE_NAMES = ("_memory", "_character", "_proactive", "_world_book", "_plugins")

# 填充 _FUNC_TO_SUBMODULE 映射（避免硬编码长列表，使用子模块名约定）
# 从 __all__ 中扣除核心函数名，剩余即为非核心函数
_submodule_export_map = {
    "_memory": [
        "init_memory", "get_memory_stats", "reset_memories",
        "set_extract_interval", "set_memory_extract_mode",
        "set_memory_config", "get_memory_config", "inject_memories",
        "remember_turn", "list_memories", "get_memory", "update_memory",
        "delete_memory", "clear_memories", "search_memories",
        "export_memories", "import_memories", "run_maintenance",
        "analyze_trends", "analyze_topics", "generate_user_profile",
        "analyze_quality", "add_tag", "tag_memory", "untag_memory",
        "get_memory_tags", "list_all_tags", "add_relation",
        "get_relations", "get_changelog",
        "build_context", "build_context_compact",
        "backup_full", "backup_json", "restore_backup",
        "list_backups", "delete_backup", "verify_backup",
        "get_backup_stats", "get_cache_stats", "invalidate_cache",
        "invalidate_cache_all", "cache_cleanup",
    ],
    "_character": [
        "set_character_card", "set_character_card_legacy",
        "get_character_card", "reload_card",
    ],
    "_proactive": [
        "generate_proactive_message",
    ],
    "_world_book": [
        "list_world_books", "enable_world_book", "disable_world_book",
        "get_enabled_world_books", "create_world_book", "delete_world_book",
        "get_world_book", "update_world_book", "add_world_book_entry",
        "update_world_book_entry", "delete_world_book_entry",
        "validate_world_book",
    ],
    "_plugins": [
        "list_plugins", "toggle_plugin", "get_plugin_detail",
        "get_plugin_count",
    ],
}

for _submod, _funcs in _submodule_export_map.items():
    for _f in _funcs:
        _FUNC_TO_SUBMODULE[_f] = _submod


def _load_submodule(submod_name: str):
    """延迟加载指定的子模块（带缓存和线程安全保护）。

    Args:
        submod_name: 子模块文件名（不含 .py），如 "_memory"

    Returns:
        已加载的子模块对象
    """
    with _lazy_modules_lock:
        if submod_name not in _lazy_modules:
            _lazy_modules[submod_name] = importlib.import_module(
                f".{submod_name}", __package__
            )
        return _lazy_modules[submod_name]


def __getattr__(name: str):
    """模块级属性代理（PEP 562），实现非核心模块的延迟加载。

    当 Kotlin 端通过 module.callAttr("init_memory") 访问时，
    Chaquopy 会触发 getattr(chat_bridge, "init_memory")，
    此方法拦截并延迟加载对应的子模块。

    也支持子模块命名空间访问：chat_bridge.memory → _memory 模块。

    Args:
        name: 属性名（函数名或子模块短名）

    Returns:
        对应的函数对象或子模块对象

    Raises:
        AttributeError: 属性名不在已知映射中
    """
    # 检查是否为子模块命名空间（如 memory, character, world_book 等）
    submod_short_names = {
        "_memory": "memory",
        "_character": "character",
        "_proactive": "proactive",
        "_world_book": "world_book",
        "_plugins": "plugins",
    }
    for submod_name, short_name in submod_short_names.items():
        if name == short_name:
            return _load_submodule(submod_name)

    # 检查是否为延迟加载的函数
    if name in _FUNC_TO_SUBMODULE:
        submod = _load_submodule(_FUNC_TO_SUBMODULE[name])
        return getattr(submod, name)

    raise AttributeError(f"module 'chat_bridge' has no attribute '{name}'")


# 保持向后兼容的 __all__
__all__ = [
    # 子模块命名空间
    "core",
    "memory",
    "character",
    "proactive",
    "world_book",
    "plugins",
    # 核心聊天引擎
    "init",
    "chat",
    "chat_stream",
    "chat_stream_start",
    "chat_stream_poll",
    "chat_stream_cancel",
    "cleanup_all_streams",
    "reset",
    "get_card_info",
    "set_api_key",
    "list_presets",
    "apply_params",
    "get_current_params",
    "export_history",
    "search_conversation",
    # 记忆系统（延迟加载）
    "init_memory",
    "get_memory_stats",
    "reset_memories",
    "set_extract_interval",
    "set_memory_extract_mode",
    "set_memory_config",
    "get_memory_config",
    "inject_memories",
    "remember_turn",
    "list_memories",
    "get_memory",
    "update_memory",
    "delete_memory",
    "clear_memories",
    "search_memories",
    "export_memories",
    "import_memories",
    "run_maintenance",
    "analyze_trends",
    "analyze_topics",
    "generate_user_profile",
    "analyze_quality",
    "add_tag",
    "tag_memory",
    "untag_memory",
    "get_memory_tags",
    "list_all_tags",
    "add_relation",
    "get_relations",
    "get_changelog",
    "build_context",
    "build_context_compact",
    "backup_full",
    "backup_json",
    "restore_backup",
    "list_backups",
    "delete_backup",
    "verify_backup",
    "get_backup_stats",
    "get_cache_stats",
    "invalidate_cache",
    "invalidate_cache_all",
    "cache_cleanup",
    # 角色卡管理（延迟加载）
    "set_character_card",
    "set_character_card_legacy",
    "get_character_card",
    "reload_card",
    # 主动消息（延迟加载）
    "generate_proactive_message",
    # 世界书（延迟加载）
    "list_world_books",
    "enable_world_book",
    "disable_world_book",
    "get_enabled_world_books",
    "create_world_book",
    "delete_world_book",
    "get_world_book",
    "update_world_book",
    "add_world_book_entry",
    "update_world_book_entry",
    "delete_world_book_entry",
    "validate_world_book",
    # 插件管理（延迟加载）
    "list_plugins",
    "toggle_plugin",
    "get_plugin_detail",
    "get_plugin_count",
    # 构建类型
    "set_build_type",
    "_plugin_manager",
]