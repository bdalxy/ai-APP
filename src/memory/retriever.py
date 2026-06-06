"""混合检索器 - 倒排索引预过滤 + 余弦相似度精排 + 时间衰减加权。"""
from __future__ import annotations
from datetime import datetime
from src.api_client.deepseek import DeepSeekClient
from src.memory.decay import get_weight
from src.memory.vector_store import MemoryEntry, VectorStore, cosine_similarity
from src.utils.logger import get_logger

class MemoryRetriever:
    def __init__(self, vector_store: VectorStore, deepseek_client: DeepSeekClient) -> None:
        self.vector_store = vector_store
        self.client = deepseek_client
        self._log = get_logger()

    def retrieve(self, query_text: str, top_k: int = 5, apply_decay: bool = True, min_similarity: float = 0.0) -> list[MemoryEntry]:
        if not query_text or not query_text.strip():
            raise ValueError("query_text 不能为空")
        embed_resp = self.client.embed(query_text)
        query_embedding = embed_resp.embeddings[0]
        candidates = self.vector_store.search(query_embedding=query_embedding, query_text=query_text, top_k=top_k * 2)
        if not candidates:
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
            return [entry for entry, _ in filtered[:top_k]]
        else:
            return candidates[:top_k]

    def get_recent(self, top_k: int = 10) -> list[MemoryEntry]:
        all_entries = self.vector_store.get_all()
        all_entries.sort(key=lambda e: e.created_at, reverse=True)
        return all_entries[:top_k]

    def get_important(self, min_importance: float = 0.7, top_k: int = 10) -> list[MemoryEntry]:
        all_entries = self.vector_store.get_all()
        filtered = [e for e in all_entries if e.importance >= min_importance]
        filtered.sort(key=lambda e: e.importance, reverse=True)
        return filtered[:top_k]
