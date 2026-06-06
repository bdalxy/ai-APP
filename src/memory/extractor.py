"""记忆提取器模块。

从对话消息中自动提取长期记忆，支持两种模式：
1. 规则提取模式（economy_mode=True）：基于正则匹配，零 API 开销
2. LLM 提取模式（默认）：调用 DeepSeek Chat API，用专门的 prompt 结构化提取

包含去重逻辑：检查是否与已有记忆高度相似。

依赖：
    - src.api_client.deepseek: DeepSeekClient（LLM 提取模式）
    - src.memory.vector_store: MemoryEntry, VectorStore, cosine_similarity
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

import json
import re
from typing import Any

from src.api_client.deepseek import DeepSeekClient
from src.memory.vector_store import MemoryEntry, VectorStore, cosine_similarity
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


# =============================================================================
# 规则提取模式的正则表达式
# =============================================================================

# 用户事实的提取规则
_USER_FACT_PATTERNS: list[tuple[re.Pattern, str]] = [
    # "我是..." / "我叫..."
    (re.compile(r"我是([\u4e00-\u9fff\w]{1,20})[，。；.!?！？\s]*$"), "user_fact"),
    (re.compile(r"我叫([\u4e00-\u9fff\w]{1,20})[，。；.!?！？\s]*$"), "user_fact"),
    # "我喜欢..." / "我不喜欢..."
    (re.compile(r"我(喜欢|不喜欢|讨厌|爱)([\u4e00-\u9fff\w，,、\s]{2,50})"), "user_fact"),
    # "我住在..." / "我的家乡是..."
    (re.compile(r"我(住在|家在|的家乡是|在)([\u4e00-\u9fff\w，,、\s]{2,30})"), "user_fact"),
    # "我[是/做/当]..." 职业/身份
    (re.compile(r"我(是|做|当|从事)([一个位名]*[\u4e00-\u9fff\w，,、\s]{2,30})"), "user_fact"),
    # "我[有/养]..." 宠物等
    (re.compile(r"我(有|养了|养过|有一只)([\u4e00-\u9fff\w，,、\s]{2,20})"), "user_fact"),
    # "我的..." 属性
    (re.compile(r"我的(生日|年龄|血型|星座|身高|体重|手机|邮箱|地址)([是]+)([\u4e00-\u9fff\w@.，,、\s]{2,50})"), "user_fact"),
]

# 事件记忆的提取规则
_EPISODIC_PATTERNS: list[re.Pattern] = [
    # "今天/昨天/刚刚/上周/前几天..." + 动作
    re.compile(r"(今天|昨天|刚刚|刚才|上周|前几天|上个月|之前)([\u4e00-\u9fff\w，,、\s]{3,80})"),
    # "我[刚/已经/正在]..." + 动作
    re.compile(r"我(刚|已经|正在|去|去了|做了|完成了|经历了)([\u4e00-\u9fff\w，,、\s]{3,80})"),
]


# =============================================================================
# LLM 提取 Prompt
# =============================================================================

_MEMORY_EXTRACTION_PROMPT = """你是一个记忆提取助手。请从以下对话中提取值得长期记忆的信息。

提取规则：
1. **user_fact（用户事实）**：用户明确表达的个人信息，如姓名、喜好、住址、职业等。
2. **semantic（语义知识）**：对话中涉及的重要知识、概念、观点。
3. **episodic（事件记忆）**：用户提到的重要事件、经历、活动。

输出格式：JSON 数组，每个元素包含：
- "memory_type": "user_fact" | "semantic" | "episodic"
- "content": 记忆文本（简洁、完整的句子）
- "importance": 重要性评分（0.0~1.0），用户事实 > 0.8，语义知识 > 0.5，事件 > 0.4

如果没有值得长期记忆的信息，返回空数组：[]。

对话内容：
{conversation}

请只输出 JSON 数组，不要包含其他内容。"""


# =============================================================================
# 记忆提取器
# =============================================================================


class MemoryExtractor:
    """记忆提取器。

    从对话消息中提取长期记忆，支持规则模式和 LLM 模式。
    包含去重逻辑，避免重复存储相似记忆。

    Attributes:
        client: DeepSeek API 客户端（LLM 模式）。
        vector_store: 向量存储实例（用于去重检查）。
    """

    # 去重的最小相似度阈值（超过此值视为重复）
    _DEDUP_SIMILARITY_THRESHOLD: float = 0.85

    def __init__(
        self,
        deepseek_client: DeepSeekClient,
        vector_store: VectorStore | None = None,
    ) -> None:
        """初始化记忆提取器。

        Args:
            deepseek_client: DeepSeek API 客户端实例。
            vector_store: 向量存储实例（用于去重）。如果为 None，跳过去重。
        """
        self.client = deepseek_client
        self.vector_store = vector_store
        self._log = get_logger()

        self._log.info("MemoryExtractor 初始化完成")

    # -------------------------------------------------------------------------
    # 主提取方法
    # -------------------------------------------------------------------------

    def extract(
        self,
        messages: list[dict[str, str]],
        mode: str = "auto",
        source_turn_id: str = "",
    ) -> list[MemoryEntry]:
        """从对话消息中提取记忆。

        mode 参数：
        - "auto"（默认）：优先 LLM 模式，失败时回退到规则模式
        - "llm": 仅使用 LLM 模式
        - "rule": 仅使用规则模式

        Args:
            messages: 对话消息列表，每项为 {"role": str, "content": str}。
            mode: 提取模式。
            source_turn_id: 来源对话轮次 ID。

        Returns:
            提取到的 MemoryEntry 列表（已去重）。
        """
        if not messages:
            self._log.warning("没有消息可提取")
            return []

        entries: list[MemoryEntry] = []

        if mode == "rule":
            # 仅规则模式
            entries = self._extract_by_rule(messages, source_turn_id)
        elif mode == "llm":
            # 仅 LLM 模式
            entries = self._extract_by_llm(messages, source_turn_id)
        elif mode == "auto":
            # 自动模式：优先 LLM，失败回退规则
            try:
                entries = self._extract_by_llm(messages, source_turn_id)
            except Exception as e:
                self._log.warning(f"LLM 提取失败，回退到规则模式: {e}")
                entries = self._extract_by_rule(messages, source_turn_id)
        else:
            raise ValueError(f"不支持的提取模式: {mode}，可选值: auto, llm, rule")

        # 去重
        if self.vector_store and entries:
            entries = self._deduplicate(entries)

        self._log.info(f"[提取] 完成: mode={mode}, 提取={len(entries)} 条记忆")
        return entries

    # -------------------------------------------------------------------------
    # 规则提取模式
    # -------------------------------------------------------------------------

    def _extract_by_rule(
        self,
        messages: list[dict[str, str]],
        source_turn_id: str,
    ) -> list[MemoryEntry]:
        """基于正则规则提取记忆。

        从用户消息中匹配用户事实和事件模式。

        Args:
            messages: 对话消息列表。
            source_turn_id: 来源对话轮次 ID。

        Returns:
            提取到的 MemoryEntry 列表。
        """
        entries: list[MemoryEntry] = []
        now = format_timestamp_iso()

        # 只提取用户消息
        user_messages = [
            msg["content"]
            for msg in messages
            if msg.get("role") == "user"
        ]

        for text in user_messages:
            if not text:
                continue

            # 提取用户事实
            for pattern, mem_type in _USER_FACT_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    if content and len(content) >= 3:
                        entry = MemoryEntry(
                            memory_type=mem_type,
                            content=content,
                            importance=0.85,
                            created_at=now,
                            last_accessed=now,
                            source_turn_id=source_turn_id,
                        )
                        entries.append(entry)

            # 提取事件记忆
            for pattern in _EPISODIC_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    if content and len(content) >= 5:
                        entry = MemoryEntry(
                            memory_type="episodic",
                            content=content,
                            importance=0.5,
                            created_at=now,
                            last_accessed=now,
                            source_turn_id=source_turn_id,
                        )
                        entries.append(entry)

        self._log.debug(f"[规则提取] 从 {len(user_messages)} 条消息中提取 {len(entries)} 条记忆")
        return entries

    # -------------------------------------------------------------------------
    # LLM 提取模式
    # -------------------------------------------------------------------------

    def _extract_by_llm(
        self,
        messages: list[dict[str, str]],
        source_turn_id: str,
    ) -> list[MemoryEntry]:
        """调用 DeepSeek Chat API 提取记忆。

        使用专门的 prompt 让 LLM 结构化提取记忆。

        Args:
            messages: 对话消息列表。
            source_turn_id: 来源对话轮次 ID。

        Returns:
            提取到的 MemoryEntry 列表。

        Raises:
            APIException: API 调用失败时。
        """
        # 构建对话文本
        conversation = "\n".join(
            f"{msg['role']}: {msg['content']}"
            for msg in messages
            if msg.get("role") in ("user", "assistant")
        )

        prompt = _MEMORY_EXTRACTION_PROMPT.format(conversation=conversation)

        # 调用 LLM
        llm_messages = [{"role": "user", "content": prompt}]
        response = self.client.chat(
            messages=llm_messages,
            temperature=0.3,  # 低温度，保证提取一致性
            max_tokens=1000,
        )

        # 解析 LLM 返回的 JSON
        raw_text = response.content.strip()
        # 清理可能的 markdown 代码块标记
        if raw_text.startswith("```"):
            # 移除 ```json 和 ```
            raw_text = re.sub(r"^```(?:json)?\s*", "", raw_text)
            raw_text = re.sub(r"\s*```$", "", raw_text)

        try:
            items = json.loads(raw_text)
        except json.JSONDecodeError as e:
            self._log.error(f"LLM 返回的 JSON 解析失败: {e}, raw_text_len={len(raw_text)}")
            return []

        if not isinstance(items, list):
            self._log.warning(f"LLM 返回格式异常，期望数组，实际: {type(items)}")
            return []

        # 转换为 MemoryEntry
        now = format_timestamp_iso()
        entries: list[MemoryEntry] = []

        for item in items:
            if not isinstance(item, dict):
                continue

            mem_type = item.get("memory_type", "semantic")
            content = item.get("content", "")
            importance = float(item.get("importance", 0.5))

            # 类型校验
            if mem_type not in ("user_fact", "semantic", "episodic"):
                mem_type = "semantic"

            # 重要性范围校验
            importance = max(0.0, min(1.0, importance))

            if content and len(content.strip()) >= 3:
                entry = MemoryEntry(
                    memory_type=mem_type,
                    content=content.strip(),
                    importance=importance,
                    created_at=now,
                    last_accessed=now,
                    source_turn_id=source_turn_id,
                )
                entries.append(entry)

        self._log.debug(f"[LLM 提取] 提取 {len(entries)} 条记忆")
        return entries

    # -------------------------------------------------------------------------
    # 去重逻辑
    # -------------------------------------------------------------------------

    def _deduplicate(self, new_entries: list[MemoryEntry]) -> list[MemoryEntry]:
        """去除与已有记忆高度相似的新条目。

        对每条新记忆，检查是否与向量存储中已有记忆高度相似。
        如果相似度超过阈值，则跳过该条目。

        Args:
            new_entries: 新提取的记忆条目列表。

        Returns:
            去重后的记忆条目列表。
        """
        if not self.vector_store:
            return new_entries

        existing_entries = self.vector_store.get_all()
        if not existing_entries:
            return new_entries

        kept: list[MemoryEntry] = []

        for new_entry in new_entries:
            is_duplicate = False

            for existing in existing_entries:
                if new_entry.memory_type != existing.memory_type:
                    continue

                # 基于内容的简单相似度检查（字符级）
                sim = self._text_similarity(new_entry.content, existing.content)
                if sim >= self._DEDUP_SIMILARITY_THRESHOLD:
                    self._log.debug(
                        f"[去重] 跳过重复记忆: '{new_entry.content[:30]}...' "
                        f"与已有记忆相似度={sim:.2f}"
                    )
                    is_duplicate = True
                    break

            if not is_duplicate:
                kept.append(new_entry)

        removed_count = len(new_entries) - len(kept)
        if removed_count > 0:
            self._log.info(f"[去重] 移除 {removed_count} 条重复记忆，保留 {len(kept)} 条")

        return kept

    @staticmethod
    def _text_similarity(text1: str, text2: str) -> float:
        """计算两个文本的字符级相似度（基于公共子串）。

        使用字符级 bigram 的 Jaccard 相似度，简单高效。

        Args:
            text1: 第一个文本。
            text2: 第二个文本。

        Returns:
            相似度值（0.0 ~ 1.0）。
        """
        if not text1 or not text2:
            return 0.0

        # 提取 bigrams
        def get_bigrams(s: str) -> set[str]:
            cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", s)
            return {cleaned[i : i + 2] for i in range(len(cleaned) - 1)}

        bg1 = get_bigrams(text1)
        bg2 = get_bigrams(text2)

        if not bg1 or not bg2:
            return 0.0

        intersection = len(bg1 & bg2)
        union = len(bg1 | bg2)

        return intersection / union if union > 0 else 0.0
