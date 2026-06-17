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
from src.memory.bm25 import BM25Scorer
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

    权重配置：
        similarity_weight: 余弦相似度在综合评分中的权重（默认 0.6）。
        decay_weight: 时间衰减/记忆强化在综合评分中的权重（默认 0.4）。
        两权重之和应接近 1.0，但系统不做强制校验。
    """

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: DeepSeekClient,
        similarity_weight: float = 0.6,
        decay_weight: float = 0.4,
    ) -> None:
        self.vector_store = vector_store
        self.client = deepseek_client
        self._log = get_logger()
        self.similarity_weight = similarity_weight
        self.decay_weight = decay_weight
        self._log.info(
            f"MemoryRetriever 初始化完成 "
            f"(sim_weight={similarity_weight}, decay_weight={decay_weight})"
        )

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
                combined_score = sim * self.similarity_weight + weight * self.decay_weight
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
        max_candidates = top_k * 10  # 全局候选上限，防止缓冲区无限增长

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
            # 全局候选上限裁剪
            if len(all_candidates) > max_candidates:
                all_candidates.sort(key=lambda x: x[1], reverse=True)
                all_candidates = all_candidates[:max_candidates]

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
        """获取最近 N 条记忆（SQL 层 ORDER BY created_at DESC，避免全量加载）。"""
        return self.vector_store.get_recent_entries(top_k)

    def get_important(self, min_importance: float = 0.7, top_k: int = 10) -> list[MemoryEntry]:
        """获取重要记忆（SQL 层 WHERE importance >= ? + ORDER BY，避免全量加载）。"""
        return self.vector_store.get_important_entries(min_importance, top_k)

    # =========================================================================
    # BM25 关键词检索
    # =========================================================================

    def bm25_retrieve(
        self,
        query_text: str,
        top_k: int = 10,
        min_score: float = 0.0,
    ) -> list[tuple[MemoryEntry, float]]:
        """使用 BM25 算法进行关键词检索。

        当 embedding API 不可用时的降级检索方案。
        首次调用时构建 BM25 索引，后续调用复用。

        Args:
            query_text: 查询文本。
            top_k: 返回的最大记忆数。
            min_score: 最低 BM25 分数阈值。

        Returns:
            list[tuple[MemoryEntry, float]]: (记忆条目, BM25分数) 列表。
        """
        if not query_text or not query_text.strip():
            return []

        # 构建 BM25 索引（延迟构建，首次调用时）
        self._log.info(f"[BM25] 开始构建索引")
        bm25 = self.vector_store.build_bm25_index()
        doc_count = bm25.doc_count
        self._log.info(
            f"[BM25] 索引构建完成: {doc_count} 文档, "
            f"{bm25.term_count} 唯一词"
        )

        if doc_count == 0:
            return []

        # 检索
        scored_ids = bm25.score(query_text, top_k=top_k)

        # 加载实际条目
        results: list[tuple[MemoryEntry, float]] = []
        for doc_id, score in scored_ids:
            if score < min_score:
                continue
            try:
                entry = self.vector_store.get(doc_id)
                results.append((entry, score))
            except Exception:
                continue

        self._log.info(
            f"[BM25] 检索完成: query='{query_text[:30]}...', "
            f"返回={len(results)} 条"
        )
        return results

    def bm25_retrieve_with_expansion(
        self,
        query_text: str,
        top_k: int = 10,
        expand_terms: int = 3,
    ) -> list[tuple[MemoryEntry, float]]:
        """BM25 + 查询扩展检索。

        先用查询扩展找到相关词，再用扩展后的查询进行 BM25 检索。

        Args:
            query_text: 查询文本。
            top_k: 返回的最大记忆数。
            expand_terms: 扩展词数量。

        Returns:
            list[tuple[MemoryEntry, float]]: (记忆条目, BM25分数) 列表。
        """
        self._log.info(f"[BM25扩展] 开始检索: query='{query_text[:30]}...'")

        bm25 = self.vector_store.build_bm25_index()
        if bm25.doc_count == 0:
            return []

        # 查询扩展
        expanded_terms = bm25.expand_query(query_text, num_expansions=expand_terms)
        if expanded_terms:
            expanded_query = query_text + " " + " ".join(expanded_terms)
            self._log.info(
                f"[BM25扩展] 扩展词: {expanded_terms}, "
                f"扩展查询: '{expanded_query[:50]}...'"
            )
        else:
            expanded_query = query_text

        # 检索
        scored_ids = bm25.score(expanded_query, top_k=top_k * 2)

        # 加载实际条目
        results: list[tuple[MemoryEntry, float]] = []
        for doc_id, score in scored_ids:
            try:
                entry = self.vector_store.get(doc_id)
                results.append((entry, score))
            except Exception:
                continue

        results.sort(key=lambda x: x[1], reverse=True)
        results = results[:top_k]

        self._log.info(
            f"[BM25扩展] 检索完成: 返回={len(results)} 条"
        )
        return results

    def multi_strategy_retrieve(
        self,
        query_text: str,
        top_k: int = 5,
    ) -> list[MemoryEntry]:
        """多策略检索：优先 embedding，回退 BM25，最后回退规则。

        检索策略优先级:
            1. 向量检索（embedding + 余弦相似度 + 时间衰减）
            2. BM25 关键词检索（embedding API 不可用时）
            3. 规则检索（倒排索引 + 关键词匹配）

        Args:
            query_text: 查询文本。
            top_k: 返回的最大记忆数。

        Returns:
            MemoryEntry 列表，按相关性排序。
        """
        # 策略 1: 尝试向量检索
        try:
            return self.retrieve(query_text, top_k=top_k)
        except Exception as e:
            self._log.info(f"[多策略] 向量检索失败，回退 BM25: {e}")

        # 策略 2: BM25 检索
        try:
            bm25_results = self.bm25_retrieve_with_expansion(query_text, top_k=top_k)
            if bm25_results:
                return [entry for entry, _ in bm25_results]
        except Exception as e:
            self._log.info(f"[多策略] BM25 检索失败，回退规则: {e}")

        # 策略 3: 规则检索（倒排索引 + 关键词匹配）
        return self.get_recent(top_k)
