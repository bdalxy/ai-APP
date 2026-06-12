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

# ---- 核心聊天引擎 ----
from ._core import (
    init,
    chat,
    reset,
    get_card_info,
    set_api_key,
    list_presets,
    apply_params,
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
)

# ---- 角色卡管理 ----
from ._character import (
    set_character_card,
    get_character_card,
)

# ---- 主动消息 ----
from ._proactive import (
    generate_proactive_message,
)

# 保持向后兼容的 __all__
__all__ = [
    "init",
    "chat",
    "reset",
    "get_card_info",
    "set_api_key",
    "list_presets",
    "apply_params",
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
    "set_character_card",
    "get_character_card",
    "generate_proactive_message",
]