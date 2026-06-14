"""测试 LRU 缓存核心功能。

覆盖：基本操作、淘汰策略、更新已存在key、命中率统计、重置。
"""

import pytest

from src.utils.lru_cache import LRUCache


class TestLRUCacheBasic:
    """LRU 缓存基本操作测试。"""

    def test_put_and_get(self):
        """测试基本存取。"""
        cache = LRUCache(capacity=3)
        cache.put("apple", [0.1, 0.2])
        cache.put("banana", [0.3, 0.4])
        cache.put("cherry", [0.5, 0.6])

        assert cache.get("apple") == [0.1, 0.2]
        assert cache.get("banana") == [0.3, 0.4]
        assert cache.get("cherry") == [0.5, 0.6]
        assert cache.get("unknown") is None

    def test_get_updates_lru_order(self):
        """测试 get 命中后将 key 移到最近使用位置。"""
        cache = LRUCache(capacity=2)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.get("a")  # 访问 a，使 b 成为最旧
        cache.put("c", [3.0])  # 淘汰最旧的 b

        assert cache.get("a") == [1.0]
        assert cache.get("b") is None
        assert cache.get("c") == [3.0]

    def test_put_update_existing(self):
        """测试更新已存在的 key。"""
        cache = LRUCache(capacity=2)
        cache.put("a", [1.0])
        cache.put("a", [100.0])

        assert cache.get("a") == [100.0]
        assert len(cache) == 1

    def test_len(self):
        """测试 __len__ 返回条目数。"""
        cache = LRUCache(capacity=10)
        assert len(cache) == 0
        cache.put("a", [1.0])
        assert len(cache) == 1
        cache.put("b", [2.0])
        assert len(cache) == 2

    def test_contains(self):
        """测试 __contains__（不改变LRU顺序）。"""
        cache = LRUCache(capacity=3)
        cache.put("hello", [1.0])
        assert "hello" in cache
        assert "world" not in cache
        # __contains__ 不应更新 LRU 顺序
        assert len(cache) == 1

    def test_capacity_zero_raises(self):
        """测试容量为 0 抛出异常。"""
        with pytest.raises(ValueError, match="容量必须大于 0"):
            LRUCache(capacity=0)

    def test_negative_capacity_raises(self):
        """测试负容量抛出异常。"""
        with pytest.raises(ValueError, match="容量必须大于 0"):
            LRUCache(capacity=-5)


class TestLRUCacheEviction:
    """LRU 缓存淘汰策略测试。"""

    def test_eviction_when_full(self):
        """测试缓存满时淘汰最久未使用的条目。"""
        cache = LRUCache(capacity=3)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.put("c", [3.0])
        cache.put("d", [4.0])  # 淘汰 a

        assert cache.get("a") is None
        assert cache.get("b") == [2.0]
        assert cache.get("c") == [3.0]
        assert cache.get("d") == [4.0]

    def test_lru_order_after_get(self):
        """测试多次 get 后的淘汰顺序。"""
        cache = LRUCache(capacity=3)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.put("c", [3.0])

        # 访问顺序: a -> b
        cache.get("a")
        cache.get("b")
        # 此时 LRU 顺序: c(最旧) -> a -> b(最新)

        cache.put("d", [4.0])  # 淘汰 c
        assert cache.get("c") is None
        assert cache.get("a") == [1.0]
        assert cache.get("b") == [2.0]
        assert cache.get("d") == [4.0]

    def test_lru_order_after_update(self):
        """测试更新已存在 key 后的淘汰顺序。"""
        cache = LRUCache(capacity=3)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.put("c", [3.0])
        cache.put("a", [10.0])  # 更新 a，a 移到最近使用

        cache.put("d", [4.0])  # 淘汰最旧的 b
        assert cache.get("b") is None
        assert cache.get("a") == [10.0]
        assert cache.get("c") == [3.0]
        assert cache.get("d") == [4.0]


class TestLRUCacheStats:
    """LRU 缓存统计测试。"""

    def test_hit_rate_initial(self):
        """测试初始命中率。"""
        cache = LRUCache(capacity=5)
        assert cache.hit_rate() == 0.0

    def test_hit_rate_after_hits(self):
        """测试有命中后的命中率。"""
        cache = LRUCache(capacity=5)
        cache.put("a", [1.0])
        cache.get("a")  # hit
        cache.get("b")  # miss

        assert cache.hit_rate() == 0.5

    def test_stats_dict(self):
        """测试 stats() 返回完整统计信息。"""
        cache = LRUCache(capacity=5)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.get("a")  # hit
        cache.get("c")  # miss

        stats = cache.stats()
        assert stats["hits"] == 1
        assert stats["misses"] == 1
        assert stats["total_queries"] == 2
        assert stats["hit_rate"] == 0.5
        assert stats["cache_size"] == 2
        assert stats["capacity"] == 5

    def test_reset(self):
        """测试重置缓存和统计。"""
        cache = LRUCache(capacity=5)
        cache.put("a", [1.0])
        cache.put("b", [2.0])
        cache.get("a")
        cache.get("c")

        cache.reset()
        assert len(cache) == 0
        assert cache.hit_rate() == 0.0

        # 检查 stats() 在 reset 后且无新查询时的状态
        stats = cache.stats()
        assert stats["hits"] == 0
        assert stats["misses"] == 0
        assert stats["cache_size"] == 0

        # 确认缓存已清空
        assert cache.get("a") is None