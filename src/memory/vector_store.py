"""向量存储模块 —— 基于 SQLite 的记忆持久化 + 倒排索引 + 余弦相似度。

纯 Python 实现，Chaquopy 兼容，不依赖 numpy。
使用 SQLite 存储记忆条目，倒排索引实现关键词预过滤，
余弦相似度做向量精排。

核心类：
    - InvertedIndex: 纯 Python 倒排索引
    - MemoryEntry: 记忆条目数据类
    - VectorStore: 向量存储主类

依赖：
    - sqlite3: Python 标准库
    - src.exceptions: MemoryException, MemoryNotFoundError, MemoryStorageError
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import base64
import json
import math
import os
import re
import secrets
import sqlite3
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock

from src.exceptions import MemoryException, MemoryNotFoundError, MemoryStorageError
from src.utils.logger import get_logger


# =============================================================================
# 记忆内容加密工具（T-FIX-04）
# =============================================================================

# 加密密钥（懒加载，首次调用 _encrypt/_decrypt 时初始化）
_ENCRYPTION_KEY: bytes | None = None
_USE_FERNET: bool = False


def _init_encryption() -> None:
    """初始化加密密钥。

    优先使用 cryptography.fernet（AES-CBC），不可用时回退到 base64 + XOR。
    密钥从环境变量 MEMORY_ENCRYPTION_KEY 读取，不存在时生成随机密钥并输出警告日志。
    """
    global _ENCRYPTION_KEY, _USE_FERNET
    _logger = get_logger()

    # 尝试使用 Fernet（更安全的 AES-CBC 实现）
    try:
        from cryptography.fernet import Fernet  # type: ignore[import-untyped]

        _USE_FERNET = True
        key_str = os.environ.get("MEMORY_ENCRYPTION_KEY")
        if key_str:
            _ENCRYPTION_KEY = key_str.encode("utf-8") if isinstance(key_str, str) else key_str
        else:
            _ENCRYPTION_KEY = Fernet.generate_key()
            _logger.warning(
                "[加密] MEMORY_ENCRYPTION_KEY 未设置，已生成随机 Fernet 密钥。"
                "重启后旧数据将无法解密！请设置环境变量 MEMORY_ENCRYPTION_KEY 以持久化密钥。"
            )
        _logger.info("[加密] 使用 cryptography.fernet (AES-CBC) 模式")
        return
    except ImportError:
        _logger.info("[加密] cryptography 不可用，回退到 base64+XOR 模式")

    # 回退到 base64 + XOR（MVP 方案）
    key_str = os.environ.get("MEMORY_ENCRYPTION_KEY")
    if key_str:
        if isinstance(key_str, str):
            key_str = key_str.encode("utf-8")
        _ENCRYPTION_KEY = key_str
    else:
        _ENCRYPTION_KEY = secrets.token_bytes(32)
        _logger.warning(
            "[加密] MEMORY_ENCRYPTION_KEY 未设置，已生成随机 XOR 密钥。"
            "重启后旧数据将无法解密！请设置环境变量 MEMORY_ENCRYPTION_KEY 以持久化密钥。"
        )


def _encrypt(text: str) -> str:
    """加密文本内容，返回 base64 编码的密文。

    如果 text 为空字符串，直接返回空字符串（不加密）。
    """
    if not text:
        return text
    global _ENCRYPTION_KEY
    if _ENCRYPTION_KEY is None:
        _init_encryption()

    if _USE_FERNET:
        from cryptography.fernet import Fernet  # type: ignore[import-untyped]

        f = Fernet(_ENCRYPTION_KEY)  # type: ignore[arg-type]
        return f.encrypt(text.encode("utf-8")).decode("ascii")
    else:
        # XOR + base64 MVP 方案
        data = text.encode("utf-8")
        key = _ENCRYPTION_KEY  # type: ignore[assignment]
        encrypted = bytes([data[i] ^ key[i % len(key)] for i in range(len(data))])  # type: ignore[index]
        return base64.b64encode(encrypted).decode("ascii")


def _decrypt(encoded: str) -> str:
    """解密文本内容。

    如果 encoded 为空字符串，直接返回空字符串。
    如果解密失败（例如旧明文数据），假定为明文直接返回。
    """
    if not encoded:
        return encoded
    global _ENCRYPTION_KEY
    if _ENCRYPTION_KEY is None:
        _init_encryption()

    try:
        if _USE_FERNET:
            from cryptography.fernet import Fernet  # type: ignore[import-untyped]

            f = Fernet(_ENCRYPTION_KEY)  # type: ignore[arg-type]
            return f.decrypt(encoded.encode("ascii")).decode("utf-8")
        else:
            encrypted = base64.b64decode(encoded)
            key = _ENCRYPTION_KEY  # type: ignore[assignment]
            decrypted = bytes([encrypted[i] ^ key[i % len(key)] for i in range(len(encrypted))])  # type: ignore[index]
            return decrypted.decode("utf-8")
    except Exception:
        # 解密失败：可能是旧明文数据或密钥变更，直接返回原文
        return encoded


@dataclass
class MemoryEntry:
    """记忆条目数据类。

    Attributes:
        id: 唯一标识符（UUID）。
        memory_type: 记忆类型，"episodic" | "semantic" | "user_fact"。
        content: 记忆文本内容。
        keywords: 提取的关键词列表。
        embedding: 向量表示（float 列表）。
        importance: 重要性分数（0.0~1.0）。
        created_at: 创建时间（ISO 8601 格式）。
        last_accessed: 最后访问时间（ISO 8601 格式）。
        access_count: 访问次数。
        decay_factor: 衰减因子（1.0 初始，随时间递减）。
        source_turn_id: 来源对话轮次 ID。
    """

    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    memory_type: str = "semantic"
    content: str = ""
    keywords: list[str] = field(default_factory=list)
    embedding: list[float] = field(default_factory=list)
    importance: float = 0.5
    created_at: str = ""
    last_accessed: str = ""
    access_count: int = 0
    decay_factor: float = 1.0
    source_turn_id: str = ""

    def to_dict(self) -> dict:
        return {
            "id": self.id, "memory_type": self.memory_type, "content": self.content,
            "keywords": self.keywords, "embedding": self.embedding,
            "importance": self.importance, "created_at": self.created_at,
            "last_accessed": self.last_accessed, "access_count": self.access_count,
            "decay_factor": self.decay_factor, "source_turn_id": self.source_turn_id,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "MemoryEntry":
        return cls(
            id=data.get("id", ""), memory_type=data.get("memory_type", "semantic"),
            content=data.get("content", ""), keywords=data.get("keywords", []),
            embedding=data.get("embedding", []), importance=data.get("importance", 0.5),
            created_at=data.get("created_at", ""), last_accessed=data.get("last_accessed", ""),
            access_count=data.get("access_count", 0), decay_factor=data.get("decay_factor", 1.0),
            source_turn_id=data.get("source_turn_id", ""),
        )


def extract_keywords(text: str) -> list[str]:
    if not text:
        return []
    cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", text)
    if len(cleaned) < 2:
        return []
    keywords: list[str] = []
    for i in range(len(cleaned) - 1):
        keywords.append(cleaned[i : i + 2])
    for i in range(len(cleaned) - 2):
        keywords.append(cleaned[i : i + 3])
    return list(set(keywords))


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        raise ValueError(f"向量维度不一致: {len(a)} vs {len(b)}")
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(x * x for x in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


class InvertedIndex:
    """纯 Python 倒排索引，用于关键词预过滤。"""

    def __init__(self) -> None:
        self._index: dict[str, set[str]] = {}
        self._log = get_logger()

    def add(self, mem_id: str, keywords: list[str]) -> None:
        for kw in keywords:
            if kw not in self._index:
                self._index[kw] = set()
            self._index[kw].add(mem_id)
        self._log.debug(f"[倒排索引] 添加记忆 {mem_id}, 关键词数={len(keywords)}")

    def remove(self, mem_id: str, keywords: list[str]) -> None:
        for kw in keywords:
            if kw in self._index:
                self._index[kw].discard(mem_id)
                if not self._index[kw]:
                    del self._index[kw]
        self._log.debug(f"[倒排索引] 移除记忆 {mem_id}")

    def search(self, query_keywords: list[str]) -> set[str]:
        result: set[str] = set()
        for kw in query_keywords:
            if kw in self._index:
                result.update(self._index[kw])
        return result

    def clear(self) -> None:
        self._index.clear()
        self._log.info("[倒排索引] 已清空")

    def size(self) -> int:
        return len(self._index)


class VectorStore:
    """基于 SQLite 的向量存储。

    使用 SQLite 数据库持久化记忆条目，配合倒排索引实现
    高效的混合检索（关键词预过滤 + 余弦相似度精排）。
    线程安全：所有数据库操作通过 threading.Lock 保护。
    """

    _TABLE_NAME = "memories"

    _CREATE_TABLE_SQL = f"""
    CREATE TABLE IF NOT EXISTS {_TABLE_NAME} (
        id TEXT PRIMARY KEY,
        type TEXT NOT NULL,
        content TEXT NOT NULL,
        keywords TEXT NOT NULL DEFAULT '[]',
        embedding TEXT NOT NULL DEFAULT '[]',
        importance REAL NOT NULL DEFAULT 0.5,
        created_at TEXT NOT NULL DEFAULT '',
        last_accessed TEXT NOT NULL DEFAULT '',
        access_count INTEGER NOT NULL DEFAULT 0,
        decay_factor REAL NOT NULL DEFAULT 1.0,
        source_turn_id TEXT NOT NULL DEFAULT ''
    )
    """

    def __init__(self, db_path: str | Path = ":memory:") -> None:
        self.db_path = str(db_path)
        self._log = get_logger()
        self._lock = Lock()
        self._closed = False
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._create_table()
        self.inverted_index = InvertedIndex()
        self._load_index()
        self._add_count = 0  # WAL checkpoint 计数器
        self._log.info(f"VectorStore 初始化完成: db_path={self.db_path}, 记忆数={self.count()}, 索引关键词数={self.inverted_index.size()}")

    def _check_closed(self) -> None:
        """检查连接是否已关闭。"""
        if self._closed:
            raise MemoryStorageError("VectorStore 已关闭，无法执行操作")

    def _create_table(self) -> None:
        try:
            self._conn.execute(self._CREATE_TABLE_SQL)
            self._conn.commit()
            self._log.debug("数据库表已就绪")
        except sqlite3.Error as e:
            raise MemoryStorageError(f"创建数据库表失败: {e}", detail=self.db_path) from e

    def _load_index(self) -> None:
        try:
            cursor = self._conn.execute(f"SELECT id, keywords FROM {self._TABLE_NAME}")
            for row in cursor:
                mem_id = row[0]
                try:
                    keywords = json.loads(row[1])
                except (json.JSONDecodeError, TypeError):
                    keywords = []
                if keywords:
                    self.inverted_index.add(mem_id, keywords)
            self._log.debug("倒排索引已从数据库加载")
        except sqlite3.Error as e:
            self._log.error(f"加载倒排索引失败: {e}")

    def add(self, entry: MemoryEntry) -> str:
        self._check_closed()
        if not entry.keywords and entry.content:
            entry.keywords = extract_keywords(entry.content)
        if not entry.id:
            entry.id = str(uuid.uuid4())
        if not entry.created_at:
            from src.utils.time_utils import format_timestamp_iso
            entry.created_at = format_timestamp_iso()
        if not entry.last_accessed:
            entry.last_accessed = entry.created_at
        # T-FIX-04: 加密 content 后再存储
        encrypted_content = _encrypt(entry.content)
        sql = f"INSERT OR REPLACE INTO {self._TABLE_NAME} (id, type, content, keywords, embedding, importance, created_at, last_accessed, access_count, decay_factor, source_turn_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        params = (entry.id, entry.memory_type, encrypted_content, json.dumps(entry.keywords, ensure_ascii=False), json.dumps(entry.embedding), entry.importance, entry.created_at, entry.last_accessed, entry.access_count, entry.decay_factor, entry.source_turn_id)
        try:
            with self._lock:
                self._conn.execute(sql, params)
                self._conn.commit()
                if entry.keywords:
                    self.inverted_index.add(entry.id, entry.keywords)
        except sqlite3.Error as e:
            raise MemoryStorageError(f"添加记忆失败: {e}", detail=f"id={entry.id}") from e
        self._add_count += 1
        # 每 100 次写入触发一次 WAL checkpoint，防止 wal 文件无限增长
        if self._add_count % 100 == 0:
            self._checkpoint()
        self._log.debug(f"记忆已添加: id={entry.id}, type={entry.memory_type}, content={entry.content[:50]}...")
        return entry.id

    def get(self, mem_id: str) -> MemoryEntry:
        self._check_closed()
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE id = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (mem_id,))
                row = cursor.fetchone()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"查询记忆失败: {e}", detail=f"id={mem_id}") from e
        if row is None:
            raise MemoryNotFoundError(f"记忆不存在: {mem_id}", detail=f"id={mem_id}")
        return self._row_to_entry(row)

    def delete(self, mem_id: str) -> None:
        """删除记忆（带 close 检查和 TOCTOU 保护）。

        整个 get + delete + 索引更新在同一个锁内完成，
        防止并发场景下数据不一致。
        """
        self._check_closed()
        sql = f"DELETE FROM {self._TABLE_NAME} WHERE id = ?"
        try:
            with self._lock:
                # 在锁内先获取记忆，确认存在后再删除，防止 TOCTOU 竞态
                cursor = self._conn.execute(f"SELECT * FROM {self._TABLE_NAME} WHERE id = ?", (mem_id,))
                row = cursor.fetchone()
                if row is None:
                    raise MemoryNotFoundError(f"记忆不存在: {mem_id}", detail=f"id={mem_id}")
                entry = self._row_to_entry(row)
                self._conn.execute(sql, (mem_id,))
                self._conn.commit()
                if entry.keywords:
                    self.inverted_index.remove(mem_id, entry.keywords)
        except MemoryNotFoundError:
            raise
        except sqlite3.Error as e:
            raise MemoryStorageError(f"删除记忆失败: {e}", detail=f"id={mem_id}") from e
        self._log.debug(f"记忆已删除: id={mem_id}")

    def search(self, query_embedding: list[float], query_text: str = "", top_k: int = 10) -> list[tuple[MemoryEntry, float]]:
        """搜索与查询最相似的记忆。

        混合检索流程：
        1. 提取 query_text 关键词
        2. 倒排索引预过滤 → 候选集
        3. 如果候选集为空，回退到全量余弦相似度
        4. 余弦相似度精排
        5. 返回 top_k 结果，每项为 (MemoryEntry, similarity_score)

        Args:
            query_embedding: 查询文本的向量表示。
            query_text: 查询原始文本（用于关键词提取和倒排索引预过滤）。
            top_k: 返回的最大记忆数。

        Returns:
            list[tuple[MemoryEntry, float]]: 结果列表，每项为 (记忆条目, 余弦相似度分数)。
        """
        self._check_closed()
        candidates: list[MemoryEntry] = []
        if query_text:
            query_keywords = extract_keywords(query_text)
            candidate_ids = self.inverted_index.search(query_keywords)
            if candidate_ids:
                candidates = self._get_by_ids(list(candidate_ids))
                self._log.debug(f"[检索] 倒排索引命中 {len(candidates)} 条候选")
        if not candidates:
            candidates = self._get_all()
            self._log.debug(f"[检索] 倒排索引无命中，回退到全量检索 ({len(candidates)} 条)")
        if not candidates:
            return []
        scored: list[tuple[MemoryEntry, float]] = []
        has_embedding = bool(query_embedding)
        for entry in candidates:
            if has_embedding and entry.embedding:
                try:
                    sim = cosine_similarity(query_embedding, entry.embedding)
                except ValueError:
                    sim = 0.0
            elif not has_embedding and query_text:
                # 无 embedding 时用关键词命中数作为分数（归一化）
                entry_kw = set(entry.keywords) if entry.keywords else set()
                query_kw = set(query_keywords) if query_keywords else set()
                match_count = len(query_kw & entry_kw)
                sim = min(match_count / max(len(query_keywords), 1), 1.0)
            else:
                sim = 0.0
            scored.append((entry, sim))
        scored.sort(key=lambda x: x[1], reverse=True)
        # 如果所有分数都是 0（无 embedding 且无关键词匹配），按最近访问时间排序
        if scored and all(s == 0.0 for _, s in scored):
            scored.sort(key=lambda x: x[0].last_accessed or "", reverse=True)
        result = scored[:top_k]
        # T-FIX-06: 收集所有访问更新，使用 executemany 批量 UPDATE
        self._batch_record_access([entry for entry, _ in result])
        self._log.info(f"[检索] 完成: 候选={len(candidates)}, 返回={len(result)}, top_k={top_k}")
        return result

    def _get_by_ids(self, mem_ids: list[str]) -> list[MemoryEntry]:
        if not mem_ids:
            return []
        placeholders = ",".join("?" for _ in mem_ids)
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE id IN ({placeholders})"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, mem_ids)
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"批量查询记忆失败: {e}")
            raise MemoryStorageError(f"批量查询记忆失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def _get_all(self) -> list[MemoryEntry]:
        """全量加载所有记忆条目（内部方法）。
        
        警告：记忆量 > 2000 条时存在 OOM 风险，外部调用应优先使用
        get_page() 分页加载或通过 search() 的混合检索路径。
        """
        sql = f"SELECT * FROM {self._TABLE_NAME}"
        try:
            with self._lock:
                cursor = self._conn.execute(sql)
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"查询所有记忆失败: {e}")
            raise MemoryStorageError(f"查询所有记忆失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def get_page(self, offset: int, limit: int) -> list[MemoryEntry]:
        """分页加载记忆条目（T4.6 OOM 防护）。

        用于全量回退检索时的分页加载，避免一次性加载大量记忆导致 OOM。
        每页独立从数据库加载，不在内存中累积已加载页。

        Args:
            offset: 分页偏移量（从 0 开始）。
            limit: 每页最大条数。

        Returns:
            MemoryEntry 列表，按 rowid 升序排列。

        Raises:
            MemoryStorageError: 数据库操作失败时抛出。
        """
        self._check_closed()
        sql = f"SELECT * FROM {self._TABLE_NAME} ORDER BY rowid LIMIT ? OFFSET ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (limit, offset))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"分页加载记忆失败 (offset={offset}, limit={limit}): {e}")
            raise MemoryStorageError(
                f"分页加载记忆失败: {e}", detail=f"offset={offset}, limit={limit}"
            ) from e
        self._log.debug(f"[分页加载] offset={offset}, limit={limit}, 实际={len(rows)}")
        return [self._row_to_entry(row) for row in rows]

    def _record_access(self, entry: MemoryEntry) -> None:
        from src.utils.time_utils import format_timestamp_iso
        entry.access_count += 1
        entry.last_accessed = format_timestamp_iso()
        sql = f"UPDATE {self._TABLE_NAME} SET last_accessed = ?, access_count = ? WHERE id = ?"
        try:
            with self._lock:
                self._conn.execute(sql, (entry.last_accessed, entry.access_count, entry.id))
                self._conn.commit()
        except sqlite3.Error as e:
            self._log.warning(f"记录访问信息失败: {e}")

    def _batch_record_access(self, entries: list[MemoryEntry]) -> None:
        """T-FIX-06: 批量更新记忆访问信息，使用 executemany + 单次 COMMIT。

        将原本 search() 中对每条结果逐一 UPDATE+COMMIT 的 N+1 模式，
        改为收集所有更新后一次性批量提交。
        """
        if not entries:
            return
        from src.utils.time_utils import format_timestamp_iso

        now = format_timestamp_iso()
        updates: list[tuple[str, int, str]] = []
        for entry in entries:
            entry.access_count += 1
            entry.last_accessed = now
            updates.append((entry.last_accessed, entry.access_count, entry.id))

        sql = f"UPDATE {self._TABLE_NAME} SET last_accessed = ?, access_count = ? WHERE id = ?"
        try:
            with self._lock:
                self._conn.executemany(sql, updates)
                self._conn.commit()
        except sqlite3.Error as e:
            self._log.debug(f"批量记录访问信息失败: {e}")

    def count(self) -> int:
        self._check_closed()
        try:
            with self._lock:
                cursor = self._conn.execute(f"SELECT COUNT(*) FROM {self._TABLE_NAME}")
                return cursor.fetchone()[0]
        except sqlite3.Error as e:
            self._log.error(f"计数失败: {e}")
            raise MemoryStorageError(f"计数失败: {e}") from e

    def clear(self) -> None:
        self._check_closed()
        try:
            with self._lock:
                self._conn.execute(f"DELETE FROM {self._TABLE_NAME}")
                self._conn.commit()
                self.inverted_index.clear()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"清空记忆失败: {e}") from e
        self._log.info("所有记忆已清空")

    def get_by_type(self, memory_type: str) -> list[MemoryEntry]:
        self._check_closed()
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE type = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (memory_type,))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"按类型查询失败: {e}")
            raise MemoryStorageError(f"按类型查询失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def get_all(self) -> list[MemoryEntry]:
        self._check_closed()
        return self._get_all()

    # =========================================================================
    # 记忆 CRUD 扩展方法（T4.2）
    # 使用 SQLite 隐式 rowid（整数）作为对外暴露的记忆 ID。
    # =========================================================================

    def get_by_rowid(self, rowid: int) -> MemoryEntry:
        """按 rowid（整数 ID）获取单条记忆。

        Args:
            rowid: SQLite 隐式行 ID（从 1 开始）。

        Returns:
            对应的 MemoryEntry 实例。

        Raises:
            MemoryNotFoundError: rowid 不存在时抛出。
            MemoryStorageError: 数据库操作失败时抛出。
        """
        self._check_closed()
        sql = f"SELECT rowid, * FROM {self._TABLE_NAME} WHERE rowid = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (rowid,))
                row = cursor.fetchone()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"按 rowid 查询记忆失败: {e}", detail=f"rowid={rowid}") from e
        if row is None:
            raise MemoryNotFoundError(f"记忆不存在: rowid={rowid}", detail=f"rowid={rowid}")
        return self._row_to_entry(row[1:])  # 跳过 rowid 列

    def delete_by_rowid(self, rowid: int) -> MemoryEntry:
        """按 rowid（整数 ID）删除记忆，返回被删除的条目。

        先获取完整条目（用于清理倒排索引），再执行 DELETE。
        整个操作在同一个锁内完成。

        Args:
            rowid: SQLite 隐式行 ID。

        Returns:
            被删除的 MemoryEntry。

        Raises:
            MemoryNotFoundError: rowid 不存在时抛出。
            MemoryStorageError: 数据库操作失败时抛出。
        """
        self._check_closed()
        sql_delete = f"DELETE FROM {self._TABLE_NAME} WHERE rowid = ?"
        sql_select = f"SELECT rowid, * FROM {self._TABLE_NAME} WHERE rowid = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql_select, (rowid,))
                row = cursor.fetchone()
                if row is None:
                    raise MemoryNotFoundError(f"记忆不存在: rowid={rowid}", detail=f"rowid={rowid}")
                entry = self._row_to_entry(row[1:])
                self._conn.execute(sql_delete, (rowid,))
                self._conn.commit()
                if entry.keywords:
                    self.inverted_index.remove(entry.id, entry.keywords)
        except MemoryNotFoundError:
            raise
        except sqlite3.Error as e:
            raise MemoryStorageError(f"按 rowid 删除记忆失败: {e}", detail=f"rowid={rowid}") from e
        self._log.debug(f"记忆已按 rowid 删除: rowid={rowid}, id={entry.id}")
        return entry

    def update(
        self,
        rowid: int,
        entry: MemoryEntry,
    ) -> None:
        """按 rowid 更新记忆条目。

        更新所有字段（content, keywords, embedding, importance 等），
        同时更新倒排索引（先移除旧关键词，再添加新关键词）。

        Args:
            rowid: SQLite 隐式行 ID。
            entry: 包含更新后数据的 MemoryEntry。

        Raises:
            MemoryNotFoundError: rowid 不存在时抛出。
            MemoryStorageError: 数据库操作失败时抛出。
        """
        self._check_closed()
        if not entry.keywords and entry.content:
            entry.keywords = extract_keywords(entry.content)
        encrypted_content = _encrypt(entry.content)
        sql_select = f"SELECT rowid, * FROM {self._TABLE_NAME} WHERE rowid = ?"
        sql_update = f"""UPDATE {self._TABLE_NAME} SET
            type = ?, content = ?, keywords = ?, embedding = ?,
            importance = ?, created_at = ?, last_accessed = ?,
            access_count = ?, decay_factor = ?, source_turn_id = ?
            WHERE rowid = ?"""
        params = (
            entry.memory_type, encrypted_content,
            json.dumps(entry.keywords, ensure_ascii=False),
            json.dumps(entry.embedding),
            entry.importance, entry.created_at, entry.last_accessed,
            entry.access_count, entry.decay_factor, entry.source_turn_id,
            rowid,
        )
        try:
            with self._lock:
                # 先查询旧条目（用于清理旧关键词）
                cursor = self._conn.execute(sql_select, (rowid,))
                row = cursor.fetchone()
                if row is None:
                    raise MemoryNotFoundError(f"记忆不存在: rowid={rowid}", detail=f"rowid={rowid}")
                old_entry = self._row_to_entry(row[1:])
                # 执行更新
                self._conn.execute(sql_update, params)
                self._conn.commit()
                # 更新倒排索引
                if old_entry.keywords:
                    self.inverted_index.remove(old_entry.id, old_entry.keywords)
                if entry.keywords:
                    self.inverted_index.add(entry.id, entry.keywords)
        except MemoryNotFoundError:
            raise
        except sqlite3.Error as e:
            raise MemoryStorageError(f"更新记忆失败: {e}", detail=f"rowid={rowid}") from e
        self._log.debug(f"记忆已更新: rowid={rowid}, id={entry.id}, content={entry.content[:50]}...")

    def list_with_rowid(
        self,
        type_filter: str = "",
        offset: int = 0,
        limit: int = 50,
    ) -> list[dict]:
        """分页列出记忆，返回包含 rowid 的字典列表。

        Args:
            type_filter: 记忆类型过滤。空字符串表示不过滤。
            offset: 分页偏移量（从 0 开始）。
            limit: 每页最大条数。

        Returns:
            dict 列表，每项包含 rowid 和 MemoryEntry.to_dict() 的所有字段。
        """
        self._check_closed()
        if type_filter:
            sql = (
                f"SELECT rowid, * FROM {self._TABLE_NAME} "
                f"WHERE type = ? ORDER BY rowid DESC LIMIT ? OFFSET ?"
            )
            params = (type_filter, limit, offset)
        else:
            sql = (
                f"SELECT rowid, * FROM {self._TABLE_NAME} "
                f"ORDER BY rowid DESC LIMIT ? OFFSET ?"
            )
            params = (limit, offset)
        try:
            with self._lock:
                cursor = self._conn.execute(sql, params)
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"分页查询记忆失败: {e}") from e
        results: list[dict] = []
        for row in rows:
            entry = self._row_to_entry(row[1:])
            item = entry.to_dict()
            item["rowid"] = row[0]
            # 不返回完整 embedding（太大，前端不需要），只返回维度信息
            item["embedding_dim"] = len(entry.embedding)
            item.pop("embedding", None)
            results.append(item)
        return results

    def search_by_keyword(self, keyword: str) -> list[dict]:
        """按关键字在 content 字段中模糊搜索（LIKE）。

        用于前端记忆可视化页面的关键字搜索。

        Args:
            keyword: 搜索关键字。

        Returns:
            dict 列表，每项包含 rowid 和 MemoryEntry.to_dict() 的所有字段。
            按创建时间降序排列。
        """
        self._check_closed()
        if not keyword or not keyword.strip():
            return []
        keyword = keyword.strip()
        # 使用 LIKE 进行模糊匹配，search 内容是在加密之前，所以需要在解密后的 content 上搜索
        # 策略：先获取所有记忆，在 Python 侧过滤
        # 对于少量记忆（<1000条）这是可接受的
        all_entries = self._get_all()
        results: list[dict] = []
        # 获取 rowid 映射：用 id 关联 rowid
        id_to_rowid: dict[str, int] = {}
        try:
            with self._lock:
                cursor = self._conn.execute(
                    f"SELECT rowid, id FROM {self._TABLE_NAME}"
                )
                for row in cursor:
                    id_to_rowid[row[1]] = row[0]
        except sqlite3.Error as e:
            self._log.error(f"获取 rowid 映射失败: {e}")
            # 降级：只返回不带 rowid 的结果
            id_to_rowid = {}

        for entry in all_entries:
            if keyword.lower() in entry.content.lower():
                item = entry.to_dict()
                item["rowid"] = id_to_rowid.get(entry.id, -1)
                item["embedding_dim"] = len(entry.embedding)
                item.pop("embedding", None)
                results.append(item)
        # 按创建时间降序
        results.sort(key=lambda x: x.get("created_at", ""), reverse=True)
        return results

    @staticmethod
    def _row_to_entry(row: tuple) -> MemoryEntry:
        try:
            keywords = json.loads(row[3])
        except (json.JSONDecodeError, TypeError):
            keywords = []
        try:
            embedding = json.loads(row[4])
        except (json.JSONDecodeError, TypeError):
            embedding = []
        # T-FIX-04: 解密 content 字段
        decrypted_content = _decrypt(row[2])
        return MemoryEntry(
            id=row[0], memory_type=row[1], content=decrypted_content, keywords=keywords,
            embedding=embedding, importance=float(row[5]), created_at=row[6],
            last_accessed=row[7], access_count=int(row[8]), decay_factor=float(row[9]),
            source_turn_id=row[10],
        )

    def close(self) -> None:
        """关闭数据库连接。

        关闭后所有操作将抛出 MemoryStorageError。
        """
        if self._closed:
            return
        try:
            # 关闭前执行一次 checkpoint，确保 WAL 数据写入主数据库
            self._checkpoint()
            self._conn.close()
            self._closed = True
            self._log.info("VectorStore 数据库连接已关闭")
        except sqlite3.Error as e:
            self._log.error(f"关闭数据库连接失败: {e}")

    def _checkpoint(self) -> None:
        """执行 WAL checkpoint，将 WAL 日志合并到主数据库。"""
        try:
            with self._lock:
                self._conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        except sqlite3.Error as e:
            self._log.debug(f"WAL checkpoint 失败（可忽略）: {e}")
