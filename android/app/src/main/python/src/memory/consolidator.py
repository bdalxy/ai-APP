"""记忆合并模块。

自动检测并合并相似或重复的记忆，保持记忆库的简洁和一致性。
支持三种合并策略:
    1. 规则合并：基于关键词重叠和文本相似度
    2. LLM 合并：调用 DeepSeek API 进行语义级合并（可选）
    3. 冲突解决：检测矛盾记忆并生成新记忆取代旧记忆

合并触发条件:
    - 记忆总数超过阈值（默认 200 条）
    - 每 N 轮对话触发一次合并检查（默认 10 轮）
    - 手动调用合并接口

核心类:
    - MemoryConsolidator: 记忆合并器

依赖:
    - src.memory.vector_store: VectorStore, MemoryEntry, cosine_similarity
    - src.memory.memory_types: detect_conflict, ConflictResult, MemoryRelation
    - src.api_client.deepseek: DeepSeekClient（可选，用于 LLM 合并）
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import math
from collections import defaultdict
from typing import Any

from src.memory.memory_types import (
    ConflictResult,
    MemoryRelation,
    detect_conflict,
    _extract_key_entities,
)
from src.memory.vector_store import (
    MemoryEntry,
    VectorStore,
    cosine_similarity,
    extract_keywords,
)
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


class MemoryConsolidator:
    """记忆合并器。

    自动检测并合并相似/重复/冲突的记忆，保持记忆库的简洁和一致性。

    合并策略:
        1. 相似合并：两条记忆内容高度相似（> 0.85），合并为一条更完整的记忆。
        2. 冲突合并：两条记忆矛盾，新记忆取代旧记忆（旧记忆标记为 archived）。
        3. 批量合并：周期性扫描记忆库，对相似记忆进行批量合并。

    Attributes:
        SIMILARITY_THRESHOLD: 触发合并的相似度阈值（默认 0.85）。
        CONSOLIDATION_INTERVAL: 合并检查间隔（默认 10 轮）。
        MAX_CONSOLIDATE_PER_RUN: 每次合并的最大记忆数（默认 50）。
    """

    SIMILARITY_THRESHOLD: float = 0.80  # 原 0.85，降低阈值捕获更多相似对
    CONSOLIDATION_INTERVAL: int = 5     # 原 10，与提取频率（2轮）匹配，合并更频繁
    MAX_CONSOLIDATE_PER_RUN: int = 80   # 原 50，提高单次合并上限

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: Any = None,
    ) -> None:
        """初始化记忆合并器。

        Args:
            vector_store: 向量存储实例。
            deepseek_client: DeepSeek API 客户端（可选，用于 LLM 合并）。
        """
        self._store = vector_store
        self._client = deepseek_client
        self._log = get_logger()
        self._consolidation_count: int = 0
        self._merged_count: int = 0
        self._resolved_count: int = 0
        self._log.info("MemoryConsolidator 初始化完成")

    # =========================================================================
    # 相似记忆检测
    # =========================================================================

    def find_similar_pairs(
        self,
        entries: list[MemoryEntry],
        threshold: float | None = None,
    ) -> list[tuple[MemoryEntry, MemoryEntry, float]]:
        """在记忆列表中查找相似记忆对。

        使用 embedding 余弦相似度（优先）或 bigram 文本相似度（回退）。

        Args:
            entries: 待检查的记忆列表。
            threshold: 相似度阈值，默认使用 SIMILARITY_THRESHOLD。

        Returns:
            list[tuple[MemoryEntry, MemoryEntry, float]]: (记忆A, 记忆B, 相似度) 列表。
        """
        if len(entries) < 2:
            return []

        threshold = threshold or self.SIMILARITY_THRESHOLD
        similar_pairs: list[tuple[MemoryEntry, MemoryEntry, float]] = []

        # 按类型分组，减少不必要的比较（跨类型不会合并）
        by_type: dict[str, list[MemoryEntry]] = defaultdict(list)
        for entry in entries:
            by_type[entry.memory_type].append(entry)

        for mem_type, mems in by_type.items():
            n = len(mems)
            if n < 2:
                continue

            for i in range(n):
                for j in range(i + 1, n):
                    a, b = mems[i], mems[j]

                    # 优先使用 embedding 相似度
                    if a.embedding and b.embedding:
                        try:
                            sim = cosine_similarity(a.embedding, b.embedding)
                        except (ValueError, TypeError):
                            sim = self._text_similarity(a.content, b.content)
                    else:
                        sim = self._text_similarity(a.content, b.content)

                    if sim >= threshold:
                        similar_pairs.append((a, b, sim))

        # 按相似度降序排列
        similar_pairs.sort(key=lambda x: x[2], reverse=True)
        return similar_pairs

    @staticmethod
    def _text_similarity(text1: str, text2: str) -> float:
        """计算两个文本的字符级相似度（基于 bigram Jaccard）。"""
        import re

        def _bigrams(s: str) -> set[str]:
            cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", s)
            return {cleaned[i:i + 2] for i in range(len(cleaned) - 1)}

        bg1 = _bigrams(text1)
        bg2 = _bigrams(text2)
        if not bg1 or not bg2:
            return 0.0
        intersection = len(bg1 & bg2)
        union = len(bg1 | bg2)
        return intersection / union if union > 0 else 0.0

    # =========================================================================
    # 合并策略
    # =========================================================================

    def merge_pair(
        self,
        entry_a: MemoryEntry,
        entry_b: MemoryEntry,
        similarity: float,
    ) -> MemoryEntry | None:
        """合并两条相似记忆为一条新记忆。

        策略:
            - 保留重要性更高的记忆作为基础
            - 合并两个记忆的关键词和内容
            - 新记忆的 importance = max(importance_a, importance_b)
            - 新记忆的 access_count = access_count_a + access_count_b
            - 旧记忆标记为 archived

        Args:
            entry_a: 第一条记忆。
            entry_b: 第二条记忆。
            similarity: 两条记忆的相似度。

        Returns:
            合并后的新 MemoryEntry，如果无法合并则返回 None。
        """
        # 确定主记忆（重要性高、访问次数多、内容长）
        score_a = entry_a.importance * 0.4 + min(entry_a.access_count / 10, 1.0) * 0.3 + min(len(entry_a.content) / 100, 1.0) * 0.3
        score_b = entry_b.importance * 0.4 + min(entry_b.access_count / 10, 1.0) * 0.3 + min(len(entry_b.content) / 100, 1.0) * 0.3

        if score_a >= score_b:
            primary, secondary = entry_a, entry_b
        else:
            primary, secondary = entry_b, entry_a

        # 合并内容
        merged_content = self._merge_content(primary.content, secondary.content)

        # 合并关键词
        merged_keywords = list(set(primary.keywords + secondary.keywords))

        # 创建新记忆
        now = format_timestamp_iso()
        merged = MemoryEntry(
            memory_type=primary.memory_type,
            content=merged_content,
            keywords=merged_keywords,
            embedding=primary.embedding if primary.embedding else secondary.embedding,
            importance=max(primary.importance, secondary.importance),
            created_at=min(primary.created_at, secondary.created_at),
            last_accessed=now,
            access_count=primary.access_count + secondary.access_count,
            source_turn_id=primary.source_turn_id or secondary.source_turn_id,
            source=primary.source,
        )

        self._log.info(
            f"[合并] 合并记忆: '{primary.content[:30]}...' + "
            f"'{secondary.content[:30]}...' -> '{merged_content[:50]}...' "
            f"(相似度={similarity:.2f})"
        )

        return merged

    def _merge_content(self, content_a: str, content_b: str) -> str:
        """合并两条记忆的文本内容。

        策略:
            - 如果 B 是 A 的子集，返回 A
            - 如果 A 是 B 的子集，返回 B
            - 如果内容高度相似，返回较长的那个
            - 否则用分号连接两者
        """
        if content_b in content_a:
            return content_a
        if content_a in content_b:
            return content_b

        # 文本相似度 > 0.9 时返回较长的
        if self._text_similarity(content_a, content_b) > 0.9:
            return content_a if len(content_a) >= len(content_b) else content_b

        # 用分号连接
        return f"{content_a}；{content_b}"

    # =========================================================================
    # 冲突解决
    # =========================================================================

    def resolve_conflicts(
        self,
        new_entry: MemoryEntry,
        existing_entries: list[MemoryEntry],
    ) -> tuple[MemoryEntry | None, list[str]]:
        """检测并解决新记忆与已有记忆的冲突。

        流程:
            1. 用规则模式检测冲突
            2. 如果置信度高，标记旧记忆为 archived
            3. 记录记忆关系

        Args:
            new_entry: 新记忆条目。
            existing_entries: 已有的相关记忆列表。

        Returns:
            tuple[MemoryEntry | None, list[str]]:
                - 保留的新记忆（可能被修改），如果为 None 表示新记忆本身被废弃
                - 需要标记为 archived 的旧记忆 ID 列表
        """
        # 构建冲突检测所需的字典格式
        existing_dicts = [
            {"id": e.id, "content": e.content, "memory_type": e.memory_type}
            for e in existing_entries
        ]

        conflict = detect_conflict(new_entry.content, existing_dicts)

        if not conflict.has_conflict:
            return new_entry, []

        self._log.info(
            f"[冲突] 检测到冲突: new='{new_entry.content[:50]}...', "
            f"conflict_with='{conflict.conflicting_memory_content[:50]}...', "
            f"type={conflict.conflict_type}, confidence={conflict.confidence:.2f}"
        )

        if conflict.confidence < 0.5:
            # 低置信度：保留新记忆，不处理旧记忆
            return new_entry, []

        # 高置信度：新记忆取代旧记忆
        to_archive = [conflict.conflicting_memory_id]

        if conflict.conflict_type == "supersede":
            # 取代：旧记忆归档，新记忆保留
            self._resolved_count += 1
            return new_entry, to_archive
        elif conflict.conflict_type == "refinement":
            # 细化：合并新旧记忆，旧记忆归档
            old_entry = None
            for e in existing_entries:
                if e.id == conflict.conflicting_memory_id:
                    old_entry = e
                    break
            if old_entry:
                merged = self._merge_content(old_entry.content, new_entry.content)
                new_entry.content = merged
            self._resolved_count += 1
            return new_entry, to_archive
        elif conflict.conflict_type == "contradiction":
            # 矛盾：新记忆取代旧记忆
            self._resolved_count += 1
            return new_entry, to_archive

        return new_entry, []

    # =========================================================================
    # 批量合并
    # =========================================================================

    def consolidate(
        self,
        max_entries: int | None = None,
        threshold: float | None = None,
    ) -> dict[str, int]:
        """执行批量记忆合并。

        流程:
            1. 获取最近的活跃记忆
            2. 查找相似记忆对
            3. 合并相似记忆
            4. 删除旧记忆，添加新记忆
            5. 返回统计信息

        Args:
            max_entries: 最多处理的记忆数，默认 MAX_CONSOLIDATE_PER_RUN。
            threshold: 相似度阈值。

        Returns:
            包含合并统计的字典: {"merged": int, "pairs_found": int, "errors": int}。
        """
        max_entries = max_entries or self.MAX_CONSOLIDATE_PER_RUN
        threshold = threshold or self.SIMILARITY_THRESHOLD

        # 1. 获取最近的活跃记忆
        entries = self._store.get_recent_entries(max_entries)
        if len(entries) < 2:
            self._log.debug("[合并] 记忆数不足，跳过合并")
            return {"merged": 0, "pairs_found": 0, "errors": 0}

        # 2. 查找相似记忆对
        pairs = self.find_similar_pairs(entries, threshold)
        if not pairs:
            self._log.debug("[合并] 未找到相似记忆对")
            return {"merged": 0, "pairs_found": 0, "errors": 0}

        self._log.info(f"[合并] 找到 {len(pairs)} 对相似记忆")

        # 3. 合并相似记忆（去重：每条记忆只参与一次合并）
        merged_ids: set[str] = set()
        merged_count = 0
        error_count = 0

        for entry_a, entry_b, sim in pairs:
            if entry_a.id in merged_ids or entry_b.id in merged_ids:
                continue  # 已参与合并，跳过

            try:
                new_entry = self.merge_pair(entry_a, entry_b, sim)
                if new_entry is None:
                    continue

                # 标记旧记忆为 archived
                self._store.mark_archived([entry_a.id, entry_b.id])

                # 添加新记忆
                self._store.add(new_entry)

                merged_ids.add(entry_a.id)
                merged_ids.add(entry_b.id)
                merged_count += 1

            except Exception as e:
                self._log.error(f"[合并] 合并失败: {e}")
                error_count += 1

        self._consolidation_count += 1
        self._merged_count += merged_count

        self._log.info(
            f"[合并] 完成: 合并 {merged_count} 对记忆, "
            f"错误 {error_count} 对, "
            f"累计合并 {self._merged_count} 条"
        )

        return {
            "merged": merged_count,
            "pairs_found": len(pairs),
            "errors": error_count,
        }

    # =========================================================================
    # 统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取合并器统计信息。

        Returns:
            包含合并计数和阈值的字典。
        """
        return {
            "consolidation_count": self._consolidation_count,
            "merged_count": self._merged_count,
            "resolved_count": self._resolved_count,
            "similarity_threshold": self.SIMILARITY_THRESHOLD,
            "interval": self.CONSOLIDATION_INTERVAL,
            "max_per_run": self.MAX_CONSOLIDATE_PER_RUN,
        }