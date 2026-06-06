"""记忆提取器 - 规则模式 + LLM 模式，含去重逻辑。"""
from __future__ import annotations
import json
import re
from typing import Any
from src.api_client.deepseek import DeepSeekClient
from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso

_USER_FACT_PATTERNS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"我是([\u4e00-\u9fff\w]{1,20})[，。；.!?！？\s]*$"), "user_fact"),
    (re.compile(r"我叫([\u4e00-\u9fff\w]{1,20})[，。；.!?！？\s]*$"), "user_fact"),
    (re.compile(r"我(喜欢|不喜欢|讨厌|爱)([\u4e00-\u9fff\w，,、\s]{2,50})"), "user_fact"),
    (re.compile(r"我(住在|家在|的家乡是|在)([\u4e00-\u9fff\w，,、\s]{2,30})"), "user_fact"),
    (re.compile(r"我(是|做|当|从事)([一个位名]*[\u4e00-\u9fff\w，,、\s]{2,30})"), "user_fact"),
    (re.compile(r"我(有|养了|养过|有一只)([\u4e00-\u9fff\w，,、\s]{2,20})"), "user_fact"),
]
_EPISODIC_PATTERNS: list[re.Pattern] = [
    re.compile(r"(今天|昨天|刚刚|刚才|上周|前几天|上个月|之前)([\u4e00-\u9fff\w，,、\s]{3,80})"),
    re.compile(r"我(刚|已经|正在|去|去了|做了|完成了|经历了)([\u4e00-\u9fff\w，,、\s]{3,80})"),
]

_MEMORY_EXTRACTION_PROMPT = """你是一个记忆提取助手。请从以下对话中提取值得长期记忆的信息。
提取规则：
1. user_fact（用户事实）：用户明确表达的个人信息。
2. semantic（语义知识）：对话中的重要知识、概念。
3. episodic（事件记忆）：用户提到的重要事件。
输出 JSON 数组，每个元素包含 memory_type、content、importance。无记忆返回 []。
对话内容：
{conversation}
只输出 JSON 数组。"""

class MemoryExtractor:
    _DEDUP_SIMILARITY_THRESHOLD = 0.85
    def __init__(self, deepseek_client: DeepSeekClient, vector_store: VectorStore | None = None) -> None:
        self.client = deepseek_client
        self.vector_store = vector_store
        self._log = get_logger()

    def extract(self, messages: list[dict[str, str]], mode: str = "auto", source_turn_id: str = "") -> list[MemoryEntry]:
        if not messages:
            return []
        entries: list[MemoryEntry] = []
        if mode == "rule":
            entries = self._extract_by_rule(messages, source_turn_id)
        elif mode == "llm":
            entries = self._extract_by_llm(messages, source_turn_id)
        elif mode == "auto":
            try:
                entries = self._extract_by_llm(messages, source_turn_id)
            except Exception as e:
                self._log.warning(f"LLM 提取失败，回退规则: {e}")
                entries = self._extract_by_rule(messages, source_turn_id)
        else:
            raise ValueError(f"不支持的提取模式: {mode}")
        if self.vector_store and entries:
            entries = self._deduplicate(entries)
        return entries

    def _extract_by_rule(self, messages: list[dict[str, str]], source_turn_id: str) -> list[MemoryEntry]:
        entries: list[MemoryEntry] = []
        now = format_timestamp_iso()
        user_messages = [msg["content"] for msg in messages if msg.get("role") == "user"]
        for text in user_messages:
            if not text:
                continue
            for pattern, mem_type in _USER_FACT_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    if content and len(content) >= 3:
                        entries.append(MemoryEntry(memory_type=mem_type, content=content, importance=0.85, created_at=now, last_accessed=now, source_turn_id=source_turn_id))
            for pattern in _EPISODIC_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    if content and len(content) >= 5:
                        entries.append(MemoryEntry(memory_type="episodic", content=content, importance=0.5, created_at=now, last_accessed=now, source_turn_id=source_turn_id))
        return entries

    def _extract_by_llm(self, messages: list[dict[str, str]], source_turn_id: str) -> list[MemoryEntry]:
        conversation = "\n".join(f"{msg['role']}: {msg['content']}" for msg in messages if msg.get("role") in ("user", "assistant"))
        prompt = _MEMORY_EXTRACTION_PROMPT.format(conversation=conversation)
        response = self.client.chat(messages=[{"role": "user", "content": prompt}], temperature=0.3, max_tokens=1000)
        raw_text = response.content.strip()
        if raw_text.startswith("```"):
            raw_text = re.sub(r"^```(?:json)?\s*", "", raw_text)
            raw_text = re.sub(r"\s*```$", "", raw_text)
        try:
            items = json.loads(raw_text)
        except json.JSONDecodeError:
            return []
        if not isinstance(items, list):
            return []
        now = format_timestamp_iso()
        entries: list[MemoryEntry] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            mem_type = item.get("memory_type", "semantic")
            content = item.get("content", "")
            importance = float(item.get("importance", 0.5))
            if mem_type not in ("user_fact", "semantic", "episodic"):
                mem_type = "semantic"
            importance = max(0.0, min(1.0, importance))
            if content and len(content.strip()) >= 3:
                entries.append(MemoryEntry(memory_type=mem_type, content=content.strip(), importance=importance, created_at=now, last_accessed=now, source_turn_id=source_turn_id))
        return entries

    def _deduplicate(self, new_entries: list[MemoryEntry]) -> list[MemoryEntry]:
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
                sim = self._text_similarity(new_entry.content, existing.content)
                if sim >= self._DEDUP_SIMILARITY_THRESHOLD:
                    is_duplicate = True
                    break
            if not is_duplicate:
                kept.append(new_entry)
        return kept

    @staticmethod
    def _text_similarity(text1: str, text2: str) -> float:
        if not text1 or not text2:
            return 0.0
        def get_bigrams(s: str) -> set[str]:
            cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", s)
            return {cleaned[i:i+2] for i in range(len(cleaned)-1)}
        bg1 = get_bigrams(text1)
        bg2 = get_bigrams(text2)
        if not bg1 or not bg2:
            return 0.0
        intersection = len(bg1 & bg2)
        union = len(bg1 | bg2)
        return intersection / union if union > 0 else 0.0
