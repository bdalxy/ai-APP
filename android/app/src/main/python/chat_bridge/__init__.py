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
    _core.py      — 核心聊天引擎桥接（init/chat/reset/apply_params）
    _memory.py    — 记忆系统桥接（CRUD/检索/导出导入）
    _character.py — 角色卡管理（set/get）
    _proactive.py — 主动消息生成
    _state.py     — 共享全局状态（_ctx/CARD_PATH）
"""

# ---- 子模块命名空间（推荐用于新代码） ----
from . import _core as core
from . import _memory as memory
from . import _character as character
from . import _proactive as proactive
from . import _world_book as world_book
from . import _plugins as plugins

# ---- 核心聊天引擎（向后兼容的平铺导出） ----
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

# ---- 记忆系统 ----
from ._memory import (
    init_memory,
    get_memory_stats,
    reset_memories,
    set_extract_interval,
    set_memory_extract_mode,
    inject_memories,
    remember_turn,
    list_memories,
    get_memory,
    update_memory,
    delete_memory,
    clear_memories,
    search_memories,
    export_memories,
    import_memories,
    run_maintenance,
    analyze_trends,
    analyze_topics,
    generate_user_profile,
    analyze_quality,
    add_tag,
    tag_memory,
    untag_memory,
    get_memory_tags,
    list_all_tags,
    add_relation,
    get_relations,
    get_changelog,
    # 上下文构建
    build_context,
    build_context_compact,
    # 备份管理
    backup_full,
    backup_json,
    restore_backup,
    list_backups,
    delete_backup,
    verify_backup,
    get_backup_stats,
    # 缓存管理
    get_cache_stats,
    invalidate_cache,
    invalidate_cache_all,
    cache_cleanup,
)

# ---- 角色卡管理 ----
from ._character import (
    set_character_card,
    set_character_card_legacy,
    get_character_card,
    reload_card,
)

# ---- 主动消息 ----
from ._proactive import (
    generate_proactive_message,
)

# ---- 世界书 ----
from ._world_book import (
    list_world_books,
    enable_world_book,
    disable_world_book,
    get_enabled_world_books,
    create_world_book,
    delete_world_book,
    get_world_book,
    update_world_book,
    add_world_book_entry,
    update_world_book_entry,
    delete_world_book_entry,
    validate_world_book,
)

# ---- 插件管理 ----
from ._plugins import (
    list_plugins,
    toggle_plugin,
    get_plugin_detail,
    get_plugin_count,
)

# ---- 构建类型设置（日志级别控制） ----
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
    "init_memory",
    "get_memory_stats",
    "reset_memories",
    "set_extract_interval",
    "set_memory_extract_mode",
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
    "set_character_card",
    "set_character_card_legacy",
    "get_character_card",
    "reload_card",
    "generate_proactive_message",
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
    "list_plugins",
    "toggle_plugin",
    "get_plugin_detail",
    "get_plugin_count",
    "set_build_type",
    "_plugin_manager",
]