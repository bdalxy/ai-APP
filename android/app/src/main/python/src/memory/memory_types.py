"""记忆类型增强模块。

扩展记忆系统中的类型定义、冲突检测、实体提取和情感分析。
为记忆系统提供更丰富的语义表达能力。

核心类:
    - MemoryCategory: 记忆分类枚举（扩展类型系统）
    - MemoryRelation: 记忆关系类型枚举
    - ConflictResult: 冲突检测结果数据类
    - EntityInfo: 提取的实体信息数据类
    - MemoryTag: 记忆标签数据类
    - MemoryChangeLog: 记忆变更日志数据类
    - conflict_detector: 冲突检测工具函数
    - entity_extractor: 实体提取工具函数
    - sentiment_analyzer: 情感分析工具函数
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


# =============================================================================
# 记忆分类扩展
# =============================================================================

# ── 旧分类名称 → 新分类名称映射（模块级常量，放在 Enum 外部避免被转为枚举成员）──
_LEGACY_MAP: dict[str, str] = {
    "user_identity": "user_profile",
    "user_attribute": "user_profile",
    "emotional_mood": "emotional_state",
    "emotional_sentiment": "emotional_state",
}


class MemoryCategory(Enum):
    """记忆分类枚举 —— 扩展自原有的三种基础类型（V1.2-14 简化版）。

    层级结构:
        EPISODIC (事件记忆)
            ├── EVENT: 具体事件（"用户昨天去了公园"）
            ├── EXPERIENCE: 经历体验（"用户第一次坐飞机很紧张"）
            └── ACTIVITY: 活动记录（"用户每周三去健身房"）

        SEMANTIC (语义知识)
            ├── KNOWLEDGE: 通用知识（"用户知道Python是编程语言"）
            ├── OPINION: 观点态度（"用户认为AI应该被监管"）
            └── CONCEPT: 抽象概念（"用户理解的'幸福'定义"）

        USER_FACT (用户事实)
            ├── PROFILE: 用户画像（身份+属性）— "用户叫李明"、"用户生日是1月1日"
            ├── PREFERENCE: 偏好（"用户喜欢猫"）
            ├── RELATIONSHIP: 人际关系（"用户有个妹妹叫小红"）
            └── STATUS: 状态（"用户目前在工作"）

        EMOTIONAL (情感记忆)
            └── STATE: 情感状态（情绪+倾向）— "用户今天心情很好"

        SUMMARY (摘要记忆)
            └── SUMMARY: 归档摘要（由归档器生成）
    """

    # 事件记忆子类
    EPISODIC_EVENT = "episodic_event"
    EPISODIC_EXPERIENCE = "episodic_experience"
    EPISODIC_ACTIVITY = "episodic_activity"

    # 语义知识子类
    SEMANTIC_KNOWLEDGE = "semantic_knowledge"
    SEMANTIC_OPINION = "semantic_opinion"
    SEMANTIC_CONCEPT = "semantic_concept"

    # 用户事实子类（V1.2-14: user_identity + user_attribute → user_profile）
    USER_PROFILE = "user_profile"
    USER_PREFERENCE = "user_preference"
    USER_RELATIONSHIP = "user_relationship"
    USER_STATUS = "user_status"

    # 情感记忆（V1.2-14: emotional_mood + emotional_sentiment → emotional_state）
    EMOTIONAL_STATE = "emotional_state"

    # 摘
    SUMMARY = "summary"

    @property
    def parent_type(self) -> str:
        """获取父级类型（用于兼容旧系统）。"""
        mapping = {
            MemoryCategory.EPISODIC_EVENT: "episodic",
            MemoryCategory.EPISODIC_EXPERIENCE: "episodic",
            MemoryCategory.EPISODIC_ACTIVITY: "episodic",
            MemoryCategory.SEMANTIC_KNOWLEDGE: "semantic",
            MemoryCategory.SEMANTIC_OPINION: "semantic",
            MemoryCategory.SEMANTIC_CONCEPT: "semantic",
            MemoryCategory.USER_PROFILE: "user_fact",
            MemoryCategory.USER_PREFERENCE: "user_fact",
            MemoryCategory.USER_RELATIONSHIP: "user_fact",
            MemoryCategory.USER_STATUS: "user_fact",
            MemoryCategory.EMOTIONAL_STATE: "emotional",
            MemoryCategory.SUMMARY: "summary",
        }
        return mapping.get(self, "semantic")

    @classmethod
    def from_string(cls, s: str) -> "MemoryCategory":
        """从字符串解析分类，兼容旧类型名称（自动映射到新分类）。"""
        # V1.2-14: 旧分类名称自动映射到新分类
        if s in _LEGACY_MAP:
            s = _LEGACY_MAP[s]
        # 旧类型名称映射
        legacy_map = {
            "episodic": cls.EPISODIC_EVENT,
            "semantic": cls.SEMANTIC_KNOWLEDGE,
            "user_fact": cls.USER_PROFILE,
            "emotional": cls.EMOTIONAL_STATE,
            "summary": cls.SUMMARY,
        }
        if s in legacy_map:
            return legacy_map[s]
        try:
            return cls(s)
        except ValueError:
            return cls.SEMANTIC_KNOWLEDGE


# =============================================================================
# 记忆关系类型
# =============================================================================

class MemoryRelation(Enum):
    """记忆关系类型枚举。

    用于构建记忆关系图，支持以下关系:
        - CONTRADICTS: 新记忆与旧记忆矛盾
        - EXTENDS: 新记忆扩展了旧记忆的信息
        - REFINES: 新记忆细化了旧记忆（更精确）
        - SUPERSEDES: 新记忆取代了旧记忆（旧记忆过时）
        - RELATED_TO: 两个记忆相关但不明确
        - CAUSED_BY: 新记忆由旧记忆引起
        - PART_OF: 新记忆是旧记忆的组成部分
        - SIMILAR_TO: 两个记忆内容相似
    """
    CONTRADICTS = "contradicts"
    EXTENDS = "extends"
    REFINES = "refines"
    SUPERSEDES = "supersedes"
    RELATED_TO = "related_to"
    CAUSED_BY = "caused_by"
    PART_OF = "part_of"
    SIMILAR_TO = "similar_to"


# =============================================================================
# 冲突检测
# =============================================================================

@dataclass
class ConflictResult:
    """冲突检测结果。

    Attributes:
        has_conflict: 是否存在冲突。
        conflicting_memory_id: 冲突的已有记忆 ID（如果有）。
        conflicting_memory_content: 冲突的已有记忆内容。
        new_content: 新记忆内容。
        conflict_type: 冲突类型（"contradiction" | "refinement" | "supersede"）。
        confidence: 冲突置信度（0.0~1.0）。
        explanation: 冲突解释文本。
    """
    has_conflict: bool = False
    conflicting_memory_id: str = ""
    conflicting_memory_content: str = ""
    new_content: str = ""
    conflict_type: str = ""
    confidence: float = 0.0
    explanation: str = ""


@dataclass
class EntityInfo:
    """提取的实体信息。

    Attributes:
        name: 实体名称。
        entity_type: 实体类型（"person" | "location" | "organization" | "event" | "object" | "date" | "other"）。
        mentions: 在文本中出现的次数。
        first_seen: 首次出现时间。
        attributes: 实体的属性键值对。
    """
    name: str = ""
    entity_type: str = "other"
    mentions: int = 0
    first_seen: str = ""
    attributes: dict[str, str] = field(default_factory=dict)


@dataclass
class MemoryTag:
    """记忆标签数据类。

    Attributes:
        name: 标签名称。
        color: 标签颜色（十六进制）。
        created_at: 创建时间。
        memory_count: 关联的记忆数量。
    """
    name: str = ""
    color: str = "#9090A0"
    created_at: str = ""
    memory_count: int = 0


@dataclass
class MemoryChangeLog:
    """记忆变更日志。

    Attributes:
        memory_id: 记忆 UUID。
        rowid: 记忆行 ID。
        change_type: 变更类型（"create" | "update" | "delete" | "archive" | "consolidate"）。
        old_content: 变更前内容（update/delete 时）。
        new_content: 变更后内容（create/update 时）。
        changed_at: 变更时间。
        reason: 变更原因。
    """
    memory_id: str = ""
    rowid: int = 0
    change_type: str = ""
    old_content: str = ""
    new_content: str = ""
    changed_at: str = ""
    reason: str = ""


# =============================================================================
# 冲突检测工具函数
# =============================================================================

# 冲突检测关键词模式
_CONTRADICTION_PATTERNS: list[tuple[re.Pattern, str]] = [
    # 否定模式："不是...而是..."、"不再..."、"不...了"
    (re.compile(r"不(?:是|再|在|会|能|想|喜欢|爱|住)([\u4e00-\u9fff\w，,、\s]{2,50})"), "contradiction"),
    # 改变模式："现在...了"、"已经...了"、"改成...了"
    (re.compile(r"(?:现在|已经|改成|换成|变成|改为)([\u4e00-\u9fff\w，,、\s]{2,50})"), "supersede"),
    # 修正模式："其实..."、"实际上..."、"准确地说..."
    (re.compile(r"(?:其实|实际上|准确地说|更正一下|纠正一下)([\u4e00-\u9fff\w，,、\s]{2,50})"), "refinement"),
    # 对比模式："以前...现在..."、"过去...现在..."
    (re.compile(r"(?:以前|过去|之前)([\u4e00-\u9fff\w，,、\s]{2,30})(?:现在|如今)([\u4e00-\u9fff\w，,、\s]{2,30})"), "supersede"),
]


def detect_conflict(
    new_content: str,
    existing_memories: list[dict[str, Any]],
    new_content_type: str = "",
) -> ConflictResult:
    """检测新记忆是否与已有记忆存在冲突。

    使用规则模式检测常见的中文冲突表达，如"不再是"、"现在...了"等。
    配合 LLM 进行语义级别的冲突检测（在 MemoryExtractor 中调用）。

    Args:
        new_content: 新记忆的文本内容。
        existing_memories: 已有的相关记忆列表，每项包含 id, content, memory_type。

    Returns:
        ConflictResult，包含冲突检测的详细信息。
    """
    if not new_content or not existing_memories:
        return ConflictResult()

    # 1. 规则模式检测：新内容是否包含冲突关键词
    rule_conflict_type = ""
    for pattern, ctype in _CONTRADICTION_PATTERNS:
        if pattern.search(new_content):
            rule_conflict_type = ctype
            break

    if not rule_conflict_type:
        return ConflictResult()

    # 2. 在已有记忆中寻找可能冲突的记忆
    # 提取新记忆中的关键实体（人名、地名、属性词）
    key_entities = _extract_key_entities(new_content)
    if not key_entities:
        return ConflictResult()

    # 3. 匹配含有相同实体的已有记忆
    best_match: dict[str, Any] = {}
    best_confidence = 0.0

    for mem in existing_memories:
        content = mem.get("content", "")
        if not content:
            continue

        # 计算实体重叠度
        mem_entities = _extract_key_entities(content)
        overlap = len(key_entities & mem_entities)
        if overlap == 0:
            continue

        # 实体重叠 + 相同类型 = 高置信度冲突
        same_type = mem.get("memory_type", "") == new_content_type
        confidence = min(overlap / len(key_entities), 1.0)
        if same_type:
            confidence = min(confidence + 0.2, 1.0)

        if confidence > best_confidence:
            best_confidence = confidence
            best_match = mem

    if best_match and best_confidence > 0.3:
        return ConflictResult(
            has_conflict=True,
            conflicting_memory_id=best_match.get("id", ""),
            conflicting_memory_content=best_match.get("content", ""),
            new_content=new_content,
            conflict_type=rule_conflict_type,
            confidence=best_confidence,
            explanation=f"检测到{rule_conflict_type}冲突，实体重叠度={best_confidence:.2f}",
        )

    return ConflictResult()


def _extract_key_entities(text: str) -> set[str]:
    """从文本中提取关键实体用于冲突检测。

    提取中文实体：人名、地名、专有名词、数字+单位等。
    """
    entities: set[str] = set()

    # 提取中文专有名词（连续2-4个汉字）
    chinese_words = re.findall(r"[\u4e00-\u9fff]{2,4}", text)
    for word in chinese_words:
        # 过滤常见虚词
        if word not in {"不是", "就是", "已经", "现在", "其实", "所以", "因为", "但是",
                        "可以", "没有", "这个", "那个", "什么", "怎么", "为什么",
                        "我们", "他们", "你们", "自己", "还是", "只是", "不过",
                        "如果", "虽然", "然而", "而且", "或者", "应该", "可能"}:
            entities.add(word)

    # 提取数字+单位
    num_units = re.findall(r"\d+[岁年月日天次个只条]", text)
    entities.update(num_units)

    return entities


# =============================================================================
# 实体提取
# =============================================================================

# 实体提取正则模式
_ENTITY_PATTERNS: list[tuple[re.Pattern, str]] = [
    # 人名："我叫..."、"我是..."
    (re.compile(r"(?:我(?:叫|是)|名为|称呼[我为]*)([\u4e00-\u9fff]{2,4})"), "person"),
    # 地名："住在..."、"在...城市"
    (re.compile(r"(?:住在|家在|在)([\u4e00-\u9fff]{2,6}(?:市|省|区|县|镇|村|城))"), "location"),
    # 组织："在...公司/学校/医院工作"
    (re.compile(r"(?:在|于)([\u4e00-\u9fff]{2,10}(?:公司|学校|医院|大学|机构|部门))"), "organization"),
    # 日期："2024年..."、"1月1日"
    (re.compile(r"(\d{4}年\d{1,2}月\d{1,2}日|\d{1,2}月\d{1,2}日)"), "date"),
    # 事件："参加了..."、"去了..."
    (re.compile(r"(?:参加|去了|经历了|举办了)([\u4e00-\u9fff]{2,10}(?:活动|比赛|会议|聚会|旅行|考试))"), "event"),
]


def extract_entities(text: str) -> list[EntityInfo]:
    """从文本中提取命名实体。

    使用规则模式提取中文实体：人名、地名、组织名、日期、事件等。

    Args:
        text: 待提取的文本。

    Returns:
        EntityInfo 列表，按出现次数降序排列。
    """
    if not text:
        return []

    extracted: dict[str, EntityInfo] = {}

    for pattern, entity_type in _ENTITY_PATTERNS:
        for match in pattern.finditer(text):
            name = match.group(1).strip()
            if name and len(name) >= 2:
                if name not in extracted:
                    extracted[name] = EntityInfo(
                        name=name,
                        entity_type=entity_type,
                        mentions=0,
                    )
                extracted[name].mentions += 1

    # 按出现次数排序
    result = sorted(
        extracted.values(),
        key=lambda e: e.mentions,
        reverse=True,
    )
    return result


# =============================================================================
# 情感分析
# =============================================================================

# 情感词典
_POSITIVE_WORDS: set[str] = {
    "开心", "高兴", "快乐", "喜欢", "爱", "满意", "幸福", "兴奋", "期待",
    "感动", "自豪", "骄傲", "放松", "舒适", "温暖", "惊喜", "满足",
    "好", "棒", "赞", "优秀", "成功", "顺利", "完美", "不错",
}

_NEGATIVE_WORDS: set[str] = {
    "难过", "伤心", "生气", "愤怒", "失望", "沮丧", "焦虑", "害怕",
    "担心", "紧张", "痛苦", "无聊", "烦躁", "厌倦", "孤独", "委屈",
    "坏", "差", "糟糕", "失败", "困难", "麻烦", "讨厌", "恨",
}

_EMOTION_CATEGORIES: dict[str, set[str]] = {
    "joy": {"开心", "高兴", "快乐", "兴奋", "惊喜", "满足", "幸福"},
    "sadness": {"难过", "伤心", "沮丧", "失望", "孤独", "委屈"},
    "anger": {"生气", "愤怒", "烦躁", "讨厌", "恨"},
    "fear": {"害怕", "担心", "紧张", "焦虑"},
    "love": {"喜欢", "爱", "感动", "温暖", "自豪", "骄傲"},
    "neutral": {},
}


def analyze_sentiment(text: str) -> dict[str, Any]:
    """分析文本的情感倾向。

    使用情感词典进行简单的情感分析，返回情感分数和分类。

    Args:
        text: 待分析的文本。

    Returns:
        包含以下字段的字典:
            - sentiment: "positive" | "negative" | "neutral"
            - score: 情感分数（-1.0 ~ 1.0）
            - emotion: 主要情绪类别
            - positive_words: 检测到的积极词列表
            - negative_words: 检测到的消极词列表
    """
    if not text:
        return {
            "sentiment": "neutral",
            "score": 0.0,
            "emotion": "neutral",
            "positive_words": [],
            "negative_words": [],
        }

    positive_found: list[str] = []
    negative_found: list[str] = []
    emotion_counts: dict[str, int] = {}

    for word in _POSITIVE_WORDS:
        if word in text:
            positive_found.append(word)
            # 找到情绪类别
            for emotion, words in _EMOTION_CATEGORIES.items():
                if word in words:
                    emotion_counts[emotion] = emotion_counts.get(emotion, 0) + 1

    for word in _NEGATIVE_WORDS:
        if word in text:
            negative_found.append(word)
            for emotion, words in _EMOTION_CATEGORIES.items():
                if word in words:
                    emotion_counts[emotion] = emotion_counts.get(emotion, 0) + 1

    pos_count = len(positive_found)
    neg_count = len(negative_found)
    total = pos_count + neg_count

    if total == 0:
        return {
            "sentiment": "neutral",
            "score": 0.0,
            "emotion": "neutral",
            "positive_words": [],
            "negative_words": [],
        }

    score = (pos_count - neg_count) / total

    if score > 0.2:
        sentiment = "positive"
    elif score < -0.2:
        sentiment = "negative"
    else:
        sentiment = "neutral"

    # 主要情绪
    primary_emotion = "neutral"
    if emotion_counts:
        primary_emotion = max(emotion_counts, key=lambda k: emotion_counts[k])

    return {
        "sentiment": sentiment,
        "score": round(score, 3),
        "emotion": primary_emotion,
        "positive_words": positive_found,
        "negative_words": negative_found,
    }


# =============================================================================
# 记忆重要性自动评估
# =============================================================================

def estimate_importance(
    content: str,
    memory_type: str,
    sentiment: dict[str, Any] | None = None,
) -> float:
    """根据内容自动估算记忆的重要性分数。

    评估维度:
        1. 内容长度（越长越重要，上限 0.3）
        2. 记忆类型（user_fact 最重要，episodic 最不重要）
        3. 情感强度（强情感 = 更重要，上限 0.2）
        4. 实体数量（包含实体越多越重要，上限 0.2）
        5. 关键词密度（特殊关键词加分，上限 0.3）

    Args:
        content: 记忆文本内容。
        memory_type: 记忆类型。
        sentiment: 情感分析结果（可选）。

    Returns:
        重要性分数（0.0 ~ 1.0）。
    """
    score = 0.0

    # 1. 内容长度
    if len(content) >= 30:
        score += 0.2
    elif len(content) >= 15:
        score += 0.1
    else:
        score += 0.05

    # 2. 记忆类型基础分
    type_base = {
        "user_fact": 0.25,
        "user_profile": 0.24,
        "user_preference": 0.22,
        "user_relationship": 0.24,
        "user_status": 0.20,
        "semantic": 0.15,
        "semantic_knowledge": 0.15,
        "semantic_opinion": 0.13,
        "semantic_concept": 0.12,
        "episodic": 0.10,
        "episodic_event": 0.10,
        "episodic_experience": 0.12,
        "episodic_activity": 0.08,
        "emotional": 0.18,
        "emotional_state": 0.17,
        "summary": 0.20,
        # 向后兼容旧类型名称
        "user_identity": 0.24,
        "user_attribute": 0.24,
        "emotional_mood": 0.17,
        "emotional_sentiment": 0.17,
    }
    score += type_base.get(memory_type, 0.10)

    # 3. 情感强度
    if sentiment and sentiment.get("sentiment") != "neutral":
        score += 0.15
        if sentiment.get("score", 0) > 0.5 or sentiment.get("score", 0) < -0.5:
            score += 0.05

    # 4. 实体数量
    entities = extract_entities(content)
    if len(entities) >= 3:
        score += 0.15
    elif len(entities) >= 1:
        score += 0.08

    # 5. 特殊关键词加分
    high_value_keywords = [
        "生日", "年龄", "地址", "手机", "邮箱", "密码", "身份证",
        "结婚", "离婚", "生", "死", "毕业", "入职", "离职",
        "第一次", "最后", "永远", "绝不", "最重要",
    ]
    for kw in high_value_keywords:
        if kw in content:
            score += 0.1
            break

    return min(score, 1.0)