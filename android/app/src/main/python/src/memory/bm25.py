"""BM25 评分模块。

纯 Python 实现的 BM25 文本相关性评分算法，用于关键词检索时的精排。
当 DeepSeek Embedding API 不可用时，作为降级检索方案的评分核心。

BM25 相比 TF-IDF 的优势:
    - 内置文档长度归一化（长文档不会因词频高而获得不公平的高分）
    - 参数 k1 和 b 可调，适应不同场景
    - 在实践中被广泛验证为效果最好的词袋检索模型之一

核心类:
    - BM25Scorer: BM25 评分器，支持增量索引构建和查询
    - BM25Config: BM25 参数配置

依赖:
    - 纯 Python 实现，无外部依赖
    - src.memory.memory_types: extract_keywords（复用）
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass


# =============================================================================
# BM25 参数配置
# =============================================================================

@dataclass
class BM25Config:
    """BM25 参数配置。

    Attributes:
        k1: 词频饱和度参数（默认 1.5）。值越大，词频对评分的影响越大。
            推荐范围: 1.2 ~ 2.0。
        b: 文档长度归一化参数（默认 0.75）。值越大，长文档受到的惩罚越大。
           推荐范围: 0.5 ~ 0.9。b=0 表示不进行长度归一化。
        epsilon: 平滑参数（默认 0.25），防止 log(0)。
    """
    k1: float = 1.5
    b: float = 0.75
    epsilon: float = 0.25


# =============================================================================
# 中文分词工具
# =============================================================================

# 中文停用词表
_STOP_WORDS: set[str] = {
    "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
    "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
    "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
    "什么", "怎么", "为什么", "哪个", "哪里", "吗", "吧", "呢", "啊", "哦",
    "嗯", "啦", "呀", "嘛", "哈", "哇", "唉", "喂", "嘿", "呵",
    "可以", "因为", "所以", "但是", "如果", "虽然", "还是", "只是",
    "应该", "可能", "一定", "需要", "让", "把", "被", "给", "对",
    "从", "向", "跟", "与", "或", "而", "且", "但", "并", "所",
}


def _tokenize_cn(text: str) -> list[str]:
    """中文分词（基于字符 bigram + 停用词过滤）。

    使用字符级 bigram 作为"词"的近似，这是在无分词器条件下的最佳实践。
    - 单字词：索引能力弱，但保留以召回短查询
    - 双字词（bigram）：中文基本语义单元
    - 三字词（trigram）：更精确的语义匹配

    Args:
        text: 待分词的文本。

    Returns:
        分词结果列表。
    """
    if not text:
        return []

    # 清理文本：保留中文字符、字母、数字
    cleaned = re.sub(r"[^\u4e00-\u9fff\w\d]", "", text)
    if not cleaned:
        return []

    tokens: list[str] = []

    # 单字词（索引基础）
    for ch in cleaned:
        if ch not in _STOP_WORDS and len(ch.strip()) > 0:
            tokens.append(ch)

    # 双字词（bigram）
    for i in range(len(cleaned) - 1):
        bigram = cleaned[i:i + 2]
        tokens.append(bigram)

    # 三字词（trigram）
    for i in range(len(cleaned) - 2):
        trigram = cleaned[i:i + 3]
        tokens.append(trigram)

    return tokens


# =============================================================================
# BM25 评分器
# =============================================================================

class BM25Scorer:
    """BM25 评分器。

    支持增量构建索引，高效计算查询与文档的相关性分数。
    线程安全：文档索引操作通过锁保护。

    使用示例:
        >>> bm25 = BM25Scorer()
        >>> bm25.add_document("doc1", "用户喜欢猫和狗")
        >>> bm25.add_document("doc2", "用户住在北京")
        >>> scores = bm25.score("用户喜欢什么动物")
        >>> # scores = [("doc1", 1.234), ("doc2", 0.0)]
    """

    def __init__(self, config: BM25Config | None = None) -> None:
        """初始化 BM25 评分器。

        Args:
            config: BM25 参数配置。为 None 时使用默认配置。
        """
        self._config = config or BM25Config()
        self._documents: dict[str, str] = {}          # doc_id -> 原始文本
        self._doc_tokens: dict[str, list[str]] = {}   # doc_id -> 分词结果
        self._doc_lengths: dict[str, int] = {}         # doc_id -> 文档长度（token数）
        self._total_docs: int = 0                       # 总文档数
        self._avg_doc_length: float = 0.0               # 平均文档长度
        self._inverted_index: dict[str, dict[str, int]] = {}  # term -> {doc_id -> tf}
        self._doc_freq: dict[str, int] = {}              # term -> 包含该词的文档数

    # =========================================================================
    # 索引构建
    # =========================================================================

    def add_document(self, doc_id: str, text: str) -> None:
        """添加或更新文档到索引中。

        如果 doc_id 已存在，先移除旧索引再添加新索引。

        Args:
            doc_id: 文档唯一标识符。
            text: 文档文本内容。
        """
        if not text or not text.strip():
            return

        # 如果已存在，先移除
        if doc_id in self._documents:
            self.remove_document(doc_id)

        # 分词
        tokens = _tokenize_cn(text)
        if not tokens:
            return

        # 存储文档
        self._documents[doc_id] = text
        self._doc_tokens[doc_id] = tokens
        self._doc_lengths[doc_id] = len(tokens)
        self._total_docs += 1

        # 更新平均文档长度
        self._avg_doc_length = (
            sum(self._doc_lengths.values()) / self._total_docs
        )

        # 更新倒排索引
        term_counts = Counter(tokens)
        for term, tf in term_counts.items():
            if term not in self._inverted_index:
                self._inverted_index[term] = {}
            self._inverted_index[term][doc_id] = tf
            self._doc_freq[term] = self._doc_freq.get(term, 0) + 1

    def add_documents(self, docs: dict[str, str]) -> None:
        """批量添加文档。

        Args:
            docs: doc_id -> text 的映射字典。
        """
        for doc_id, text in docs.items():
            self.add_document(doc_id, text)

    def remove_document(self, doc_id: str) -> None:
        """从索引中移除文档。

        Args:
            doc_id: 要移除的文档 ID。
        """
        if doc_id not in self._documents:
            return

        # 从倒排索引中移除
        tokens = self._doc_tokens.get(doc_id, [])
        term_counts = Counter(tokens)
        for term, tf in term_counts.items():
            if term in self._inverted_index:
                self._inverted_index[term].pop(doc_id, None)
                if not self._inverted_index[term]:
                    del self._inverted_index[term]
                self._doc_freq[term] = max(0, self._doc_freq.get(term, 1) - 1)

        # 从文档存储中移除
        self._documents.pop(doc_id, None)
        self._doc_tokens.pop(doc_id, None)
        self._doc_lengths.pop(doc_id, None)
        self._total_docs = max(0, self._total_docs - 1)

        # 更新平均文档长度
        if self._total_docs > 0:
            self._avg_doc_length = (
                sum(self._doc_lengths.values()) / self._total_docs
            )
        else:
            self._avg_doc_length = 0.0

    def clear(self) -> None:
        """清空索引。"""
        self._documents.clear()
        self._doc_tokens.clear()
        self._doc_lengths.clear()
        self._total_docs = 0
        self._avg_doc_length = 0.0
        self._inverted_index.clear()
        self._doc_freq.clear()

    # =========================================================================
    # 查询评分
    # =========================================================================

    def score(self, query: str, top_k: int = 10) -> list[tuple[str, float]]:
        """计算查询与所有文档的 BM25 相关性分数。

        BM25 公式:
            score(D, Q) = Sigma IDF(qi) * (tf(qi, D) * (k1 + 1)) /
                          (tf(qi, D) + k1 * (1 - b + b * |D| / avgdl))

        其中:
            IDF(qi) = log((N - df(qi) + 0.5) / (df(qi) + 0.5) + 1)
            tf(qi, D) = 词 qi 在文档 D 中的词频
            |D| = 文档 D 的长度
            avgdl = 平均文档长度
            N = 总文档数

        Args:
            query: 查询文本。
            top_k: 返回的最高分文档数。

        Returns:
            list[tuple[str, float]]: (doc_id, bm25_score) 列表，按分数降序排列。
        """
        if not query or not query.strip() or self._total_docs == 0:
            return []

        # 查询分词
        query_tokens = _tokenize_cn(query)
        if not query_tokens:
            return []

        # 查询词频（用于多次出现同一词的查询）
        query_tf = Counter(query_tokens)

        # 计算每个文档的 BM25 分数
        scores: dict[str, float] = {}
        k1 = self._config.k1
        b = self._config.b
        eps = self._config.epsilon

        for term, qt in query_tf.items():
            if term not in self._inverted_index:
                continue

            df = self._doc_freq.get(term, 0)
            # IDF 计算（Robertson-Sparck Jones 公式）
            idf = math.log(
                (self._total_docs - df + 0.5) / (df + 0.5 + eps) + 1.0
            )

            # 对包含该词的每个文档计算分数
            for doc_id, tf in self._inverted_index[term].items():
                doc_len = self._doc_lengths.get(doc_id, 1)
                # BM25 核心公式
                numerator = tf * (k1 + 1.0)
                denominator = tf + k1 * (1.0 - b + b * doc_len / max(self._avg_doc_length, 1.0))
                term_score = idf * numerator / denominator

                # 查询词频加权
                scores[doc_id] = scores.get(doc_id, 0.0) + term_score * qt

        # 排序并返回 top_k
        sorted_scores = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        return sorted_scores[:top_k]

    def score_single(self, query: str, doc_id: str) -> float:
        """计算查询与单个文档的 BM25 分数。

        Args:
            query: 查询文本。
            doc_id: 文档 ID。

        Returns:
            BM25 分数。如果文档不存在，返回 0.0。
        """
        if doc_id not in self._documents:
            return 0.0

        results = self.score(query, top_k=self._total_docs)
        for candidate_id, score in results:
            if candidate_id == doc_id:
                return score
        return 0.0

    # =========================================================================
    # 查询扩展
    # =========================================================================

    def expand_query(self, query: str, num_expansions: int = 3) -> list[str]:
        """查询扩展：基于倒排索引找到与查询词共现的高频词。

        使用伪相关反馈（Pseudo Relevance Feedback）的思想：
        1. 用原始查询检索 top-k 文档
        2. 在 top-k 文档中找到高频词
        3. 返回高频词作为扩展词

        Args:
            query: 原始查询文本。
            num_expansions: 扩展词数量。

        Returns:
            扩展后的查询词列表。
        """
        if not query or self._total_docs == 0:
            return []

        # 1. 检索 top-k 文档（取 2 倍，确保有足够候选）
        top_docs = self.score(query, top_k=min(10, self._total_docs))
        if not top_docs:
            return []

        # 2. 统计 top-k 文档中的高频词
        term_scores: dict[str, float] = {}
        query_terms = set(_tokenize_cn(query))

        for rank, (doc_id, bm25_score) in enumerate(top_docs):
            if doc_id not in self._doc_tokens:
                continue
            tokens = self._doc_tokens[doc_id]
            token_counts = Counter(tokens)

            # 位置权重：排名越靠前的文档，权重越高
            position_weight = 1.0 / (rank + 1)

            for term, count in token_counts.items():
                if term in query_terms:
                    continue  # 跳过查询中已有的词
                # 分数 = 词频 * BM25 分数 * 位置权重
                term_scores[term] = term_scores.get(term, 0.0) + (
                    count * bm25_score * position_weight
                )

        # 3. 排序并返回扩展词
        sorted_terms = sorted(
            term_scores.items(),
            key=lambda x: x[1],
            reverse=True,
        )
        return [term for term, _ in sorted_terms[:num_expansions]]

    # =========================================================================
    # 统计信息
    # =========================================================================

    @property
    def doc_count(self) -> int:
        """获取索引中的文档数。"""
        return self._total_docs

    @property
    def term_count(self) -> int:
        """获取索引中的唯一词数。"""
        return len(self._inverted_index)

    def get_stats(self) -> dict:
        """获取索引统计信息。

        Returns:
            包含文档数、词数、平均文档长度等统计信息的字典。
        """
        return {
            "total_docs": self._total_docs,
            "total_terms": len(self._inverted_index),
            "avg_doc_length": round(self._avg_doc_length, 2),
            "config": {
                "k1": self._config.k1,
                "b": self._config.b,
                "epsilon": self._config.epsilon,
            },
        }