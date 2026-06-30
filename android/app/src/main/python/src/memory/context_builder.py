"""记忆上下文窗口构建器。

负责为 LLM 对话构建最优的记忆上下文，在有限的 Token 预算内
最大化注入相关记忆的信息量。

核心功能：
    1. 多策略记忆检索（向量 + BM25 + 关键词）
    2. 按重要性、时效性、相关性三维排序
    3. Token 预算管理（自动截断超长记忆）
    4. 上下文模板格式化（适配不同的 System Prompt 风格）
    5. 分层记忆注入（核心记忆 + 扩展记忆 + 背景记忆）

核心类:
    - ContextBuilder: 上下文构建器
    - ContextConfig: 上下文构建配置
    - MemoryContext: 构建完成的记忆上下文

依赖:
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.retriever: MemoryRetriever
    - src.memory.decay: get_weight
    - src.utils.logger: get_logger
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from src.memory.decay import get_weight
from src.memory.retriever import MemoryRetriever
from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.text_utils import text_similarity


# =============================================================================
# 配置
# =============================================================================

@dataclass
class ContextConfig:
    """上下文构建配置。

    Attributes:
        max_tokens: 记忆上下文的最大 Token 数（默认 800）。
        chars_per_token: 中文字符到 Token 的估算比例（默认 1.5）。
        core_memory_ratio: 核心记忆占总预算的比例（默认 0.5）。
        extended_memory_ratio: 扩展记忆占总预算的比例（默认 0.3）。
        recent_memory_ratio: 最近记忆占总预算的比例（默认 0.2）。
        max_per_memory_chars: 单条记忆的最大字符数（默认 200）。
        max_core_count: 核心记忆最大条数（默认 5）。
        max_extended_count: 扩展记忆最大条数（默认 10）。
        max_recent_count: 最近记忆最大条数（默认 5）。
        include_timestamps: 是否在上下文中包含时间戳（默认 False）。
        include_importance: 是否在上下文中包含重要性标记（默认 False）。
        include_types: 是否在上下文中包含记忆类型（默认 False）。
        dedup_threshold: 记忆去重相似度阈值（默认 0.8）。
    """
    max_tokens: int = 800
    chars_per_token: float = 1.5
    core_memory_ratio: float = 0.5
    extended_memory_ratio: float = 0.3
    recent_memory_ratio: float = 0.2
    max_per_memory_chars: int = 200
    max_core_count: int = 5
    max_extended_count: int = 10
    max_recent_count: int = 5
    include_timestamps: bool = False
    include_importance: bool = False
    include_types: bool = False
    dedup_threshold: float = 0.8


# =============================================================================
# 上下文结果
# =============================================================================

@dataclass
class MemoryContext:
    """构建完成的记忆上下文。

    Attributes:
        formatted_text: 格式化后的记忆文本（可直接注入 System Prompt）。
        core_memories: 核心记忆列表。
        extended_memories: 扩展记忆列表。
        recent_memories: 最近记忆列表。
        total_chars: 上下文总字符数。
        estimated_tokens: 估算 Token 数。
        budget_used_ratio: Token 预算使用率。
        build_time_ms: 构建耗时（毫秒）。
    """
    formatted_text: str = ""
    core_memories: list[MemoryEntry] = field(default_factory=list)
    extended_memories: list[MemoryEntry] = field(default_factory=list)
    recent_memories: list[MemoryEntry] = field(default_factory=list)
    total_chars: int = 0
    estimated_tokens: int = 0
    budget_used_ratio: float = 0.0
    build_time_ms: float = 0.0


# =============================================================================
# 上下文构建器
# =============================================================================

class ContextBuilder:
    """记忆上下文窗口构建器。

    为 LLM 对话构建最优的记忆上下文，在有限的 Token 预算内
    最大化注入相关记忆的信息量。

    分层策略:
        1. 核心记忆（Core）：与当前查询最相关的记忆，占预算 50%
        2. 扩展记忆（Extended）：次相关但重要的记忆，占预算 30%
        3. 最近记忆（Recent）：最近产生的记忆，占预算 20%

    排序策略:
        - 相关性分数（向量相似度）: 权重 0.5
        - 重要性分数: 权重 0.3
        - 时间衰减权重: 权重 0.2
    """

    def __init__(
        self,
        vector_store: VectorStore,
        retriever: MemoryRetriever,
        config: ContextConfig | None = None,
    ) -> None:
        """初始化上下文构建器。

        Args:
            vector_store: 向量存储实例。
            retriever: 记忆检索器实例。
            config: 上下文构建配置。
        """
        self._store = vector_store
        self._retriever = retriever
        self._config = config or ContextConfig()
        self._log = get_logger()
        self._log.info("ContextBuilder 初始化完成")

    # =========================================================================
    # 主构建方法
    # =========================================================================

    def build(
        self,
        query_text: str,
        conversation_history: list[str] | None = None,
        user_profile: dict[str, Any] | None = None,
    ) -> MemoryContext:
        """构建记忆上下文。

        流程:
            1. 检索核心记忆（与查询最相关）
            2. 检索扩展记忆（重要但非核心）
            3. 获取最近记忆
            4. 去重合并
            5. 按 Token 预算截断
            6. 格式化输出

        Args:
            query_text: 用户当前查询文本。
            conversation_history: 最近的对话历史（用于提取上下文关键词）。
            user_profile: 用户画像（用于个性化记忆选择）。

        Returns:
            MemoryContext，包含格式化后的记忆上下文。
        """
        import time
        start_time = time.time()

        max_chars = int(self._config.max_tokens * self._config.chars_per_token)

        # 1. 检索核心记忆
        core_memories = self._retrieve_core(query_text, conversation_history)

        # 2. 检索扩展记忆
        core_ids = {m.id for m in core_memories}
        extended_memories = self._retrieve_extended(
            query_text, core_ids, user_profile
        )

        # 3. 获取最近记忆
        recent_ids = core_ids | {m.id for m in extended_memories}
        recent_memories = self._get_recent_memories(recent_ids)

        # 4. 去重
        all_memories = self._deduplicate(
            core_memories + extended_memories + recent_memories
        )

        # 5. 三层排序：相关性 + 重要性 + 时效性
        scored = self._rank_memories(all_memories, query_text)

        # 6. 按 Token 预算截断
        selected = self._truncate_by_budget(scored, max_chars)

        # 7. 格式化
        formatted = self._format_context(selected)

        # 8. 构建结果
        total_chars = len(formatted)
        estimated_tokens = int(total_chars / self._config.chars_per_token)

        elapsed_ms = (time.time() - start_time) * 1000

        context = MemoryContext(
            formatted_text=formatted,
            core_memories=[m for m in selected if m in core_memories],
            extended_memories=[m for m in selected if m in extended_memories],
            recent_memories=[m for m in selected if m in recent_memories],
            total_chars=total_chars,
            estimated_tokens=estimated_tokens,
            budget_used_ratio=min(estimated_tokens / max(self._config.max_tokens, 1), 1.0),
            build_time_ms=round(elapsed_ms, 1),
        )

        self._log.info(
            f"[上下文构建] 完成: 核心={len(context.core_memories)}, "
            f"扩展={len(context.extended_memories)}, "
            f"最近={len(context.recent_memories)}, "
            f"总字符={total_chars}, 估算Token={estimated_tokens}, "
            f"耗时={elapsed_ms:.1f}ms"
        )

        return context

    # =========================================================================
    # 分层检索
    # =========================================================================

    def _retrieve_core(
        self,
        query_text: str,
        conversation_history: list[str] | None = None,
    ) -> list[MemoryEntry]:
        """检索核心记忆：与当前查询最相关的记忆。

        使用多策略检索（向量 + BM25 + 关键词），返回最相关的记忆。
        """
        try:
            # 使用多策略检索
            entries = self._retriever.multi_strategy_retrieve(
                query_text,
                top_k=self._config.max_core_count * 2,  # 检索 2 倍，后续排序
            )
            return entries[:self._config.max_core_count]
        except Exception as e:
            self._log.warning(f"[上下文构建] 核心记忆检索失败: {e}")
            return []

    def _retrieve_extended(
        self,
        query_text: str,
        exclude_ids: set[str],
        user_profile: dict[str, Any] | None = None,
    ) -> list[MemoryEntry]:
        """检索扩展记忆：次相关但重要的记忆。

        策略:
            1. 获取重要记忆（importance > 0.6）
            2. 如果有用户画像，获取与用户偏好相关的记忆
            3. 排除核心记忆已包含的
        """
        entries: list[MemoryEntry] = []
        exclude = exclude_ids or set()

        try:
            # 1. 重要记忆
            important = self._retriever.get_important(
                min_importance=0.6,
                top_k=self._config.max_extended_count,
            )
            for e in important:
                if e.id not in exclude:
                    entries.append(e)
                    exclude.add(e.id)

            # 2. 用户画像相关记忆（如果提供）
            if user_profile and len(entries) < self._config.max_extended_count:
                interests = user_profile.get("interests", [])
                if interests:
                    # 用兴趣关键词检索
                    interest_query = " ".join(interests[:5])
                    try:
                        interest_entries = self._retriever.retrieve(
                            interest_query,
                            top_k=self._config.max_extended_count - len(entries),
                        )
                        for e in interest_entries:
                            if e.id not in exclude:
                                entries.append(e)
                                exclude.add(e.id)
                    except Exception:
                        self._log.debug(f"[上下文构建] 兴趣检索 '{keyword}' 失败，跳过")

        except Exception as e:
            self._log.warning(f"[上下文构建] 扩展记忆检索失败: {e}")

        return entries[:self._config.max_extended_count]

    def _get_recent_memories(
        self,
        exclude_ids: set[str],
    ) -> list[MemoryEntry]:
        """获取最近产生的记忆。"""
        try:
            recent = self._retriever.get_recent(
                top_k=self._config.max_recent_count * 2
            )
            return [
                e for e in recent
                if e.id not in (exclude_ids or set())
            ][:self._config.max_recent_count]
        except Exception as e:
            self._log.warning(f"[上下文构建] 最近记忆获取失败: {e}")
            return []

    # =========================================================================
    # 去重
    # =========================================================================

    def _deduplicate(
        self,
        entries: list[MemoryEntry],
    ) -> list[MemoryEntry]:
        """去除内容高度相似的记忆。

        使用 bigram Jaccard 相似度进行去重，保留重要性更高的那条。
        """
        if len(entries) <= 1:
            return entries

        kept: list[MemoryEntry] = []
        for entry in entries:
            is_dup = False
            for existing in kept:
                sim = text_similarity(entry.content, existing.content)
                if sim >= self._config.dedup_threshold:
                    # 保留重要性更高的
                    if entry.importance > existing.importance:
                        kept.remove(existing)
                        kept.append(entry)
                    is_dup = True
                    break
            if not is_dup:
                kept.append(entry)

        return kept

    # =========================================================================
    # 排序
    # =========================================================================

    def _rank_memories(
        self,
        entries: list[MemoryEntry],
        query_text: str,
    ) -> list[tuple[MemoryEntry, float]]:
        """三维排序：相关性 + 重要性 + 时效性。

        排序权重:
            - 相关性（与查询的关键词匹配度）: 0.5
            - 重要性（记忆自身的 importance）: 0.3
            - 时效性（时间衰减权重）: 0.2
        """
        if not entries:
            return []

        now = datetime.now(tz=timezone.utc)
        scored: list[tuple[MemoryEntry, float]] = []

        for entry in entries:
            # 相关性分数（基于关键词匹配）
            relevance = self._calculate_relevance(entry, query_text)

            # 重要性分数
            importance = entry.importance

            # 时效性分数（时间衰减权重）
            try:
                timeliness = get_weight(entry, now)
            except Exception:
                timeliness = entry.decay_factor

            # 综合分数
            score = (
                relevance * 0.5 +
                importance * 0.3 +
                timeliness * 0.2
            )
            scored.append((entry, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return scored

    def _calculate_relevance(
        self,
        entry: MemoryEntry,
        query_text: str,
    ) -> float:
        """计算记忆与查询文本的相关性。

        基于关键词匹配度计算。
        """
        if not query_text or not entry.keywords:
            return 0.5  # 无法计算时给中等分数

        # 提取查询关键词
        from src.memory.vector_store import extract_keywords
        query_kw = set(extract_keywords(query_text))
        entry_kw = set(entry.keywords)

        if not query_kw:
            return 0.5

        # Jaccard 相似度
        intersection = len(query_kw & entry_kw)
        union = len(query_kw | entry_kw)

        return intersection / max(union, 1)

    # =========================================================================
    # Token 预算管理
    # =========================================================================

    def _truncate_by_budget(
        self,
        scored: list[tuple[MemoryEntry, float]],
        max_chars: int,
    ) -> list[MemoryEntry]:
        """按 Token 预算截断记忆列表。

        按分数从高到低选择记忆，直到达到字符数上限。
        单条记忆超过 max_per_memory_chars 时会被截断。
        """
        selected: list[MemoryEntry] = []
        current_chars = 0

        for entry, score in scored:
            content = entry.content
            # 截断过长记忆
            if len(content) > self._config.max_per_memory_chars:
                content = content[:self._config.max_per_memory_chars - 3] + "..."

            # 检查预算
            if current_chars + len(content) > max_chars:
                # 如果还有空间，尝试截断更短
                available = max_chars - current_chars
                if available > 30:  # 至少保留 30 个字符
                    content = content[:available - 3] + "..."
                else:
                    break  # 空间不足，停止添加

            selected.append(entry)
            current_chars += len(content)

        return selected

    # =========================================================================
    # 格式化
    # =========================================================================

    def _format_context(self, entries: list[MemoryEntry]) -> str:
        """将记忆列表格式化为注入 System Prompt 的文本。

        格式:
            [核心记忆]
            - 用户叫李明，住在北京

            [重要记忆]
            - 用户喜欢猫
            - 用户每周三去健身房

            [最近记忆]
            - 用户昨天去了公园
        """
        if not entries:
            return ""

        # 分层显示
        formatted: list[str] = []

        # 按记忆来源分组
        core: list[str] = []
        extended: list[str] = []
        recent: list[str] = []

        for entry in entries:
            line = self._format_single_memory(entry)
            # 按重要性分层
            if entry.importance >= 0.7:
                core.append(line)
            elif entry.importance >= 0.4:
                extended.append(line)
            else:
                recent.append(line)

        if core:
            formatted.append("[核心记忆]")
            formatted.extend(f"- {line}" for line in core)

        if extended:
            if formatted:
                formatted.append("")
            formatted.append("[重要记忆]")
            formatted.extend(f"- {line}" for line in extended)

        if recent:
            if formatted:
                formatted.append("")
            formatted.append("[最近记忆]")
            formatted.extend(f"- {line}" for line in recent)

        return "\n".join(formatted)

    def _format_single_memory(self, entry: MemoryEntry) -> str:
        """格式化单条记忆。

        根据配置决定是否包含时间戳、重要性、类型标记。
        """
        parts: list[str] = []

        if self._config.include_types:
            type_label = {
                "user_fact": "[事实]",
                "user_profile": "[画像]",
                "user_identity": "[身份]",
                "user_preference": "[偏好]",
                "user_attribute": "[属性]",
                "user_relationship": "[关系]",
                "user_status": "[状态]",
                "episodic": "[事件]",
                "episodic_event": "[事件]",
                "episodic_experience": "[经历]",
                "episodic_activity": "[活动]",
                "semantic": "[知识]",
                "semantic_knowledge": "[知识]",
                "semantic_opinion": "[观点]",
                "semantic_concept": "[概念]",
                "emotional": "[情感]",
                "emotional_state": "[情感]",
                "emotional_mood": "[情绪]",
                "emotional_sentiment": "[情感]",
                "summary": "[摘要]",
            }.get(entry.memory_type, "")
            if type_label:
                parts.append(type_label)

        if self._config.include_importance:
            parts.append(f"[重要度:{entry.importance:.1f}]")

        parts.append(entry.content)

        if self._config.include_timestamps and entry.created_at:
            parts.append(f"({entry.created_at[:10]})")

        return " ".join(parts)

    # =========================================================================
    # 快速构建（简化接口）
    # =========================================================================

    def build_compact(
        self,
        query_text: str,
        max_memories: int = 5,
    ) -> str:
        """快速构建紧凑的记忆上下文。

        仅返回最相关的记忆文本，无分层标记。

        Args:
            query_text: 查询文本。
            max_memories: 最大记忆数。

        Returns:
            格式化的记忆文本。
        """
        try:
            entries = self._retriever.multi_strategy_retrieve(
                query_text,
                top_k=max_memories,
            )
            if not entries:
                return ""

            lines = [entry.content for entry in entries[:max_memories]]
            return "\n".join(f"- {line}" for line in lines)
        except Exception as e:
            self._log.warning(f"[快速构建] 失败: {e}")
            return ""

    # =========================================================================
    # Token 估算
    # =========================================================================

    @staticmethod
    def estimate_tokens(text: str, chars_per_token: float = 1.5) -> int:
        """估算文本的 Token 数。

        中文 1 个字符约等于 1.5~2 个 Token（取决于 tokenizer）。
        英文 1 个单词约等于 1.3 个 Token。

        Args:
            text: 待估算的文本。
            chars_per_token: 中文字符到 Token 的比例。

        Returns:
            估算的 Token 数。
        """
        if not text:
            return 0

        # 分别统计中英文字符
        import re
        chinese_chars = len(re.findall(r"[\u4e00-\u9fff]", text))
        other_chars = len(text) - chinese_chars

        # 中文按 chars_per_token 估算，英文按单词估算
        chinese_tokens = chinese_chars / chars_per_token
        english_words = len(re.findall(r"[a-zA-Z]+", text))
        english_tokens = english_words * 1.3

        return int(chinese_tokens + english_tokens)