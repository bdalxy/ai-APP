"""记忆归档/摘要模块。

当记忆数量超过阈值时，自动将旧记忆压缩为摘要，减少存储压力。
归档后的记忆在检索时应用 0.2 倍权重衰减。

核心类：
    - MemoryArchiver: 记忆归档器

依赖：
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.api_client.deepseek: DeepSeekClient（用于 LLM 摘要生成）
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import threading

from src.api_client.deepseek import DeepSeekClient
from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


class MemoryArchiver:
    """记忆归档器。

    当记忆总数超过阈值时触发归档，将旧记忆压缩为摘要。
    归档后的记忆标记为 archived=1，检索时应用 0.2 倍权重。

    Attributes:
        ARCHIVE_THRESHOLD: 触发归档的记忆数阈值（默认 500）。
        BATCH_SIZE: 每批压缩的记忆数（默认 50）。
        SUMMARY_COUNT: 每批生成的摘要数（默认 3）。
        ARCHIVED_WEIGHT: 归档记忆的检索权重（默认 0.2）。
    """

    ARCHIVE_THRESHOLD: int = 500
    BATCH_SIZE: int = 50
    SUMMARY_COUNT: int = 3
    ARCHIVED_WEIGHT: float = 0.2

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: DeepSeekClient | None = None,
    ) -> None:
        """初始化记忆归档器。

        Args:
            vector_store: 向量存储实例。
            deepseek_client: DeepSeek API 客户端（可选，用于 LLM 摘要生成）。
                             为 None 时使用规则摘要（不调用 API）。
        """
        self._store = vector_store
        self._client = deepseek_client
        self._lock = threading.Lock()
        self._log = get_logger()
        self._archived_count = 0
        self._log.info("MemoryArchiver 初始化完成")

    # =========================================================================
    # 归档触发判断
    # =========================================================================

    def should_archive(self) -> bool:
        """检查是否应该触发归档。

        Returns:
            True 如果当前记忆总数超过阈值，否则 False。
        """
        try:
            count = self._store.count()
            return count > self.ARCHIVE_THRESHOLD
        except Exception as e:
            self._log.warning(f"[归档] 检查记忆数失败: {e}")
            return False

    # =========================================================================
    # 归档主流程
    # =========================================================================

    def archive(self) -> int:
        """执行记忆归档。

        流程：
            1. 检查是否超过阈值
            2. 获取最旧的未归档记忆
            3. 生成摘要（LLM 或规则模式）
            4. 标记原记忆为已归档
            5. 将摘要存储为新记忆

        Returns:
            成功归档的记忆条数。
        """
        if not self.should_archive():
            self._log.debug("[归档] 记忆数未超过阈值，跳过")
            return 0

        # 1. 获取旧记忆（锁内，仅数据访问）
        with self._lock:
            try:
                old_memories = self._store.get_oldest_unarchived(self.BATCH_SIZE)
                if not old_memories:
                    self._log.debug("[归档] 没有可归档的旧记忆")
                    return 0
            except Exception as e:
                self._log.error(f"[归档] 获取旧记忆失败: {e}")
                return 0

        self._log.info(f"[归档] 开始归档 {len(old_memories)} 条旧记忆")

        # 2. 生成摘要（锁外，可能调用 LLM API，避免长时间持锁）
        summaries = self._generate_summaries(old_memories)
        if not summaries:
            self._log.warning("[归档] 摘要生成失败，跳过本次归档")
            return 0

        # 3. 标记 + 存储（锁内，数据操作）
        with self._lock:
            try:
                archived_count = self._store.mark_archived(
                    [m.id for m in old_memories]
                )
                self._log.info(f"[归档] 已标记 {archived_count} 条记忆为已归档")

                now = format_timestamp_iso()
                for summary in summaries:
                    entry = MemoryEntry(
                        memory_type="summary",
                        content=summary,
                        importance=0.8,
                        created_at=now,
                        last_accessed=now,
                    )
                    self._store.add(entry)

                self._archived_count += archived_count
                self._log.info(
                    f"[归档] 完成: 归档 {archived_count} 条旧记忆, "
                    f"生成 {len(summaries)} 条摘要, "
                    f"累计归档 {self._archived_count} 条"
                )
                return archived_count

            except Exception as e:
                self._log.error(f"[归档] 归档过程异常: {e}")
                return 0

    # =========================================================================
    # 摘要生成
    # =========================================================================

    def _generate_summaries(self, memories) -> list[str]:
        """将一批旧记忆压缩为摘要。

        优先使用 LLM 模式，失败时回退到规则模式。

        Args:
            memories: MemoryEntry 列表。

        Returns:
            摘要文本列表。
        """
        if self._client is not None:
            try:
                return self._llm_summarize(memories)
            except Exception as e:
                self._log.warning(
                    f"[归档] LLM 摘要生成失败，回退到规则模式: {e}"
                )

        return self._rule_summarize(memories)

    def _llm_summarize(self, memories) -> list[str]:
        """使用 LLM 将记忆压缩为摘要。

        Args:
            memories: MemoryEntry 列表。

        Returns:
            摘要文本列表。
        """
        # 构建记忆文本列表
        memory_texts = []
        for i, mem in enumerate(memories, 1):
            memory_texts.append(f"{i}. [{mem.memory_type}] {mem.content}")

        prompt = f"""你是一个记忆摘要助手。请将以下 {len(memories)} 条记忆压缩为 {self.SUMMARY_COUNT} 条简洁的摘要。

要求：
1. 每条摘要不超过 50 个字
2. 保留关键信息（人名、事件、偏好、重要事实）
3. 合并相似或相关的记忆
4. 用客观的第三人称描述

记忆列表：
{chr(10).join(memory_texts)}

请只输出 {self.SUMMARY_COUNT} 条摘要，每行一条，不要编号，不要其他内容。"""

        messages = [{"role": "user", "content": prompt}]
        response = self._client.chat(messages, temperature=0.3, max_tokens=500)

        # 解析摘要
        lines = response.content.strip().split("\n")
        summaries = [
            line.strip()
            for line in lines
            if line.strip() and not line.strip().startswith(("1.", "2.", "3.", "4.", "5.", "-", "*"))
        ]

        # 去重并限制数量
        seen = set()
        unique = []
        for s in summaries:
            if s not in seen and len(s) >= 5:
                seen.add(s)
                unique.append(s)
                if len(unique) >= self.SUMMARY_COUNT:
                    break

        self._log.debug(
            f"[归档] LLM 摘要: {len(memories)} 条记忆 → {len(unique)} 条摘要"
        )
        return unique

    def _rule_summarize(self, memories) -> list[str]:
        """规则模式摘要生成（不调用 API）。

        按记忆类型分组，提取关键词最多的内容作为摘要。

        Args:
            memories: MemoryEntry 列表。

        Returns:
            摘要文本列表。
        """
        # 按类型分组
        by_type: dict[str, list] = {}
        for mem in memories:
            t = mem.memory_type
            if t not in by_type:
                by_type[t] = []
            by_type[t].append(mem)

        summaries = []
        for mem_type, mems in by_type.items():
            if not summaries:
                # 从每种类型中选一条代表性记忆
                # 优先选择重要性高、内容较长的
                mems.sort(key=lambda m: (m.importance, len(m.content)), reverse=True)
                best = mems[0]
                prefix = {
                    "episodic": "曾经历",
                    "semantic": "了解",
                    "user_fact": "用户",
                    "summary": "总结",
                }.get(mem_type, "记忆")
                summaries.append(f"[{prefix}] {best.content[:80]}")

            if len(summaries) >= self.SUMMARY_COUNT:
                break

        self._log.debug(
            f"[归档] 规则摘要: {len(memories)} 条记忆 → {len(summaries)} 条摘要"
        )
        return summaries

    # =========================================================================
    # 统计
    # =========================================================================

    def get_stats(self) -> dict:
        """获取归档统计信息。

        Returns:
            包含归档计数和阈值的字典。
        """
        return {
            "archived_count": self._archived_count,
            "threshold": self.ARCHIVE_THRESHOLD,
            "batch_size": self.BATCH_SIZE,
            "summary_count": self.SUMMARY_COUNT,
            "archived_weight": self.ARCHIVED_WEIGHT,
        }