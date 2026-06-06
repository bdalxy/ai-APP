"""向量存储模块 - 基于 SQLite 的记忆持久化 + 倒排索引 + 余弦相似度。"""
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

@dataclass
class MemoryEntry:
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
        return {"id": self.id, "memory_type": self.memory_type, "content": self.content, "keywords": self.keywords, "embedding": self.embedding, "importance": self.importance, "created_at": self.created_at, "last_accessed": self.last_accessed, "access_count": self.access_count, "decay_factor": self.decay_factor, "source_turn_id": self.source_turn_id}

    @classmethod
    def from_dict(cls, data: dict) -> "MemoryEntry":
        return cls(id=data.get("id", ""), memory_type=data.get("memory_type", "semantic"), content=data.get("content", ""), keywords=data.get("keywords", []), embedding=data.get("embedding", []), importance=data.get("importance", 0.5), created_at=data.get("created_at", ""), last_accessed=data.get("last_accessed", ""), access_count=data.get("access_count", 0), decay_factor=data.get("decay_factor", 1.0), source_turn_id=data.get("source_turn_id", ""))

def extract_keywords(text: str) -> list[str]:
    if not text:
        return []
    cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", text)
    if len(cleaned) < 2:
        return []
    keywords: list[str] = []
    for i in range(len(cleaned) - 1):
        keywords.append(cleaned[i:i+2])
    for i in range(len(cleaned) - 2):
        keywords.append(cleaned[i:i+3])
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
    def __init__(self) -> None:
        self._index: dict[str, set[str]] = {}
        self._log = get_logger()

    def add(self, mem_id: str, keywords: list[str]) -> None:
        for kw in keywords:
            if kw not in self._index:
                self._index[kw] = set()
            self._index[kw].add(mem_id)

    def remove(self, mem_id: str, keywords: list[str]) -> None:
        for kw in keywords:
            if kw in self._index:
                self._index[kw].discard(mem_id)
                if not self._index[kw]:
                    del self._index[kw]

    def search(self, query_keywords: list[str]) -> set[str]:
        result: set[str] = set()
        for kw in query_keywords:
            if kw in self._index:
                result.update(self._index[kw])
        return result

    def clear(self) -> None:
        self._index.clear()

    def size(self) -> int:
        return len(self._index)

class VectorStore:
    _TABLE_NAME = "memories"
    _CREATE_TABLE_SQL = f"""CREATE TABLE IF NOT EXISTS {_TABLE_NAME} (
        id TEXT PRIMARY KEY, type TEXT NOT NULL, content TEXT NOT NULL,
        keywords TEXT NOT NULL DEFAULT '[]', embedding TEXT NOT NULL DEFAULT '[]',
        importance REAL NOT NULL DEFAULT 0.5, created_at TEXT NOT NULL DEFAULT '',
        last_accessed TEXT NOT NULL DEFAULT '', access_count INTEGER NOT NULL DEFAULT 0,
        decay_factor REAL NOT NULL DEFAULT 1.0, source_turn_id TEXT NOT NULL DEFAULT '')"""

    def __init__(self, db_path: str | Path = ":memory:") -> None:
        self.db_path = str(db_path)
        self._log = get_logger()
        self._lock = Lock()
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._create_table()
        self.inverted_index = InvertedIndex()
        self._load_index()

    def _create_table(self) -> None:
        try:
            self._conn.execute(self._CREATE_TABLE_SQL)
            self._conn.commit()
        except sqlite3.Error as e:
            raise MemoryStorageError(f"创建表失败: {e}", detail=self.db_path)

    def _load_index(self) -> None:
        cursor = self._conn.execute(f"SELECT id, keywords FROM {self._TABLE_NAME}")
        for row in cursor:
            try:
                keywords = json.loads(row[1])
            except (json.JSONDecodeError, TypeError):
                keywords = []
            if keywords:
                self.inverted_index.add(row[0], keywords)

    def add(self, entry: MemoryEntry) -> str:
        if not entry.keywords and entry.content:
            entry.keywords = extract_keywords(entry.content)
        if not entry.id:
            entry.id = str(uuid.uuid4())
        if not entry.created_at:
            from src.utils.time_utils import format_timestamp_iso
            entry.created_at = format_timestamp_iso()
        if not entry.last_accessed:
            entry.last_accessed = entry.created_at
        sql = f"INSERT OR REPLACE INTO {self._TABLE_NAME} (id,type,content,keywords,embedding,importance,created_at,last_accessed,access_count,decay_factor,source_turn_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        params = (entry.id, entry.memory_type, entry.content, json.dumps(entry.keywords, ensure_ascii=False), json.dumps(entry.embedding), entry.importance, entry.created_at, entry.last_accessed, entry.access_count, entry.decay_factor, entry.source_turn_id)
        with self._lock:
            self._conn.execute(sql, params)
            self._conn.commit()
            if entry.keywords:
                self.inverted_index.add(entry.id, entry.keywords)
        return entry.id

    def get(self, mem_id: str) -> MemoryEntry:
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE id = ?"
        with self._lock:
            cursor = self._conn.execute(sql, (mem_id,))
            row = cursor.fetchone()
        if row is None:
            raise MemoryNotFoundError(f"记忆不存在: {mem_id}")
        return self._row_to_entry(row)

    def search(self, query_embedding: list[float], query_text: str = "", top_k: int = 5) -> list[MemoryEntry]:
        candidates: list[MemoryEntry] = []
        if query_text:
            query_keywords = extract_keywords(query_text)
            candidate_ids = self.inverted_index.search(query_keywords)
            if candidate_ids:
                candidates = self._get_by_ids(list(candidate_ids))
        if not candidates:
            candidates = self._get_all()
        if not candidates:
            return []
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
        scored.sort(key=lambda x: x[1], reverse=True)
        result = [entry for entry, _ in scored[:top_k]]
        for entry in result:
            self._record_access(entry)
        return result

    def _get_by_ids(self, mem_ids: list[str]) -> list[MemoryEntry]:
        if not mem_ids:
            return []
        placeholders = ",".join("?" for _ in mem_ids)
        sql = f"SELECT * FROM {self._TABLE_NAME} WHERE id IN ({placeholders})"
        with self._lock:
            cursor = self._conn.execute(sql, mem_ids)
            rows = cursor.fetchall()
        return [self._row_to_entry(row) for row in rows]

    def _get_all(self) -> list[MemoryEntry]:
        sql = f"SELECT * FROM {self._TABLE_NAME}"
        with self._lock:
            cursor = self._conn.execute(sql)
            rows = cursor.fetchall()
        return [self._row_to_entry(row) for row in rows]

    def _record_access(self, entry: MemoryEntry) -> None:
        from src.utils.time_utils import format_timestamp_iso
        entry.access_count += 1
        entry.last_accessed = format_timestamp_iso()
        sql = f"UPDATE {self._TABLE_NAME} SET last_accessed = ?, access_count = ? WHERE id = ?"
        with self._lock:
            self._conn.execute(sql, (entry.last_accessed, entry.access_count, entry.id))
            self._conn.commit()

    def count(self) -> int:
        with self._lock:
            cursor = self._conn.execute(f"SELECT COUNT(*) FROM {self._TABLE_NAME}")
            return cursor.fetchone()[0]

    def get_all(self) -> list[MemoryEntry]:
        return self._get_all()

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
        return MemoryEntry(id=row[0], memory_type=row[1], content=row[2], keywords=keywords, embedding=embedding, importance=float(row[5]), created_at=row[6], last_accessed=row[7], access_count=int(row[8]), decay_factor=float(row[9]), source_turn_id=row[10])

    def close(self) -> None:
        self._conn.close()
