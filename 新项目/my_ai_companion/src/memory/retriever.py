"""混合检索器模块。

组合倒排索引预过滤 + 余弦相似度精排 + 时间衰减加权的检索流程。
依赖 DeepSeek API 获取查询文本的向量表示。

检索流程：
    1. 调用 DeepSeek embed API 获取 query 向量
    2. 提取 query 关键词
    3. 倒排索引预过滤 -> 候选集
    4. 如果候选集为空，回退到全量余弦相似度
    5. 余弦相似度精排
    6. 应用时间衰减和强化记忆加权
    7. 返回 top_k 结果

依赖：
    - src.api_client.deepseek: DeepSeekClient（获取 query embedding）
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.decay: get_weight（时间衰减加权）
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

from datetime import datetime

from src.api_client.deepseek import DeepSeekClient
from src.memory.decay import get_weight
from src.memory.vector_store import MemoryEntry, VectorStore, cosine_similarity
from src.utils.logger import get_logger


class MemoryRetriever:
    """混合检索器。

    封装完整的记忆检索流程，提供 retrieve() 方法。
    支持倒排索引预过滤和全量回退，结合时间衰减加权。

    Attributes:
        vector_store: 向量存储实例。
        client: DeepSeek API 客户端（用于获取 embedding）。
    """

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: DeepSeekClient,
    ) -> None:
        """初始化检索器。

        Args:
            vector_store: VectorStore 实例。
            deepseek_client: DeepSeekClient 实例（用于 embedding 调用）。
        """
        self.vector_store = vector_store
        self.client = deepseek_client
        self._log = get_logger()

        self._log.info("MemoryRetriever 初始化完成")

    # -------------------------------------------------------------------------
    # 检索主流程
    # -------------------------------------------------------------------------

    def retrieve(
        self,
        query_text: str,
        top_k: int = 5,
        apply_decay: bool = True,
        min_similarity: float = 0.0,
    ) -> list[MemoryEntry]:
        """执行混合检索，返回最相关的记忆。

        完整流程：
        1. 调用 DeepSeek embed API 获取 query 向量。
        2. 通过 VectorStore.search() 执行混合检索（倒排索引 + 余弦相似度）。
        3. 如果 apply_decay=True，应用时间衰减加权重排。
        4. 过滤低于 min_similarity 的结果。
        5. 返回 top_k 结果。

        Args:
            query_text: 查询文本。
            top_k: 最大返回结果数。
            apply_decay: 是否应用时间衰减加权。默认 True。
            min_similarity: 最小相似度阈值，低于此值的结果被过滤。默认 0.0（不过滤）。

        Returns:
            按相关性降序排列的记忆条目列表。

        Raises:
            ValueError: query_text 为空时。
            APIException: Embedding API 调用失败时（由 DeepSeekClient 抛出）。
        """
        if not query_text or not query_text.strip():
            raise ValueError("query_text 不能为空")

        self._log.debug(f"[检索] 开始: query='{query_text[:50]}...', top_k={top_k}")

        # 步骤1：获取 query 向量
        try:
            embed_resp = self.client.embed(query_text)
            query_embedding = embed_resp.embeddings[0]
        except Exception:
            self._log.error("[检索] Embedding API 调用失败")
            raise

        # 步骤2：混合检索（倒排索引 + 余弦相似度）
        candidates = self.vector_store.search(
            query_embedding=query_embedding,
            query_text=query_text,
            top_k=top_k * 2,  # 多取一些候选，后续用衰减加权重排
        )

        if not candidates:
            self._log.info("[检索] 无匹配结果")
            return []

        # 步骤3：时间衰减加权重排
        if apply_decay:
            now = datetime.now()
            scored: list[tuple[MemoryEntry, float]] = []

            for entry in candidates:
                # 计算余弦相似度
                if entry.embedding and query_embedding:
                    sim = cosine_similarity(query_embedding, entry.embedding)
                else:
                    sim = 0.0

                # 计算时间衰减权重
                weight = get_weight(entry, now)

                # 综合评分：余弦相似度 * 0.6 + 衰减权重 * 0.4
                combined_score = sim * 0.6 + weight * 0.4

                scored.append((entry, combined_score))

            # 按综合评分降序排序
            scored.sort(key=lambda x: x[1], reverse=True)

            # 过滤低于最小相似度的结果
            filtered = [
                (entry, score)
                for entry, score in scored
                if score >= min_similarity
            ]

            result = [entry for entry, _ in filtered[:top_k]]
        else:
            # 不应用衰减加权，直接返回前 top_k
            result = candidates[:top_k]

        self._log.info(
            f"[检索] 完成: 候选={len(candidates)}, 返回={len(result)}"
        )
        return result

    # -------------------------------------------------------------------------
    # 便捷方法
    # -------------------------------------------------------------------------

    def retrieve_by_type(
        self,
        query_text: str,
        memory_type: str,
        top_k: int = 5,
    ) -> list[MemoryEntry]:
        """按类型检索记忆。

        先按类型过滤，再执行混合检索。

        Args:
            query_text: 查询文本。
            memory_type: 记忆类型（"episodic" | "semantic" | "user_fact"）。
            top_k: 最大返回结果数。

        Returns:
            符合条件的记忆条目列表。
        """
        # 获取指定类型的所有记忆
        typed_entries = self.vector_store.get_by_type(memory_type)

        if not typed_entries:
            self._log.info(f"[检索] 类型 '{memory_type}' 无记忆")
            return []

        # 获取 query 向量
        try:
            embed_resp = self.client.embed(query_text)
            query_embedding = embed_resp.embeddings[0]
        except Exception:
            self._log.error("[检索] Embedding API 调用失败")
            raise

        # 计算余弦相似度
        scored: list[tuple[MemoryEntry, float]] = []
        for entry in typed_entries:
            if entry.embedding:
                sim = cosine_similarity(query_embedding, entry.embedding)
            else:
                sim = 0.0
            scored.append((entry, sim))

        scored.sort(key=lambda x: x[1], reverse=True)
        return [entry for entry, _ in scored[:top_k]]

    def get_recent(self, top_k: int = 10) -> list[MemoryEntry]:
        """获取最近添加的记忆。

        Args:
            top_k: 最大返回数量。

        Returns:
            按创建时间降序排列的记忆条目。
        """
        all_entries = self.vector_store.get_all()
        # 按创建时间降序排序
        all_entries.sort(key=lambda e: e.created_at, reverse=True)
        return all_entries[:top_k]

    def get_important(self, min_importance: float = 0.7, top_k: int = 10) -> list[MemoryEntry]:
        """获取重要性较高的记忆。

        Args:
            min_importance: 最低重要性阈值。
            top_k: 最大返回数量。

        Returns:
            按重要性降序排列的记忆条目。
        """
        all_entries = self.vector_store.get_all()
        filtered = [e for e in all_entries if e.importance >= min_importance]
        filtered.sort(key=lambda e: e.importance, reverse=True)
        return filtered[:top_k]