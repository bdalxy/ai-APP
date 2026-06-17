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
from src.memory.memory_types import (
    ConflictResult,
    analyze_sentiment,
    detect_conflict,
    estimate_importance,
    extract_entities,
)
from src.memory.vector_store import MemoryEntry, VectorStore, cosine_similarity
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


# =============================================================================
# 规则提取模式的正则表达式
# =============================================================================

# 用户事实的提取规则
_USER_FACT_PATTERNS: list[tuple[re.Pattern, str]] = [
    # "我是..." / "我叫..."（去掉 $ 锚点，支持句子中间出现）
    (re.compile(r"我是([\u4e00-\u9fff\w]{1,20})"), "user_fact"),
    (re.compile(r"我叫([\u4e00-\u9fff\w]{1,20})"), "user_fact"),
    # "我喜欢..." / "我不喜欢..."（"我"可选，支持省略主语的流水句）
    (re.compile(r"我?(喜欢|不喜欢|讨厌|爱)([\u4e00-\u9fff\w，,、\s]{2,30}?)(?:[，。；.!?！？]|$)"), "user_fact"),
    # "我住在..." / "我的家乡是..."（"我"可选，支持省略主语的流水句）
    (re.compile(r"我?(住在|家在|的家乡是|在)([\u4e00-\u9fff\w，,、\s]{2,30}?)(?:[，。；.!?！？]|$)"), "user_fact"),
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


def _to_third_person(text: str) -> str:
    """将第一人称文本转换为第三人称视角。

    我叫李明 → 用户叫李明
    我是程序员 → 用户是程序员
    我的生日是1月1日 → 用户的生日是1月1日
    """
    if not text:
        return text
    # 先替换"我的"→"用户的"，再替换"我"→"用户"
    result = re.sub(r"^我的", "用户的", text)
    result = re.sub(r"^我", "用户", result)
    return result


def _infer_source(text: str, speaker_role: str) -> str:
    """基于内容语义推断记忆来源分类。

    规则：
    - 如果内容以"用户"开头或包含"用户"相关表述 → user_related
    - 如果内容以"AI"开头或包含"AI角色"相关表述 → ai_related
    - 回退：按说话者角色判断（user→user_related, assistant→ai_related）

    Args:
        text: 消息文本。
        speaker_role: 说话者角色（"user" 或 "assistant"）。

    Returns:
        来源分类字符串。
    """
    # 用户说话时，检查是否在描述AI
    if speaker_role == "user":
        ai_patterns = [
            r"你是", r"你真", r"你很", r"你太", r"你非常",
            r"AI你", r"ai你",
        ]
        # "你好"是纯问候语，不表示在描述AI，单独过滤
        if text.strip() in ("你好", "嗨", "在吗", "hello", "hi"):
            return "user_related"
        for pat in ai_patterns:
            if re.search(pat, text):
                return "ai_related"
        return "user_related"

    # AI说话时，检查是否在描述用户
    if speaker_role == "assistant":
        user_patterns = [
            r"你是", r"你叫", r"你喜欢", r"你住在", r"你的",
            r"用户", r"看来你", r"原来你", r"你之前", r"你曾经",
        ]
        for pat in user_patterns:
            if re.search(pat, text):
                return "user_related"
        return "ai_related"

    return "other"


# 无效记忆内容关键词：这些词单独出现时不构成有效记忆
_INVALID_MEMORY_PATTERNS: list[re.Pattern] = [
    re.compile(r"^[\s，,。；.!?！？]+$"),           # 纯标点/空白
    re.compile(r"^(哈哈|嘿嘿|嘻嘻|呵呵|嗯嗯|哦哦|啊啊)+$"),  # 纯语气词
    re.compile(r"^(就是|那个|随便|还行|可以|好吧|是的|对的)$"),  # 无信息量短语
    re.compile(r"^(我是|我叫|我喜欢|我不喜欢|我住在|我家在)$"),  # 不完整的句式（无宾语）
    re.compile(r"^(今天|昨天|刚刚|刚才|上周|前几天)$"),       # 只有时间词无内容
]


def _is_valid_memory_content(content: str) -> bool:
    """检查记忆内容是否有效（不是噪音/语气词/不完整信息）。

    Args:
        content: 记忆文本内容。

    Returns:
        True 如果内容有效，False 如果应被过滤。
    """
    if not content or not content.strip():
        return False

    stripped = content.strip()

    # 检查所有无效模式
    for pattern in _INVALID_MEMORY_PATTERNS:
        if pattern.match(stripped):
            return False

    # 太短且无实质内容
    if len(stripped) < 4 and not any(
        c in stripped for c in "是叫喜欢爱恨有住在做当"
    ):
        return False

    return True


# LLM 提取的额外质量过滤规则
_LLM_INVALID_CATEGORIES: set[str] = {
    "greeting", "farewell", "small_talk", "backchannel", "acknowledgment",
}

_LLM_TRIVIAL_PATTERNS: list[re.Pattern] = [
    re.compile(r"^(用户|AI角色)(说了|回复了|表示|说)[\u4e00-\u9fff]{1,5}$"),  # "用户说了你好"
    re.compile(r"^(用户|AI角色)(打了个|咳嗽了|打了个喷嚏|伸了个懒腰)$"),  # 过于琐碎
    re.compile(r"^对话中(用户|AI角色)使用了[\u4e00-\u9fff]{1,3}语气$"),  # "对话中用户使用了友好语气"
    # 注意：^[^。，；]{1,6}$ 已移除，会误杀"用户27岁"等5-6字符的重要短信息
    # 改为更精确的过滤：仅过滤纯语气/动作描述，保留含数字、年龄、姓名等关键信息的短内容
    re.compile(r"^(用户|AI角色)(笑|哭|叹气|点头|摇头|沉默|停顿|犹豫)了?$"),  # 纯动作描述
    re.compile(r"^(用户|AI角色)(很|非常|有点|稍微)(高兴|难过|生气|开心|紧张|焦虑|兴奋)$"),  # 过于宽泛的情绪
]


def _is_valid_llm_memory(content: str, mem_type: str, importance: float) -> bool:
    """LLM 提取结果的后处理质量过滤。

    过滤规则：
    1. 内容太短（<6字符且无实质内容）
    2. 过于琐碎的身体动作/语气描述
    3. 重要性过低（<0.2）的语义记忆

    Args:
        content: 记忆内容。
        mem_type: 记忆类型。
        importance: LLM 给出的重要性评分。

    Returns:
        True 如果应保留，False 如果应过滤。
    """
    if not content or not content.strip():
        return False

    stripped = content.strip()

    # 基础有效性检查（复用规则提取的过滤）
    if not _is_valid_memory_content(stripped):
        return False

    # 检查琐碎模式
    for pattern in _LLM_TRIVIAL_PATTERNS:
        if pattern.match(stripped):
            return False

    # 极低重要性的语义记忆过滤
    if mem_type == "semantic" and importance < 0.25:
        return False

    # 极低重要性的事件记忆过滤
    if mem_type == "episodic" and importance < 0.15:
        return False

    return True


# =============================================================================
# LLM 提取 Prompt
# =============================================================================

_MEMORY_EXTRACTION_PROMPT = """你是一个记忆提取助手。请从以下对话中提取值得长期记忆的信息。

提取规则：
1. **user_fact（用户事实）**：关于用户明确表达的个人信息，如姓名、喜好、住址、职业等。
2. **semantic（语义知识）**：对话中涉及的重要知识、概念、观点。
3. **episodic（事件记忆）**：对话中提到的重要事件、经历、活动。

**来源分类（source）**——每条记忆必须标注来源：
- **ai_related**：关于AI角色自身的信息（AI角色的立场、经历、偏好、设定等）
- **user_related**：关于用户的信息（用户的个人信息、经历、偏好等）
- **other**：关于第三方人物、环境、世界设定的信息，或不明确归属于AI或用户的信息

**重要**：content 必须使用第三人称描述，如"用户叫李明"而非"我叫李明"，"AI角色喜欢猫"而非"我喜欢猫"。

输出格式：JSON 数组，每个元素包含：
- "memory_type": "user_fact" | "semantic" | "episodic"
- "source": "ai_related" | "user_related" | "other"
- "content": 记忆文本（简洁、完整的句子，第三人称）
- "importance": 重要性评分（0.0~1.0），用户事实 > 0.8，语义知识 > 0.5，事件 > 0.4

如果没有值得长期记忆的信息，返回空数组：[]。

对话内容：
{conversation}

请只输出 JSON 数组，不要包含其他内容。"""


# =============================================================================
# 记忆提取器
# =============================================================================

# 低信息量关键词：这些词频繁出现在纯聊天/寒暄中，不包含长期记忆价值
_LOW_INFO_KEYWORDS: set[str] = {
    "哈哈", "嗯", "哦", "好", "行", "是的", "对", "谢谢", "不客气",
    "你好", "嗨", "在吗", "晚安", "早安", "再见", "拜拜", "好的呢",
    "嗯嗯", "好呀", "好吧", "没事", "没关系", "就是", "那个", "随便",
    "hh", "emmm", "emmm", "哈哈", "嘿嘿", "嘻嘻",
}

# 高信息量短模式：即使对话很短，包含这些模式也不应跳过LLM提取
_HIGH_INFO_SHORT_PATTERNS: list[re.Pattern] = [
    re.compile(r"\d+岁"),         # "我27岁"
    re.compile(r"我叫[\u4e00-\u9fff]{1,4}"),  # "我叫李明"
    re.compile(r"我是[\u4e00-\u9fff]{1,10}"), # "我是程序员"
    re.compile(r"我喜欢"),         # "我喜欢猫"
    re.compile(r"我住在"),         # "我住在北京"
    re.compile(r"我的[\u4e00-\u9fff]"),  # "我的生日"
    re.compile(r"我(不|很|最|非常|特别)(喜欢|爱|讨厌|恨)"),  # 情感表达
    re.compile(r"\d{4}年"),        # 年份
    re.compile(r"\d{1,2}月\d{1,2}日"),  # 具体日期
]


def _is_low_info_conversation(messages: list[dict[str, str]]) -> bool:
    """检测对话是否属于低信息量的寒暄/闲聊，不值得LLM提取。

    判断标准：
    1. 总字符数 < 15（很短的消息），但包含高信息量短模式（如"我27岁"）例外
    2. 对话以寒暄关键词为主（占比 > 80%）

    Args:
        messages: 对话消息列表。

    Returns:
        True 表示低信息量，应跳过 LLM 提取。
    """
    if not messages:
        return True

    # 收集所有对话内容
    all_text = "".join(
        msg.get("content", "") for msg in messages
        if msg.get("role") in ("user", "assistant")
    )

    # 标准1：总字符数 < 15（降低阈值，短消息也可能包含重要信息如"我27岁"）
    if len(all_text) < 15:
        # 检查是否包含高信息量短模式，如果包含则不跳过
        for pattern in _HIGH_INFO_SHORT_PATTERNS:
            if pattern.search(all_text):
                return False  # 包含重要信息，不跳过
        return True

    # 标准2：寒暄关键词占比过高
    low_info_count = 0
    total_chars = len(all_text)
    for kw in _LOW_INFO_KEYWORDS:
        low_info_count += all_text.count(kw) * len(kw)

    if total_chars > 0 and low_info_count / total_chars > 0.8:
        return True

    return False


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

        # 冲突检测：检查新记忆是否与已有记忆冲突
        if self.vector_store and entries:
            entries = self._check_conflicts(entries)

        # 自动补充重要性评分和情感分析
        entries = self._enrich_entries(entries)

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

        # 按角色提取，区分来源
        role_messages: list[tuple[str, str]] = []  # (role, content)
        for msg in messages:
            role = msg.get("role", "")
            content = msg.get("content", "")
            if role in ("user", "assistant") and content:
                role_messages.append((role, content))

        for role, text in role_messages:
            if not text:
                continue

            # 确定来源分类：基于内容语义推断，而非简单按说话者角色
            # 规则：如果内容包含"用户"→user_related，包含"AI"→ai_related
            # 回退时按说话者角色判断
            source = _infer_source(text, role)

            self._log.info(f"[规则提取] 处理消息: role={role}, repr={repr(text[:80])}, len={len(text)}")

            # 提取用户事实
            for pattern, mem_type in _USER_FACT_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    self._log.info(f"[规则提取] 匹配到: '{content}' (len={len(content)}, type={mem_type})")
                    if content and len(content) >= 3:
                        # 质量过滤：排除纯语气词和噪音内容
                        if not _is_valid_memory_content(content):
                            self._log.info(f"[规则提取] 跳过低质量内容: '{content}'")
                            continue
                        content = _to_third_person(content)
                        entry = MemoryEntry(
                            memory_type=mem_type,
                            content=content,
                            importance=0.85,
                            created_at=now,
                            last_accessed=now,
                            source_turn_id=source_turn_id,
                            source=source,
                        )
                        entries.append(entry)

            # 提取事件记忆
            for pattern in _EPISODIC_PATTERNS:
                for match in pattern.finditer(text):
                    content = match.group(0).strip()
                    self._log.info(f"[规则提取-事件] 匹配到: '{content}' (len={len(content)})")
                    if content and len(content) >= 5:
                        # 质量过滤：排除纯语气词和噪音内容
                        if not _is_valid_memory_content(content):
                            self._log.info(f"[规则提取-事件] 跳过低质量内容: '{content}'")
                            continue
                        entry = MemoryEntry(
                            memory_type="episodic",
                            content=content,
                            importance=0.5,
                            created_at=now,
                            last_accessed=now,
                            source_turn_id=source_turn_id,
                            source=source,
                        )
                        entries.append(entry)

        self._log.info(f"[规则提取] 从 {len(role_messages)} 条消息中提取 {len(entries)} 条记忆")
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

        prompt = _MEMORY_EXTRACTION_PROMPT.replace("{conversation}", conversation)

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
        except json.JSONDecodeError:
            # T-FIX-05: json.loads 失败后，尝试多种正则模式提取 [{...}] 部分重试解析
            # 使用非贪婪匹配 + 多层回退，提升 JSON 降级解析鲁棒性
            parsed = False
            for pattern in [
                r"\[\s*\{[\s\S]*?\}\s*\]",   # 标准 JSON 数组（非贪婪）
                r"\[[\s\S]*\]",               # 贪婪回退：匹配到最后一个 ]
                r"\{[\s\S]*\}",               # 最差回退：尝试解析为单个对象
            ]:
                match = re.search(pattern, raw_text)
                if not match:
                    continue
                try:
                    result = json.loads(match.group(0))
                    if isinstance(result, list):
                        items = result
                    elif isinstance(result, dict):
                        items = [result]
                    else:
                        continue
                    parsed = True
                    self._log.info(f"通过正则模式 {pattern[:20]}... 成功解析 JSON, 获得 {len(items)} 条记忆")
                    break
                except json.JSONDecodeError:
                    continue

            if not parsed:
                error_msg = (
                    f"LLM 返回的 JSON 解析失败（3种正则模式均失败），"
                    f"raw_text 前200字符: {raw_text[:200]}"
                )
                self._log.warning(error_msg)
                # B2-FIX: 抛出异常而非静默返回 []，
                # 让 auto 模式的 try/except 捕获并回退到规则提取模式
                raise ValueError(error_msg)

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
            source = item.get("source", "user_related")

            # 类型校验
            if mem_type not in ("user_fact", "semantic", "episodic"):
                mem_type = "semantic"

            # 来源校验
            if source not in ("ai_related", "user_related", "other"):
                source = "user_related"

            # 重要性范围校验
            importance = max(0.0, min(1.0, importance))

            if content and len(content.strip()) >= 3:
                # 后处理过滤：排除过于琐碎或不完整的记忆
                content = content.strip()
                if not _is_valid_llm_memory(content, mem_type, importance):
                    self._log.debug(f"[LLM 提取] 跳过低质量记忆: '{content[:40]}...'")
                    continue
                entry = MemoryEntry(
                    memory_type=mem_type,
                    content=content.strip(),
                    importance=importance,
                    created_at=now,
                    last_accessed=now,
                    source_turn_id=source_turn_id,
                    source=source,
                )
                entries.append(entry)

        self._log.debug(f"[LLM 提取] 提取 {len(entries)} 条记忆")
        return entries

    # -------------------------------------------------------------------------
    # 冲突检测
    # -------------------------------------------------------------------------

    def _check_conflicts(
        self,
        new_entries: list[MemoryEntry],
    ) -> list[MemoryEntry]:
        """检测新记忆是否与已有记忆存在冲突。

        对每条新记忆:
            1. 获取同类型的已有记忆
            2. 调用 detect_conflict() 进行规则级冲突检测
            3. 如果检测到冲突，标记旧记忆为 archived
            4. 记录记忆关系

        Args:
            new_entries: 新提取的记忆列表。

        Returns:
            处理后的记忆列表（可能包含合并后的冲突解决结果）。
        """
        if not self.vector_store:
            return new_entries

        processed: list[MemoryEntry] = []
        archived_count = 0

        for entry in new_entries:
            try:
                # 获取同类型的已有记忆
                existing = self.vector_store.get_by_type(entry.memory_type)
                if not existing:
                    processed.append(entry)
                    continue

                # 构建冲突检测格式
                existing_dicts = [
                    {"id": e.id, "content": e.content, "memory_type": e.memory_type}
                    for e in existing[:50]  # 限制检查数量
                ]

                conflict = detect_conflict(entry.content, existing_dicts, entry.memory_type)

                if conflict.has_conflict and conflict.confidence > 0.5:
                    # 高置信度冲突：标记旧记忆为 archived
                    try:
                        self.vector_store.mark_archived([conflict.conflicting_memory_id])
                        archived_count += 1
                        self._log.info(
                            f"[冲突] 已解决: new='{entry.content[:40]}...', "
                            f"archived='{conflict.conflicting_memory_content[:40]}...', "
                            f"type={conflict.conflict_type}"
                        )
                        # 记录关系
                        try:
                            self.vector_store.add_relation(
                                entry.id,
                                conflict.conflicting_memory_id,
                                relation_type=conflict.conflict_type,
                                confidence=conflict.confidence,
                                notes=conflict.explanation,
                            )
                        except Exception:
                            pass  # 关系记录失败不影响主流程
                    except Exception as e:
                        self._log.debug(f"[冲突] 归档失败: {e}")

                processed.append(entry)

            except Exception as e:
                self._log.debug(f"[冲突检测] 处理失败: {e}")
                processed.append(entry)

        if archived_count > 0:
            self._log.info(f"[冲突] 共解决 {archived_count} 条冲突记忆")

        return processed

    # -------------------------------------------------------------------------
    # 条目增强（重要性+情感+实体）
    # -------------------------------------------------------------------------

    def _enrich_entries(
        self,
        entries: list[MemoryEntry],
    ) -> list[MemoryEntry]:
        """自动补充记忆条目的重要性评分、情感分析和实体信息。

        对每条记忆:
            1. 如果 importance 为默认值 0.5，自动估算
            2. 进行情感分析并记录
            3. 提取实体信息

        Args:
            entries: 记忆条目列表。

        Returns:
            增强后的记忆条目列表。
        """
        for entry in entries:
            try:
                # 1. 自动估算重要性（仅在默认值时）
                if entry.importance == 0.5 or entry.importance == 0.0:
                    sentiment = analyze_sentiment(entry.content)
                    entry.importance = estimate_importance(
                        entry.content,
                        entry.memory_type,
                        sentiment,
                    )

                # 2. 情感分析：仅在内容质量达标时才提升重要性
                if len(entry.content) >= 10:  # 内容足够长才考虑情感加成
                    sentiment = analyze_sentiment(entry.content)
                    if sentiment["sentiment"] != "neutral":
                        # 强情感且有实体才提升
                        if abs(sentiment["score"]) > 0.5:
                            entities = extract_entities(entry.content)
                            if entities:
                                entry.importance = min(entry.importance + 0.1, 1.0)

                # 3. 实体提取：仅在内容质量达标时加分
                if len(entry.content) >= 8:
                    entities = extract_entities(entry.content)
                    if entities and len(entities) >= 2:  # 至少2个实体才加分
                        entry.importance = min(entry.importance + 0.05 * len(entities), 1.0)

            except Exception as e:
                self._log.debug(f"[增强] 处理失败: {e}")

        return entries

    # -------------------------------------------------------------------------
    # 去重逻辑
    # -------------------------------------------------------------------------

    def _deduplicate(self, new_entries: list[MemoryEntry]) -> list[MemoryEntry]:
        """去除与已有记忆高度相似的新条目。

        优先使用 embedding 余弦相似度进行语义去重（当新旧记忆都有 embedding 时），
        回退到 bigram Jaccard 相似度进行字符级去重。

        Args:
            new_entries: 新提取的记忆条目列表。

        Returns:
            去重后的记忆条目列表。
        """
        if not self.vector_store:
            return new_entries

        # 只用最近 500 条记忆做去重，避免全量加载导致性能下降
        existing_entries = self.vector_store.get_recent_entries(500)
        if not existing_entries:
            return new_entries

        # 按类型分组已有记忆，减少 O(n*m) 中的 n
        existing_by_type: dict[str, list[MemoryEntry]] = {}
        for e in existing_entries:
            existing_by_type.setdefault(e.memory_type, []).append(e)

        kept: list[MemoryEntry] = []

        for new_entry in new_entries:
            is_duplicate = False
            candidates = existing_by_type.get(new_entry.memory_type, [])

            for existing in candidates:
                # 优先使用语义向量去重（如果双方都有 embedding）
                if new_entry.embedding and existing.embedding:
                    try:
                        sim = cosine_similarity(new_entry.embedding, existing.embedding)
                    except (ValueError, TypeError):
                        sim = self._text_similarity(new_entry.content, existing.content)
                else:
                    # 回退到字符级 bigram Jaccard
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