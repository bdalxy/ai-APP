"""记忆编排器模块。

串联记忆系统的完整链路：提取 → 向量化 → 存储 → 检索 → 衰减加权 → 上下文构建。

核心职责：
    - 持有 VectorStore、MemoryRetriever、MemoryExtractor 实例
    - remember(): 对话完成后提取+存储记忆
    - recall(): 对话前检索相关记忆
    - build_context(): 构建优化的记忆上下文窗口
    - run_maintenance(): 执行记忆库维护
    - get_stats(): 返回记忆统计信息

依赖：
    - src.api_client.deepseek: DeepSeekClient（embed 和 LLM 提取）
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.retriever: MemoryRetriever
    - src.memory.extractor: MemoryExtractor
    - src.memory.context_builder: ContextBuilder, ContextConfig, MemoryContext
    - src.memory.backup: MemoryBackup
    - src.memory.memory_cache: MemoryCache
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from src.api_client.deepseek import DeepSeekClient
from src.memory.analyzer import MemoryAnalyzer
from src.memory.consolidator import MemoryConsolidator
from src.memory.extractor import MemoryExtractor, _is_low_info_conversation, _is_question_or_request
from src.memory.lifecycle import MemoryLifecycle
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
        self._turn_count = 0          # 用于 LLM 提取节流
        self._extract_interval = 2    # 每 N 轮启用一次 LLM 提取（降低到2，减少信息丢失）
        self.config: dict[str, object] = {
            "max_memory_count": 1000,
            "dedup_threshold": 0.7,
            "decay_half_life_days": 30,
        }
        self._archiver = None          # 懒加载的记忆归档器
        self._consolidator: MemoryConsolidator | None = None  # 懒加载的记忆合并器
        self._lifecycle: MemoryLifecycle | None = None        # 懒加载的生命周期管理器
        self._analyzer: MemoryAnalyzer | None = None           # 懒加载的记忆分析器
        self._context_builder: "ContextBuilder | None" = None  # 懒加载的上下文构建器
        self._cache: "MemoryCache | None" = None               # 懒加载的记忆缓存
        self._backup: "MemoryBackup | None" = None             # 懒加载的备份管理器
        self._knowledge_graph: "KnowledgeGraph | None" = None  # 懒加载的知识图谱
        self._summarizer: "MemorySummarizer | None" = None      # 懒加载的摘要器
        self._log = get_logger()
        self._log.info("MemoryOrchestrator 初始化完成")

    # =========================================================================
    # 提取间隔配置
    # =========================================================================

    def set_extract_interval(self, n: int) -> None:
        """设置 LLM 提取的间隔轮数。

        每 N 轮对话触发一次 LLM 提取，其余轮次使用规则模式（rule）。
        设置为 0 表示始终使用 LLM 模式。
        设置为 1 表示每轮都使用 LLM 模式。
        设置为非常大的值（如 999999）表示始终使用规则模式。

        Args:
            n: LLM 提取间隔轮数，必须 >= 0。
        """
        if n < 0:
            raise ValueError(f"提取间隔不能为负数: {n}")
        old = self._extract_interval
        self._extract_interval = n
        self._log.info(
            f"[提取间隔] 已更新: {old} -> {n}"
        )

    # =========================================================================
    # 配置管理
    # =========================================================================

    def set_config(self, config_dict: dict[str, object]) -> None:
        """设置记忆系统配置参数。

        支持的配置项：
            - max_memory_count: 记忆容量上限（默认 1000）
            - dedup_threshold: 去重相似度阈值（默认 0.7）
            - decay_half_life_days: 衰减半衰期天数（默认 30）

        Args:
            config_dict: 配置字典，可以只包含部分键。
        """
        for key in ("max_memory_count", "dedup_threshold", "decay_half_life_days"):
            if key in config_dict:
                self.config[key] = config_dict[key]

        # 同步去重阈值到 extractor
        if "dedup_threshold" in config_dict:
            self.extractor._DEDUP_SIMILARITY_THRESHOLD = float(config_dict["dedup_threshold"])

        # 同步衰减半衰期到 decay 模块
        if "decay_half_life_days" in config_dict:
            from src.memory import decay
            half_life = float(config_dict["decay_half_life_days"])
            decay.DEFAULT_HALF_LIFE = half_life
            for key in decay.HALF_LIFE_DAYS:
                decay.HALF_LIFE_DAYS[key] = half_life

        self._log.info(
            f"[配置] 已更新: max={self.config['max_memory_count']}, "
            f"dedup={self.config['dedup_threshold']}, "
            f"half_life={self.config['decay_half_life_days']}天"
        )

    def get_config(self) -> dict[str, object]:
        """获取当前记忆系统配置。

        Returns:
            配置字典。
        """
        return dict(self.config)

    # =========================================================================
    # 记忆存储
    # =========================================================================

    def remember(
        self,
        turn_id: str,
        user_msg: str,
        ai_reply: str,
        conversation_history: list[dict[str, str]] | None = None,
    ) -> int:
        """对话完成后提取并存储本轮记忆。

        流程：
            1. 构建本轮对话 messages（user + assistant + 可选历史上下文）
            2. 根据节流策略决定提取模式：
               - 每 _extract_interval 轮触发一次 LLM 提取（mode="llm"）
               - 其余轮次使用规则模式（mode="rule"，零 API 开销）
               - _extract_interval <= 0 时始终使用 LLM 模式
            3. 对新记忆调用 client.embed() 补充向量
            4. 调用 vector_store.add() 逐条存储

        embed API 失败不阻断流程：无向量的记忆仍会被存储（后续可补向量）。
        每条记忆的存储操作独立包裹 try/except，单条失败不影响其他记忆。

        Args:
            turn_id: 对话轮次 ID（用于关联记忆与对话）。
            user_msg: 用户输入消息。
            ai_reply: AI 回复消息。
            conversation_history: 最近几轮对话历史（可选），为 LLM 提取提供上下文。

        Returns:
            成功存储的记忆条数。
        """
        self._turn_count += 1

        if not user_msg or not ai_reply:
            self._log.warning("[记忆存储] 消息为空，跳过")
            return 0

        # 1. 构建本轮对话 messages（含历史上下文，帮助LLM理解省略和指代）
        messages: list[dict[str, str]] = []
        if conversation_history:
            messages.extend(conversation_history[-6:])  # 最多3轮历史（6条消息）
        messages.append({"role": "user", "content": user_msg})
        messages.append({"role": "assistant", "content": ai_reply})

        # 2. 提取记忆：根据节流策略决定使用 LLM 还是规则模式
        #    每 _extract_interval 轮启用一次 LLM 提取，其余轮次使用规则模式
        #    _extract_interval == 0 始终使用 LLM
        #    低信息量对话始终使用规则模式（省Token）
        #    纯提问/请求对话使用规则模式（省Token，且不太可能产生有价值的记忆）
        is_low_info = _is_low_info_conversation(messages)
        is_pure_question = _is_question_or_request(user_msg)
        use_llm: bool
        if is_low_info:
            use_llm = False
            self._log.info(f"[记忆存储] 检测到低信息量对话（纯寒暄），跳过 LLM 提取，使用规则模式")
        elif is_pure_question:
            use_llm = False
            self._log.info(f"[记忆存储] 检测到纯提问/请求，跳过 LLM 提取，使用规则模式")
        elif self._extract_interval <= 0:
            use_llm = True
        elif self._extract_interval == 1:
            use_llm = True
        else:
            use_llm = (self._turn_count % self._extract_interval == 0)

        extract_mode = "llm" if use_llm else "rule"
        self._log.info(
            f"[记忆存储] 第 {self._turn_count} 轮，"
            f"节流间隔={self._extract_interval}，使用 {extract_mode} 模式提取"
        )

        try:
            entries = self.extractor.extract(
                messages,
                mode=extract_mode,
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

        # 检查容量上限：超出时跳过存储并触发维护
        max_count = int(self.config.get("max_memory_count", 1000))
        current_count = self.vector_store.count()
        if current_count >= max_count:
            self._log.info(
                f"[记忆存储] 记忆数已达上限 ({current_count}/{max_count})，"
                f"跳过存储并触发维护"
            )
            try:
                self.run_maintenance()
            except Exception as e:
                self._log.warning(f"[记忆存储] 维护触发失败: {e}")
            return 0

        # 3 & 4. 逐条补充向量并存储
        stored_count = 0
        for entry in entries:
            try:
                # 3. 补充向量（失败不阻断，最多重试 3 次）
                embedding_success = False
                max_embedding_retries = 3
                for attempt in range(max_embedding_retries):
                    try:
                        embed_resp = self.client.embed_cached(entry.content)
                        entry.embedding = embed_resp.embeddings[0]
                        embedding_success = True
                        break
                    except Exception as e:
                        if attempt < max_embedding_retries - 1:
                            self._log.debug(
                                f"[记忆存储] embedding 重试 {attempt + 1}/{max_embedding_retries}: "
                                f"content='{entry.content[:30]}...', error={e}"
                            )
                        else:
                            self._log.warning(
                                f"[记忆存储] embedding 失败（已重试 {max_embedding_retries} 次），跳过向量但继续存储: "
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

        # 缓存失效：新记忆入库后使相关缓存失效
        if stored_count > 0:
            try:
                self._get_cache().on_memory_change()
            except Exception as e:
                self._log.debug(f"[缓存] 失效通知失败: {e}")

            # 知识图谱更新：从新记忆提取实体和关系（异步，不阻断主流程）
            try:
                kg = self._get_knowledge_graph()
                kg_result = kg.extract_from_memories(entries)
                if kg_result["entities_added"] > 0:
                    self._log.debug(
                        f"[知识图谱] 新增 {kg_result['entities_added']} 实体, "
                        f"{kg_result['relations_added']} 关系"
                    )
            except Exception as e:
                self._log.debug(f"[知识图谱] 更新失败（不影响对话）: {e}")

        # 归档检查：记忆数超过阈值时自动触发归档
        self._check_archive()

        # 自动备份检查
        try:
            backup = self._get_backup_manager()
            if backup.should_auto_backup():
                self._log.info("[备份] 触发自动备份")
                backup.full_backup()
        except Exception as e:
            self._log.debug(f"[备份] 自动备份检查失败: {e}")

        # 维护检查：每 CONSOLIDATION_INTERVAL 轮触发一次维护
        if self._turn_count % MemoryConsolidator.CONSOLIDATION_INTERVAL == 0:
            try:
                self._log.info(f"[记忆存储] 触发定期维护 (第 {self._turn_count} 轮)")
                self.run_maintenance()
            except Exception as e:
                self._log.warning(f"[记忆存储] 维护失败: {e}")

        return stored_count

    # =========================================================================
    # 归档集成
    # =========================================================================

    def _check_archive(self) -> None:
        """检查并触发记忆归档（懒加载 archiver）。"""
        try:
            if self._archiver is None:
                from src.memory.archiver import MemoryArchiver
                self._archiver = MemoryArchiver(self.vector_store, self.client)
            if self._archiver.should_archive():
                archived = self._archiver.archive()
                if archived > 0:
                    self._log.info(
                        f"[归档] 已归档 {archived} 条记忆, "
                        f"当前活跃记忆数: {self.vector_store.count_active()}"
                    )
        except Exception as e:
            self._log.warning(f"[归档] 检查失败（不影响对话）: {e}")

    # =========================================================================
    # 记忆检索
    # =========================================================================

    def recall(
        self,
        query_text: str,
        top_k: int = 6,
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

        # 尝试从缓存获取检索结果
        try:
            cache = self._get_cache()
            cached = cache.get_query_result(query_text.strip(), top_k)
            if cached is not None:
                result = [entry.content for entry in cached]
                self._log.debug(
                    f"[记忆检索] 缓存命中: query='{query_text[:30]}...', "
                    f"返回={len(result)} 条"
                )
                return result
        except Exception as e:
            self._log.debug(f"[缓存] 检索缓存查询失败: {e}")

        try:
            entries = self.retriever.retrieve(
                query_text.strip(),
                top_k=top_k,
                apply_decay=True,
            )
            result = [entry.content for entry in entries]

            # 缓存检索结果
            if entries:
                try:
                    cache = self._get_cache()
                    cache.put_query_result(query_text.strip(), top_k, entries)
                except Exception as e:
                    self._log.debug(f"[缓存] 检索结果缓存失败: {e}")

            self._log.debug(
                f"[记忆检索] 完成: query='{query_text[:30]}...', "
                f"返回={len(result)} 条"
            )
            return result
        except Exception as e:
            self._log.warning(f"[记忆检索] 检索失败，返回空列表: {e}")
            return []

    # =========================================================================
    # 上下文构建
    # =========================================================================

    def build_context(
        self,
        query_text: str,
        conversation_history: list[str] | None = None,
        user_profile: dict[str, Any] | None = None,
    ) -> "MemoryContext":
        """构建优化的记忆上下文窗口。

        在有限 Token 预算内，分层注入最相关的记忆信息。
        流程：
            1. 检索核心记忆（与查询最相关）
            2. 检索扩展记忆（重要但非核心）
            3. 获取最近记忆
            4. 去重、排序、截断
            5. 格式化输出

        Args:
            query_text: 用户当前查询文本。
            conversation_history: 最近的对话历史（可选）。
            user_profile: 用户画像（可选，用于个性化记忆选择）。

        Returns:
            MemoryContext，包含格式化后的记忆上下文。
        """
        builder = self._get_context_builder()
        return builder.build(
            query_text=query_text,
            conversation_history=conversation_history,
            user_profile=user_profile,
        )

    def build_context_compact(
        self,
        query_text: str,
        max_memories: int = 5,
    ) -> str:
        """快速构建紧凑的记忆上下文。

        Args:
            query_text: 查询文本。
            max_memories: 最大记忆数。

        Returns:
            格式化的记忆文本字符串。
        """
        builder = self._get_context_builder()
        return builder.build_compact(query_text, max_memories)

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

    # =========================================================================
    # 生命周期管理（合并 + 衰减 + 清理 + 健康检查）
    # =========================================================================

    def _get_lifecycle(self) -> MemoryLifecycle:
        """懒加载获取生命周期管理器。"""
        if self._lifecycle is None:
            self._lifecycle = MemoryLifecycle(self.vector_store)
        return self._lifecycle

    def _get_consolidator(self) -> MemoryConsolidator:
        """懒加载获取记忆合并器。"""
        if self._consolidator is None:
            self._consolidator = MemoryConsolidator(self.vector_store, self.client)
        return self._consolidator

    def _get_analyzer(self) -> MemoryAnalyzer:
        """懒加载获取记忆分析器。"""
        if self._analyzer is None:
            self._analyzer = MemoryAnalyzer(self.vector_store)
        return self._analyzer

    def _get_context_builder(self) -> "ContextBuilder":
        """懒加载获取上下文构建器。"""
        if self._context_builder is None:
            from src.memory.context_builder import ContextBuilder, ContextConfig
            self._context_builder = ContextBuilder(
                self.vector_store,
                self.retriever,
                ContextConfig(),
            )
        return self._context_builder

    def _get_cache(self) -> "MemoryCache":
        """懒加载获取记忆缓存管理器。"""
        if self._cache is None:
            from src.memory.memory_cache import MemoryCache
            self._cache = MemoryCache()
        return self._cache

    def _get_backup_manager(self) -> "MemoryBackup":
        """懒加载获取备份管理器。

        备份目录默认在数据库文件同级的 backups/ 目录下。
        内存数据库使用当前目录下的 backups/。
        """
        if self._backup is None:
            from src.memory.backup import MemoryBackup
            db_path = self.vector_store.db_path
            if db_path and db_path != ":memory:":
                backup_dir = Path(db_path).parent / "backups"
            else:
                backup_dir = Path("backups")
            self._backup = MemoryBackup(self.vector_store, str(backup_dir))
        return self._backup

    def _get_knowledge_graph(self) -> "KnowledgeGraph":
        """懒加载获取知识图谱。"""
        if self._knowledge_graph is None:
            from src.memory.knowledge_graph import KnowledgeGraph
            self._knowledge_graph = KnowledgeGraph(self.vector_store, self.client)
        return self._knowledge_graph

    def _get_summarizer(self) -> "MemorySummarizer":
        """懒加载获取记忆摘要器。"""
        if self._summarizer is None:
            from src.memory.summarizer import MemorySummarizer
            self._summarizer = MemorySummarizer(self.vector_store, self.client)
        return self._summarizer

    def run_maintenance(self) -> dict[str, Any]:
        """执行记忆库维护任务。

        每 N 轮对话触发一次，执行以下维护操作:
            1. 衰减更新（decay update）
            2. 记忆合并（consolidation）
            3. 重要性重评估（importance reassessment）
            4. 记忆摘要（summarization）
            5. 智能清理（pruning）
            6. 健康检查（health check）

        Returns:
            维护报告字典。
        """
        lifecycle = self._get_lifecycle()
        consolidator = self._get_consolidator()

        report = {
            "decay_updated": 0,
            "consolidated": 0,
            "importance_reassessed": 0,
            "summaries_generated": 0,
            "pruned": 0,
            "health": {},
        }

        # 1. 衰减更新
        try:
            report["decay_updated"] = lifecycle.update_decay()
        except Exception as e:
            self._log.warning(f"[维护] 衰减更新失败: {e}")

        # 2. 记忆合并（每 CONSOLIDATION_INTERVAL 轮触发）
        if self._turn_count % consolidator.CONSOLIDATION_INTERVAL == 0:
            try:
                result = consolidator.consolidate()
                report["consolidated"] = result["merged"]
            except Exception as e:
                self._log.warning(f"[维护] 合并失败: {e}")

        # 3. 重要性重评估（每 CONSOLIDATION_INTERVAL * 2 轮触发）
        if self._turn_count % (consolidator.CONSOLIDATION_INTERVAL * 2) == 0:
            try:
                imp_result = lifecycle.recalibrate_importance()
                report["importance_reassessed"] = imp_result
            except Exception as e:
                self._log.warning(f"[维护] 重要性重评估失败: {e}")

        # 4. 记忆摘要检查
        try:
            summarizer = self._get_summarizer()
            if summarizer.should_summarize(self._turn_count):
                summary_result = summarizer.generate_summaries(self._turn_count)
                report["summaries_generated"] = summary_result["summaries_generated"]
        except Exception as e:
            self._log.warning(f"[维护] 摘要生成失败: {e}")

        # 5. 智能清理
        try:
            report["pruned"] = lifecycle.prune()
        except Exception as e:
            self._log.warning(f"[维护] 清理失败: {e}")

        # 6. 健康检查
        try:
            report["health"] = lifecycle.health_check(self._turn_count)
        except Exception as e:
            self._log.warning(f"[维护] 健康检查失败: {e}")

        # 7. 缓存清理
        try:
            cache_cleanup = self.cache_cleanup()
            report["cache_cleanup"] = cache_cleanup
        except Exception as e:
            self._log.warning(f"[维护] 缓存清理失败: {e}")

        self._log.info(
            f"[维护] 完成: 衰减={report['decay_updated']}, "
            f"合并={report['consolidated']}, "
            f"重要性重评估={report['importance_reassessed']}, "
            f"摘要={report['summaries_generated']}, "
            f"清理={report['pruned']}"
        )
        return report

    # =========================================================================
    # 分析接口
    # =========================================================================

    def analyze_trends(self, days: int = 30) -> dict[str, Any]:
        """分析记忆变化趋势。

        Args:
            days: 分析的时间范围（天）。

        Returns:
            趋势分析报告字典。
        """
        analyzer = self._get_analyzer()
        return analyzer.analyze_trends(days)

    def analyze_topics(self, num_clusters: int = 5) -> dict[str, Any]:
        """主题聚类分析。

        Args:
            num_clusters: 聚类数量。

        Returns:
            主题聚类报告字典。
        """
        analyzer = self._get_analyzer()
        return analyzer.cluster_topics(num_clusters)

    def generate_user_profile(self) -> dict[str, Any]:
        """生成用户画像。

        Returns:
            用户画像字典。
        """
        analyzer = self._get_analyzer()
        return analyzer.generate_user_profile()

    def analyze_quality(self) -> dict[str, Any]:
        """分析记忆库质量。

        Returns:
            质量分析报告字典。
        """
        analyzer = self._get_analyzer()
        return analyzer.analyze_quality()

    # =========================================================================
    # 标签管理接口
    # =========================================================================

    def add_tag(self, name: str, color: str = "#9B59B6") -> int:
        """添加标签。

        Returns:
            标签 ID。
        """
        return self.vector_store.add_tag(name, color)

    def tag_memory(self, memory_id: str, tag_name: str) -> None:
        """为记忆添加标签。"""
        self.vector_store.tag_memory(memory_id, tag_name)

    def untag_memory(self, memory_id: str, tag_name: str) -> None:
        """移除记忆的标签。"""
        self.vector_store.untag_memory(memory_id, tag_name)

    def get_memory_tags(self, memory_id: str) -> list[dict]:
        """获取记忆的标签列表。"""
        return self.vector_store.get_memory_tags(memory_id)

    def list_all_tags(self) -> list[dict]:
        """列出所有标签。"""
        return self.vector_store.list_all_tags()

    # =========================================================================
    # 关系管理接口
    # =========================================================================

    def add_relation(
        self,
        source_id: str,
        target_id: str,
        relation_type: str = "related_to",
        confidence: float = 0.5,
        notes: str = "",
    ) -> int:
        """添加记忆关系。"""
        return self.vector_store.add_relation(
            source_id, target_id, relation_type, confidence, notes
        )

    def get_relations(self, memory_id: str) -> list[dict]:
        """获取记忆的关系列表。"""
        return self.vector_store.get_relations(memory_id)

    # =========================================================================
    # 知识图谱接口
    # =========================================================================

    def build_knowledge_graph(self, limit: int = 50) -> dict[str, int]:
        """从现有记忆中构建/更新知识图谱。

        获取最近的 N 条记忆，使用 LLM 提取实体和关系。

        Args:
            limit: 处理的记忆数上限。

        Returns:
            统计字典: {"entities_added": int, "relations_added": int}。
        """
        try:
            entries = self.vector_store.get_recent_entries(limit)
            if not entries:
                return {"entities_added": 0, "relations_added": 0}
            kg = self._get_knowledge_graph()
            result = kg.extract_from_memories(entries)
            self._log.info(
                f"[知识图谱] 构建完成: 实体={result['entities_added']}, "
                f"关系={result['relations_added']}"
            )
            return result
        except Exception as e:
            self._log.warning(f"[知识图谱] 构建失败: {e}")
            return {"entities_added": 0, "relations_added": 0}

    def query_graph(
        self,
        start_entity: str = "",
        max_hops: int = 2,
    ) -> dict:
        """查询知识图谱。

        如果指定起始实体，则沿关系图遍历。
        否则返回图谱统计信息。

        Args:
            start_entity: 起始实体名称。空字符串表示返回统计。
            max_hops: 最大遍历跳数。

        Returns:
            图谱遍历结果或统计信息。
        """
        try:
            kg = self._get_knowledge_graph()
            if start_entity:
                return kg.traverse(start_entity, max_hops=max_hops)
            return kg.get_stats()
        except Exception as e:
            self._log.warning(f"[知识图谱] 查询失败: {e}")
            return {"error": str(e)}

    def list_kg_entities(self, entity_type: str = "", limit: int = 100) -> list[dict]:
        """列出知识图谱中的实体。

        Args:
            entity_type: 实体类型过滤（PERSON/LOCATION/ORG/EVENT/CONCEPT/TIME）。
            limit: 返回上限。

        Returns:
            实体字典列表。
        """
        try:
            kg = self._get_knowledge_graph()
            return kg.list_entities(entity_type, limit)
        except Exception as e:
            self._log.warning(f"[知识图谱] 列出实体失败: {e}")
            return []

    def get_kg_entity(self, name: str) -> dict | None:
        """查询知识图谱实体。

        Args:
            name: 实体名称。

        Returns:
            实体字典，不存在返回 None。
        """
        try:
            kg = self._get_knowledge_graph()
            return kg.get_entity(name)
        except Exception as e:
            self._log.warning(f"[知识图谱] 查询实体失败: {e}")
            return None

    def get_kg_relations(self, entity_name: str, direction: str = "both") -> list[dict]:
        """查询知识图谱实体的关系。

        Args:
            entity_name: 实体名称。
            direction: 方向，"out"（出边）/ "in"（入边）/ "both"（双向）。

        Returns:
            关系字典列表。
        """
        try:
            kg = self._get_knowledge_graph()
            return kg.get_relations(entity_name, direction)
        except Exception as e:
            self._log.warning(f"[知识图谱] 查询关系失败: {e}")
            return []

    def get_kg_stats(self) -> dict:
        """获取知识图谱统计信息。

        Returns:
            统计字典。
        """
        try:
            kg = self._get_knowledge_graph()
            return kg.get_stats()
        except Exception as e:
            self._log.warning(f"[知识图谱] 统计失败: {e}")
            return {"entity_count": 0, "relation_count": 0, "error": str(e)}

    # =========================================================================
    # 变更日志接口
    # =========================================================================

    def get_changelog(self, memory_id: str = "", limit: int = 50) -> list[dict]:
        """获取变更日志。"""
        return self.vector_store.get_changelog(memory_id, limit)

    # =========================================================================
    # 增强统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取增强的记忆统计信息。

        包含基础统计 + 生命周期统计 + 合并统计 + 质量评估。

        Returns:
            增强的统计信息字典。
        """
        try:
            total = self.vector_store.count()
            active = self.vector_store.count_active()
            by_type: dict[str, int] = {}
            for mem_type in ("episodic", "semantic", "user_fact", "emotional", "summary"):
                try:
                    entries = self.vector_store.get_by_type(mem_type)
                    by_type[mem_type] = len(entries)
                except Exception:
                    by_type[mem_type] = 0

            stats = {
                "total": total,
                "active": active,
                "archived": total - active,
                "by_type": by_type,
                "turn_count": self._turn_count,
            }

            # 附加生命周期和合并统计
            try:
                lifecycle = self._get_lifecycle()
                stats["lifecycle"] = lifecycle.get_stats()
            except Exception:
                pass

            try:
                consolidator = self._get_consolidator()
                stats["consolidation"] = consolidator.get_stats()
            except Exception:
                pass

            return stats

        except Exception as e:
            self._log.error(f"[统计] 获取统计信息失败: {e}")
            return {
                "total": 0,
                "by_type": {},
                "turn_count": self._turn_count,
                "error": str(e),
            }

    # =========================================================================
    # 备份管理
    # =========================================================================

    def backup_full(self) -> "BackupMetadata | None":
        """执行完整备份。

        使用 SQLite backup API 进行在线备份，不阻塞读写。

        Returns:
            BackupMetadata，如果备份失败则返回 None。
        """
        try:
            backup = self._get_backup_manager()
            metadata = backup.full_backup()
            if metadata:
                self._log.info(f"[备份] 完整备份完成: {metadata.backup_id}")
            return metadata
        except Exception as e:
            self._log.error(f"[备份] 完整备份失败: {e}")
            return None

    def backup_json(self) -> "BackupMetadata | None":
        """执行 JSON 格式备份。

        将记忆导出为 JSON 文件，适用于跨平台迁移。

        Returns:
            BackupMetadata，如果备份失败则返回 None。
        """
        try:
            backup = self._get_backup_manager()
            metadata = backup.json_backup()
            if metadata:
                self._log.info(f"[备份] JSON 备份完成: {metadata.backup_id}")
            return metadata
        except Exception as e:
            self._log.error(f"[备份] JSON 备份失败: {e}")
            return None

    def restore_backup(self, backup_id: str) -> bool:
        """从备份恢复记忆库。

        流程：
            1. 查找备份文件
            2. 验证校验和
            3. 清空当前记忆库
            4. 从备份恢复
            5. 使所有缓存失效

        Args:
            backup_id: 备份标识符。

        Returns:
            True 如果恢复成功。
        """
        try:
            backup = self._get_backup_manager()
            success = backup.restore(backup_id)
            if success:
                # 恢复后使所有缓存失效
                try:
                    self._get_cache().invalidate_all()
                except Exception:
                    pass
                self._log.info(f"[备份] 恢复成功: {backup_id}")
            return success
        except Exception as e:
            self._log.error(f"[备份] 恢复失败: {e}")
            return False

    def list_backups(self) -> list[dict]:
        """列出所有备份。

        Returns:
            备份列表，每项包含备份元数据。
        """
        try:
            backup = self._get_backup_manager()
            return backup.list_backups()
        except Exception as e:
            self._log.error(f"[备份] 列出备份失败: {e}")
            return []

    def delete_backup(self, backup_id: str) -> bool:
        """删除指定备份。

        Args:
            backup_id: 备份标识符。

        Returns:
            True 如果删除成功。
        """
        try:
            backup = self._get_backup_manager()
            return backup.delete_backup(backup_id)
        except Exception as e:
            self._log.error(f"[备份] 删除备份失败: {e}")
            return False

    def verify_backup(self, backup_id: str) -> dict:
        """验证备份文件的完整性。

        Args:
            backup_id: 备份标识符。

        Returns:
            验证结果字典。
        """
        try:
            backup = self._get_backup_manager()
            return backup.verify_backup(backup_id)
        except Exception as e:
            self._log.error(f"[备份] 验证备份失败: {e}")
            return {"valid": False, "reason": str(e)}

    def get_backup_stats(self) -> dict:
        """获取备份管理器统计信息。

        Returns:
            备份统计字典。
        """
        try:
            backup = self._get_backup_manager()
            return backup.get_stats()
        except Exception as e:
            self._log.error(f"[备份] 获取备份统计失败: {e}")
            return {"backup_count": 0, "error": str(e)}

    # =========================================================================
    # 缓存管理
    # =========================================================================

    def get_cache_stats(self) -> dict:
        """获取所有缓存的统计信息。

        Returns:
            缓存统计字典，包含各层缓存的命中率、大小等。
        """
        try:
            cache = self._get_cache()
            return cache.get_cache_stats()
        except Exception as e:
            self._log.error(f"[缓存] 获取缓存统计失败: {e}")
            return {"error": str(e)}

    def invalidate_cache(self) -> None:
        """使检索和统计缓存失效（记忆库发生变化时调用）。

        热点缓存和画像缓存保留。
        """
        try:
            self._get_cache().on_memory_change()
            self._log.debug("[缓存] 检索和统计缓存已失效")
        except Exception as e:
            self._log.debug(f"[缓存] 缓存失效失败: {e}")

    def invalidate_cache_all(self) -> None:
        """使所有缓存失效。

        在记忆库发生重大变化时调用（如批量导入、清空、恢复等）。
        """
        try:
            self._get_cache().invalidate_all()
            self._log.info("[缓存] 全部缓存已失效")
        except Exception as e:
            self._log.debug(f"[缓存] 全部缓存失效失败: {e}")

    def cache_cleanup(self) -> dict:
        """执行缓存定期清理（清理过期 TTL 条目）。

        Returns:
            清理统计字典。
        """
        try:
            cache = self._get_cache()
            return cache.cleanup()
        except Exception as e:
            self._log.error(f"[缓存] 清理失败: {e}")
            return {"total": 0, "error": str(e)}