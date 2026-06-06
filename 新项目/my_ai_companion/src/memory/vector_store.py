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

import json
import math
import re
import sqlite3
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock

from src.exceptions import MemoryException, MemoryNotFoundError, MemoryStorageError
from src.utils.logger import get_logger


# =============================================================================
# 数据结构
# =============================================================================


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
        """转换为字典（用于 JSON 序列化）。"""
        return {
            "id": self.id,
            "memory_type": self.memory_type,
            "content": self.content,
            "keywords": self.keywords,
            "embedding": self.embedding,
            "importance": self.importance,
            "created_at": self.created_at,
            "last_accessed": self.last_accessed,
            "access_count": self.access_count,
            "decay_factor": self.decay_factor,
            "source_turn_id": self.source_turn_id,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "MemoryEntry":
        """从字典创建 MemoryEntry 实例。

        Args:
            data: 包含所有字段的字典。

        Returns:
            MemoryEntry 实例。
        """
        return cls(
            id=data.get("id", ""),
            memory_type=data.get("memory_type", "semantic"),
            content=data.get("content", ""),
            keywords=data.get("keywords", []),
            embedding=data.get("embedding", []),
            importance=data.get("importance", 0.5),
            created_at=data.get("created_at", ""),
            last_accessed=data.get("last_accessed", ""),
            access_count=data.get("access_count", 0),
            decay_factor=data.get("decay_factor", 1.0),
            source_turn_id=data.get("source_turn_id", ""),
        )


# =============================================================================
# 工具函数
# =============================================================================


def extract_keywords(text: str) -> list[str]:
    """从文本中提取关键词（bigram/trigram）。

    移除标点符号后，提取所有 2-gram 和 3-gram 作为关键词。
    这是纯 Python 实现，Chaquopy 兼容。

    Args:
        text: 输入文本。

    Returns:
        去重后的关键词列表。
    """
    if not text:
        return []

    # 移除标点，保留中文字符、字母、数字
    cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", text)
    if len(cleaned) < 2:
        return []

    keywords: list[str] = []

    # 提取 bigram（2-gram）
    for i in range(len(cleaned) - 1):
        keywords.append(cleaned[i : i + 2])

    # 提取 trigram（3-gram）
    for i in range(len(cleaned) - 2):
        keywords.append(cleaned[i : i + 3])

    return list(set(keywords))


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """纯 Python 余弦相似度计算。

    计算两个向量之间的余弦相似度，范围 [-1, 1]。
    对于语义向量，通常在 [0, 1] 之间。

    Args:
        a: 第一个向量（float 列表）。
        b: 第二个向量（float 列表）。

    Returns:
        余弦相似度值。若任一向量模长为零，返回 0.0。
    """
    if len(a) != len(b):
        raise ValueError(f"向量维度不一致: {len(a)} vs {len(b)}")

    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(x * x for x in b))

    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0

    return dot / (norm_a * norm_b)


# =============================================================================
# 倒排索引
# =============================================================================


class InvertedIndex:
    """纯 Python 倒排索引，用于关键词预过滤。

    维护 keyword -> set of memory IDs 的映射，
    支持快速添加、删除和查询。

    Attributes:
        _index: 关键词到记忆 ID 集合的映射。
    """

    def __init__(self) -> None:
        """初始化空的倒排索引。"""
        self._index: dict[str, set[str]] = {}
        self._log = get_logger()

    def add(self, mem_id: str, keywords: list[str]) -> None:
        """向索引中添加一个记忆的关键词映射。

        Args:
            mem_id: 记忆唯一标识符。
            keywords: 关键词列表。
        """
        for kw in keywords:
            if kw not in self._index:
                self._index[kw] = set()
            self._index[kw].add(mem_id)
        self._log.debug(f"[倒排索引] 添加记忆 {mem_id}, 关键词数={len(keywords)}")

    def remove(self, mem_id: str, keywords: list[str]) -> None:
        """从索引中移除一个记忆的关键词映射。

        Args:
            mem_id: 记忆唯一标识符。
            keywords: 关键词列表。
        """
        for kw in keywords:
            if kw in self._index:
                self._index[kw].discard(mem_id)
                # 清理空集合
                if not self._index[kw]:
                    del self._index[kw]
        self._log.debug(f"[倒排索引] 移除记忆 {mem_id}")

    def search(self, query_keywords: list[str]) -> set[str]:
        """检索包含任意查询关键词的记忆 ID 集合。

        Args:
            query_keywords: 查询关键词列表。

        Returns:
            包含任意关键词的记忆 ID 集合。
        """
        result: set[str] = set()
        for kw in query_keywords:
            if kw in self._index:
                result.update(self._index[kw])
        return result

    def clear(self) -> None:
        """清空索引。"""
        self._index.clear()
        self._log.info("[倒排索引] 已清空")

    def size(self) -> int:
        """获取索引中不同关键词的数量。

        Returns:
            关键词数量。
        """
        return len(self._index)


# =============================================================================
# 向量存储
# =============================================================================


class VectorStore:
    """基于 SQLite 的向量存储。

    使用 SQLite 数据库持久化记忆条目，配合倒排索引实现
    高效的混合检索（关键词预过滤 + 余弦相似度精排）。

    线程安全：所有数据库操作通过 threading.Lock 保护。

    Attributes:
        db_path: SQLite 数据库文件路径。
        inverted_index: 倒排索引实例。
    """

    # SQLite 表名
    _TABLE_NAME = "memories"

    # 建表 SQL
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
        """初始化向量存储。

        Args:
            db_path: SQLite 数据库文件路径。默认为 ":memory:"（内存数据库）。
                     可使用磁盘路径持久化存储。
        """
        self.db_path = str(db_path)
        self._log = get_logger()
        self._lock = Lock()

        # 初始化数据库连接和表
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._create_table()

        # 初始化倒排索引并从数据库加载
        self.inverted_index = InvertedIndex()
        self._load_index()

        self._log.info(
            f"VectorStore 初始化完成: db_path={self.db_path}, "
            f"记忆数={self.count()}, 索引关键词数={self.inverted_index.size()}"
        )

    def _create_table(self) -> None:
        """创建记忆表（如果不存在）。"""
        try:
            self._conn.execute(self._CREATE_TABLE_SQL)
            self._conn.commit()
            self._log.debug("数据库表已就绪")
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"创建数据库表失败: {e}", detail=self.db_path
            ) from e

    def _load_index(self) -> None:
        """从数据库加载所有记忆的关键词到倒排索引。"""
        try:
            cursor = self._conn.execute(
                f"SELECT id, keywords FROM {self._TABLE_NAME}"
            )
            for row in cursor:
                mem_id = row[0]
                try:
                    keywords = json.loads(row[1])
                except (json.JSONDecodeError, TypeError):
                    keywords = []
                if keywords:
                    self.inverted_index.add(mem_id, keywords)
            self._log.debug(f"倒排索引已从数据库加载")
        except sqlite3.Error as e:
            self._log.error(f"加载倒排索引失败: {e}")

    # -------------------------------------------------------------------------
    # CRUD 操作
    # -------------------------------------------------------------------------

    def add(self, entry: MemoryEntry) -> str:
        """添加一条记忆到数据库。

        自动提取关键词（如果未提供）并更新倒排索引。

        Args:
            entry: 记忆条目。

        Returns:
            记忆的 ID。

        Raises:
            MemoryStorageError: 数据库写入失败时。
        """
        # 自动提取关键词（如果为空）
        if not entry.keywords and entry.content:
            entry.keywords = extract_keywords(entry.content)

        # 确保 ID 存在
        if not entry.id:
            entry.id = str(uuid.uuid4())

        # 设置时间戳（如果为空）
        if not entry.created_at:
            from src.utils.time_utils import format_timestamp_iso

            entry.created_at = format_timestamp_iso()
        if not entry.last_accessed:
            entry.last_accessed = entry.created_at

        sql = f"""
        INSERT OR REPLACE INTO {self._TABLE_NAME}
        (id, type, content, keywords, embedding, importance,
         created_at, last_accessed, access_count, decay_factor, source_turn_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        params = (
            entry.id,
            entry.memory_type,
            entry.content,
            json.dumps(entry.keywords, ensure_ascii=False),
            json.dumps(entry.embedding),
            entry.importance,
            entry.created_at,
            entry.last_accessed,
            entry.access_count,
            entry.decay_factor,
            entry.source_turn_id,
        )

        try:
            with self._lock:
                self._conn.execute(sql, params)
                self._conn.commit()
                # 更新倒排索引
                if entry.keywords:
                    self.inverted_index.add(entry.id, entry.keywords)
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"添加记忆失败: {e}", detail=f"id={entry.id}, content={entry.content[:50]}"
            ) from e

        self._log.debug(
            f"记忆已添加: id={entry.id}, type={entry.memory_type}, "
            f"content={entry.content[:50]}..."
        )
        return entry.id

    def get(self, mem_id: str) -> MemoryEntry:
        """根据 ID 获取单条记忆。

        Args:
            mem_id: 记忆唯一标识符。

        Returns:
            MemoryEntry 实例。

        Raises:
            MemoryNotFoundError: 记忆不存在时。
            MemoryStorageError: 数据库查询失败时。
        """
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE id = ?"

        try:
            with self._lock:
                cursor = self._conn.execute(sql, (mem_id,))
                row = cursor.fetchone()
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"查询记忆失败: {e}", detail=f"id={mem_id}"
            ) from e

        if row is None:
            raise MemoryNotFoundError(f"记忆不存在: {mem_id}", detail=f"id={mem_id}")

        return self._row_to_entry(row)

    def delete(self, mem_id: str) -> None:
        """删除指定记忆。

        Args:
            mem_id: 记忆唯一标识符。

        Raises:
            MemoryNotFoundError: 记忆不存在时。
            MemoryStorageError: 数据库操作失败时。
        """
        # 先获取记忆以拿到关键词（用于更新索引）
        try:
            entry = self.get(mem_id)
        except MemoryNotFoundError:
            raise

        sql = f"DELETE FROM {self._TABLE_NAME} WHERE id = ?"

        try:
            with self._lock:
                self._conn.execute(sql, (mem_id,))
                self._conn.commit()
                # 更新倒排索引
                if entry.keywords:
                    self.inverted_index.remove(mem_id, entry.keywords)
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"删除记忆失败: {e}", detail=f"id={mem_id}"
            ) from e

        self._log.debug(f"记忆已删除: id={mem_id}")

    def update(self, entry: MemoryEntry) -> None:
        """更新记忆条目。

        Args:
            entry: 包含新数据的记忆条目（按 id 匹配更新）。

        Raises:
            MemoryNotFoundError: 记忆不存在时。
            MemoryStorageError: 数据库操作失败时。
        """
        # 先获取旧记忆以清理旧关键词
        try:
            old_entry = self.get(entry.id)
        except MemoryNotFoundError:
            raise

        sql = f"""
        UPDATE {self._TABLE_NAME}
        SET type = ?, content = ?, keywords = ?, embedding = ?, importance = ?,
            created_at = ?, last_accessed = ?, access_count = ?, decay_factor = ?,
            source_turn_id = ?
        WHERE id = ?
        """
        params = (
            entry.memory_type,
            entry.content,
            json.dumps(entry.keywords, ensure_ascii=False),
            json.dumps(entry.embedding),
            entry.importance,
            entry.created_at,
            entry.last_accessed,
            entry.access_count,
            entry.decay_factor,
            entry.source_turn_id,
            entry.id,
        )

        try:
            with self._lock:
                self._conn.execute(sql, params)
                self._conn.commit()
                # 更新倒排索引：先移除旧关键词，再添加新关键词
                if old_entry.keywords:
                    self.inverted_index.remove(entry.id, old_entry.keywords)
                if entry.keywords:
                    self.inverted_index.add(entry.id, entry.keywords)
        except sqlite3.Error as e:
            raise MemoryStorageError(
                f"更新记忆失败: {e}", detail=f"id={entry.id}"
            ) from e

        self._log.debug(f"记忆已更新: id={entry.id}")

    # -------------------------------------------------------------------------
    # 检索操作
    # -------------------------------------------------------------------------

    def search(
        self,
        query_embedding: list[float],
        query_text: str = "",
        top_k: int = 5,
    ) -> list[MemoryEntry]:
        """混合检索：倒排索引预过滤 + 余弦相似度精排。

        检索流程：
        1. 如果提供了 query_text，提取关键词并通过倒排索引获取候选集。
        2. 如果候选集为空，回退到全量余弦相似度检索。
        3. 对候选集计算余弦相似度并排序。
        4. 返回 top_k 结果。

        Args:
            query_embedding: 查询文本的向量表示。
            query_text: 查询文本（用于关键词预过滤，可选）。
            top_k: 返回结果数量。

        Returns:
            按相似度降序排列的记忆条目列表。
        """
        # 步骤1：获取候选集
        candidates: list[MemoryEntry] = []

        if query_text:
            # 提取查询关键词
            query_keywords = extract_keywords(query_text)
            # 倒排索引预过滤
            candidate_ids = self.inverted_index.search(query_keywords)

            if candidate_ids:
                # 根据候选 ID 批量获取记忆
                candidates = self._get_by_ids(list(candidate_ids))
                self._log.debug(
                    f"[检索] 倒排索引命中 {len(candidates)} 条候选"
                )

        # 步骤2：如果候选集为空，回退到全量
        if not candidates:
            candidates = self._get_all()
            self._log.debug(
                f"[检索] 倒排索引无命中，回退到全量检索 ({len(candidates)} 条)"
            )

        if not candidates:
            return []

        # 步骤3：余弦相似度精排
        scored: list[tuple[MemoryEntry, float]] = []
        for entry in candidates:
            if entry.embedding:
                try:
                    sim = cosine_similarity(query_embedding, entry.embedding)
                except ValueError:
                    sim = 0.0
            else:
                sim = 0.0
            scored.append((entry, sim))

        # 按相似度降序排序
        scored.sort(key=lambda x: x[1], reverse=True)

        # 步骤4：返回 top_k
        result = [entry for entry, _ in scored[:top_k]]

        # 更新访问信息
        for entry in result:
            self._record_access(entry)

        self._log.info(
            f"[检索] 完成: 候选={len(candidates)}, 返回={len(result)}, top_k={top_k}"
        )
        return result

    def _get_by_ids(self, mem_ids: list[str]) -> list[MemoryEntry]:
        """批量根据 ID 获取记忆条目。

        Args:
            mem_ids: 记忆 ID 列表。

        Returns:
            记忆条目列表。
        """
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
            return []

        return [self._row_to_entry(row) for row in rows]

    def _get_all(self) -> list[MemoryEntry]:
        """获取所有记忆条目。

        Returns:
            所有记忆条目列表。
        """
        sql = f"SELECT * FROM {self._TABLE_NAME}"

        try:
            with self._lock:
                cursor = self._conn.execute(sql)
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"查询所有记忆失败: {e}")
            return []

        return [self._row_to_entry(row) for row in rows]

    def _record_access(self, entry: MemoryEntry) -> None:
        """记录记忆访问（更新 last_accessed 和 access_count）。

        这是一个轻量级更新，不影响检索性能。

        Args:
            entry: 被访问的记忆条目。
        """
        from src.utils.time_utils import format_timestamp_iso

        entry.access_count += 1
        entry.last_accessed = format_timestamp_iso()

        sql = f"""
        UPDATE {self._TABLE_NAME}
        SET last_accessed = ?, access_count = ?
        WHERE id = ?
        """
        try:
            with self._lock:
                self._conn.execute(
                    sql, (entry.last_accessed, entry.access_count, entry.id)
                )
                self._conn.commit()
        except sqlite3.Error as e:
            # 访问记录更新失败不应影响检索流程
            self._log.debug(f"记录访问信息失败: {e}")

    # -------------------------------------------------------------------------
    # 批量/辅助操作
    # -------------------------------------------------------------------------

    def count(self) -> int:
        """获取记忆总数。

        Returns:
            记忆条目数量。
        """
        try:
            with self._lock:
                cursor = self._conn.execute(f"SELECT COUNT(*) FROM {self._TABLE_NAME}")
                return cursor.fetchone()[0]
        except sqlite3.Error as e:
            self._log.error(f"计数失败: {e}")
            return 0

    def clear(self) -> None:
        """清空所有记忆。"""
        try:
            with self._lock:
                self._conn.execute(f"DELETE FROM {self._TABLE_NAME}")
                self._conn.commit()
                self.inverted_index.clear()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"清空记忆失败: {e}") from e

        self._log.info("所有记忆已清空")

    def get_by_type(self, memory_type: str) -> list[MemoryEntry]:
        """按类型获取记忆列表。

        Args:
            memory_type: 记忆类型（"episodic" | "semantic" | "user_fact"）。

        Returns:
            匹配的记忆条目列表。
        """
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE type = ?"

        try:
            with self._lock:
                cursor = self._conn.execute(sql, (memory_type,))
                rows = cursor.fetchall()
        except sqlite3.Error as e:
            self._log.error(f"按类型查询失败: {e}")
            return []

        return [self._row_to_entry(row) for row in rows]

    def get_all(self) -> list[MemoryEntry]:
        """获取所有记忆条目。

        Returns:
            所有记忆条目列表。
        """
        return self._get_all()

    # -------------------------------------------------------------------------
    # 内部工具方法
    # -------------------------------------------------------------------------

    @staticmethod
    def _row_to_entry(row: tuple) -> MemoryEntry:
        """将数据库行转换为 MemoryEntry。

        Args:
            row: 数据库查询结果行。

        Returns:
            MemoryEntry 实例。
        """
        # 列顺序: id, type, content, keywords, embedding, importance,
        #          created_at, last_accessed, access_count, decay_factor, source_turn_id
        try:
            keywords = json.loads(row[3])
        except (json.JSONDecodeError, TypeError):
            keywords = []

        try:
            embedding = json.loads(row[4])
        except (json.JSONDecodeError, TypeError):
            embedding = []

        return MemoryEntry(
            id=row[0],
            memory_type=row[1],
            content=row[2],
            keywords=keywords,
            embedding=embedding,
            importance=float(row[5]),
            created_at=row[6],
            last_accessed=row[7],
            access_count=int(row[8]),
            decay_factor=float(row[9]),
            source_turn_id=row[10],
        )

    def close(self) -> None:
        """关闭数据库连接，释放资源。"""
        try:
            self._conn.close()
            self._log.info("VectorStore 数据库连接已关闭")
        except sqlite3.Error as e:
            self._log.error(f"关闭数据库连接失败: {e}")