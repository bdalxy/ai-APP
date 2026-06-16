"""
记忆系统桥接模块 — 记忆存储、检索、CRUD、导出/导入等。
"""
import base64
import hashlib
import json
import os

from src.exceptions import MemoryNotFoundError
from src.memory.vector_store import MemoryEntry, _init_encryption
from src.utils.time_utils import format_timestamp_iso
from src.utils.logger import get_logger

from ._state import _ctx

_log = get_logger()


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
        _init_encryption(db_path)
        orchestrator = _ctx.init_memory(db_path)
        memory_count = orchestrator.vector_store.count()
        return json.dumps({"status": "ok", "memory_count": memory_count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def get_memory_stats() -> dict:
    """获取记忆统计信息。"""
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
        _state._cached_memories = []  # 清除缓存，防止已删除记忆继续注入
        _ctx.reset_turn_counter()
        return json.dumps({"status": "ok", "deleted": count})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_extract_interval(n: int) -> dict:
    """设置 LLM 提取的间隔轮数。"""
    orchestrator = _ctx.orchestrator
    if orchestrator is None:
        return json.dumps({"status": "error", "message": "记忆系统未初始化，请先调用 init_memory()"})

    try:
        orchestrator.set_extract_interval(n)
        return json.dumps({"status": "ok", "extract_interval": n})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def set_memory_extract_mode(mode: str = "rule") -> dict:
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


def inject_memories(query_text: str = "") -> dict:
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


def remember_turn(turn_id: str = "", user_msg: str = "", ai_reply: str = "") -> dict:
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


def list_memories(type_filter: str = "", page: int = 1, page_size: int = 50) -> dict:
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


def get_memory(memory_id: int) -> dict:
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


def update_memory(memory_id: int, content: str) -> dict:
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


def delete_memory(memory_id: int) -> dict:
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


def clear_memories() -> dict:
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


def search_memories(keyword: str) -> dict:
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
# 记忆导出 / 导入接口
# =============================================================================


def export_memories(password: str = "") -> dict:
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
            salt = os.urandom(16)
            key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100000, dklen=32)
            plaintext = json.dumps(all_memories, ensure_ascii=False).encode("utf-8")
            try:
                from cryptography.fernet import Fernet
                fernet_key = base64.urlsafe_b64encode(key)
                f = Fernet(fernet_key)
                ciphertext = f.encrypt(plaintext)
                encrypted = base64.b64encode(salt + ciphertext).decode("ascii")
            except ImportError:
                ciphertext = bytes(
                    plaintext[i] ^ key[i % len(key)] for i in range(len(plaintext))
                )
                encrypted = base64.b64encode(salt + ciphertext).decode("ascii")
            result["memories"] = encrypted
            result["encrypted"] = True
            _log.info("[导出] 已加密导出")
        else:
            result["memories"] = all_memories

        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def import_memories(memories_json: str) -> dict:
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