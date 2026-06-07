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
    """

    def __init__(self, vector_store: VectorStore, deepseek_client: DeepSeekClient) -> None:
        self.vector_store = vector_store
        self.client = deepseek_client
        self._log = get_logger()
        self._log.info("MemoryRetriever 初始化完成")

    def retrieve(self, query_text: str, top_k: int = 5, apply_decay: bool = True, min_similarity: float = 0.0) -> list[MemoryEntry]:
        if not query_text or not query_text.strip():
            raise ValueError("query_text 不能为空")
        self._log.debug(f"[检索] 开始: query='{query_text[:50]}...', top_k={top_k}")
        try:
            embed_resp = self.client.embed(query_text)
            query_embedding = embed_resp.embeddings[0]
        except Exception:
            self._log.error("[检索] Embedding API 调用失败")
            raise
        candidates = self.vector_store.search(query_embedding=query_embedding, query_text=query_text, top_k=top_k * 2)
        if not candidates:
            self._log.info("[检索] 无匹配结果")
            return []
        if apply_decay:
            now = datetime.now()
            scored: list[tuple[MemoryEntry, float]] = []
            for entry in candidates:
                if entry.embedding and query_embedding:
                    sim = cosine_similarity(query_embedding, entry.embedding)
                else:
                    sim = 0.0
                weight = get_weight(entry, now)
                combined_score = sim * 0.6 + weight * 0.4
                scored.append((entry, combined_score))
            scored.sort(key=lambda x: x[1], reverse=True)
            filtered = [(entry, score) for entry, score in scored if score >= min_similarity]
            result = [entry for entry, _ in filtered[:top_k]]
        else:
            result = candidates[:top_k]
        self._log.info(f"[检索] 完成: 候选={len(candidates)}, 返回={len(result)}")
        return result

    def retrieve_by_type(self, query_text: str, memory_type: str, top_k: int = 5) -> list[MemoryEntry]:
        typed_entries = self.vector_store.get_by_type(memory_type)
        if not typed_entries:
            self._log.info(f"[检索] 类型 '{memory_type}' 无记忆")
            return []
        try:
            embed_resp = self.client.embed(query_text)
            query_embedding = embed_resp.embeddings[0]
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
