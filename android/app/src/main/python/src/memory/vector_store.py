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
from contextlib import contextmanager
from threading import Lock, RLock

from src.exceptions import MemoryException, MemoryNotFoundError, MemoryStorageError
from src.utils.logger import get_logger


# =============================================================================
# 记忆内容加密工具（T-FIX-04）
# =============================================================================

# 加密密钥（懒加载，首次调用 _encrypt/_decrypt 时初始化）
_ENCRYPTION_KEY: bytes | None = None
_USE_FERNET: bool = False
# 加密标记前缀：F:0: = Fernet, X:0: = XOR, 无前缀 = 旧数据
_ENCRYPT_PREFIX_FERNET: str = "F:0:"
_ENCRYPT_PREFIX_XOR: str = "X:0:"


def _init_encryption(key_dir: str = "") -> None:
    """初始化加密密钥，优先使用 Fernet (AES-CBC)，兼容旧 XOR 数据。

    密钥加载优先级：
        1. key_dir 目录下的 encryption.key 文件（持久化密钥）
        2. 环境变量 MEMORY_ENCRYPTION_KEY
        3. 随机生成 Fernet 密钥并保存到 key_dir/encryption.key

    Args:
        key_dir: 密钥文件存储目录（通常为数据库所在目录）。空字符串表示不持久化。
    """
    global _ENCRYPTION_KEY, _USE_FERNET
    _logger = get_logger()

    has_fernet = False
    try:
        from cryptography.fernet import Fernet  # noqa: F811
        has_fernet = True
    except ImportError:
        _logger.info("[加密] cryptography 不可用，回退到 base64+XOR 模式")

    # 尝试从持久化文件加载密钥
    if key_dir:
        key_file = Path(key_dir) / "encryption.key"
        if key_file.exists():
            try:
                _ENCRYPTION_KEY = key_file.read_bytes()
                # 检测密钥类型：Fernet 密钥是 44 字节 URL-safe base64
                if has_fernet and len(_ENCRYPTION_KEY) == 44:
                    _USE_FERNET = True
                    _logger.info("[加密] 已从持久化文件加载 Fernet 密钥 (AES-CBC)")
                else:
                    _USE_FERNET = False
                    _logger.info(f"[加密] 已从持久化文件加载 XOR 密钥 ({len(_ENCRYPTION_KEY)} 字节)")
                return
            except Exception as e:
                _logger.warning(f"[加密] 加载持久化密钥失败: {e}，将重新生成")

    # 生成新密钥
    if has_fernet:
        try:
            from cryptography.fernet import Fernet  # noqa: F811
            _USE_FERNET = True
            key_str = os.environ.get("MEMORY_ENCRYPTION_KEY")
            if key_str:
                _ENCRYPTION_KEY = key_str.encode("utf-8") if isinstance(key_str, str) else key_str
            else:
                _ENCRYPTION_KEY = Fernet.generate_key()
                if key_dir:
                    _save_key_to_file(key_dir, _ENCRYPTION_KEY)
                else:
                    _logger.warning(
                        "[加密] 已生成临时 Fernet 密钥，重启后旧数据将无法解密。"
                    )
            _logger.info(f"[加密] 使用 Fernet (AES-CBC) 模式, 密钥={len(_ENCRYPTION_KEY)}字节")
            return
        except Exception as e:
            _logger.warning(f"[加密] Fernet 初始化失败: {e}，回退 XOR")

    # 回退到 base64 + XOR
    _USE_FERNET = False
    key_str = os.environ.get("MEMORY_ENCRYPTION_KEY")
    if key_str:
        if isinstance(key_str, str):
            key_str = key_str.encode("utf-8")
        _ENCRYPTION_KEY = key_str
    elif key_dir:
        _ENCRYPTION_KEY = secrets.token_bytes(32)
        _save_key_to_file(key_dir, _ENCRYPTION_KEY)
    else:
        _ENCRYPTION_KEY = secrets.token_bytes(32)
        _logger.warning(
            "[加密] 已生成临时 XOR 密钥，重启后旧数据将无法解密。"
        )


def _save_key_to_file(key_dir: str, key: bytes) -> None:
    """将加密密钥持久化到文件。"""
    key_file = Path(key_dir) / "encryption.key"
    try:
        key_file.write_bytes(key)
        get_logger().info(f"[加密] 密钥已保存到 {key_file}")
    except Exception as e:
        get_logger().warning(f"[加密] 保存密钥失败: {e}")


def _encrypt(text: str) -> str:
    """加密文本内容，返回带前缀标记的密文。

    Fernet 模式: F:0:<base64密文>
    XOR 模式:   X:0:<base64密文>
    空字符串直接返回空字符串。
    """
    if not text:
        return text
    global _ENCRYPTION_KEY
    if _ENCRYPTION_KEY is None:
        _init_encryption()

    if _USE_FERNET:
        from cryptography.fernet import Fernet  # type: ignore[import-untyped]
        f = Fernet(_ENCRYPTION_KEY)  # type: ignore[arg-type]
        cipher = f.encrypt(text.encode("utf-8")).decode("ascii")
        return _ENCRYPT_PREFIX_FERNET + cipher
    else:
        data = text.encode("utf-8")
        key = _ENCRYPTION_KEY  # type: ignore[assignment]
        encrypted = bytes([data[i] ^ key[i % len(key)] for i in range(len(data))])  # type: ignore[index]
        cipher = base64.b64encode(encrypted).decode("ascii")
        return _ENCRYPT_PREFIX_XOR + cipher


def _decrypt(encoded: str) -> str:
    """解密文本内容，自动检测加密方式（Fernet/XOR/旧数据）。

    支持：
        - F:0: 前缀 = Fernet 加密
        - X:0: 前缀 = XOR 加密
        - 无前缀 = 尝试 Fernet 解密，失败则尝试 XOR，再失败视为明文
    空字符串直接返回空字符串。
    """
    if not encoded:
        return encoded
    global _ENCRYPTION_KEY
    if _ENCRYPTION_KEY is None:
        _init_encryption()

    # 检测前缀标记
    if encoded.startswith(_ENCRYPT_PREFIX_FERNET):
        try:
            from cryptography.fernet import Fernet  # type: ignore[import-untyped]
            f = Fernet(_ENCRYPTION_KEY)  # type: ignore[arg-type]
            return f.decrypt(encoded[len(_ENCRYPT_PREFIX_FERNET):].encode("ascii")).decode("utf-8")
        except Exception as e:
            get_logger().warning(f"[解密] Fernet 解密失败: {e}")
            return encoded

    if encoded.startswith(_ENCRYPT_PREFIX_XOR):
        try:
            encrypted = base64.b64decode(encoded[len(_ENCRYPT_PREFIX_XOR):])
            key = _ENCRYPTION_KEY  # type: ignore[assignment]
            decrypted = bytes([encrypted[i] ^ key[i % len(key)] for i in range(len(encrypted))])  # type: ignore[index]
            return decrypted.decode("utf-8")
        except Exception as e:
            get_logger().warning(f"[解密] XOR 解密失败: {e}")
            return encoded

    # 无前缀：旧数据，尝试 Fernet → XOR → 明文
    try:
        from cryptography.fernet import Fernet  # type: ignore[import-untyped]
        f = Fernet(_ENCRYPTION_KEY)  # type: ignore[arg-type]
        return f.decrypt(encoded.encode("ascii")).decode("utf-8")
    except Exception:
        pass

    try:
        encrypted = base64.b64decode(encoded)
        key = _ENCRYPTION_KEY  # type: ignore[assignment]
        decrypted = bytes([encrypted[i] ^ key[i % len(key)] for i in range(len(encrypted))])  # type: ignore[index]
        return decrypted.decode("utf-8")
    except Exception:
        pass

    # 明文数据
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
    archived: bool = False
    source: str = "user_related"  # ai_related | user_related | other

    def to_dict(self) -> dict:
        return {
            "id": self.id, "memory_type": self.memory_type, "content": self.content,
            "keywords": self.keywords, "embedding": self.embedding,
            "importance": self.importance, "created_at": self.created_at,
            "last_accessed": self.last_accessed, "access_count": self.access_count,
            "decay_factor": self.decay_factor, "source_turn_id": self.source_turn_id,
            "archived": self.archived, "source": self.source,
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
            archived=data.get("archived", False),
            source=data.get("source", "user_related"),
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
        source_turn_id TEXT NOT NULL DEFAULT '',
        archived INTEGER DEFAULT 0,
        source TEXT NOT NULL DEFAULT 'user_related'
    )
    """

    def __init__(self, db_path: str | Path = ":memory:") -> None:
        self.db_path = str(db_path)
        self._log = get_logger()
        self._lock = RLock()
        self._closed = False
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._create_table()
        self._create_indexes()
        self.inverted_index = InvertedIndex()
        self._load_index()
        self._add_count = 0  # WAL checkpoint 计数器
        self._log.info(f"VectorStore 初始化完成: db_path={self.db_path}, 记忆数={self.count()}, 索引关键词数={self.inverted_index.size()}")

    @property
    def lock(self) -> RLock:
        """公开锁，供 KnowledgeGraph / Summarizer 等外部模块安全访问数据库。"""
        return self._lock

    def _check_closed(self) -> None:
        """检查连接是否已关闭。"""
        if self._closed:
            raise MemoryStorageError("VectorStore 已关闭，无法执行操作")

    @contextmanager
    def transaction(self):
        """SQLite 事务上下文管理器，确保原子性操作。

        在事务块内执行的所有数据库操作（通过 _no_commit 方法）
        要么全部提交，要么全部回滚。

        使用示例:
            >>> with store.transaction():
            ...     store._mark_archived_no_commit([id1, id2])
            ...     store._add_no_commit(merged_entry)

        注意:
            - 事务期间持有 self._lock（RLock），其他线程将被阻塞。
            - 不要在事务块内调用会自行 commit 的公开方法（如 add()、mark_archived()）。
        """
        self._check_closed()
        with self._lock:
            self._conn.execute("BEGIN")
            try:
                yield
                self._conn.commit()
            except Exception:
                self._conn.rollback()
                raise

    def _create_table(self) -> None:
        try:
            self._conn.execute(self._CREATE_TABLE_SQL)
            self._conn.commit()
            # 迁移：为旧数据库添加 archived 字段
            self._migrate_add_archived()
            # 迁移：为旧数据库添加 source 字段
            self._migrate_add_source()
            self._log.debug("数据库表已就绪")
        except sqlite3.Error as e:
            raise MemoryStorageError(f"创建数据库表失败: {e}", detail=self.db_path) from e

    def _migrate_add_archived(self) -> None:
        """为旧数据库添加 archived 字段（兼容无该字段的旧数据库）。"""
        try:
            self._conn.execute(
                f"ALTER TABLE {self._TABLE_NAME} ADD COLUMN archived INTEGER DEFAULT 0"
            )
            self._conn.commit()
            self._log.info("[迁移] 已添加 archived 字段")
        except sqlite3.OperationalError:
            # 字段已存在，忽略
            pass

    def _migrate_add_source(self) -> None:
        """为旧数据库添加 source 字段（兼容无该字段的旧数据库）。"""
        try:
            self._conn.execute(
                f"ALTER TABLE {self._TABLE_NAME} ADD COLUMN source TEXT NOT NULL DEFAULT 'user_related'"
            )
            self._conn.commit()
            self._log.info("[迁移] 已添加 source 字段")
        except sqlite3.OperationalError:
            # 字段已存在，忽略
            pass

    def _create_indexes(self) -> None:
        """创建性能索引，加速按类型/重要性/创建时间的查询。

        索引列表：
            - idx_memories_type: 按记忆类型过滤（如 episodic/semantic）
            - idx_memories_importance: 按重要性降序（Top-K 重要记忆）
            - idx_memories_created: 按创建时间降序（最近记忆）
        """
        indexes = [
            "CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(type)",
            "CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance DESC)",
            "CREATE INDEX IF NOT EXISTS idx_memories_created ON memories(created_at DESC)",
        ]
        for idx_sql in indexes:
            try:
                self._conn.execute(idx_sql)
            except sqlite3.Error as e:
                self._log.warning(f"创建索引失败: {e}")
        self._conn.commit()
        self._log.debug("数据库索引已就绪")

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

    def _prepare_entry_for_insert(self, entry: MemoryEntry) -> None:
        """准备记忆条目：设置默认值、提取关键词、加密内容。

        在调用 _add_no_commit() 之前调用，确保条目已准备好插入。
        此方法不涉及数据库操作，无需持有锁。

        Args:
            entry: 要准备的记忆条目（原地修改）。
        """
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
        entry._encrypted_content = _encrypt(entry.content)

    def _add_no_commit(self, entry: MemoryEntry) -> str:
        """内部方法：添加记忆但不提交事务。

        调用者必须持有 self._lock 并负责事务管理（BEGIN/COMMIT/ROLLBACK）。
        通常与 transaction() 上下文管理器配合使用。

        Args:
            entry: 已通过 _prepare_entry_for_insert() 准备好的条目。

        Returns:
            记忆 ID。

        Raises:
            MemoryStorageError: 数据库操作失败。
        """
        encrypted_content = getattr(entry, "_encrypted_content", _encrypt(entry.content))
        sql = (
            f"INSERT OR REPLACE INTO {self._TABLE_NAME} "
            f"(id, type, content, keywords, embedding, importance, "
            f"created_at, last_accessed, access_count, decay_factor, "
            f"source_turn_id, archived, source) "
            f"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        params = (
            entry.id, entry.memory_type, encrypted_content,
            json.dumps(entry.keywords, ensure_ascii=False),
            json.dumps(entry.embedding), entry.importance,
            entry.created_at, entry.last_accessed, entry.access_count,
            entry.decay_factor, entry.source_turn_id, int(entry.archived),
            entry.source,
        )
        try:
            self._conn.execute(sql, params)
            if entry.keywords:
                self.inverted_index.add(entry.id, entry.keywords)
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"添加记忆失败: {e}", detail=f"id={entry.id}"
            ) from e
        self._add_count += 1
        self._log.debug(
            f"记忆已添加: id={entry.id}, type={entry.memory_type}, "
            f"content={entry.content[:50]}..."
        )
        return entry.id

    def add(self, entry: MemoryEntry) -> str:
        """添加记忆条目（公开方法，自行管理事务和锁）。

        Args:
            entry: 记忆条目。

        Returns:
            记忆 ID。
        """
        self._check_closed()
        self._prepare_entry_for_insert(entry)
        try:
            with self._lock:
                result = self._add_no_commit(entry)
                self._conn.commit()
                should_checkpoint = (self._add_count % 100 == 0)
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"添加记忆失败: {e}", detail=f"id={entry.id}"
            ) from e
        # 每 100 次写入触发一次 WAL checkpoint，防止 wal 文件无限增长
        if should_checkpoint:
            self._checkpoint()
        return result

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
            total_count = self.count()
            if total_count > 2000:
                self._log.warning(
                    f"[检索] 全量回退已拒绝: 记忆数 {total_count} > 2000，防止 OOM"
                )
                return []
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
        # 归档记忆权重衰减：archived 的记忆相似度乘以 0.2
        scored = [
            (entry, sim * 0.2 if entry.archived else sim)
            for entry, sim in scored
        ]
        # 重新排序
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

    def get_recent_entries(self, top_k: int = 10) -> list[MemoryEntry]:
        """按创建时间降序获取最近 N 条记忆（SQL 层排序，避免全量加载）。

        Args:
            top_k: 返回的最大记忆数。

        Returns:
            按 created_at 降序排列的 MemoryEntry 列表。
        """
        self._check_closed()
        sql = f"SELECT * FROM {self._TABLE_NAME} ORDER BY created_at DESC LIMIT ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (top_k,))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"获取最近记忆失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def get_important_entries(self, min_importance: float, top_k: int) -> list[MemoryEntry]:
        """按重要性降序获取重要记忆（SQL 层过滤+排序，避免全量加载）。

        Args:
            min_importance: 最低重要性阈值（0.0~1.0）。
            top_k: 返回的最大记忆数。

        Returns:
            按 importance 降序排列的 MemoryEntry 列表。
        """
        self._check_closed()
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE importance >= ? ORDER BY importance DESC LIMIT ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (min_importance, top_k))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"获取重要记忆失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def get_entries_by_ids(self, mem_ids: list[str]) -> list[MemoryEntry]:
        """按 ID 列表批量获取记忆（SQL IN 查询，_get_by_ids 的公开封装）。

        Args:
            mem_ids: 记忆 UUID 列表。

        Returns:
            MemoryEntry 列表，顺序与数据库返回一致。
        """
        self._check_closed()
        return self._get_by_ids(mem_ids)

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

    def count_active(self) -> int:
        """统计未归档的记忆数量。

        Returns:
            未归档的记忆总数。
        """
        self._check_closed()
        try:
            with self._lock:
                cursor = self._conn.execute(
                    f"SELECT COUNT(*) FROM {self._TABLE_NAME} WHERE archived = 0"
                )
                return cursor.fetchone()[0]
        except sqlite3.Error as e:
            self._log.error(f"计数失败: {e}")
            raise MemoryStorageError(f"计数失败: {e}") from e

    def get_oldest_unarchived(self, limit: int = 50) -> list[MemoryEntry]:
        """获取最旧的未归档记忆。

        按创建时间升序（最旧的优先），返回指定数量的未归档记忆。

        Args:
            limit: 返回的最大记忆数。

        Returns:
            MemoryEntry 列表，按创建时间升序排列。
        """
        self._check_closed()
        sql = (
            f"SELECT * FROM {self._TABLE_NAME} "
            "WHERE archived = 0 "
            "ORDER BY created_at ASC LIMIT ?"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (limit,))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"获取最旧未归档记忆失败: {e}")
            raise MemoryStorageError(f"获取最旧未归档记忆失败: {e}") from e
        return [self._row_to_entry(row) for row in rows]

    def _mark_archived_no_commit(self, mem_ids: list[str]) -> int:
        """内部方法：批量标记记忆为已归档，不提交事务。

        调用者必须持有 self._lock 并负责事务管理（BEGIN/COMMIT/ROLLBACK）。
        通常与 transaction() 上下文管理器配合使用。

        Args:
            mem_ids: 要标记的记忆 ID 列表。

        Returns:
            成功标记的记忆条数。
        """
        placeholders = ",".join("?" for _ in mem_ids)
        sql = (
            f"UPDATE {self._TABLE_NAME} SET archived = 1 "
            f"WHERE id IN ({placeholders})"
        )
        cursor = self._conn.execute(sql, mem_ids)
        self._log.info(
            f"[归档标记] 已标记 {cursor.rowcount} 条记忆为已归档"
        )
        return cursor.rowcount

    def mark_archived(self, mem_ids: list[str]) -> int:
        """批量标记记忆为已归档（公开方法，自行管理事务和锁）。

        Args:
            mem_ids: 要标记的记忆 ID 列表。

        Returns:
            成功标记的记忆条数。
        """
        if not mem_ids:
            return 0
        self._check_closed()
        try:
            with self._lock:
                result = self._mark_archived_no_commit(mem_ids)
                self._conn.commit()
                return result
        except sqlite3.Error as e:
            self._log.error(f"标记归档失败: {e}")
            raise MemoryStorageError(f"标记归档失败: {e}") from e

    def update_importance(self, memory_id: str, new_importance: float) -> bool:
        """更新记忆的重要性评分。

        Args:
            memory_id: 记忆 UUID。
            new_importance: 新重要性评分（0.0~1.0）。

        Returns:
            True 如果更新成功。
        """
        self._check_closed()
        new_importance = max(0.0, min(1.0, new_importance))
        sql = f"UPDATE {self._TABLE_NAME} SET importance = ? WHERE id = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (new_importance, memory_id))
                self._conn.commit()
                return cursor.rowcount > 0
        except sqlite3.Error as e:
            self._log.debug(f"更新重要性失败: {e}")
            return False

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
            access_count = ?, decay_factor = ?, source_turn_id = ?,
            source = ?
            WHERE rowid = ?"""
        params = (
            entry.memory_type, encrypted_content,
            json.dumps(entry.keywords, ensure_ascii=False),
            json.dumps(entry.embedding), entry.importance,
            entry.created_at, entry.last_accessed,
            entry.access_count, entry.decay_factor,
            entry.source_turn_id, entry.source, rowid,
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
        """按关键字在 content 字段中模糊搜索。

        使用倒排索引预过滤候选 ID，再通过 SQL IN 查询加载实际数据，
        避免全量加载所有记忆。content 字段已加密，LIKE 匹配在 Python 侧完成。

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

        # 1. 倒排索引预过滤：提取查询关键词，获取候选记忆 ID
        query_keywords = extract_keywords(keyword)
        candidate_ids = self.inverted_index.search(query_keywords)

        if candidate_ids:
            # 倒排索引命中：仅加载候选记忆
            candidates = self._get_by_ids(list(candidate_ids))
            self._log.debug(f"[关键字搜索] 倒排索引命中 {len(candidates)} 条候选")
        else:
            # 倒排索引无命中：回退到全量加载，但有阈值保护
            total_count = self.count()
            if total_count > 2000:
                self._log.warning(
                    f"[关键字搜索] 全量回退已拒绝: 记忆数 {total_count} > 2000，防止 OOM"
                )
                return []
            candidates = self._get_all()
            self._log.debug(f"[关键字搜索] 倒排索引无命中，回退全量 ({len(candidates)} 条)")

        # 2. 仅获取候选记忆的 rowid 映射（而非全量）
        id_to_rowid: dict[str, int] = {}
        if candidates:
            mem_ids = [e.id for e in candidates]
            placeholders = ",".join("?" for _ in mem_ids)
            try:
                with self._lock:
                    cursor = self._conn.execute(
                        f"SELECT rowid, id FROM {self._TABLE_NAME} WHERE id IN ({placeholders})",
                        mem_ids,
                    )
                    for row in cursor:
                        id_to_rowid[row[1]] = row[0]
            except sqlite3.Error as e:
                self._log.error(f"[关键字搜索] 获取 rowid 映射失败: {e}")
                id_to_rowid = {}

        # 3. Python 侧 LIKE 匹配（content 加密，无法在 SQL 层做 LIKE）
        results: list[dict] = []
        for entry in candidates:
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
        # 读取 archived 字段（兼容旧数据库可能没有该字段）
        archived = bool(row[11]) if len(row) > 11 else False
        # 读取 source 字段（兼容旧数据库可能没有该字段）
        source = row[12] if len(row) > 12 else "user_related"
        return MemoryEntry(
            id=row[0], memory_type=row[1], content=decrypted_content, keywords=keywords,
            embedding=embedding, importance=float(row[5]), created_at=row[6],
            last_accessed=row[7], access_count=int(row[8]), decay_factor=float(row[9]),
            source_turn_id=row[10], archived=archived, source=source,
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

    def reopen(self) -> None:
        """重新打开数据库连接（在 close() 或 restore 后调用）。

        恢复数据库连接并重建索引，使 VectorStore 恢复可用状态。
        """
        if not self._closed:
            self._log.warning("VectorStore 连接已打开，无需 reopen")
            return
        try:
            self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.execute("PRAGMA busy_timeout=5000")
            self._create_indexes()
            self._closed = False
            self._log.info("VectorStore 数据库连接已重新打开")
        except sqlite3.Error as e:
            self._log.error(f"重新打开数据库连接失败: {e}")
            raise MemoryStorageError(f"无法重新打开数据库: {e}") from e

    def _checkpoint(self) -> None:
        """执行 WAL checkpoint，将 WAL 日志合并到主数据库。"""
        try:
            with self._lock:
                self._conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        except sqlite3.Error as e:
            self._log.debug(f"WAL checkpoint 失败（可忽略）: {e}")

    # =========================================================================
    # 辅助方法：通过 entry 获取 rowid
    # =========================================================================

    def _get_rowid_for_entry(self, entry: MemoryEntry) -> int:
        """通过 entry.id 获取对应的 SQLite rowid。

        Args:
            entry: MemoryEntry 实例，必须包含有效的 id 字段。

        Returns:
            SQLite 隐式 rowid（整数）。

        Raises:
            MemoryNotFoundError: entry.id 不存在时。
            MemoryStorageError: 数据库操作失败时。
        """
        self._check_closed()
        sql = "SELECT rowid FROM memories WHERE id = ?"
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (entry.id,))
                row = cursor.fetchone()
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"获取 rowid 失败: {e}", detail=f"id={entry.id}"
            ) from e
        if row is None:
            raise MemoryNotFoundError(
                f"记忆不存在: id={entry.id}", detail=f"id={entry.id}"
            )
        return row[0]

    # =========================================================================
    # 记忆关系图管理
    # =========================================================================

    _RELATIONS_TABLE = "memory_relations"

    _CREATE_RELATIONS_TABLE_SQL = f"""
    CREATE TABLE IF NOT EXISTS {_RELATIONS_TABLE} (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        source_id TEXT NOT NULL,
        target_id TEXT NOT NULL,
        relation_type TEXT NOT NULL DEFAULT 'related_to',
        confidence REAL NOT NULL DEFAULT 0.5,
        created_at TEXT NOT NULL DEFAULT '',
        notes TEXT NOT NULL DEFAULT '',
        FOREIGN KEY (source_id) REFERENCES memories(id) ON DELETE CASCADE,
        FOREIGN KEY (target_id) REFERENCES memories(id) ON DELETE CASCADE
    )
    """

    def _ensure_relations_table(self) -> None:
        """确保记忆关系表存在。"""
        try:
            with self._lock:
                self._conn.execute(self._CREATE_RELATIONS_TABLE_SQL)
                self._conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_relations_source "
                    f"ON {self._RELATIONS_TABLE}(source_id)"
                )
                self._conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_relations_target "
                    f"ON {self._RELATIONS_TABLE}(target_id)"
                )
                self._conn.commit()
        except sqlite3.Error as e:
            self._log.warning(f"创建关系表失败: {e}")

    def add_relation(
        self,
        source_id: str,
        target_id: str,
        relation_type: str = "related_to",
        confidence: float = 0.5,
        notes: str = "",
    ) -> int:
        """添加记忆关系。

        Args:
            source_id: 源记忆 UUID。
            target_id: 目标记忆 UUID。
            relation_type: 关系类型（contradicts/extends/refines/supersedes/related_to/caused_by/part_of/similar_to）。
            confidence: 置信度（0.0~1.0）。
            notes: 关系备注。

        Returns:
            新关系的 ID。
        """
        self._check_closed()
        self._ensure_relations_table()
        from src.utils.time_utils import format_timestamp_iso
        now = format_timestamp_iso()
        sql = (
            f"INSERT INTO {self._RELATIONS_TABLE} "
            "(source_id, target_id, relation_type, confidence, created_at, notes) "
            "VALUES (?, ?, ?, ?, ?, ?)"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(
                    sql, (source_id, target_id, relation_type, confidence, now, notes)
                )
                self._conn.commit()
                self._log.debug(
                    f"[关系] 添加: {source_id[:8]} --{relation_type}--> {target_id[:8]}"
                )
                return cursor.lastrowid
        except sqlite3.Error as e:
            raise MemoryStorageError(f"添加关系失败: {e}") from e

    def get_relations(self, memory_id: str) -> list[dict]:
        """获取某条记忆的所有关系。

        Args:
            memory_id: 记忆 UUID。

        Returns:
            关系字典列表，每项包含 source_id, target_id, relation_type, confidence。
        """
        self._check_closed()
        self._ensure_relations_table()
        sql = (
            f"SELECT * FROM {self._RELATIONS_TABLE} "
            "WHERE source_id = ? OR target_id = ?"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (memory_id, memory_id))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            return []
        results = []
        for row in rows:
            results.append({
                "id": row[0],
                "source_id": row[1],
                "target_id": row[2],
                "relation_type": row[3],
                "confidence": row[4],
                "created_at": row[5],
                "notes": row[6],
            })
        return results

    def delete_relations(self, memory_id: str) -> int:
        """删除某条记忆的所有关系。

        Args:
            memory_id: 记忆 UUID。

        Returns:
            删除的关系数。
        """
        self._check_closed()
        self._ensure_relations_table()
        sql = (
            f"DELETE FROM {self._RELATIONS_TABLE} "
            "WHERE source_id = ? OR target_id = ?"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (memory_id, memory_id))
                self._conn.commit()
                return cursor.rowcount
        except sqlite3.Error as e:
            return 0

    # =========================================================================
    # 记忆标签管理
    # =========================================================================

    _TAGS_TABLE = "memory_tags"
    _TAG_MAP_TABLE = "memory_tag_map"

    _CREATE_TAGS_TABLE_SQL = f"""
    CREATE TABLE IF NOT EXISTS {_TAGS_TABLE} (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL UNIQUE,
        color TEXT NOT NULL DEFAULT '#9B59B6',
        created_at TEXT NOT NULL DEFAULT ''
    )
    """

    _CREATE_TAG_MAP_TABLE_SQL = f"""
    CREATE TABLE IF NOT EXISTS {_TAG_MAP_TABLE} (
        tag_id INTEGER NOT NULL,
        memory_id TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT '',
        PRIMARY KEY (tag_id, memory_id),
        FOREIGN KEY (tag_id) REFERENCES {_TAGS_TABLE}(id) ON DELETE CASCADE,
        FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE
    )
    """

    def _ensure_tags_tables(self) -> None:
        """确保标签相关表存在。"""
        try:
            self._conn.execute(self._CREATE_TAGS_TABLE_SQL)
            self._conn.execute(self._CREATE_TAG_MAP_TABLE_SQL)
            self._conn.execute(
                f"CREATE INDEX IF NOT EXISTS idx_tag_map_memory "
                f"ON {_TAG_MAP_TABLE}(memory_id)"
            )
            self._conn.commit()
        except sqlite3.Error as e:
            self._log.warning(f"创建标签表失败: {e}")

    def add_tag(self, name: str, color: str = "#9B59B6") -> int:
        """添加标签。

        Args:
            name: 标签名称。
            color: 标签颜色。

        Returns:
            标签 ID。
        """
        self._check_closed()
        self._ensure_tags_tables()
        from src.utils.time_utils import format_timestamp_iso
        now = format_timestamp_iso()
        sql = (
            f"INSERT OR IGNORE INTO {_TAGS_TABLE} (name, color, created_at) "
            "VALUES (?, ?, ?)"
        )
        try:
            with self._lock:
                self._conn.execute(sql, (name, color, now))
                self._conn.commit()
                cursor = self._conn.execute(
                    f"SELECT id FROM {_TAGS_TABLE} WHERE name = ?", (name,)
                )
                row = cursor.fetchone()
                return row[0] if row else -1
        except sqlite3.Error as e:
            raise MemoryStorageError(f"添加标签失败: {e}") from e

    def tag_memory(self, memory_id: str, tag_name: str) -> None:
        """为记忆添加标签。

        Args:
            memory_id: 记忆 UUID。
            tag_name: 标签名称。
        """
        self._check_closed()
        self._ensure_tags_tables()
        # 先创建标签（如果不存在）
        tag_id = self.add_tag(tag_name)
        if tag_id < 0:
            return
        from src.utils.time_utils import format_timestamp_iso
        now = format_timestamp_iso()
        sql = (
            f"INSERT OR IGNORE INTO {_TAG_MAP_TABLE} (tag_id, memory_id, created_at) "
            "VALUES (?, ?, ?)"
        )
        try:
            with self._lock:
                self._conn.execute(sql, (tag_id, memory_id, now))
                self._conn.commit()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"标记记忆失败: {e}") from e

    def untag_memory(self, memory_id: str, tag_name: str) -> None:
        """移除记忆的标签。

        Args:
            memory_id: 记忆 UUID。
            tag_name: 标签名称。
        """
        self._check_closed()
        self._ensure_tags_tables()
        sql = (
            f"DELETE FROM {_TAG_MAP_TABLE} "
            "WHERE memory_id = ? AND tag_id = (SELECT id FROM memory_tags WHERE name = ?)"
        )
        try:
            with self._lock:
                self._conn.execute(sql, (memory_id, tag_name))
                self._conn.commit()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"移除标签失败: {e}") from e

    def get_memory_tags(self, memory_id: str) -> list[dict]:
        """获取记忆的所有标签。

        Args:
            memory_id: 记忆 UUID。

        Returns:
            标签字典列表。
        """
        self._check_closed()
        self._ensure_tags_tables()
        sql = (
            f"SELECT t.id, t.name, t.color, t.created_at "
            f"FROM {_TAGS_TABLE} t "
            f"JOIN {_TAG_MAP_TABLE} m ON t.id = m.tag_id "
            "WHERE m.memory_id = ?"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(sql, (memory_id,))
                rows = cursor.fetchall()
        except sqlite3.Error:
            return []
        return [
            {"id": r[0], "name": r[1], "color": r[2], "created_at": r[3]}
            for r in rows
        ]

    def list_all_tags(self) -> list[dict]:
        """列出所有标签。

        Returns:
            标签字典列表，包含每个标签关联的记忆数量。
        """
        self._check_closed()
        self._ensure_tags_tables()
        sql = (
            f"SELECT t.id, t.name, t.color, t.created_at, COUNT(m.memory_id) as cnt "
            f"FROM {_TAGS_TABLE} t "
            f"LEFT JOIN {_TAG_MAP_TABLE} m ON t.id = m.tag_id "
            "GROUP BY t.id ORDER BY cnt DESC"
        )
        try:
            with self._lock:
                cursor = self._conn.execute(sql)
                rows = cursor.fetchall()
        except sqlite3.Error:
            return []
        return [
            {"id": r[0], "name": r[1], "color": r[2], "created_at": r[3], "memory_count": r[4]}
            for r in rows
        ]

    # =========================================================================
    # 变更日志管理
    # =========================================================================

    _CHANGELOG_TABLE = "memory_changelog"

    _CREATE_CHANGELOG_TABLE_SQL = f"""
    CREATE TABLE IF NOT EXISTS {_CHANGELOG_TABLE} (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        memory_id TEXT NOT NULL,
        rowid INTEGER NOT NULL DEFAULT 0,
        change_type TEXT NOT NULL,
        old_content TEXT NOT NULL DEFAULT '',
        new_content TEXT NOT NULL DEFAULT '',
        changed_at TEXT NOT NULL DEFAULT '',
        reason TEXT NOT NULL DEFAULT ''
    )
    """

    def _ensure_changelog_table(self) -> None:
        """确保变更日志表存在。"""
        try:
            self._conn.execute(self._CREATE_CHANGELOG_TABLE_SQL)
            self._conn.execute(
                f"CREATE INDEX IF NOT EXISTS idx_changelog_memory "
                f"ON {_CHANGELOG_TABLE}(memory_id)"
            )
            self._conn.execute(
                f"CREATE INDEX IF NOT EXISTS idx_changelog_time "
                f"ON {_CHANGELOG_TABLE}(changed_at DESC)"
            )
            self._conn.commit()
        except sqlite3.Error as e:
            self._log.warning(f"创建变更日志表失败: {e}")

    def log_change(
        self,
        memory_id: str,
        rowid: int,
        change_type: str,
        old_content: str = "",
        new_content: str = "",
        reason: str = "",
    ) -> None:
        """记录记忆变更日志。

        Args:
            memory_id: 记忆 UUID。
            rowid: 记忆行 ID。
            change_type: 变更类型（create/update/delete/archive/consolidate）。
            old_content: 变更前内容。
            new_content: 变更后内容。
            reason: 变更原因。
        """
        self._check_closed()
        self._ensure_changelog_table()
        from src.utils.time_utils import format_timestamp_iso
        now = format_timestamp_iso()
        sql = (
            f"INSERT INTO {_CHANGELOG_TABLE} "
            "(memory_id, rowid, change_type, old_content, new_content, changed_at, reason) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        try:
            with self._lock:
                self._conn.execute(
                    sql, (memory_id, rowid, change_type, old_content, new_content, now, reason)
                )
                self._conn.commit()
        except sqlite3.Error as e:
            self._log.debug(f"记录变更日志失败: {e}")

    def get_changelog(self, memory_id: str = "", limit: int = 50) -> list[dict]:
        """获取变更日志。

        Args:
            memory_id: 记忆 UUID（空字符串返回所有日志）。
            limit: 返回的最大日志数。

        Returns:
            变更日志字典列表。
        """
        self._check_closed()
        self._ensure_changelog_table()
        if memory_id:
            sql = (
                f"SELECT * FROM {_CHANGELOG_TABLE} "
                "WHERE memory_id = ? ORDER BY changed_at DESC LIMIT ?"
            )
            params = (memory_id, limit)
        else:
            sql = (
                f"SELECT * FROM {_CHANGELOG_TABLE} "
                "ORDER BY changed_at DESC LIMIT ?"
            )
            params = (limit,)
        try:
            with self._lock:
                cursor = self._conn.execute(sql, params)
                rows = cursor.fetchall()
        except sqlite3.Error:
            return []
        return [
            {
                "id": r[0], "memory_id": r[1], "rowid": r[2],
                "change_type": r[3], "old_content": r[4],
                "new_content": r[5], "changed_at": r[6], "reason": r[7],
            }
            for r in rows
        ]

    # =========================================================================
    # BM25 索引构建
    # =========================================================================

    def build_bm25_index(self) -> "BM25Scorer":
        """构建 BM25 索引（从所有记忆构建）。

        将所有记忆的 content 作为文档，id 作为文档 ID，构建 BM25 倒排索引。

        Returns:
            构建好的 BM25Scorer 实例。
        """
        from src.memory.bm25 import BM25Scorer
        bm25 = BM25Scorer()
        total = self.count()
        offset = 0
        page_size = 500
        while offset < total:
            page = self.get_page(offset, page_size)
            for entry in page:
                if entry.content:
                    bm25.add_document(entry.id, entry.content)
            offset += len(page)
            if len(page) < page_size:
                break
        return bm25
