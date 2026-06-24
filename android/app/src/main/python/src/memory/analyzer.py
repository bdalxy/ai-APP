"""记忆分析模块。

提供记忆库的多维度分析功能，包括:
    1. 趋势分析（trend analysis）：记忆增长趋势、类型变化趋势、情感趋势
    2. 主题聚类（topic clustering）：基于关键词共现的主题聚类
    3. 用户画像（user profile）：从记忆库中提取用户画像
    4. 记忆质量分析（quality analysis）：评估记忆库的整体质量

核心类:
    - MemoryAnalyzer: 记忆分析器

依赖:
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.memory_types: analyze_sentiment, extract_entities, EntityInfo
    - src.memory.decay: get_decay_stats
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

import math
import re
from collections import Counter, defaultdict
from datetime import timedelta, timezone
from typing import Any

from src.memory.decay import get_decay_stats, _parse_iso_datetime
from src.memory.memory_types import (
    analyze_sentiment,
    extract_entities,
    MemoryCategory,
)
from src.memory.vector_store import MemoryEntry, VectorStore, extract_keywords
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso, now_cst

# 东八区时区
TZ_CST = timezone(timedelta(hours=8))


class MemoryAnalyzer:
    """记忆分析器。

    提供记忆库的多维度分析功能，帮助理解记忆系统的运行状态和用户画像。

    Attributes:
        MAX_SAMPLE: 分析时采用的最大样本数（默认 500）。
        CLUSTER_SIMILARITY_THRESHOLD: 主题聚类相似度阈值（默认 0.3）。
    """

    MAX_SAMPLE: int = 500
    CLUSTER_SIMILARITY_THRESHOLD: float = 0.3

    def __init__(self, vector_store: VectorStore) -> None:
        """初始化记忆分析器。

        Args:
            vector_store: 向量存储实例。
        """
        self._store = vector_store
        self._log = get_logger()
        self._log.info("MemoryAnalyzer 初始化完成")

    # =========================================================================
    # 趋势分析
    # =========================================================================

    def analyze_trends(self, days: int = 30) -> dict[str, Any]:
        """分析记忆变化趋势。

        按时间窗口分析记忆的增长趋势、类型分布和情感趋势。

        Args:
            days: 分析的时间范围（天），默认 30 天。

        Returns:
            趋势分析报告字典:
                - growth: 增长趋势（按天）
                - type_distribution: 类型分布变化
                - sentiment_trend: 情感趋势
                - decay_trend: 衰减趋势
        """
        entries = self._store.get_page(0, self.MAX_SAMPLE)
        if not entries:
            return self._empty_trend_report()

        now = now_cst()
        cutoff = now - timedelta(days=days)

        # 按天分组
        daily_counts: dict[str, int] = defaultdict(int)
        daily_types: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
        daily_sentiments: dict[str, dict[str, int]] = defaultdict(
            lambda: defaultdict(int)
        )

        for entry in entries:
            try:
                created = _parse_iso_datetime(entry.created_at)
            except (ValueError, TypeError):
                continue

            if created < cutoff:
                continue

            day_key = created.strftime("%Y-%m-%d")
            daily_counts[day_key] += 1
            daily_types[day_key][entry.memory_type] += 1

            # 情感分析
            sentiment = analyze_sentiment(entry.content)
            daily_sentiments[day_key][sentiment["sentiment"]] += 1

        # 增长趋势
        growth = [
            {"date": day, "count": count}
            for day, count in sorted(daily_counts.items())
        ]

        # 类型分布（取最新一天）
        type_dist = {}
        if daily_types:
            latest_day = max(daily_types.keys())
            type_dist = dict(daily_types[latest_day])

        # 情感趋势
        sentiment_trend = []
        for day in sorted(daily_sentiments.keys()):
            day_data = daily_sentiments[day]
            total = sum(day_data.values())
            sentiment_trend.append({
                "date": day,
                "positive_ratio": round(day_data.get("positive", 0) / max(total, 1), 3),
                "negative_ratio": round(day_data.get("negative", 0) / max(total, 1), 3),
                "neutral_ratio": round(day_data.get("neutral", 0) / max(total, 1), 3),
            })

        # 衰减趋势
        decay_stats = get_decay_stats(entries)

        return {
            "period_days": days,
            "total_analyzed": len(entries),
            "growth": growth,
            "total_growth": sum(daily_counts.values()),
            "type_distribution": type_dist,
            "sentiment_trend": sentiment_trend[-14:],  # 最近 14 天
            "decay_trend": decay_stats,
        }

    def _empty_trend_report(self) -> dict:
        return {
            "period_days": 0,
            "total_analyzed": 0,
            "growth": [],
            "total_growth": 0,
            "type_distribution": {},
            "sentiment_trend": [],
            "decay_trend": {},
        }

    # =========================================================================
    # 主题聚类
    # =========================================================================

    def cluster_topics(self, num_clusters: int = 5) -> dict[str, Any]:
        """基于关键词共现的主题聚类。

        使用简单的贪心聚类算法，将记忆按关键词重叠度分组。

        Args:
            num_clusters: 期望的聚类数量。

        Returns:
            主题聚类报告字典:
                - clusters: 聚类列表，每项包含主题名、记忆数、关键词
                - unclustered: 未归类的记忆数
                - cluster_diversity: 聚类多样性指标
        """
        entries = self._store.get_page(0, self.MAX_SAMPLE)
        if not entries:
            return {"clusters": [], "unclustered": 0, "cluster_diversity": 0.0}

        # 1. 为每条记忆提取关键词
        entry_keywords: dict[str, set[str]] = {}
        for entry in entries:
            keywords = set(entry.keywords) if entry.keywords else set()
            if not keywords and entry.content:
                # 回退到内容分词
                keywords = set(extract_keywords(entry.content))
            entry_keywords[entry.id] = keywords

        # 2. 贪心聚类
        # 选择一些记忆作为聚类中心，将其他记忆归入最近的聚类
        if len(entries) <= num_clusters:
            # 记忆数不足，每条记忆一个聚类
            clusters = [
                {
                    "topic": self._extract_topic_name([e]),
                    "count": 1,
                    "keywords": list(entry_keywords.get(e.id, set()))[:10],
                    "sample_content": e.content[:50],
                }
                for e in entries
            ]
            return {
                "clusters": clusters,
                "unclustered": 0,
                "cluster_diversity": 1.0,
            }

        # 选择聚类中心：优先选择重要性高、内容长的记忆
        sorted_entries = sorted(
            entries,
            key=lambda e: (e.importance, len(e.content)),
            reverse=True,
        )

        # 确保聚类中心不相似
        centers: list[MemoryEntry] = []
        for entry in sorted_entries:
            if len(centers) >= num_clusters:
                break
            is_duplicate = False
            for center in centers:
                kw1 = entry_keywords[entry.id]
                kw2 = entry_keywords[center.id]
                if kw1 and kw2:
                    overlap = len(kw1 & kw2) / max(len(kw1 | kw2), 1)
                    if overlap > self.CLUSTER_SIMILARITY_THRESHOLD:
                        is_duplicate = True
                        break
            if not is_duplicate:
                centers.append(entry)

        if not centers:
            centers = sorted_entries[:num_clusters]

        # 3. 将记忆分配到最近的聚类
        cluster_members: dict[int, list[MemoryEntry]] = defaultdict(list)
        for entry in entries:
            min_dist = float("inf")
            best_cluster = 0
            for i, center in enumerate(centers):
                kw1 = entry_keywords[entry.id]
                kw2 = entry_keywords[center.id]
                if kw1 and kw2:
                    dist = 1.0 - len(kw1 & kw2) / max(len(kw1 | kw2), 1)
                else:
                    dist = 1.0
                if dist < min_dist:
                    min_dist = dist
                    best_cluster = i
            cluster_members[best_cluster].append(entry)

        # 4. 构建聚类结果
        clusters = []
        all_types: set[str] = set()
        for i, center in enumerate(centers):
            members = cluster_members.get(i, [center])
            # 提取主题名
            topic_name = self._extract_topic_name(members)
            # 收集关键词
            all_kw: set[str] = set()
            for m in members:
                all_kw.update(entry_keywords.get(m.id, set()))
                all_types.add(m.memory_type)

            clusters.append({
                "topic": topic_name,
                "count": len(members),
                "keywords": list(all_kw)[:10],
                "sample_content": center.content[:60],
                "types": list(set(m.memory_type for m in members)),
            })

        # 按成员数排序
        clusters.sort(key=lambda c: c["count"], reverse=True)

        # 多样性 = 不同聚类间类型分布的熵
        diversity = 0.0
        if len(clusters) > 1 and all_types:
            type_counts = Counter()
            for c in clusters:
                for t in c.get("types", []):
                    type_counts[t] += 1
            total = sum(type_counts.values())
            if total > 0:
                for count in type_counts.values():
                    p = count / total
                    diversity -= p * math.log2(p)
                diversity = diversity / math.log2(len(all_types))

        return {
            "clusters": clusters,
            "unclustered": 0,
            "cluster_diversity": round(diversity, 3),
        }

    def _extract_topic_name(self, entries: list[MemoryEntry]) -> str:
        """从一组记忆中提取主题名称。

        选择出现频率最高的关键词或内容片段作为主题名。
        """
        if not entries:
            return "未知"

        # 1. 收集所有关键词
        all_kw: list[str] = []
        for e in entries:
            if e.keywords:
                all_kw.extend(e.keywords)

        if all_kw:
            # 找出现最多的关键词
            kw_counter = Counter(all_kw)
            top_kw = kw_counter.most_common(3)
            return "、".join(kw for kw, _ in top_kw)

        # 2. 回退：使用内容中的核心词
        # 取第一条记忆的内容作为代表
        content = entries[0].content
        # 提取中文词
        words = re.findall(r"[\u4e00-\u9fff]{2,4}", content)
        if words:
            word_counter = Counter(words)
            top_words = word_counter.most_common(2)
            return "、".join(w for w, _ in top_words)

        return content[:30]

    # =========================================================================
    # 用户画像
    # =========================================================================

    def generate_user_profile(self) -> dict[str, Any]:
        """从记忆库中提取用户画像。

        基于 user_fact 类型的记忆，提取用户的基本信息、偏好、人际关系等。

        Returns:
            用户画像字典:
                - identity: 身份信息
                - preferences: 偏好列表
                - attributes: 属性列表
                - relationships: 人际关系
                - status: 当前状态
                - emotional_profile: 情感画像
                - interests: 兴趣话题
                - summary: 画像摘要文本
        """
        entries = self._store.get_page(0, self.MAX_SAMPLE)
        if not entries:
            return self._empty_profile()

        identity: list[str] = []
        preferences: list[str] = []
        attributes: list[str] = []
        relationships: list[str] = []
        status: list[str] = []
        emotions: list[dict] = []
        interests: set[str] = set()

        for entry in entries:
            mem_type = entry.memory_type

            if mem_type in ("user_profile", "user_identity", "user_fact"):
                # 检查是否包含身份相关词
                identity_keywords = ["叫", "是", "名字", "姓名", "年龄", "性别"]
                if any(kw in entry.content for kw in identity_keywords):
                    identity.append(entry.content)

            if mem_type in ("user_preference", "user_fact"):
                pref_keywords = ["喜欢", "爱", "不喜欢", "讨厌", "偏好", "兴趣"]
                if any(kw in entry.content for kw in pref_keywords):
                    preferences.append(entry.content)

            if mem_type in ("user_profile", "user_attribute", "user_fact"):
                attr_keywords = ["生日", "血型", "星座", "身高", "体重", "地址", "手机"]
                if any(kw in entry.content for kw in attr_keywords):
                    attributes.append(entry.content)

            if mem_type in ("user_relationship", "user_fact"):
                rel_keywords = ["妈妈", "爸爸", "妹妹", "姐姐", "哥哥", "弟弟", "朋友", "同事", "家人"]
                if any(kw in entry.content for kw in rel_keywords):
                    relationships.append(entry.content)

            if mem_type in ("user_status", "episodic_event"):
                stat_keywords = ["现在", "目前", "正在", "最近", "刚"]
                if any(kw in entry.content for kw in stat_keywords):
                    status.append(entry.content)

            if mem_type in ("emotional_state", "emotional_mood", "emotional_sentiment"):
                sentiment = analyze_sentiment(entry.content)
                emotions.append({
                    "content": entry.content,
                    "sentiment": sentiment["sentiment"],
                    "emotion": sentiment["emotion"],
                })

            # 兴趣话题提取
            if entry.keywords:
                interests.update(entry.keywords[:5])

        # 情感画像
        if emotions:
            sentiment_counts = Counter(e["sentiment"] for e in emotions)
            emotion_counts = Counter(e["emotion"] for e in emotions)
            dominant_sentiment = sentiment_counts.most_common(1)[0][0] if sentiment_counts else "neutral"
            dominant_emotion = emotion_counts.most_common(1)[0][0] if emotion_counts else "neutral"
        else:
            dominant_sentiment = "neutral"
            dominant_emotion = "neutral"

        # 生成摘要
        summary_parts = []
        if identity:
            summary_parts.append(f"身份: {identity[0]}")
        if preferences:
            summary_parts.append(f"偏好: {preferences[0]}")
        if status:
            summary_parts.append(f"近况: {status[0]}")
        summary = "；".join(summary_parts) if summary_parts else "暂无足够信息生成画像"

        profile = {
            "identity": identity[:5],
            "preferences": preferences[:5],
            "attributes": attributes[:5],
            "relationships": relationships[:5],
            "status": status[:3],
            "emotional_profile": {
                "dominant_sentiment": dominant_sentiment,
                "dominant_emotion": dominant_emotion,
                "emotion_count": len(emotions),
                "recent_emotions": emotions[-5:] if len(emotions) > 5 else emotions,
            },
            "interests": list(interests)[:20],
            "summary": summary,
            "total_facts": len(identity) + len(preferences) + len(attributes) + len(relationships),
        }

        self._log.info(
            f"[用户画像] 生成完成: "
            f"身份={len(identity)}, 偏好={len(preferences)}, "
            f"属性={len(attributes)}, 关系={len(relationships)}, "
            f"情感={len(emotions)}"
        )

        return profile

    def _empty_profile(self) -> dict:
        return {
            "identity": [],
            "preferences": [],
            "attributes": [],
            "relationships": [],
            "status": [],
            "emotional_profile": {
                "dominant_sentiment": "neutral",
                "dominant_emotion": "neutral",
                "emotion_count": 0,
                "recent_emotions": [],
            },
            "interests": [],
            "summary": "暂无足够信息生成画像",
            "total_facts": 0,
        }

    # =========================================================================
    # 质量分析
    # =========================================================================

    def analyze_quality(self) -> dict[str, Any]:
        """分析记忆库的整体质量。

        评估维度:
            1. 完整性：是否有足够的记忆覆盖不同类型
            2. 一致性：是否有矛盾记忆
            3. 新鲜度：记忆的时效性
            4. 多样性：记忆类型的分布均衡度
            5. 可检索性：记忆是否有足够的向量/关键词

        Returns:
            质量分析报告字典。
        """
        entries = self._store.get_page(0, self.MAX_SAMPLE)
        if not entries:
            return {"status": "empty", "total": 0, "scores": {}}

        total = len(entries)

        # 1. 完整性
        type_coverage = len(set(e.memory_type for e in entries))
        completeness = min(type_coverage / 5, 1.0)  # 如果有 5+ 种类型，满分

        # 2. 一致性（检查是否有明显的矛盾）
        # 简化：检查 user_fact 类型中是否有含义冲突的
        conflicts = 0
        user_facts = [e for e in entries if "user_fact" in e.memory_type or "user_" in e.memory_type]
        for i in range(len(user_facts)):
            for j in range(i + 1, min(i + 3, len(user_facts))):
                # 检查否定词
                if "不" in user_facts[i].content and _overlap_entities(user_facts[i].content, user_facts[j].content):
                    conflicts += 1
        consistency = max(0.0, 1.0 - conflicts / max(total, 1))

        # 3. 新鲜度
        now = now_cst()
        recent_count = 0
        for e in entries:
            try:
                created = _parse_iso_datetime(e.created_at)
                if (now - created).days < 7:
                    recent_count += 1
            except (ValueError, TypeError):
                self._log.debug(f"[质量分析] 时间解析失败: {e.created_at}")
        freshness = recent_count / max(total, 1)

        # 4. 多样性
        type_counts = Counter(e.memory_type for e in entries)
        if len(type_counts) > 1:
            entropy = 0.0
            for count in type_counts.values():
                p = count / total
                entropy -= p * math.log2(p)
            diversity = entropy / math.log2(len(type_counts))
        else:
            diversity = 0.0

        # 5. 可检索性
        with_embedding = sum(1 for e in entries if e.embedding)
        with_keywords = sum(1 for e in entries if e.keywords)
        retrievability = (with_embedding / max(total, 1) * 0.6) + (with_keywords / max(total, 1) * 0.4)

        # 综合评分
        scores = {
            "completeness": round(completeness, 3),
            "consistency": round(consistency, 3),
            "freshness": round(freshness, 3),
            "diversity": round(diversity, 3),
            "retrievability": round(retrievability, 3),
        }
        overall = round(sum(scores.values()) / len(scores), 3)

        # 评级
        if overall >= 0.8:
            grade = "A"
        elif overall >= 0.6:
            grade = "B"
        elif overall >= 0.4:
            grade = "C"
        elif overall >= 0.2:
            grade = "D"
        else:
            grade = "F"

        suggestions: list[str] = []
        if completeness < 0.5:
            suggestions.append("记忆类型过于单一，建议启用 LLM 提取模式以增加多样性")
        if freshness < 0.3:
            suggestions.append("最近一周新增记忆较少，记忆库可能已停滞")
        if retrievability < 0.5:
            suggestions.append("较多记忆缺少向量或关键词，检索效果可能不佳")
        if consistency < 0.8:
            suggestions.append("检测到可能的矛盾记忆，建议执行合并操作")

        self._log.info(
            f"[质量分析] 综合评分={overall:.2f} ({grade}), "
            f"完整性={completeness:.2f}, 一致性={consistency:.2f}, "
            f"新鲜度={freshness:.2f}, 多样性={diversity:.2f}, "
            f"可检索性={retrievability:.2f}"
        )

        return {
            "status": "ok",
            "total": total,
            "grade": grade,
            "overall_score": overall,
            "scores": scores,
            "type_distribution": dict(type_counts),
            "with_embedding": with_embedding,
            "with_keywords": with_keywords,
            "suggestions": suggestions,
        }


def _overlap_entities(text1: str, text2: str) -> bool:
    """检查两个文本是否有重叠的实体。"""
    entities1 = _extract_entities_simple(text1)
    entities2 = _extract_entities_simple(text2)
    return bool(entities1 & entities2)


def _extract_entities_simple(text: str) -> set[str]:
    """简单实体提取。"""
    entities = set()
    chinese_words = re.findall(r"[\u4e00-\u9fff]{2,4}", text)
    stop_words = {"不是", "就是", "已经", "现在", "其实", "所以", "因为", "但是",
                  "可以", "没有", "这个", "那个", "什么", "怎么", "为什么"}
    for word in chinese_words:
        if word not in stop_words:
            entities.add(word)
    return entities