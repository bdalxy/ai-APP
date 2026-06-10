"""LRU 缓存模块。

基于 collections.OrderedDict 实现的 LRU（最近最少使用）缓存，
用于缓存 Embedding 调用结果，减少重复 API 请求。

使用示例:
    cache = LRUCache(capacity=500)
    vec = cache.get("你好")          # 命中返回向量，未命中返回 None
    cache.put("你好", [0.1, 0.2])   # 存入缓存
    stats = cache.stats()            # 查看命中率统计
"""

from collections import OrderedDict


class LRUCache:
    """LRU 缓存，用于缓存 Embedding 向量结果。

    基于 OrderedDict 实现：
        - get() 命中时将 key 移到末尾（标记为最近使用）
        - put() 超过容量时淘汰最旧的条目（OrderedDict 头部）

    Attributes:
        _cache: 内部 OrderedDict，key 为文本 hash，value 为向量列表。
        _capacity: 最大缓存条目数（默认 500）。
        _hits: 命中次数计数器。
        _misses: 未命中次数计数器。
    """

    def __init__(self, capacity: int = 500) -> None:
        """初始化 LRU 缓存。

        Args:
            capacity: 最大缓存条目数，默认 500。
        """
        if capacity <= 0:
            raise ValueError(f"缓存容量必须大于 0，当前值: {capacity}")
        self._cache: OrderedDict[int, list[float]] = OrderedDict()
        self._capacity: int = capacity
        self._hits: int = 0
        self._misses: int = 0

    # ------------------------------------------------------------------
    # 核心操作
    # ------------------------------------------------------------------

    def get(self, text: str) -> list[float] | None:
        """查找缓存中的向量。

        命中时将 key 移到 OrderedDict 末尾（标记为最近使用），
        并更新命中计数器。

        Args:
            text: 需要查询向量的文本。

        Returns:
            命中时返回向量列表，未命中时返回 None。
        """
        key = hash(text)
        if key in self._cache:
            self._cache.move_to_end(key)
            self._hits += 1
            return self._cache[key]
        self._misses += 1
        return None

    def put(self, text: str, embedding: list[float]) -> None:
        """将文本及其向量存入缓存。

        如果文本已存在，将其移到末尾（更新 LRU 顺序）；
        如果缓存已满，淘汰最久未使用的条目。

        Args:
            text: 原始文本（用于生成缓存 key）。
            embedding: 文本对应的向量。
        """
        key = hash(text)
        if key in self._cache:
            self._cache.move_to_end(key)
        else:
            if len(self._cache) >= self._capacity:
                self._cache.popitem(last=False)  # 淘汰最旧条目（头部）
        self._cache[key] = embedding

    # ------------------------------------------------------------------
    # 统计
    # ------------------------------------------------------------------

    def hit_rate(self) -> float:
        """计算缓存命中率。

        Returns:
            命中率浮点数，范围 [0.0, 1.0]。如果没有任何查询则返回 0.0。
        """
        total = self._hits + self._misses
        if total == 0:
            return 0.0
        return self._hits / total

    def stats(self) -> dict:
        """获取缓存统计信息。

        Returns:
            包含 hits、misses、total、hit_rate、size、capacity 的字典。
        """
        total = self._hits + self._misses
        return {
            "hits": self._hits,
            "misses": self._misses,
            "total_queries": total,
            "hit_rate": round(self.hit_rate(), 4),
            "cache_size": len(self._cache),
            "capacity": self._capacity,
        }

    def reset(self) -> None:
        """清空缓存并重置所有计数器。"""
        self._cache.clear()
        self._hits = 0
        self._misses = 0

    # ------------------------------------------------------------------
    # 属性
    # ------------------------------------------------------------------

    def __len__(self) -> int:
        """返回当前缓存条目数。"""
        return len(self._cache)

    def __contains__(self, text: str) -> bool:
        """检查文本是否在缓存中（不改变 LRU 顺序、不更新计数器）。"""
        return hash(text) in self._cache