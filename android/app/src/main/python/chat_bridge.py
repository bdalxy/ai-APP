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

import json
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
from src.exceptions import MemoryNotFoundError
from src.memory.vector_store import MemoryEntry
from src.proactive.engine import ProactiveEngine
from src.utils.time_utils import format_timestamp_iso
from src.utils.logger import get_logger

_log = get_logger()

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
            return json.dumps({"status": "error", "message": "API Key 未配置，请先设置 API Key"})

        # AppContext.initialize() 会先调用 shutdown() 清理旧资源（包括
        # orchestrator），再创建新的 client 和 player，从根本上解决切换
        # 预设时 orchestrator 持有旧 client 引用的问题。
        player = _ctx.initialize(preset=preset, model=model if model else "")

        # 加载角色卡
        player.load_card(str(_CARD_PATH))

        info = player.get_card_info()
        info["preset"] = preset
        return json.dumps({"status": "ok", "card": info})
    except FileNotFoundError as e:
        return json.dumps({"status": "error", "message": f"角色卡文件不存在: {e}"})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


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
        return json.dumps({"status": "error", "message": "引擎未初始化，请先调用 init()"})

    if not user_input or not user_input.strip():
        return json.dumps({"status": "error", "message": "消息不能为空"})

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

        return json.dumps({"status": "ok", "reply": reply})
    except RolePlayerError as e:
        return json.dumps({"status": "error", "message": str(e)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def reset() -> dict:
    """重置对话上下文，开始新对话。

    Returns:
        {"status": "ok"}
    """
    player = _ctx.player
    if player is not None:
        player.clear_context()
    return json.dumps({"status": "ok"})


def get_card_info() -> dict:
    """获取当前角色卡信息。

    Returns:
        {"status": "ok", "card": {...}} 或 {"status": "error", "message": str}
    """
    player = _ctx.player
    if player is None:
        return json.dumps({"status": "error", "message": "引擎未初始化"})
    try:
        info: dict[str, Any] = dict(player.get_card_info())
        info["preset"] = _ctx.current_preset
        return json.dumps({"status": "ok", "card": info})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


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
        return json.dumps({"status": "error", "message": "API Key 不能为空"})
    key = key.strip()
    # 同时设置到环境变量和 Settings 单例
    os.environ["DEEPSEEK_API_KEY"] = key
    settings.DEEPSEEK_API_KEY = key
    # 如果已有活跃的 DeepSeekClient，同步更新其 session header
    client = _ctx.client
    if client is not None:
        client.update_api_key(key)
    return json.dumps({"status": "ok"})


def list_presets() -> dict:
    """列出所有可用的 Token 预设。

    Returns:
        {"status": "ok", "presets": {...}}
    """
    from src.chat_engine.token_presets import list_presets as _list

    return json.dumps({"status": "ok", "presets": _list()})


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
        return json.dumps({"status": "error", "message": "聊天引擎未初始化，请先调用 init()"})

    try:
        orchestrator = _ctx.init_memory(db_path)
        memory_count = orchestrator.vector_store.count()
        return json.dumps({"status": "ok", "memory_count": memory_count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_memory_stats() -> dict:
    """获取记忆统计信息。

    Returns:
        {"status": "ok", "total": int, "by_type": dict, "turn_count": int}
        或 {"status": "error", "message": str}（当记忆系统未初始化时）。
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        stats = orchestrator.get_stats()
        return json.dumps({"status": "ok", **stats})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def reset_memories() -> dict:
    """清除所有记忆（调试用）。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化"})
    try:
        count = orchestrator.vector_store.count()
        orchestrator.vector_store.clear()
        _ctx.reset_turn_counter()
        return json.dumps({"status": "ok", "deleted": count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_extract_interval(n: int) -> dict:
    """设置 LLM 提取的间隔轮数。

    每 N 轮对话触发一次 LLM 提取，其余轮次使用规则模式。
    - 默认值：5（每 5 轮触发一次 LLM 提取）
    - 设为 0：始终使用 LLM 模式
    - 设为 1：每轮都使用 LLM 模式
    - 设为非常大的值（如 999999）：始终使用规则模式

    Args:
        n: LLM 提取间隔轮数，必须 >= 0。

    Returns:
        {"status": "ok", "extract_interval": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.set_extract_interval(n)
        return json.dumps({"status": "ok", "extract_interval": n})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_memory_extract_mode(mode: str = "rule") -> dict:
    """设置记忆提取模式。

    Args:
        mode: "rule"（仅规则，免费）、"mixed"（每5轮LLM）、
              "smart"（每轮LLM，费用较高）

    Returns:
        {"status": "ok", "mode": str, "interval": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    _MODE_MAP: dict[str, int] = {
        "rule": 999999,
        "mixed": 5,
        "smart": 1,
    }
    if mode not in _MODE_MAP:
        return json.dumps({
            "status": "error",
            "message": f"无效的提取模式: {mode}，可选值: rule/mixed/smart",
        })

    try:
        interval = _MODE_MAP[mode]
        orchestrator.set_extract_interval(interval)
        _log.info(f"[记忆模式] 已切换为: {mode}（间隔={interval}）")
        return json.dumps({"status": "ok", "mode": mode, "interval": interval})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


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
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if player is None:
        return json.dumps({"status": "error", "message": "聊天引擎未初始化，请先调用 init()"})

    if not query_text or not query_text.strip():
        return json.dumps({"status": "ok", "count": 0, "memories": []})

    try:
        # 从记忆库检索
        memories = orchestrator.recall(query_text.strip())

        # 注入到 RolePlayer
        player.inject_memories(memories)

        return json.dumps({"status": "ok", "count": len(memories), "memories": memories})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


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
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if not user_msg or not ai_reply:
        return json.dumps({"status": "error", "message": "user_msg 和 ai_reply 不能为空"})

    try:
        if not turn_id:
            turn_num = _ctx.increment_turn()
            turn_id = f"turn_{turn_num}_{format_timestamp_iso()}"

        stored_count = orchestrator.remember(turn_id, user_msg.strip(), ai_reply.strip())
        return json.dumps({"status": "ok", "stored_count": stored_count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆 CRUD 接口（T4.2）
# 为前端记忆可视化页面提供增删改查接口。
# 使用 SQLite rowid（整数 ID）作为对外暴露的记忆标识。
# =============================================================================


def list_memories(type_filter: str = "", page: int = 1, page_size: int = 50) -> dict:
    """分页列出记忆，支持按类型筛选。

    Args:
        type_filter: 记忆类型过滤（"episodic"/"semantic"/"user_fact"）。空字符串表示不过滤。
        page: 页码（从 1 开始）。
        page_size: 每页条数（默认 50）。

    Returns:
        {"status": "ok", "items": [...], "total": int, "page": int, "page_size": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        page = max(1, page)
        page_size = max(1, min(page_size, 200))
        offset = (page - 1) * page_size
        items = orchestrator.vector_store.list_with_rowid(
            type_filter=type_filter if type_filter else "",
            offset=offset,
            limit=page_size,
        )
        total = orchestrator.vector_store.count()
        return json.dumps({
            "status": "ok",
            "items": items,
            "total": total,
            "page": page,
            "page_size": page_size,
        })
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_memory(memory_id: int) -> dict:
    """获取单条记忆详情。

    Args:
        memory_id: 记忆 rowid（整数 ID）。

    Returns:
        {"status": "ok", "memory": {...}}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        entry = orchestrator.vector_store.get_by_rowid(memory_id)
        item = entry.to_dict()
        item["rowid"] = memory_id
        item["embedding_dim"] = len(entry.embedding)
        item.pop("embedding", None)
        return json.dumps({"status": "ok", "memory": item})
    except MemoryNotFoundError:
        return json.dumps({"status": "error", "message": f"记忆不存在: {memory_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def update_memory(memory_id: int, content: str) -> dict:
    """更新记忆内容，重新生成 embedding。

    流程：
        1. 按 rowid 获取旧条目
        2. 更新 content 并调用 embed API 重新向量化
        3. 自动重新提取关键词
        4. 写入数据库并更新倒排索引

    Args:
        memory_id: 记忆 rowid（整数 ID）。
        content: 新的记忆内容。

    Returns:
        {"status": "ok", "memory": {...}}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if not content or not content.strip():
        return json.dumps({"status": "error", "message": "内容不能为空"})

    try:
        # 1. 获取旧条目
        old_entry = orchestrator.vector_store.get_by_rowid(memory_id)
        # 2. 更新内容
        old_entry.content = content.strip()
        old_entry.keywords = []  # 清空，让 update() 方法重新提取
        # 3. 重新生成 embedding（失败不阻断，保留旧向量）
        try:
            embed_resp = orchestrator.client.embed(old_entry.content)
            old_entry.embedding = embed_resp.embeddings[0]
            _log.debug(f"[更新记忆] embedding 已重新生成: rowid={memory_id}")
        except Exception as e:
            _log.warning(f"[更新记忆] embedding 失败，保留旧向量: {e}")
        # 4. 更新存储
        orchestrator.vector_store.update(memory_id, old_entry)
        # 5. 构建返回
        item = old_entry.to_dict()
        item["rowid"] = memory_id
        item["embedding_dim"] = len(old_entry.embedding)
        item.pop("embedding", None)
        return json.dumps({"status": "ok", "memory": item})
    except MemoryNotFoundError:
        return json.dumps({"status": "error", "message": f"记忆不存在: {memory_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def delete_memory(memory_id: int) -> dict:
    """删除单条记忆。

    Args:
        memory_id: 记忆 rowid（整数 ID）。

    Returns:
        {"status": "ok", "deleted": {"rowid": int, "id": str, "content": str}}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        deleted = orchestrator.vector_store.delete_by_rowid(memory_id)
        content_preview = deleted.content[:50] + "..." if len(deleted.content) > 50 else deleted.content
        return json.dumps({
            "status": "ok",
            "deleted": {
                "rowid": memory_id,
                "id": deleted.id,
                "content": content_preview,
            },
        })
    except MemoryNotFoundError:
        return json.dumps({"status": "error", "message": f"记忆不存在: {memory_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def clear_memories() -> dict:
    """清空全部记忆（不重置 _turn_counter）。

    与 reset_memories() 的区别：
        - reset_memories(): 清空记忆 + 重置 _turn_counter（用于调试/重置用户数据）
        - clear_memories(): 仅清空记忆，保留 turn_counter（用于前端"清空全部"按钮）

    Returns:
        {"status": "ok", "deleted": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        count = orchestrator.vector_store.count()
        orchestrator.vector_store.clear()
        _log.info(f"[清空记忆] 已清空 {count} 条记忆，未重置 turn_counter")
        return json.dumps({"status": "ok", "deleted": count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def search_memories(keyword: str) -> dict:
    """按关键字模糊搜索记忆（在 content 字段中不区分大小写搜索）。

    Args:
        keyword: 搜索关键字。

    Returns:
        {"status": "ok", "items": [...], "count": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if not keyword or not keyword.strip():
        return json.dumps({"status": "ok", "items": [], "count": 0})

    try:
        results = orchestrator.vector_store.search_by_keyword(keyword.strip())
        return json.dumps({"status": "ok", "items": results, "count": len(results)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆导出 / 导入接口
# =============================================================================


def export_memories() -> dict:
    """导出所有记忆为 JSON 格式。

    使用分页遍历 vector_store.list_with_rowid() 获取全部记忆，
    每页 200 条，避免一次性加载过多数据导致 OOM。
    返回的记忆不包含 embedding 向量（太大且导出无意义）。

    Returns:
        {"status": "ok", "memories": [...], "total": int, "exported_at": str}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        all_memories: list[dict] = []
        page_size: int = 200
        offset: int = 0

        while True:
            page = orchestrator.vector_store.list_with_rowid(
                offset=offset,
                limit=page_size,
            )
            if not page:
                break
            # 转换字段名：memory_type → type，只保留必要字段
            for item in page:
                export_item: dict[str, object] = {
                    "rowid": item.get("rowid"),
                    "id": item.get("id"),
                    "type": item.get("memory_type"),
                    "content": item.get("content"),
                    "created_at": item.get("created_at"),
                    "keywords": item.get("keywords"),
                    "importance": item.get("importance"),
                }
                all_memories.append(export_item)
            if len(page) < page_size:
                break
            offset += page_size

        _log.info(f"[导出] 已导出 {len(all_memories)} 条记忆")
        return json.dumps({
            "status": "ok",
            "memories": all_memories,
            "total": len(all_memories),
            "exported_at": format_timestamp_iso(),
        }, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def import_memories(memories_json: str) -> dict:
    """从 JSON 字符串导入记忆。

    支持 export_memories() 导出的 JSON 格式。
    每条记忆会重新生成 embedding 向量并写入数据库。
    单条导入失败不会中断整体流程。

    Args:
        memories_json: export_memories() 导出的 JSON 字符串。
                       预期格式: {"memories": [{"type": str, "content": str, ...}, ...]}

    Returns:
        {"status": "ok", "imported": int, "skipped": int}
        或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})
    if _ctx.client is None:
        return json.dumps({"status": "error", "message": "API 客户端未初始化，请先调用 init()"})

    try:
        data = json.loads(memories_json)
        memories: list[dict] = data.get("memories", [])
        if not memories:
            return json.dumps({"status": "ok", "imported": 0, "skipped": 0})

        imported: int = 0
        skipped: int = 0

        for item in memories:
            try:
                # 映射 type → memory_type（export_memories 输出的是 type）
                if "type" in item and "memory_type" not in item:
                    item["memory_type"] = item.pop("type")
                # 移除 rowid（导入时不保留原 rowid，由数据库自动分配）
                item.pop("rowid", None)

                entry = MemoryEntry.from_dict(item)

                # 补充 embedding 向量
                if not entry.embedding and entry.content:
                    try:
                        embed_resp = orchestrator.client.embed_cached(entry.content)
                        if embed_resp.embeddings:
                            entry.embedding = embed_resp.embeddings[0]
                        else:
                            _log.warning(
                                f"[导入] embedding API 返回空数据: content={entry.content[:30]}..."
                            )
                    except Exception as e:
                        _log.warning(f"[导入] embedding 生成失败（将使用空向量）: {e}")

                orchestrator.vector_store.add(entry)
                imported += 1
            except Exception as e:
                _log.warning(f"[导入] 单条记忆导入失败，跳过: {e}")
                skipped += 1

        _log.info(f"[导入] 完成: 成功={imported}, 跳过={skipped}")
        return json.dumps({"status": "ok", "imported": imported, "skipped": skipped})
    except json.JSONDecodeError as e:
        return json.dumps({"status": "error", "message": f"JSON 解析失败: {e}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 角色卡管理接口
# =============================================================================


def set_character_card(name: str, personality: str, speaking_style: str, backstory: str) -> str:
    """设置当前使用的角色卡信息（运行时覆盖默认值）。

    通过 Kotlin 端 CharacterManageActivity 调用，将用户自定义的角色属性
    写入 Settings 单例，后续 prompt_builder 会自动使用这些值。

    Args:
        name: 角色名称。
        personality: 性格描述。
        speaking_style: 说话风格描述。
        backstory: 背景故事。

    Returns:
        {"status": "ok"} 或 {"status": "error", "message": str}
    """
    try:
        settings.CHARACTER_NAME = name
        settings.CHARACTER_PERSONALITY = personality
        settings.CHARACTER_SPEAKING_STYLE = speaking_style
        settings.CHARACTER_BACKSTORY = backstory
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_character_card() -> str:
    """获取当前角色卡信息。

    Returns:
        {"status": "ok", "name": str, "personality": str,
         "speaking_style": str, "backstory": str}
        或 {"status": "error", "message": str}
    """
    try:
        return json.dumps({
            "status": "ok",
            "name": getattr(settings, 'CHARACTER_NAME', '小美'),
            "personality": getattr(settings, 'CHARACTER_PERSONALITY', ''),
            "speaking_style": getattr(settings, 'CHARACTER_SPEAKING_STYLE', ''),
            "backstory": getattr(settings, 'CHARACTER_BACKSTORY', ''),
        }, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 主动消息接口（P1 手动触发 / P2 定时推送）
# =============================================================================


def generate_proactive_message() -> str:
    """生成一条主动推送消息，供 WorkManager 定时调用。

    由 ProactiveWorker/ProactiveService 通过 Chaquopy 调用。
    流程：
        1. 检查 _ctx.player 和 _ctx.client 是否已初始化
        2. 获取 retriever（如果记忆系统已初始化）
        3. 创建 ProactiveEngine 实例并调用 decide_and_generate()
        4. 返回 JSON 结果

    Returns:
        JSON 字符串:
        - 成功发送: {"status": "ok", "title": "小美", "message": "生成的消息内容"}
        - 跳过（不满足条件）: {"status": "skip", "message": "跳过原因"}
        - 错误: {"status": "error", "message": "错误信息"}
    """
    # 前置检查：聊天引擎必须已初始化
    if _ctx.player is None:
        return json.dumps({"status": "error", "message": "聊天引擎未初始化，请先调用 init()"})

    if _ctx.client is None:
        return json.dumps({"status": "error", "message": "API 客户端未初始化，请先调用 init()"})

    try:
        # 创建主动消息决策引擎
        engine = ProactiveEngine()

        # 获取角色（RolePlayer 实例，作为 card 参数传入）
        card = _ctx.player

        # 获取记忆检索器（记忆系统未初始化时传 None，不影响消息生成）
        retriever = _ctx.orchestrator.retriever if _ctx.orchestrator else None

        # 获取 API 客户端
        api_client = _ctx.client

        # 执行决策 + 生成
        result = engine.decide_and_generate(card, retriever, api_client)

        if result is None:
            # 不满足发送条件（功能未开启 / 不在活跃时段 / 间隔不足 / 生成失败）
            return json.dumps({"status": "skip", "message": "当前不满足主动消息发送条件"})

        # 成功生成消息，从角色卡获取名称作为 title
        title = card.card.name if card.card else "AI伴侣"

        _log.info(f"[主动消息] 生成成功，即将推送: {result[:50]}...")
        return json.dumps({"status": "ok", "title": title, "message": result})

    except Exception as e:
        _log.error(f"[主动消息] 异常: {e}")
        return json.dumps({"status": "error", "message": str(e)})