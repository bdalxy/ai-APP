"""记忆摘要链模块。

定期将多条相关的 episodic 记忆压缩为一条 summary 类型记忆，
减少记忆库冗余，保持长期记忆的简洁和可读性。

三层摘要架构：
    1. 单轮摘要：将同一轮对话的多条记忆合并为一条摘要
    2. 会话摘要：将同一主题的多轮对话摘要合并为会话摘要
    3. 主题摘要：将多个会话摘要合并为跨会话的主题摘要

触发条件：
    - 同类型记忆超过 20 条
    - 距离上次摘要超过 50 轮对话
    - 手动调用触发

核心类：
    - MemorySummarizer: 记忆摘要器

依赖：
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.api_client.deepseek: DeepSeekClient（LLM 摘要生成）
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

import json
import re
from typing import Any

from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


# =============================================================================
# 摘要 LLM Prompt
# =============================================================================

_SINGLE_ROUND_SUMMARY_PROMPT = """你是一个记忆摘要助手。请将以下多条记忆合并为一条简洁的摘要记忆。

规则：
1. 保留所有关键信息（人名、地点、事件、数字、日期）
2. 合并冗余内容，删除重复信息
3. 使用第三人称，如"用户..."、"AI角色..."
4. 输出为一条完整的摘要句子

原始记忆：
{memories}

请只输出一条摘要文本，不要包含任何其他内容。"""

_SESSION_SUMMARY_PROMPT = """你是一个记忆摘要助手。请将以下多条摘要合并为一条会话级别的摘要。

规则：
1. 提取共同主题
2. 保留所有关键信息（人名、地点、事件、数字、日期）
3. 删除冗余，合并相关内容
4. 使用第三人称

输入摘要：
{summaries}

请只输出一条摘要文本，不要包含任何其他内容。"""

_TOPIC_SUMMARY_PROMPT = """你是一个记忆摘要助手。请将以下会话摘要合并为一条主题级别的摘要。

规则：
1. 识别跨会话的核心主题
2. 提取最重要的信息（人名、关键事件、重要观点）
3. 高度概括，删除细节
4. 使用第三人称

输入摘要：
{summaries}

请只输出一条摘要文本，不要包含任何其他内容。"""


# =============================================================================
# 摘要状态管理
# =============================================================================

# 摘要状态存储在向量存储同一 SQLite 中的 summary_state 表
_SUMMARY_STATE_TABLE = "summary_state"

_CREATE_SUMMARY_STATE_SQL = f"""
CREATE TABLE IF NOT EXISTS {_SUMMARY_STATE_TABLE} (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL DEFAULT '{{}}'
)
"""


class MemorySummarizer:
    """记忆摘要器。

    定期将多条相关记忆压缩为摘要，减少记忆库冗余。

    触发条件：
        - 同类型记忆超过 SUMMARY_THRESHOLD 条（默认 20）
        - 距离上次摘要超过 TURN_THRESHOLD 轮（默认 50）

    Attributes:
        _store: 向量存储实例。
        _client: DeepSeek API 客户端（可选，用于 LLM 摘要）。
        _summary_count: 累计生成的摘要数。
    """

    # 摘要触发阈值
    SUMMARY_THRESHOLD: int = 20       # 同类型记忆超过此数触发摘要
    TURN_THRESHOLD: int = 50          # 距上次摘要超过此轮数触发摘要

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: Any = None,
    ) -> None:
        """初始化记忆摘要器。

        Args:
            vector_store: 向量存储实例。
            deepseek_client: DeepSeek API 客户端（可选）。
        """
        self._store = vector_store
        self._client = deepseek_client
        self._log = get_logger()
        self._summary_count: int = 0
        self._initialized = False
        self._log.info("MemorySummarizer 初始化完成")

    # =========================================================================
    # 状态管理
    # =========================================================================

    def _ensure_state_table(self) -> None:
        """确保摘要状态表存在。"""
        if self._initialized:
            return
        try:
            with self._store.lock:
                conn = self._store._conn
                conn.execute(_CREATE_SUMMARY_STATE_SQL)
                conn.commit()
            self._initialized = True
        except Exception as e:
            self._log.warning(f"[摘要] 状态表初始化失败: {e}")

    def _get_state(self, key: str, default: Any = None) -> Any:
        """获取摘要状态。"""
        self._ensure_state_table()
        try:
            conn = self._store._conn
            row = conn.execute(
                f"SELECT value FROM {_SUMMARY_STATE_TABLE} WHERE key = ?",
                (key,),
            ).fetchone()
            if row:
                return json.loads(row[0])
            return default
        except Exception:
            return default

    def _set_state(self, key: str, value: Any) -> None:
        """设置摘要状态。"""
        self._ensure_state_table()
        try:
            with self._store.lock:
                conn = self._store._conn
                conn.execute(
                    f"INSERT OR REPLACE INTO {_SUMMARY_STATE_TABLE} (key, value) VALUES (?, ?)",
                    (key, json.dumps(value, ensure_ascii=False)),
                )
                conn.commit()
        except Exception as e:
            self._log.debug(f"[摘要] 状态写入失败: {e}")

    # =========================================================================
    # 摘要触发判断
    # =========================================================================

    def should_summarize(self, turn_count: int) -> bool:
        """判断是否应该触发摘要。

        Args:
            turn_count: 当前对话轮次。

        Returns:
            True 如果应该触发摘要。
        """
        # 检查同类型记忆数
        types_to_check = ("episodic", "semantic")
        for mem_type in types_to_check:
            try:
                entries = self._store.get_by_type(mem_type)
                if len(entries) >= self.SUMMARY_THRESHOLD:
                    self._log.info(
                        f"[摘要] 类型 '{mem_type}' 记忆数 {len(entries)} >= {self.SUMMARY_THRESHOLD}，触发摘要"
                    )
                    return True
            except Exception:
                pass

        # 检查距上次摘要的轮数
        last_summary_turn = self._get_state("last_summary_turn", 0)
        if turn_count - last_summary_turn >= self.TURN_THRESHOLD:
            self._log.info(
                f"[摘要] 距上次摘要已 {turn_count - last_summary_turn} 轮 >= {self.TURN_THRESHOLD}，触发摘要"
            )
            return True

        return False

    # =========================================================================
    # 摘要生成
    # =========================================================================

    def generate_summaries(
        self,
        turn_count: int,
        max_memories: int = 30,
    ) -> dict[str, int]:
        """执行摘要生成。

        流程：
            1. 检查是否需要摘要
            2. 对每种类型执行单轮摘要
            3. 更新摘要状态

        Args:
            turn_count: 当前对话轮次。
            max_memories: 最多处理的记忆数。

        Returns:
            统计字典: {"summaries_generated": int, "memories_archived": int}。
        """
        if not self._client:
            self._log.info("[摘要] 无 LLM 客户端，跳过摘要")
            return {"summaries_generated": 0, "memories_archived": 0}

        summaries = 0
        archived = 0

        for mem_type in ("episodic", "semantic"):
            try:
                entries = self._store.get_by_type(mem_type)
                if len(entries) < 10:  # 太少不值得摘要
                    continue

                # 取最近的 N 条记忆做摘要
                recent = entries[-max_memories:]
                result = self._summarize_batch(recent, mem_type, turn_count)
                summaries += result["summaries"]
                archived += result["archived"]
            except Exception as e:
                self._log.warning(f"[摘要] 类型 '{mem_type}' 摘要失败: {e}")

        # 更新摘要状态
        self._set_state("last_summary_turn", turn_count)
        self._summary_count += summaries

        self._log.info(
            f"[摘要] 完成: 生成 {summaries} 条摘要, "
            f"归档 {archived} 条原始记忆"
        )
        return {
            "summaries_generated": summaries,
            "memories_archived": archived,
        }

    def _summarize_batch(
        self,
        entries: list[MemoryEntry],
        mem_type: str,
        turn_count: int,
    ) -> dict[str, int]:
        """对一批记忆执行摘要。

        每 15 条记忆为一组，生成一条摘要。
        原始记忆标记为 archived。

        Args:
            entries: 待摘要的记忆列表。
            mem_type: 记忆类型。
            turn_count: 当前轮次。

        Returns:
            统计字典。
        """
        batch_size = 15  # 每 15 条生成一条摘要
        summaries = 0
        archived = 0

        for i in range(0, len(entries), batch_size):
            batch = entries[i : i + batch_size]
            if len(batch) < 5:  # 最后一批太少，跳过
                break

            # 构建记忆文本
            memory_texts = [
                f"{j+1}. {e.content}"
                for j, e in enumerate(batch)
            ]
            memory_text = "\n".join(memory_texts)

            # 调用 LLM 生成摘要
            try:
                summary_content = self._generate_summary_by_llm(
                    memory_text, _SINGLE_ROUND_SUMMARY_PROMPT
                )
                if not summary_content:
                    continue

                # 创建摘要记忆
                now = format_timestamp_iso()
                summary_entry = MemoryEntry(
                    memory_type="summary",
                    content=summary_content,
                    importance=max(
                        sum(e.importance for e in batch) / len(batch),
                        0.5,
                    ),
                    created_at=now,
                    last_accessed=now,
                    source_turn_id=f"summary_{turn_count}_{mem_type}_{i}",
                    source="other",
                )

                # 在事务中原子性地归档原始记忆 + 插入摘要
                with self._store.transaction():
                    archive_ids = [e.id for e in batch]
                    self._store._mark_archived_no_commit(archive_ids)
                    self._store._prepare_entry_for_insert(summary_entry)
                    self._store._add_no_commit(summary_entry)

                summaries += 1
                archived += len(batch)

                self._log.info(
                    f"[摘要] 类型 '{mem_type}': {len(batch)} 条 -> 1 条摘要"
                )
            except Exception as e:
                self._log.warning(f"[摘要] LLM 摘要生成失败: {e}")
                continue

        return {"summaries": summaries, "archived": archived}

    def _generate_summary_by_llm(
        self, memory_text: str, prompt_template: str
    ) -> str:
        """调用 LLM 生成摘要。

        Args:
            memory_text: 记忆文本。
            prompt_template: 提示词模板。

        Returns:
            摘要文本，失败返回空字符串。
        """
        if not self._client:
            return ""
        prompt = prompt_template.replace("{memories}", memory_text)
        response = self._client.chat(
            messages=[{"role": "user", "content": prompt}],
            temperature=0.5,
            max_tokens=800,
        )
        content = response.content.strip()
        # 清理可能的 markdown 引用
        content = re.sub(r'^["\']|["\']$', '', content)
        return content

    # =========================================================================
    # 会话摘要（Layer 2）
    # =========================================================================

    def generate_session_summary(
        self,
        session_id: str,
    ) -> str:
        """将会话内的多条摘要合并为一条会话摘要。

        Args:
            session_id: 会话标识。

        Returns:
            会话摘要文本，失败返回空字符串。
        """
        if not self._client:
            return ""

        try:
            # 获取该会话的所有 summary 类型记忆
            all_summaries = self._store.get_by_type("summary")
            # 过滤出该会话的摘要（通过 source_turn_id 前缀匹配）
            session_summaries = [
                s for s in all_summaries
                if session_id in (s.source_turn_id or "")
            ]
            if len(session_summaries) < 2:
                return ""

            summary_texts = [
                f"{i+1}. {s.content}"
                for i, s in enumerate(session_summaries[:10])
            ]
            summary_text = "\n".join(summary_texts)

            content = self._generate_summary_by_llm(
                summary_text, _SESSION_SUMMARY_PROMPT
            )
            if content:
                self._log.info(
                    f"[会话摘要] 会话 '{session_id[:20]}...': "
                    f"{len(session_summaries)} 条 -> 1 条会话摘要"
                )
            return content
        except Exception as e:
            self._log.warning(f"[会话摘要] 失败: {e}")
            return ""

    # =========================================================================
    # 主题摘要（Layer 3）
    # =========================================================================

    def generate_topic_summary(
        self,
        topic_name: str,
        related_memories: list[MemoryEntry],
    ) -> str:
        """将多个会话摘要合并为一条主题摘要。

        Args:
            topic_name: 主题名称。
            related_memories: 相关的记忆列表（通常是 summary 类型）。

        Returns:
            主题摘要文本，失败返回空字符串。
        """
        if not self._client or len(related_memories) < 3:
            return ""

        try:
            summary_texts = [
                f"{i+1}. {s.content}"
                for i, s in enumerate(related_memories[:20])
            ]
            summary_text = "\n".join(summary_texts)

            content = self._generate_summary_by_llm(
                summary_text, _TOPIC_SUMMARY_PROMPT
            )
            if content:
                self._log.info(
                    f"[主题摘要] 主题 '{topic_name}': "
                    f"{len(related_memories)} 条 -> 1 条主题摘要"
                )
            return content
        except Exception as e:
            self._log.warning(f"[主题摘要] 失败: {e}")
            return ""

    # =========================================================================
    # 统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取摘要器统计信息。

        Returns:
            统计字典。
        """
        last_summary_turn = self._get_state("last_summary_turn", 0)
        return {
            "summary_count": self._summary_count,
            "last_summary_turn": last_summary_turn,
            "summary_threshold": self.SUMMARY_THRESHOLD,
            "turn_threshold": self.TURN_THRESHOLD,
        }