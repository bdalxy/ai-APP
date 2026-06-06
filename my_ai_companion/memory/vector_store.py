# -*- coding: utf-8 -*-
"""
向量存储模块
实现基于 JSON 的向量存储、关键词倒排索引和纯 Python 余弦相似度检索

设计原则:
- 零外部依赖: 不依赖 numpy、FAISS、ChromaDB 等 C 扩展
- 适用于 Chaquopy (Python 嵌入 Android): 纯 Python 实现
- 支持 K 级数据量 (5000~20000 条) 的实时检索
- 关键词倒排索引预过滤 + 余弦相似度精排
"""

import json
import math
import logging
import hashlib
import threading
from typing import Dict, Any, Optional, List, Tuple, Callable
from pathlib import Path
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


# ============================================================
# 数据模型
# ============================================================

@dataclass
class VectorEntry:
    """向量条目"""
    id: str                          # 唯一标识
    text: str                        # 原文
    vector: List[float]              # 向量 (768 维)
    metadata: Dict[str, Any] = field(default_factory=dict)
    keywords: List[str] = field(default_factory=list)  # 预提取的关键词
    timestamp: int = 0               # Unix 时间戳


# ============================================================
# 关键词倒排索引
# ============================================================

class InvertedIndex:
    """关键词倒排索引 — 用于大规模记忆的预过滤"""

    def __init__(self):
        self._index: Dict[str, set] = {}  # keyword -> set of entry_ids

    def add(self, entry_id: str, keywords: List[str]) -> None:
        """添加条目到索引"""
        for kw in keywords:
            kw_lower = kw.lower()
            if kw_lower not in self._index:
                self._index[kw_lower] = set()
            self._index[kw_lower].add(entry_id)

    def remove(self, entry_id: str, keywords: List[str]) -> None:
        """从索引中移除条目"""
        for kw in keywords:
            kw_lower = kw.lower()
            if kw_lower in self._index:
                self._index[kw_lower].discard(entry_id)
                if not self._index[kw_lower]:
                    del self._index[kw_lower]

    def search(self, query: str, max_candidates: int = 500) -> List[str]:
        """
        搜索匹配的条目 ID 列表

        Args:
            query: 搜索查询文本
            max_candidates: 最大候选数量

        Returns:
            匹配的条目 ID 列表
        """
        # 从查询中提取关键词 (简单分词)
        query_keywords = self._tokenize(query)
        if not query_keywords:
            return []

        # 对每个匹配的条目打分 (命中关键词数越多分越高)
        scores: Dict[str, int] = {}
        for kw in query_keywords:
            kw_lower = kw.lower()
            if kw_lower in self._index:
                for entry_id in self._index[kw_lower]:
                    scores[entry_id] = scores.get(entry_id, 0) + 1

        # 按分数降序排序，返回前 max_candidates 个
        sorted_ids = sorted(scores.keys(), key=lambda x: scores[x], reverse=True)
        return sorted_ids[:max_candidates]

    def _tokenize(self, text: str) -> List[str]:
        """简单中文分词 (基于字符级 bigram)"""
        # 中文: 使用 bigram
        # 英文: 使用空格分词
        import re
        # 提取中文字符的 bigram
        chinese_chars = re.findall(r'[\u4e00-\u9fa5]', text)
        bigrams = []
        for i in range(len(chinese_chars) - 1):
            bigrams.append(chinese_chars[i] + chinese_chars[i + 1])

        # 提取英文单词
        english_words = re.findall(r'[a-zA-Z]+', text.lower())

        # 合并，去重
        tokens = list(set(bigrams + english_words))
        return tokens

    def clear(self) -> None:
        """清空索引"""
        self._index.clear()

    def size(self) -> int:
        """返回索引中唯一关键词数"""
        return len(self._index)


# ============================================================
# 纯 Python 余弦相似度计算
# ============================================================

class CosineSimilarity:
    """纯 Python 余弦相似度计算器"""

    @staticmethod
    def compute(vec_a: List[float], vec_b: List[float]) -> float:
        """
        计算两个向量的余弦相似度

        时间复杂度: O(n), n 为向量维度
        空间复杂度: O(1)

        Args:
            vec_a: 向量 A
            vec_b: 向量 B

        Returns:
            余弦相似度 (0.0 ~ 1.0)
        """
        if len(vec_a) != len(vec_b):
            raise ValueError(f"向量维度不匹配: {len(vec_a)} vs {len(vec_b)}")

        dot_product = 0.0
        norm_a = 0.0
        norm_b = 0.0

        # 单次遍历完成点积和模长计算
        for a, b in zip(vec_a, vec_b):
            dot_product += a * b
            norm_a += a * a
            norm_b += b * b

        if norm_a == 0.0 or norm_b == 0.0:
            return 0.0

        return dot_product / (math.sqrt(norm_a) * math.sqrt(norm_b))

    @staticmethod
    def batch_compute(
        query_vec: List[float],
        candidate_vecs: List[Tuple[str, List[float]]],
        top_k: int = 10
    ) -> List[Tuple[str, float]]:
        """
        批量计算查询向量与候选向量之间的余弦相似度

        使用堆排序保留 top_k 结果，避免全量排序

        Args:
            query_vec: 查询向量
            candidate_vecs: 候选向量列表 [(id, vector), ...]
            top_k: 返回最高分的条目数

        Returns:
            [(id, score), ...] 按分数降序排列
        """
        import heapq

        # 使用最小堆保留 top_k
        heap: List[Tuple[float, str]] = []

        for entry_id, vec in candidate_vecs:
            score = CosineSimilarity.compute(query_vec, vec)
            if len(heap) < top_k:
                heapq.heappush(heap, (score, entry_id))
            elif score > heap[0][0]:
                heapq.heapreplace(heap, (score, entry_id))

        # 转为降序
        return [(entry_id, score) for score, entry_id in sorted(heap, key=lambda x: x[0], reverse=True)]


# ============================================================
# 向量存储管理器
# ============================================================

class VectorStore:
    """
    向量存储管理器

    存储架构:
    - vectors.json: 所有向量条目 (全量存储)
    - 内存中的倒排索引 (用于快速预过滤)
    - 分页加载: 检索时按需加载候选向量

    性能预估 (768 维向量, Android 中端设备):
    - 5000 条: 全量检索 ~0.5s, 索引预过滤后 ~0.05s
    - 10000 条: 全量检索 ~1.0s, 索引预过滤后 ~0.08s
    - 20000 条: 全量检索 ~2.0s, 索引预过滤后 ~0.12s
    """

    # 默认向量维度 (DeepSeek Embedding V2)
    DEFAULT_DIM = 768

    def __init__(self, data_dir: str = "data"):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)

        self._vector_file = self.data_dir / "vectors.json"
        self._index = InvertedIndex()
        self._entries: Dict[str, VectorEntry] = {}
        self._lock = threading.RLock()

        # 加载已有数据
        self._load()

        logger.info(f"向量存储初始化完成 (条目数: {len(self._entries)}, 索引关键词数: {self._index.size()})")

    # ---- 数据持久化 ----

    def _load(self) -> None:
        """从文件加载向量数据"""
        if not self._vector_file.exists():
            logger.info("向量文件不存在，创建空存储")
            return

        try:
            with open(self._vector_file, 'r', encoding='utf-8') as f:
                data = json.load(f)

            entries_list = data if isinstance(data, list) else data.get("entries", [])
            for item in entries_list:
                entry = VectorEntry(**item)
                self._entries[entry.id] = entry
                if entry.keywords:
                    self._index.add(entry.id, entry.keywords)

            logger.info(f"从文件加载了 {len(self._entries)} 条向量")
        except Exception as e:
            logger.error(f"加载向量数据失败: {e}")

    def _save(self) -> None:
        """保存向量数据到文件"""
        try:
            entries_list = [self._entry_to_dict(e) for e in self._entries.values()]
            with open(self._vector_file, 'w', encoding='utf-8') as f:
                json.dump(entries_list, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.error(f"保存向量数据失败: {e}")

    def _entry_to_dict(self, entry: VectorEntry) -> Dict[str, Any]:
        """将条目转为可序列化字典"""
        return {
            "id": entry.id,
            "text": entry.text,
            "vector": entry.vector,
            "metadata": entry.metadata,
            "keywords": entry.keywords,
            "timestamp": entry.timestamp
        }

    # ---- 向量操作 ----

    def add(
        self,
        text: str,
        vector: List[float],
        metadata: Dict[str, Any] = None,
        entry_id: str = None,
        extract_keywords: bool = True
    ) -> str:
        """
        添加向量条目

        Args:
            text: 原始文本
            vector: 向量 (768 维)
            metadata: 元数据
            entry_id: 条目 ID (不提供则自动生成)
            extract_keywords: 是否自动提取关键词

        Returns:
            条目 ID
        """
        with self._lock:
            if entry_id is None:
                entry_id = self._generate_id(text)

            # 提取关键词
            keywords = []
            if extract_keywords:
                keywords = self._extract_keywords(text)

            entry = VectorEntry(
                id=entry_id,
                text=text,
                vector=vector,
                metadata=metadata or {},
                keywords=keywords,
                timestamp=int(Path(self._vector_file).stat().st_mtime * 1000) if self._vector_file.exists() else 0
            )

            self._entries[entry_id] = entry
            self._index.add(entry_id, keywords)

            # 异步保存 (简单实现: 同步保存, 后续可优化)
            self._save()

            return entry_id

    def search(
        self,
        query_vector: List[float],
        query_text: str = "",
        top_k: int = 10,
        use_index: bool = True,
        min_score: float = 0.5
    ) -> List[Dict[str, Any]]:
        """
        向量检索

        检索策略:
        1. 如果 use_index=True 且 query_text 非空:
           - 先用倒排索引预过滤 (关键词匹配)
           - 在候选集上计算余弦相似度
        2. 否则: 全量计算余弦相似度

        Args:
            query_vector: 查询向量
            query_text: 查询文本 (用于关键词预过滤)
            top_k: 返回结果数
            use_index: 是否使用倒排索引预过滤
            min_score: 最低相似度阈值

        Returns:
            [{"id": ..., "text": ..., "score": ..., "metadata": ...}, ...]
        """
        with self._lock:
            if not self._entries:
                return []

            # 步骤1: 预过滤
            if use_index and query_text:
                candidate_ids = self._index.search(query_text, max_candidates=500)
                if not candidate_ids:
                    # 索引无匹配, 回退到全量检索
                    logger.debug("索引无匹配结果，回退到全量检索")
                    candidate_ids = list(self._entries.keys())
            else:
                candidate_ids = list(self._entries.keys())

            # 步骤2: 构建候选向量列表
            candidate_vecs = [
                (eid, self._entries[eid].vector)
                for eid in candidate_ids
                if eid in self._entries
            ]

            if not candidate_vecs:
                return []

            # 步骤3: 计算余弦相似度
            scored = CosineSimilarity.batch_compute(query_vector, candidate_vecs, top_k)

            # 步骤4: 过滤低分，构建结果
            results = []
            for entry_id, score in scored:
                if score < min_score:
                    continue
                entry = self._entries[entry_id]
                results.append({
                    "id": entry.id,
                    "text": entry.text,
                    "score": round(score, 4),
                    "metadata": entry.metadata
                })

            return results

    def remove(self, entry_id: str) -> bool:
        """移除向量条目"""
        with self._lock:
            if entry_id not in self._entries:
                return False

            entry = self._entries[entry_id]
            self._index.remove(entry_id, entry.keywords)
            del self._entries[entry_id]
            self._save()
            return True

    def clear(self) -> None:
        """清空所有向量"""
        with self._lock:
            self._entries.clear()
            self._index.clear()
            self._save()

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        with self._lock:
            return {
                "total_entries": len(self._entries),
                "index_keywords": self._index.size(),
                "vector_dimension": self.DEFAULT_DIM,
                "file_size_mb": round(
                    self._vector_file.stat().st_size / (1024 * 1024), 2
                ) if self._vector_file.exists() else 0
            }

    def get_all_entries(self) -> List[Dict[str, Any]]:
        """获取所有条目 (用于导出)"""
        with self._lock:
            return [self._entry_to_dict(e) for e in self._entries.values()]

    # ---- 工具方法 ----

    @staticmethod
    def _generate_id(text: str) -> str:
        """基于文本生成唯一 ID"""
        return hashlib.md5(text.encode('utf-8')).hexdigest()[:16]

    @staticmethod
    def _extract_keywords(text: str, max_keywords: int = 20) -> List[str]:
        """
        提取关键词 (简单实现, 后续可接入 jieba 分词)

        当前策略:
        - 中文: 字符级 bigram
        - 英文: 空格分词 + 过滤停用词
        """
        import re

        keywords = []

        # 中文 bigram
        chinese_chars = re.findall(r'[\u4e00-\u9fa5]', text)
        for i in range(len(chinese_chars) - 1):
            bigram = chinese_chars[i] + chinese_chars[i + 1]
            if bigram not in keywords:
                keywords.append(bigram)

        # 英文单词 (过滤短词和停用词)
        stop_words = {
            'the', 'a', 'an', 'is', 'are', 'was', 'were', 'be', 'been',
            'i', 'you', 'he', 'she', 'it', 'we', 'they', 'me', 'him', 'her',
            'my', 'your', 'his', 'its', 'our', 'their', 'this', 'that',
            'in', 'on', 'at', 'to', 'for', 'of', 'with', 'and', 'or', 'but',
            'not', 'so', 'if', 'then', 'than', 'too', 'very', 'just', 'now'
        }
        english_words = re.findall(r'[a-zA-Z]{3,}', text.lower())
        for word in english_words:
            if word not in stop_words and word not in keywords:
                keywords.append(word)

        return keywords[:max_keywords]


# ============================================================
# 存档管理器 (用于定期归档旧记忆)
# ============================================================

class ArchiveManager:
    """记忆归档管理器 — 定期将旧记忆归档到压缩文件"""

    def __init__(self, vector_store: VectorStore, archive_dir: str = "data/archives"):
        self.vector_store = vector_store
        self.archive_dir = Path(archive_dir)
        self.archive_dir.mkdir(parents=True, exist_ok=True)

    def archive_old(
        self,
        days_threshold: int = 30,
        min_entries: int = 1000
    ) -> Optional[str]:
        """
        归档旧记忆

        当总条目超过 min_entries 时，将超过 days_threshold 天的旧条目
        移动到归档文件，只保留最近的和最重要的条目

        Args:
            days_threshold: 天数阈值
            min_entries: 触发归档的最小条目数

        Returns:
            归档文件路径, 或 None (未触发归档)
        """
        import time

        with self.vector_store._lock:
            total = len(self.vector_store._entries)
            if total < min_entries:
                logger.debug(f"条目数 {total} 未达到归档阈值 {min_entries}")
                return None

            cutoff = int(time.time()) - days_threshold * 86400
            to_archive = []
            to_keep = []

            for entry_id, entry in self.vector_store._entries.items():
                if entry.timestamp < cutoff:
                    to_archive.append(entry_id)
                else:
                    to_keep.append(entry_id)

            if len(to_archive) < 100:
                logger.debug(f"可归档条目 {len(to_archive)} 太少，跳过")
                return None

            # 保存归档
            archive_file = self.archive_dir / f"archive_{int(time.time())}.json"
            archive_data = []
            for entry_id in to_archive:
                entry = self.vector_store._entries[entry_id]
                archive_data.append(self.vector_store._entry_to_dict(entry))

            with open(archive_file, 'w', encoding='utf-8') as f:
                json.dump(archive_data, f, ensure_ascii=False, indent=2)

            # 从主存储中移除
            for entry_id in to_archive:
                entry = self.vector_store._entries[entry_id]
                self.vector_store._index.remove(entry_id, entry.keywords)
                del self.vector_store._entries[entry_id]

            self.vector_store._save()

            logger.info(f"归档完成: {len(to_archive)} 条 -> {archive_file}, 保留 {len(to_keep)} 条")
            return str(archive_file)

    def list_archives(self) -> List[Dict[str, Any]]:
        """列出所有归档文件"""
        archives = []
        for f in sorted(self.archive_dir.glob("archive_*.json"), reverse=True):
            archives.append({
                "path": str(f),
                "size_mb": round(f.stat().st_size / (1024 * 1024), 2),
                "created": f.stat().st_mtime
            })
        return archives


# ============================================================
# 性能测试工具
# ============================================================

class VectorStoreBenchmark:
    """向量存储性能基准测试"""

    @staticmethod
    def run_benchmark(store: VectorStore, query_vector: List[float], query_text: str) -> Dict[str, Any]:
        """
        运行基准测试

        Returns:
            {"total_entries": ..., "full_scan_ms": ..., "indexed_scan_ms": ...}
        """
        import time

        total = len(store._entries)
        if total == 0:
            return {"total_entries": 0, "full_scan_ms": 0, "indexed_scan_ms": 0}

        # 全量扫描
        start = time.perf_counter()
        store.search(query_vector, query_text="", use_index=False, top_k=10)
        full_scan_ms = round((time.perf_counter() - start) * 1000, 1)

        # 索引预过滤
        start = time.perf_counter()
        store.search(query_vector, query_text=query_text, use_index=True, top_k=10)
        indexed_scan_ms = round((time.perf_counter() - start) * 1000, 1)

        return {
            "total_entries": total,
            "full_scan_ms": full_scan_ms,
            "indexed_scan_ms": indexed_scan_ms,
            "speedup": round(full_scan_ms / indexed_scan_ms, 1) if indexed_scan_ms > 0 else 0
        }


# ============================================================
# 工厂函数
# ============================================================

def get_vector_store(data_dir: str = "data") -> VectorStore:
    """获取向量存储实例"""
    return VectorStore(data_dir=data_dir)