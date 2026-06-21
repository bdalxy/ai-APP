"""
记忆系统桥接模块 — 记忆存储、检索、CRUD、导出/导入等。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os

from src.exceptions import MemoryNotFoundError
from src.memory.vector_store import MemoryEntry, _init_encryption
from src.utils.time_utils import format_timestamp_iso
from src.utils.logger import get_logger

from ._state import _ctx
from . import _state

_log = get_logger()


def init_memory(db_path: str) -> str:
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
        _init_encryption(db_path)
        orchestrator = _ctx.init_memory(db_path)
        memory_count = orchestrator.vector_store.count()
        return json.dumps({"status": "ok", "memory_count": memory_count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_memory_stats() -> str:
    """获取记忆统计信息。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        stats = orchestrator.get_stats()
        return json.dumps({"status": "ok", **stats})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def reset_memories() -> str:
    """清除所有记忆（调试用）。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化"})
    try:
        count = orchestrator.vector_store.count()
        orchestrator.vector_store.clear()
        _state._cached_memories = []  # 清除缓存，防止已删除记忆继续注入
        _ctx.reset_turn_counter()
        return json.dumps({"status": "ok", "deleted": count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_extract_interval(n: int) -> str:
    """设置 LLM 提取的间隔轮数。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.set_extract_interval(n)
        return json.dumps({"status": "ok", "extract_interval": n})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_memory_extract_mode(mode: str = "rule") -> str:
    """设置记忆提取模式。"""
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


def inject_memories(query_text: str = "") -> str:
    """检索相关记忆并注入到 RolePlayer 的 System Prompt。"""
    orchestrator = _ctx.orchestrator
    player = _ctx.player

    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if player is None:
        return json.dumps({"status": "error", "message": "聊天引擎未初始化，请先调用 init()"})

    if not query_text or not query_text.strip():
        return json.dumps({"status": "ok", "count": 0, "memories": []})

    try:
        memories = orchestrator.recall(query_text.strip())
        player.inject_memories(memories)
        return json.dumps({"status": "ok", "count": len(memories), "memories": memories})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def remember_turn(turn_id: str = "", user_msg: str = "", ai_reply: str = "") -> str:
    """手动存储一轮对话的记忆。"""
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
# 记忆 CRUD 接口
# =============================================================================


def list_memories(type_filter: str = "", page: int = 1, page_size: int = 50) -> str:
    """分页列出记忆，支持按类型筛选。"""
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


def get_memory(memory_id: int) -> str:
    """获取单条记忆详情。"""
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


def update_memory(memory_id: int, content: str) -> str:
    """更新记忆内容，重新生成 embedding。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if not content or not content.strip():
        return json.dumps({"status": "error", "message": "内容不能为空"})

    try:
        old_entry = orchestrator.vector_store.get_by_rowid(memory_id)
        old_entry.content = content.strip()
        old_entry.keywords = []
        try:
            embed_resp = orchestrator.client.embed(old_entry.content)
            old_entry.embedding = embed_resp.embeddings[0]
            _log.debug(f"[更新记忆] embedding 已重新生成: rowid={memory_id}")
        except Exception as e:
            _log.warning(f"[更新记忆] embedding 失败，保留旧向量: {e}")
        orchestrator.vector_store.update(memory_id, old_entry)
        item = old_entry.to_dict()
        item["rowid"] = memory_id
        item["embedding_dim"] = len(old_entry.embedding)
        item.pop("embedding", None)
        return json.dumps({"status": "ok", "memory": item})
    except MemoryNotFoundError:
        return json.dumps({"status": "error", "message": f"记忆不存在: {memory_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def delete_memory(memory_id: int) -> str:
    """删除单条记忆。"""
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


def clear_memories() -> str:
    """清空全部记忆（不重置 _turn_counter）。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        count = orchestrator.vector_store.count()
        orchestrator.vector_store.clear()
        _state._cached_memories = []  # 清除缓存，防止已删除记忆继续注入
        _log.info(f"[清空记忆] 已清空 {count} 条记忆，未重置 turn_counter")
        return json.dumps({"status": "ok", "deleted": count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def search_memories(keyword: str) -> str:
    """按关键字模糊搜索记忆（在 content 字段中不区分大小写搜索）。"""
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
# 记忆维护接口
# =============================================================================


def run_maintenance() -> str:
    """执行记忆库维护任务（衰减更新 + 合并 + 清理 + 健康检查）。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        report = orchestrator.run_maintenance()
        return json.dumps({"status": "ok", **report})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆分析接口
# =============================================================================


def analyze_trends(days: int = 30) -> str:
    """分析记忆变化趋势。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        trends = orchestrator.analyze_trends(days)
        return json.dumps({"status": "ok", **trends})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def analyze_topics(num_clusters: int = 5) -> str:
    """主题聚类分析。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        topics = orchestrator.analyze_topics(num_clusters)
        return json.dumps({"status": "ok", **topics})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def generate_user_profile() -> str:
    """生成用户画像。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        profile = orchestrator.generate_user_profile()
        return json.dumps({"status": "ok", **profile})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def analyze_quality() -> str:
    """分析记忆库质量。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        quality = orchestrator.analyze_quality()
        return json.dumps({"status": "ok", **quality})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆标签接口
# =============================================================================


def add_tag(name: str, color: str = "#9B59B6") -> str:
    """添加标签。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        tag_id = orchestrator.add_tag(name, color)
        return json.dumps({"status": "ok", "tag_id": tag_id, "name": name})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def tag_memory(memory_id: str, tag_name: str) -> str:
    """为记忆添加标签。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.tag_memory(memory_id, tag_name)
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def untag_memory(memory_id: str, tag_name: str) -> str:
    """移除记忆的标签。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.untag_memory(memory_id, tag_name)
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_memory_tags(memory_id: str) -> str:
    """获取记忆的标签列表。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        tags = orchestrator.get_memory_tags(memory_id)
        return json.dumps({"status": "ok", "tags": tags})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def list_all_tags() -> str:
    """列出所有标签。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        tags = orchestrator.list_all_tags()
        return json.dumps({"status": "ok", "tags": tags})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆关系接口
# =============================================================================


def add_relation(source_id: str, target_id: str, relation_type: str = "related_to", confidence: float = 0.5) -> str:
    """添加记忆关系。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        rel_id = orchestrator.add_relation(source_id, target_id, relation_type, confidence)
        return json.dumps({"status": "ok", "relation_id": rel_id})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_relations(memory_id: str) -> str:
    """获取记忆的关系列表。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        relations = orchestrator.get_relations(memory_id)
        return json.dumps({"status": "ok", "relations": relations})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 变更日志接口
# =============================================================================


def get_changelog(memory_id: str = "", limit: int = 50) -> str:
    """获取变更日志。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        changelog = orchestrator.get_changelog(memory_id, limit)
        return json.dumps({"status": "ok", "changelog": changelog})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 记忆导出 / 导入接口
# =============================================================================


def export_memories(password: str = "") -> str:
    """导出所有记忆为 JSON 格式。"""
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

        result = {
            "status": "ok",
            "total": len(all_memories),
            "exported_at": format_timestamp_iso(),
            "encrypted": False,
        }

        if password:
            if len(password) < 8:
                return json.dumps({"status": "error", "message": "密码长度不足，至少需要 8 位"})
            salt = os.urandom(16)
            key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600000, dklen=32)
            plaintext = json.dumps(all_memories, ensure_ascii=False).encode("utf-8")
            try:
                from cryptography.fernet import Fernet
                fernet_key = base64.urlsafe_b64encode(key)
                f = Fernet(fernet_key)
                ciphertext = f.encrypt(plaintext)
                encrypted = base64.b64encode(salt + ciphertext).decode("ascii")
            except ImportError:
                return json.dumps({"status": "error", "message": "cryptography 库不可用，无法加密导出"})
            result["memories"] = encrypted
            result["encrypted"] = True
            _log.info("[导出] 已加密导出")
        else:
            result["memories"] = all_memories

        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def import_memories(memories_json: str) -> str:
    """从 JSON 字符串导入记忆。"""
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
                if "type" in item and "memory_type" not in item:
                    item["memory_type"] = item.pop("type")
                item.pop("rowid", None)

                entry = MemoryEntry.from_dict(item)

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
# 上下文构建接口
# =============================================================================


def build_context(query_text: str, conversation_history_json: str = "", user_profile_json: str = "") -> str:
    """构建优化的记忆上下文窗口。

    在有限 Token 预算内，分层注入最相关的记忆信息。

    Args:
        query_text: 用户当前查询文本。
        conversation_history_json: 对话历史 JSON 数组字符串（可选）。
        user_profile_json: 用户画像 JSON 字符串（可选）。

    Returns:
        {"status": "ok", "context": {...}} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    if not query_text or not query_text.strip():
        return json.dumps({"status": "error", "message": "query_text 不能为空"})

    try:
        conversation_history: list[str] | None = None
        if conversation_history_json:
            conversation_history = json.loads(conversation_history_json)

        user_profile: dict | None = None
        if user_profile_json:
            user_profile = json.loads(user_profile_json)

        context = orchestrator.build_context(
            query_text=query_text.strip(),
            conversation_history=conversation_history,
            user_profile=user_profile,
        )

        return json.dumps({
            "status": "ok",
            "formatted_text": context.formatted_text,
            "core_memory_count": len(context.core_memories),
            "extended_memory_count": len(context.extended_memories),
            "recent_memory_count": len(context.recent_memories),
            "total_chars": context.total_chars,
            "estimated_tokens": context.estimated_tokens,
            "budget_used_ratio": context.budget_used_ratio,
            "build_time_ms": context.build_time_ms,
        }, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def build_context_compact(query_text: str, max_memories: int = 5) -> str:
    """快速构建紧凑的记忆上下文。

    Args:
        query_text: 查询文本。
        max_memories: 最大记忆数。

    Returns:
        {"status": "ok", "context": str} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        context_text = orchestrator.build_context_compact(
            query_text=query_text.strip(),
            max_memories=max_memories,
        )
        return json.dumps({"status": "ok", "context": context_text}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 备份管理接口
# =============================================================================


def backup_full() -> str:
    """执行完整备份（SQLite 数据库文件备份）。

    Returns:
        {"status": "ok", "metadata": {...}} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        metadata = orchestrator.backup_full()
        if metadata is None:
            return json.dumps({"status": "error", "message": "备份失败，可能数据库为内存模式"})
        return json.dumps({"status": "ok", "metadata": metadata.to_dict()})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def backup_json() -> str:
    """执行 JSON 格式备份（记忆导出为 JSON 文件）。

    Returns:
        {"status": "ok", "metadata": {...}} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        metadata = orchestrator.backup_json()
        if metadata is None:
            return json.dumps({"status": "error", "message": "JSON 备份失败"})
        return json.dumps({"status": "ok", "metadata": metadata.to_dict()})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def restore_backup(backup_id: str) -> str:
    """从备份恢复记忆库。

    Args:
        backup_id: 备份标识符。

    Returns:
        {"status": "ok"} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        success = orchestrator.restore_backup(backup_id)
        if success:
            return json.dumps({"status": "ok", "message": f"恢复成功: {backup_id}"})
        return json.dumps({"status": "error", "message": f"恢复失败: {backup_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def list_backups() -> str:
    """列出所有备份。

    Returns:
        {"status": "ok", "backups": [...]} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        backups = orchestrator.list_backups()
        return json.dumps({"status": "ok", "backups": backups, "count": len(backups)})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def delete_backup(backup_id: str) -> str:
    """删除指定备份。

    Args:
        backup_id: 备份标识符。

    Returns:
        {"status": "ok"} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        success = orchestrator.delete_backup(backup_id)
        if success:
            return json.dumps({"status": "ok", "message": f"已删除备份: {backup_id}"})
        return json.dumps({"status": "error", "message": f"删除失败: {backup_id}"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def verify_backup(backup_id: str) -> str:
    """验证备份文件的完整性。

    Args:
        backup_id: 备份标识符。

    Returns:
        {"status": "ok", "valid": bool, ...} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        result = orchestrator.verify_backup(backup_id)
        return json.dumps({"status": "ok", **result})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_backup_stats() -> str:
    """获取备份管理器统计信息。

    Returns:
        {"status": "ok", ...} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        stats = orchestrator.get_backup_stats()
        return json.dumps({"status": "ok", **stats})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


# =============================================================================
# 缓存管理接口
# =============================================================================


def get_cache_stats() -> str:
    """获取所有缓存的统计信息。

    Returns:
        {"status": "ok", ...} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        stats = orchestrator.get_cache_stats()
        return json.dumps({"status": "ok", **stats})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def invalidate_cache() -> str:
    """使检索和统计缓存失效（记忆库发生变化时调用）。

    Returns:
        {"status": "ok"}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.invalidate_cache()
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def invalidate_cache_all() -> str:
    """使所有缓存失效。

    Returns:
        {"status": "ok"}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.invalidate_cache_all()
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def cache_cleanup() -> str:
    """执行缓存定期清理（清理过期 TTL 条目）。

    Returns:
        {"status": "ok", "cleaned": int} 或 {"status": "error", "message": str}
    """
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        result = orchestrator.cache_cleanup()
        return json.dumps({"status": "ok", "cleaned": result.get("total", 0), **result})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})