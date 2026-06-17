"""记忆缓存层。

提供内存级缓存以减少数据库查询频率，加速记忆检索和访问。
支持 LRU 淘汰策略和 TTL 过期机制。

缓存层级:
    1. 热点记忆缓存（Hot Cache）：最近访问的 N 条记忆
    2. 检索结果缓存（Query Cache）：最近 M 次检索结果
    3. 统计缓存（Stats Cache）：记忆统计信息（TTL 30秒）
    4. 用户画像缓存（Profile Cache）：用户画像（TTL 5分钟）

核心类:
    - MemoryCache: 记忆缓存管理器
    - LRUCache: 通用 LRU 缓存实现
    - TTLCache: 带 TTL 的缓存实现

依赖:
    - threading: 线程安全
    - time: TTL 过期检查
    - src.memory.vector_store: MemoryEntry
    - src.utils.logger: get_logger
"""

from __future__ import annotations

import hashlib
import threading
import time
from collections import OrderedDict
from typing import Any

from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger


# =============================================================================
# LRU 缓存
# =============================================================================

class LRUCache:
    """线程安全的 LRU（最近最少使用）缓存。

    基于 OrderedDict 实现，支持泛型键值存储。
    当缓存满时，自动淘汰最久未使用的条目。

    使用示例:
        >>> cache = LRUCache(max_size=100)
        >>> cache.put("key1", "value1")
        >>> value = cache.get("key1")  # "value1"
    """

    def __init__(self, max_size: int = 100) -> None:
        """初始化 LRU 缓存。

        Args:
            max_size: 最大缓存条目数。
        """
        self._max_size = max(max_size, 1)
        self._cache: OrderedDict = OrderedDict()
        self._lock = threading.Lock()
        self._hits: int = 0
        self._misses: int = 0

    def get(self, key: str) -> Any | None:
        """获取缓存值。

        访问会更新条目的 LRU 位置（移到最前面）。

        Args:
            key: 缓存键。

        Returns:
            缓存值，如果不存在则返回 None。
        """
        with self._lock:
            if key not in self._cache:
                self._misses += 1
                return None
            self._hits += 1
            # 移到末尾（最近使用）
            self._cache.move_to_end(key)
            return self._cache[key]

    def put(self, key: str, value: Any) -> None:
        """存入缓存。

        如果键已存在，更新值并移到最前面。
        如果缓存已满，淘汰最久未使用的条目。

        Args:
            key: 缓存键。
            value: 缓存值。
        """
        with self._lock:
            if key in self._cache:
                self._cache.move_to_end(key)
            self._cache[key] = value
            if len(self._cache) > self._max_size:
                # 淘汰最久未使用的（第一个）
                self._cache.popitem(last=False)

    def remove(self, key: str) -> None:
        """移除缓存条目。

        Args:
            key: 缓存键。
        """
        with self._lock:
            self._cache.pop(key, None)

    def clear(self) -> None:
        """清空缓存。"""
        with self._lock:
            self._cache.clear()
            self._hits = 0
            self._misses = 0

    def contains(self, key: str) -> bool:
        """检查键是否在缓存中。

        Args:
            key: 缓存键。

        Returns:
            True 如果键存在。
        """
        with self._lock:
            return key in self._cache

    @property
    def size(self) -> int:
        """获取当前缓存条目数。"""
        with self._lock:
            return len(self._cache)

    @property
    def hit_rate(self) -> float:
        """获取缓存命中率（0.0 ~ 1.0）。"""
        total = self._hits + self._misses
        if total == 0:
            return 0.0
        return self._hits / total

    def get_stats(self) -> dict:
        """获取缓存统计信息。"""
        with self._lock:
            return {
                "size": len(self._cache),
                "max_size": self._max_size,
                "hits": self._hits,
                "misses": self._misses,
                "hit_rate": round(self.hit_rate, 3),
            }


# =============================================================================
# TTL 缓存
# =============================================================================

class TTLCache:
    """带 TTL（生存时间）的缓存。

    每个缓存条目有独立的过期时间，过期后自动失效。
    支持惰性过期（访问时检查）和主动过期（定期清理）。

    使用示例:
        >>> cache = TTLCache(default_ttl=30.0)  # 30 秒 TTL
        >>> cache.put("key1", "value1")
        >>> value = cache.get("key1")  # 未过期，返回 "value1"
        >>> time.sleep(31)
        >>> value = cache.get("key1")  # 已过期，返回 None
    """

    def __init__(self, default_ttl: float = 30.0) -> None:
        """初始化 TTL 缓存。

        Args:
            default_ttl: 默认 TTL（秒）。
        """
        self._default_ttl = default_ttl
        self._cache: dict[str, tuple[Any, float]] = {}
        self._lock = threading.Lock()
        self._hits: int = 0
        self._misses: int = 0
        self._expired: int = 0

    def get(self, key: str) -> Any | None:
        """获取缓存值（惰性过期检查）。

        Args:
            key: 缓存键。

        Returns:
            缓存值，如果不存在或已过期则返回 None。
        """
        with self._lock:
            if key not in self._cache:
                self._misses += 1
                return None

            value, expiry = self._cache[key]
            if time.time() > expiry:
                # 已过期
                del self._cache[key]
                self._misses += 1
                self._expired += 1
                return None

            self._hits += 1
            return value

    def put(self, key: str, value: Any, ttl: float | None = None) -> None:
        """存入缓存。

        Args:
            key: 缓存键。
            value: 缓存值。
            ttl: TTL（秒），为 None 时使用默认 TTL。
        """
        expiry = time.time() + (ttl if ttl is not None else self._default_ttl)
        with self._lock:
            self._cache[key] = (value, expiry)

    def remove(self, key: str) -> None:
        """移除缓存条目。"""
        with self._lock:
            self._cache.pop(key, None)

    def clear(self) -> None:
        """清空缓存。"""
        with self._lock:
            self._cache.clear()
            self._hits = 0
            self._misses = 0
            self._expired = 0

    def cleanup(self) -> int:
        """主动清理过期条目。

        Returns:
            清理的条目数。
        """
        now = time.time()
        cleaned = 0
        with self._lock:
            expired_keys = [
                key for key, (_, expiry) in self._cache.items()
                if now > expiry
            ]
            for key in expired_keys:
                del self._cache[key]
                cleaned += 1
            self._expired += cleaned
        return cleaned

    @property
    def size(self) -> int:
        """获取当前缓存条目数（包括已过期但未清理的）。"""
        with self._lock:
            return len(self._cache)

    @property
    def hit_rate(self) -> float:
        """获取缓存命中率。"""
        total = self._hits + self._misses
        if total == 0:
            return 0.0
        return self._hits / total

    def get_stats(self) -> dict:
        """获取缓存统计信息。"""
        with self._lock:
            now = time.time()
            expired_count = sum(
                1 for _, expiry in self._cache.values()
                if now > expiry
            )
            return {
                "size": len(self._cache),
                "expired": expired_count,
                "hits": self._hits,
                "misses": self._misses,
                "total_expired": self._expired,
                "hit_rate": round(self.hit_rate, 3),
            }


# =============================================================================
# 记忆缓存管理器
# =============================================================================

class MemoryCache:
    """记忆缓存管理器。

    提供分层缓存以加速记忆系统的访问速度:
        1. 热点记忆缓存（LRU）：最近访问的 N 条记忆，避免重复 DB 查询
        2. 检索结果缓存（TTL）：最近 M 次检索结果，避免重复向量检索
        3. 统计缓存（TTL）：记忆统计信息，避免频繁 COUNT 查询
        4. 用户画像缓存（TTL）：用户画像，避免重复分析

    Attributes:
        HOT_CACHE_SIZE: 热点记忆缓存大小（默认 200）。
        QUERY_CACHE_SIZE: 检索结果缓存大小（默认 50）。
        STATS_TTL: 统计缓存 TTL（默认 30 秒）。
        PROFILE_TTL: 用户画像缓存 TTL（默认 300 秒）。
        QUERY_TTL: 检索结果缓存 TTL（默认 60 秒）。
    """

    HOT_CACHE_SIZE: int = 200
    QUERY_CACHE_SIZE: int = 50
    STATS_TTL: float = 30.0
    PROFILE_TTL: float = 300.0
    QUERY_TTL: float = 60.0

    def __init__(self) -> None:
        """初始化记忆缓存管理器。"""
        # 热点记忆缓存（LRU）
        self._hot_cache = LRUCache(max_size=self.HOT_CACHE_SIZE)

        # 检索结果缓存（TTL）
        self._query_cache = TTLCache(default_ttl=self.QUERY_TTL)

        # 统计缓存（TTL）
        self._stats_cache = TTLCache(default_ttl=self.STATS_TTL)

        # 用户画像缓存（TTL）
        self._profile_cache = TTLCache(default_ttl=self.PROFILE_TTL)

        # 标签列表缓存（TTL）
        self._tags_cache = TTLCache(default_ttl=self.STATS_TTL)

        self._log = get_logger()
        self._log.info("MemoryCache 初始化完成")

    # =========================================================================
    # 热点记忆缓存
    # =========================================================================

    def get_memory(self, memory_id: str) -> MemoryEntry | None:
        """从热点缓存获取记忆。

        Args:
            memory_id: 记忆 UUID。

        Returns:
            MemoryEntry，如果缓存未命中则返回 None。
        """
        return self._hot_cache.get(memory_id)

    def put_memory(self, entry: MemoryEntry) -> None:
        """将记忆存入热点缓存。

        Args:
            entry: MemoryEntry 实例。
        """
        if entry and entry.id:
            self._hot_cache.put(entry.id, entry)

    def put_memories(self, entries: list[MemoryEntry]) -> None:
        """批量将记忆存入热点缓存。

        Args:
            entries: MemoryEntry 列表。
        """
        for entry in entries:
            self.put_memory(entry)

    def invalidate_memory(self, memory_id: str) -> None:
        """使指定记忆的缓存失效。

        Args:
            memory_id: 记忆 UUID。
        """
        self._hot_cache.remove(memory_id)

    def invalidate_memories(self, memory_ids: list[str]) -> None:
        """批量使记忆缓存失效。

        Args:
            memory_ids: 记忆 UUID 列表。
        """
        for mid in memory_ids:
            self._hot_cache.remove(mid)

    # =========================================================================
    # 检索结果缓存
    # =========================================================================

    @staticmethod
    def _make_query_key(query_text: str, top_k: int) -> str:
        """生成检索缓存键。

        使用查询文本的 MD5 哈希 + top_k 作为缓存键。

        Args:
            query_text: 查询文本。
            top_k: 返回数量。

        Returns:
            缓存键字符串。
        """
        query_hash = hashlib.md5(query_text.encode("utf-8")).hexdigest()[:12]
        return f"query:{query_hash}:k={top_k}"

    def get_query_result(
        self,
        query_text: str,
        top_k: int,
    ) -> list[MemoryEntry] | None:
        """获取缓存的检索结果。

        Args:
            query_text: 查询文本。
            top_k: 返回数量。

        Returns:
            MemoryEntry 列表，如果缓存未命中则返回 None。
        """
        key = self._make_query_key(query_text, top_k)
        return self._query_cache.get(key)

    def put_query_result(
        self,
        query_text: str,
        top_k: int,
        entries: list[MemoryEntry],
        ttl: float | None = None,
    ) -> None:
        """缓存检索结果。

        Args:
            query_text: 查询文本。
            top_k: 返回数量。
            entries: 检索结果。
            ttl: 自定义 TTL（秒）。
        """
        key = self._make_query_key(query_text, top_k)
        self._query_cache.put(key, entries, ttl=ttl)

        # 同时将结果中的记忆存入热点缓存
        self.put_memories(entries)

    def invalidate_query_cache(self) -> None:
        """清空所有检索结果缓存（记忆库发生变化时调用）。"""
        self._query_cache.clear()

    # =========================================================================
    # 统计缓存
    # =========================================================================

    def get_stats(self) -> dict | None:
        """获取缓存的统计信息。

        Returns:
            统计信息字典，如果缓存未命中则返回 None。
        """
        return self._stats_cache.get("memory_stats")

    def put_stats(self, stats: dict) -> None:
        """缓存统计信息。

        Args:
            stats: 统计信息字典。
        """
        self._stats_cache.put("memory_stats", stats)

    def invalidate_stats(self) -> None:
        """使统计缓存失效。"""
        self._stats_cache.remove("memory_stats")

    # =========================================================================
    # 用户画像缓存
    # =========================================================================

    def get_profile(self) -> dict | None:
        """获取缓存的用户画像。

        Returns:
            用户画像字典，如果缓存未命中则返回 None。
        """
        return self._profile_cache.get("user_profile")

    def put_profile(self, profile: dict) -> None:
        """缓存用户画像。

        Args:
            profile: 用户画像字典。
        """
        self._profile_cache.put("user_profile", profile)

    def invalidate_profile(self) -> None:
        """使用户画像缓存失效。"""
        self._profile_cache.remove("user_profile")

    # =========================================================================
    # 标签缓存
    # =========================================================================

    def get_tags(self) -> list[dict] | None:
        """获取缓存的标签列表。

        Returns:
            标签列表，如果缓存未命中则返回 None。
        """
        return self._tags_cache.get("all_tags")

    def put_tags(self, tags: list[dict]) -> None:
        """缓存标签列表。

        Args:
            tags: 标签字典列表。
        """
        self._tags_cache.put("all_tags", tags)

    def invalidate_tags(self) -> None:
        """使标签缓存失效。"""
        self._tags_cache.remove("all_tags")

    # =========================================================================
    # 全局缓存失效
    # =========================================================================

    def invalidate_all(self) -> None:
        """使所有缓存失效。

        在记忆库发生重大变化时调用（如批量导入、清空、恢复等）。
        """
        self._hot_cache.clear()
        self._query_cache.clear()
        self._stats_cache.clear()
        self._profile_cache.clear()
        self._tags_cache.clear()
        self._log.info("[缓存] 已全部清空")

    def on_memory_change(self) -> None:
        """记忆库发生变化时调用。

        使检索缓存和统计缓存失效（热点缓存和画像缓存保留）。
        """
        self._query_cache.clear()
        self._stats_cache.remove("memory_stats")
        self._tags_cache.remove("all_tags")

    # =========================================================================
    # 定期清理
    # =========================================================================

    def cleanup(self) -> dict:
        """执行定期清理（清理过期 TTL 条目）。

        Returns:
            清理统计字典。
        """
        query_cleaned = self._query_cache.cleanup()
        stats_cleaned = self._stats_cache.cleanup()
        profile_cleaned = self._profile_cache.cleanup()
        tags_cleaned = self._tags_cache.cleanup()

        total = query_cleaned + stats_cleaned + profile_cleaned + tags_cleaned
        if total > 0:
            self._log.debug(f"[缓存] 清理过期条目: {total}")

        return {
            "query_cleaned": query_cleaned,
            "stats_cleaned": stats_cleaned,
            "profile_cleaned": profile_cleaned,
            "tags_cleaned": tags_cleaned,
            "total": total,
        }

    # =========================================================================
    # 统计
    # =========================================================================

    def get_cache_stats(self) -> dict:
        """获取所有缓存的统计信息。

        Returns:
            缓存统计字典。
        """
        return {
            "hot_cache": self._hot_cache.get_stats(),
            "query_cache": self._query_cache.get_stats(),
            "stats_cache": self._stats_cache.get_stats(),
            "profile_cache": self._profile_cache.get_stats(),
            "tags_cache": self._tags_cache.get_stats(),
        }