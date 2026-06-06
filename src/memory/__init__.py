"""记忆系统模块。

包含向量存储、时间衰减、混合检索、记忆提取和导出功能。

核心类:
    - VectorStore: 基于 SQLite 的向量存储 + 倒排索引
    - InvertedIndex: 纯 Python 倒排索引
    - MemoryEntry: 记忆条目数据类
    - MemoryRetriever: 混合检索器（倒排索引 + 余弦相似度 + 时间衰减）
    - MemoryExtractor: 记忆提取器（规则模式 + LLM 模式）
    - TimeDecay: 时间衰减算法工具函数

工具函数:
    - cosine_similarity: 余弦相似度计算
    - extract_keywords: 关键词提取（bigram/trigram）
    - calculate_decay: 衰减因子计算
    - get_weight: 综合权重计算
    - export_chat_history: 导出对话历史
    - export_memories: 导出长期记忆
    - export_full: 完整导出
"""

from src.memory.vector_store import (
    InvertedIndex,
    MemoryEntry,
    VectorStore,
    cosine_similarity,
    extract_keywords,
)
from src.memory.decay import (
    calculate_decay,
    get_decay_stats,
    get_half_life,
    get_type_multiplier,
    get_weight,
    update_decay,
)
from src.memory.retriever import MemoryRetriever
from src.memory.extractor import MemoryExtractor
from src.memory.exporter import (
    export_chat_history,
    export_full,
    export_memories,
)

__all__ = [
    # 向量存储
    "VectorStore",
    "InvertedIndex",
    "MemoryEntry",
    "cosine_similarity",
    "extract_keywords",
    # 时间衰减
    "calculate_decay",
    "get_weight",
    "update_decay",
    "get_half_life",
    "get_type_multiplier",
    "get_decay_stats",
    # 检索器
    "MemoryRetriever",
    # 提取器
    "MemoryExtractor",
    # 导出
    "export_chat_history",
    "export_memories",
    "export_full",
]
