"""记忆编排器模块。

串联记忆系统的完整链路：提取 → 向量化 → 存储 → 检索 → 衰减加权。

核心职责：
    - 持有 VectorStore、MemoryRetriever、MemoryExtractor 实例
    - remember(): 对话完成后提取+存储记忆
    - recall(): 对话前检索相关记忆
    - get_stats(): 返回记忆统计信息

依赖：
    - src.api_client.deepseek: DeepSeekClient（embed 和 LLM 提取）
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.retriever: MemoryRetriever
    - src.memory.extractor: MemoryExtractor
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

from src.api_client.deepseek import DeepSeekClient
from src.memory.extractor import MemoryExtractor
from src.memory.retriever import MemoryRetriever
from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger


class MemoryOrchestrator:
    """记忆编排器。

    串联记忆系统的完整链路，提供统一的高层接口。
    不创建 DeepSeekClient，接收外部传入的共享实例。

    Attributes:
        vector_store: 向量存储实例。
        client: DeepSeek API 客户端（共享实例）。
        extractor: 记忆提取器。
        retriever: 混合检索器。
        _turn_count: 对话轮次计数器，用于 LLM 提取节流。
    """

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: DeepSeekClient,
    ) -> None:
        """初始化记忆编排器。

        Args:
            vector_store: 向量存储实例（已初始化的 VectorStore）。
            deepseek_client: DeepSeek API 客户端（与 RolePlayer 共享）。
        """
        self.vector_store = vector_store
        self.client = deepseek_client
        self.extractor = MemoryExtractor(deepseek_client, vector_store)
        self.retriever = MemoryRetriever(vector_store, deepseek_client)
        self._turn_count = 0  # 用于 LLM 提取节流
        self._log = get_logger()
        self._log.info("MemoryOrchestrator 初始化完成")

    # =========================================================================
    # 记忆存储
    # =========================================================================

    def remember(
        self,
        turn_id: str,
        user_msg: str,
        ai_reply: str,
    ) -> int:
        """对话完成后提取并存储本轮记忆。

        流程：
            1. 构建本轮对话 messages（user + assistant）
            2. 调用 extractor.extract() 提取记忆（MVP 阶段只用 rule 模式）
            3. 对新记忆调用 client.embed() 补充向量
            4. 调用 vector_store.add() 逐条存储

        embed API 失败不阻断流程：无向量的记忆仍会被存储（后续可补向量）。
        每条记忆的存储操作独立包裹 try/except，单条失败不影响其他记忆。

        Args:
            turn_id: 对话轮次 ID（用于关联记忆与对话）。
            user_msg: 用户输入消息。
            ai_reply: AI 回复消息。

        Returns:
            成功存储的记忆条数。
        """
        self._turn_count += 1

        if not user_msg or not ai_reply:
            self._log.warning("[记忆存储] 消息为空，跳过")
            return 0

        # 1. 构建本轮对话 messages
        messages: list[dict[str, str]] = [
            {"role": "user", "content": user_msg},
            {"role": "assistant", "content": ai_reply},
        ]

        # 2. 提取记忆（MVP 阶段只用规则模式，零 API 开销）
        try:
            entries = self.extractor.extract(
                messages,
                mode="rule",
                source_turn_id=turn_id,
            )
        except Exception as e:
            self._log.error(f"[记忆存储] 提取失败: {e}")
            return 0

        if not entries:
            self._log.debug(f"[记忆存储] 本轮未提取到新记忆 (turn={turn_id})")
            return 0

        self._log.info(
            f"[记忆存储] 提取到 {len(entries)} 条记忆 (turn={turn_id})"
        )

        # 3 & 4. 逐条补充向量并存储
        stored_count = 0
        for entry in entries:
            try:
                # 3. 补充向量（失败不阻断）
                try:
                    embed_resp = self.client.embed(entry.content)
                    entry.embedding = embed_resp.embeddings[0]
                except Exception as e:
                    self._log.warning(
                        f"[记忆存储] embedding 失败，跳过向量但继续存储: "
                        f"content='{entry.content[:30]}...', error={e}"
                    )

                # 4. 存储记忆
                try:
                    self.vector_store.add(entry)
                    stored_count += 1
                except Exception as e:
                    self._log.error(
                        f"[记忆存储] 存储失败: "
                        f"content='{entry.content[:30]}...', error={e}"
                    )
            except Exception as e:
                # 最外层兜底，确保单条记忆的任何异常都不影响其他记忆
                self._log.error(
                    f"[记忆存储] 处理记忆时发生意外错误: {e}"
                )

        self._log.info(
            f"[记忆存储] 完成: 提取={len(entries)}, 成功存储={stored_count} "
            f"(turn={turn_id})"
        )
        return stored_count

    # =========================================================================
    # 记忆检索
    # =========================================================================

    def recall(
        self,
        query_text: str,
        top_k: int = 3,
    ) -> list[str]:
        """对话前检索相关记忆。

        流程：
            1. 调用 retriever.retrieve() 检索（含时间衰减加权）
            2. 返回记忆纯文本列表

        如果检索失败（如 embed API 不可用），返回空列表，不抛异常。

        Args:
            query_text: 用于检索的查询文本（通常是用户最新输入）。
            top_k: 返回的最大记忆数。

        Returns:
            记忆纯文本列表，按相关度降序排列。
        """
        if not query_text or not query_text.strip():
            self._log.debug("[记忆检索] 查询文本为空，跳过")
            return []

        # 检查是否有记忆可检索
        try:
            if self.vector_store.count() == 0:
                self._log.debug("[记忆检索] 向量库为空，跳过")
                return []
        except Exception:
            pass  # count() 失败也继续尝试检索

        try:
            entries = self.retriever.retrieve(
                query_text.strip(),
                top_k=top_k,
                apply_decay=True,
            )
            result = [entry.content for entry in entries]
            self._log.info(
                f"[记忆检索] 完成: query='{query_text[:30]}...', "
                f"返回={len(result)} 条"
            )
            return result
        except Exception as e:
            self._log.warning(f"[记忆检索] 检索失败，返回空列表: {e}")
            return []

    # =========================================================================
    # 统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取记忆统计信息。

        Returns:
            包含总数和各类型数量的字典。
        """
        try:
            total = self.vector_store.count()
            by_type: dict[str, int] = {}
            for mem_type in ("episodic", "semantic", "user_fact"):
                try:
                    entries = self.vector_store.get_by_type(mem_type)
                    by_type[mem_type] = len(entries)
                except Exception:
                    by_type[mem_type] = 0
            return {
                "total": total,
                "by_type": by_type,
                "turn_count": self._turn_count,
            }
        except Exception as e:
            self._log.error(f"[统计] 获取统计信息失败: {e}")
            return {
                "total": 0,
                "by_type": {},
                "turn_count": self._turn_count,
                "error": str(e),
            }

    # =========================================================================
    # 资源管理
    # =========================================================================

    def close(self) -> None:
        """关闭向量存储连接。

        注意：不关闭 DeepSeekClient（与 RolePlayer 共享）。
        """
        try:
            self.vector_store.close()
            self._log.info("MemoryOrchestrator 已关闭")
        except Exception as e:
            self._log.error(f"关闭 MemoryOrchestrator 失败: {e}")