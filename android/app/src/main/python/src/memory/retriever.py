"""混合检索器模块。

组合倒排索引预过滤 + 余弦相似度精排 + 时间衰减加权的检索流程。
依赖 DeepSeek API 获取查询文本的向量表示。

检索流程：
    1. 调用 DeepSeek embed API 获取 query 向量
    2. 提取 query 关键词
    3. 倒排索引预过滤 -> 候选集
    4. 如果候选集为空，回退到分页全量余弦相似度（T4.6 OOM 防护）
    5. 余弦相似度精排
    6. 应用时间衰减和强化记忆加权
    7. 返回 top_k 结果
"""

from __future__ import annotations

from datetime import datetime

from src.api_client.deepseek import DeepSeekClient
from src.memory.decay import get_weight
from src.memory.vector_store import (
    MemoryEntry,
    VectorStore,
    cosine_similarity,
    extract_keywords,
)
from src.utils.logger import get_logger

# T4.6: 全量回退分页加载的每页大小
_PAGE_SIZE = 500


class MemoryRetriever:
    """混合检索器。

    封装完整的记忆检索流程，提供 retrieve() 方法。
    支持倒排索引预过滤和分页全量回退（OOM 防护），结合时间衰减加权。
    """

    def __init__(self, vector_store: VectorStore, deepseek_client: DeepSeekClient) -> None:
        self.vector_store = vector_store
        self.client = deepseek_client
        self._log = get_logger()
        self._log.info("MemoryRetriever 初始化完成")

    def retrieve(
        self,
        query_text: str,
        top_k: int = 5,
        apply_decay: bool = True,
        min_similarity: float = 0.0,
    ) -> list[MemoryEntry]:
        """检索与查询最相关的记忆。

        检索策略：
        - 优先使用倒排索引预过滤 + 余弦相似度精排（高效路径）。
        - 倒排索引无候选时，回退到分页全量余弦相似度（OOM 防护路径）。
        - 最后应用时间衰减和强化记忆加权。

        Args:
            query_text: 查询文本。
            top_k: 返回的最大记忆数。
            apply_decay: 是否应用时间衰减加权。
            min_similarity: 最低相似度阈值，低于此值的记忆会被过滤。

        Returns:
            按相关性排序的 MemoryEntry 列表。
        """
        if not query_text or not query_text.strip():
            raise ValueError("query_text 不能为空")
        self._log.debug(f"[检索] 开始: query='{query_text[:50]}...', top_k={top_k}")

        # 1. 获取 query 向量
        try:
            embed_resp = self.client.embed_cached(query_text)
            if embed_resp.embeddings:
                query_embedding = embed_resp.embeddings[0]
            else:
                self._log.warning("[检索] Embedding API 返回空数据，降级为关键词检索")
                query_embedding = []
        except Exception:
            self._log.warning("[检索] Embedding API 不可用，降级为关键词检索")
            query_embedding = []

        # 2. 提取关键词，检查倒排索引
        query_keywords = extract_keywords(query_text)
        candidate_ids = self.vector_store.inverted_index.search(query_keywords)

        # 3. 根据倒排索引结果选择检索路径
        if candidate_ids:
            # 倒排索引命中：使用高效的精排路径
            self._log.debug(f"[检索] 倒排索引命中 {len(candidate_ids)} 条候选")
            scored_candidates = self.vector_store.search(
                query_embedding=query_embedding,
                query_text=query_text,
                top_k=top_k * 2,
            )
        else:
            # 倒排索引无命中：回退到分页全量检索（T4.6 OOM 防护）
            self._log.info(
                f"[检索] 倒排索引无命中，回退到分页全量检索 "
                f"(记忆总数={self.vector_store.count()})"
            )
            scored_candidates = self._fallback_full_search(
                query_embedding=query_embedding,
                query_keywords=query_keywords,
                top_k=top_k * 2,
            )

        if not scored_candidates:
            self._log.info("[检索] 无匹配结果")
            return []

        # 4. 时间衰减加权 + 精排
        if apply_decay:
            now = datetime.now()
            combined: list[tuple[MemoryEntry, float]] = []
            for entry, sim in scored_candidates:
                weight = get_weight(entry, now)
                combined_score = sim * 0.6 + weight * 0.4
                combined.append((entry, combined_score))
            combined.sort(key=lambda x: x[1], reverse=True)
            filtered = [
                (entry, score) for entry, score in combined if score >= min_similarity
            ]
            result = [entry for entry, _ in filtered[:top_k]]
        else:
            result = [entry for entry, _ in scored_candidates[:top_k]]

        self._log.info(
            f"[检索] 完成: 候选={len(scored_candidates)}, 返回={len(result)}"
        )
        return result

    # =========================================================================
    # T4.6: 全量回退 OOM 防护 —— 分页加载 + 余弦相似度计算
    # =========================================================================

    def _fallback_full_search(
        self,
        query_embedding: list[float],
        query_keywords: list[str],
        top_k: int,
    ) -> list[tuple[MemoryEntry, float]]:
        """全量回退的分页检索（OOM 防护）。

        当倒排索引无候选时，用分页方式加载所有记忆，逐页计算余弦相似度，
        每页保留 top_k * 3 候选，最后合并排序取 top_k。
        避免一次性加载大量记忆到内存导致 OOM。

        Args:
            query_embedding: 查询文本的向量表示，可能为空列表。
            query_keywords: 查询文本提取的关键词（用于 embedding 不可用时的降级）。
            top_k: 最终需要返回的候选数量。

        Returns:
            list[tuple[MemoryEntry, float]]: (记忆条目, 相似度分数) 列表，
                                             按相似度降序排列，最多 top_k 条。
        """
        total = self.vector_store.count()
        if total == 0:
            return []

        candidates_per_page = top_k * 3  # 每页保留的候选数，放大 3 倍确保不遗漏
        all_candidates: list[tuple[MemoryEntry, float]] = []
        has_embedding = bool(query_embedding)

        offset = 0
        while offset < total:
            # 分页加载
            page = self.vector_store.get_page(offset, _PAGE_SIZE)
            offset += len(page)

            # 逐条计算相似度
            page_scored: list[tuple[MemoryEntry, float]] = []
            for entry in page:
                if has_embedding and entry.embedding:
                    try:
                        sim = cosine_similarity(query_embedding, entry.embedding)
                    except ValueError:
                        sim = 0.0
                elif not has_embedding and query_keywords:
                    # embedding 不可用时的关键词降级匹配
                    entry_kw = set(entry.keywords) if entry.keywords else set()
                    query_kw = set(query_keywords) if query_keywords else set()
                    match_count = len(query_kw & entry_kw)
                    sim = min(
                        match_count / max(len(query_keywords), 1), 1.0
                    )
                else:
                    sim = 0.0
                page_scored.append((entry, sim))

            # 当前页排序，保留 top candidates_per_page
            page_scored.sort(key=lambda x: x[1], reverse=True)
            page_top = page_scored[:candidates_per_page]

            self._log.debug(
                f"[分页回退] offset={offset - len(page)}, "
                f"页大小={len(page)}, 页候选={len(page_top)}"
            )

            all_candidates.extend(page_top)

        # 合并所有页候选，最终排序取 top_k
        all_candidates.sort(key=lambda x: x[1], reverse=True)
        return all_candidates[:top_k]

    def retrieve_by_type(self, query_text: str, memory_type: str, top_k: int = 5) -> list[MemoryEntry]:
        typed_entries = self.vector_store.get_by_type(memory_type)
        if not typed_entries:
            self._log.info(f"[检索] 类型 '{memory_type}' 无记忆")
            return []
        try:
            embed_resp = self.client.embed_cached(query_text)
            if embed_resp.embeddings:
                query_embedding = embed_resp.embeddings[0]
            else:
                self._log.error("[检索] Embedding API 返回空数据")
                raise ValueError("Embedding API 返回空数据")
        except Exception:
            self._log.error("[检索] Embedding API 调用失败")
            raise
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
        all_entries = self.vector_store.get_all()
        all_entries.sort(key=lambda e: e.created_at, reverse=True)
        return all_entries[:top_k]

    def get_important(self, min_importance: float = 0.7, top_k: int = 10) -> list[MemoryEntry]:
        all_entries = self.vector_store.get_all()
        filtered = [e for e in all_entries if e.importance >= min_importance]
        filtered.sort(key=lambda e: e.importance, reverse=True)
        return filtered[:top_k]
