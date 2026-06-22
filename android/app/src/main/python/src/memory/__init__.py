"""记忆系统模块。

包含向量存储、时间衰减、混合检索、记忆提取、编排和导出功能，
以及记忆备份、缓存加速、上下文构建、知识图谱、摘要生成等高级功能。

核心类:
    - VectorStore: 基于 SQLite 的向量存储 + 倒排索引 + 关系图 + 标签 + 变更日志
    - InvertedIndex: 纯 Python 倒排索引
    - BM25Scorer: BM25 文本相关性评分器
    - MemoryEntry: 记忆条目数据类
    - MemoryRetriever: 混合检索器（倒排索引 + 余弦相似度 + BM25 + 时间衰减 + 时间感知 + 图谱增强）
    - MemoryExtractor: 记忆提取器（规则模式 + LLM 模式 + 冲突检测 + LLM重要性评估）
    - MemoryOrchestrator: 记忆编排器（串联提取→存储→检索完整链路 + 知识图谱 + 摘要 + 生命周期管理）
    - MemoryConsolidator: 记忆合并器（相似记忆合并 + 冲突解决 + 重要性重评估）
    - MemoryLifecycle: 记忆生命周期管理器（衰减更新 + 重要性重校准 + 智能清理）
    - MemoryAnalyzer: 记忆分析器（趋势分析 + 主题聚类 + 用户画像 + 质量评估）
    - ContextBuilder: 上下文构建器（Token预算管理 + 分层记忆注入）
    - ContextConfig: 上下文构建配置
    - MemoryContext: 构建完成的记忆上下文
    - MemoryBackup: 记忆备份与恢复管理器
    - BackupMetadata: 备份元数据
    - MemoryCache: 记忆缓存管理器（LRU + TTL 双层缓存）
    - LRUCache: 通用 LRU 缓存
    - TTLCache: TTL 过期缓存
    - MemoryArchiver: 记忆归档器
    - KnowledgeGraph: 知识图谱管理器（实体提取 + 关系提取 + 图谱遍历）
    - MemorySummarizer: 记忆摘要器（单轮/会话/主题三层摘要）
    - TemporalWeight: 时间感知权重调整器
    - MemoryCategory: 记忆分类枚举（扩展类型系统）
    - MemoryRelation: 记忆关系类型枚举
    - ConflictResult: 冲突检测结果
    - EntityInfo: 实体信息
    - MemoryTag: 记忆标签
    - MemoryChangeLog: 记忆变更日志
"""

from src.memory.vector_store import (
    InvertedIndex,
    MemoryEntry,
    VectorStore,
    cosine_similarity,
    extract_keywords,
)
from src.memory.bm25 import BM25Scorer, BM25Config
from src.memory.decay import (
    calculate_decay,
    get_decay_stats,
    get_half_life,
    get_temporal_context,
    get_type_multiplier,
    get_weight,
    TemporalWeight,
    update_decay,
)
from src.memory.memory_types import (
    ConflictResult,
    EntityInfo,
    MemoryCategory,
    MemoryChangeLog,
    MemoryRelation,
    MemoryTag,
    analyze_sentiment,
    detect_conflict,
    estimate_importance,
    extract_entities,
)
from src.memory.retriever import MemoryRetriever
from src.memory.extractor import MemoryExtractor
from src.memory.consolidator import MemoryConsolidator
from src.memory.lifecycle import MemoryLifecycle
from src.memory.analyzer import MemoryAnalyzer
from src.memory.orchestrator import MemoryOrchestrator
from src.memory.context_builder import (
    ContextBuilder,
    ContextConfig,
    MemoryContext,
)
from src.memory.backup import BackupMetadata, MemoryBackup
from src.memory.memory_cache import LRUCache, MemoryCache, TTLCache
from src.memory.knowledge_graph import KnowledgeGraph
from src.memory.summarizer import MemorySummarizer
from src.memory.exporter import (
    export_chat_history,
    export_full,
    export_memories,
)

__all__ = [
    # 核心存储
    "VectorStore",
    "InvertedIndex",
    "MemoryEntry",
    "cosine_similarity",
    "extract_keywords",
    # BM25
    "BM25Scorer",
    "BM25Config",
    # 衰减 + 时间感知
    "calculate_decay",
    "get_weight",
    "update_decay",
    "get_half_life",
    "get_type_multiplier",
    "get_decay_stats",
    "get_temporal_context",
    "TemporalWeight",
    # 类型系统
    "MemoryCategory",
    "MemoryRelation",
    "ConflictResult",
    "EntityInfo",
    "MemoryTag",
    "MemoryChangeLog",
    "detect_conflict",
    "extract_entities",
    "analyze_sentiment",
    "estimate_importance",
    # 检索
    "MemoryRetriever",
    # 提取
    "MemoryExtractor",
    # 合并
    "MemoryConsolidator",
    # 生命周期
    "MemoryLifecycle",
    # 分析
    "MemoryAnalyzer",
    # 编排
    "MemoryOrchestrator",
    # 知识图谱
    "KnowledgeGraph",
    # 摘要
    "MemorySummarizer",
    # 上下文构建
    "ContextBuilder",
    "ContextConfig",
    "MemoryContext",
    # 备份
    "MemoryBackup",
    "BackupMetadata",
    # 缓存
    "MemoryCache",
    "LRUCache",
    "TTLCache",
    # 导出
    "export_chat_history",
    "export_memories",
    "export_full",
]
