# -*- coding: utf-8 -*-
"""
记忆检索器
封装向量检索 + 关键词检索的混合检索策略

在 Chaquopy 环境下，DeepSeek Embedding API 调用由 Kotlin 侧
通过 HTTP 直接调用，Python 侧只负责向量存储和检索。
"""

import logging
from typing import Dict, Any, Optional, List
from datetime import datetime

from .vector_store import VectorStore, CosineSimilarity

logger = logging.getLogger(__name__)


class MemoryRetriever:
    """
    记忆检索器 — 混合检索策略

    检索流程:
    1. 关键词倒排索引预过滤 (快速, 50ms)
    2. 向量余弦相似度精排 (准确, 在候选集上计算)
    3. 时间衰减加权 (近期记忆权重大)
    4. 返回 top_k 结果
    """

    # 默认配置
    DEFAULT_TOP_K = 10
    DEFAULT_MIN_SCORE = 0.5
    DEFAULT_TIME_DECAY_DAYS = 7  # 7天内记忆无衰减

    def __init__(
        self,
        vector_store: VectorStore,
        top_k: int = None,
        min_score: float = None,
        time_decay_days: int = None
    ):
        self.vector_store = vector_store
        self.top_k = top_k or self.DEFAULT_TOP_K
        self.min_score = min_score or self.DEFAULT_MIN_SCORE
        self.time_decay_days = time_decay_days or self.DEFAULT_TIME_DECAY_DAYS

        logger.info(
            f"记忆检索器初始化完成 (top_k={self.top_k}, "
            f"min_score={self.min_score}, time_decay={self.time_decay_days}天)"
        )

    def retrieve(
        self,
        query_vector: List[float],
        query_text: str,
        conversation_id: str = None,
        top_k: int = None,
        min_score: float = None
    ) -> List[Dict[str, Any]]:
        """
        检索相关记忆

        Args:
            query_vector: 查询向量 (由 Kotlin 侧调用 DeepSeek Embedding API 获取)
            query_text: 查询文本 (用于关键词预过滤)
            conversation_id: 对话 ID (可选, 用于限定检索范围)
            top_k: 返回结果数 (覆盖默认值)
            min_score: 最低相似度阈值 (覆盖默认值)

        Returns:
            [{"id": ..., "text": ..., "score": ..., "metadata": ...}, ...]
        """
        k = top_k or self.top_k
        score = min_score or self.min_score

        # 向量检索
        results = self.vector_store.search(
            query_vector=query_vector,
            query_text=query_text,
            top_k=k * 2,  # 多取一些，后续做时间衰减
            use_index=True,
            min_score=score
        )

        if not results:
            logger.debug("未检索到相关记忆")
            return []

        # 应用时间衰减加权
        results = self._apply_time_decay(results)

        # 如果指定了 conversation_id，优先返回该对话的记忆
        if conversation_id:
            results = self._boost_conversation(results, conversation_id)

        # 截取 top_k
        return results[:k]

    def _apply_time_decay(self, results: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        应用时间衰减加权

        衰减公式: adjusted_score = score * decay_factor
        decay_factor = 1.0 / (1.0 + days_old / time_decay_days)
        """
        import time

        now = int(time.time())
        decay_days = self.time_decay_days

        for r in results:
            timestamp = r.get("metadata", {}).get("timestamp", 0)
            if timestamp:
                days_old = (now - timestamp) / 86400.0
                if days_old > decay_days:
                    decay_factor = 1.0 / (1.0 + (days_old - decay_days) / decay_days)
                    r["raw_score"] = r["score"]
                    r["score"] = round(r["score"] * decay_factor, 4)
                    r["time_decay"] = True

        # 按调整后的分数重新排序
        results.sort(key=lambda x: x["score"], reverse=True)
        return results

    def _boost_conversation(
        self,
        results: List[Dict[str, Any]],
        conversation_id: str
    ) -> List[Dict[str, Any]]:
        """
        提升当前对话的记忆权重

        当前对话的记忆得分乘以 1.2 倍
        """
        for r in results:
            r_conv_id = r.get("metadata", {}).get("conversation_id", "")
            if r_conv_id == conversation_id:
                r["raw_score"] = r.get("raw_score", r["score"])
                r["score"] = round(r["score"] * 1.2, 4)
                r["conv_boost"] = True

        results.sort(key=lambda x: x["score"], reverse=True)
        return results

    def retrieve_as_context(
        self,
        query_vector: List[float],
        query_text: str,
        conversation_id: str = None,
        max_tokens: int = 2000,
        top_k: int = None
    ) -> str:
        """
        检索记忆并格式化为上下文文本

        Args:
            query_vector: 查询向量
            query_text: 查询文本
            conversation_id: 对话 ID
            max_tokens: 最大 token 数 (粗略估算, 中文约 1.5 字符/token)
            top_k: 返回结果数

        Returns:
            格式化的记忆上下文文本
        """
        results = self.retrieve(
            query_vector=query_vector,
            query_text=query_text,
            conversation_id=conversation_id,
            top_k=top_k
        )

        if not results:
            return ""

        max_chars = max_tokens * 1.5
        context_parts = []
        current_chars = 0

        for r in results:
            text = r["text"]
            if current_chars + len(text) > max_chars:
                # 截断最后一条
                remaining = max_chars - current_chars
                if remaining > 50:
                    context_parts.append(text[:int(remaining)] + "...")
                break
            context_parts.append(text)
            current_chars += len(text)

        return "\n---\n".join(context_parts)

    def set_config(self, top_k: int = None, min_score: float = None, time_decay_days: int = None) -> None:
        """动态更新检索配置 (热更新)"""
        if top_k is not None:
            self.top_k = max(1, min(top_k, 100))
        if min_score is not None:
            self.min_score = max(0.0, min(min_score, 1.0))
        if time_decay_days is not None:
            self.time_decay_days = max(1, time_decay_days)
        logger.info(f"检索配置已更新: top_k={self.top_k}, min_score={self.min_score}, time_decay={self.time_decay_days}")


def get_retriever(vector_store: VectorStore) -> MemoryRetriever:
    """获取记忆检索器实例"""
    return MemoryRetriever(vector_store=vector_store)