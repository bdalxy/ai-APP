"""时间衰减算法模块。

基于记忆类型和访问频率的衰减计算，模拟人类记忆的遗忘曲线。
三种记忆类型有不同的衰减行为，纯 Python 实现，Chaquopy 兼容。

核心函数：
    - calculate_decay: 计算衰减因子
    - get_weight: 计算综合权重（重要性 * 衰减 * 类型权重）

依赖：
    - src.memory.vector_store: MemoryEntry
    - src.utils.time_utils: 时间工具函数
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone
from typing import Any

from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger

# 东八区时区
TZ_CST = timezone(timedelta(hours=8))


# =============================================================================
# 衰减参数配置
# =============================================================================

# 各记忆类型的半衰期（天数）—— 调整后更符合真人记忆特征
HALF_LIFE_DAYS: dict[str, float] = {
    "episodic": 30.0,     # 原 7.0，事件记忆一个月内仍有较高权重
    "semantic": 90.0,     # 原 30.0，语义知识一季度内保持稳定
    "user_fact": 365.0,   # 原 180.0，用户事实一年内保持高权重
}

DEFAULT_HALF_LIFE: float = 60.0  # 原 30.0

TYPE_MULTIPLIERS: dict[str, float] = {
    "episodic": 0.8,
    "semantic": 1.0,
    "user_fact": 1.3,     # 原 1.2，用户事实权重更高
}

DEFAULT_TYPE_MULTIPLIER: float = 1.0

ACCESS_BOOST_FACTOR: float = 0.08  # 原 0.05，每次访问的衰减补偿提升

# 访问加成上限：防止 access_count 无上限导致衰减因子永久锁定在 1.0
# 达到上限后，再多的访问也不会增加衰减补偿
MAX_ACCESS_BOOST: float = 0.5  # 最大访问加成（相当于 6-7 次有效访问）


def calculate_decay(entry: MemoryEntry, current_time: datetime | None = None) -> float:
    """计算记忆衰减因子。

    使用 last_accessed（最后访问时间）而非 created_at（创建时间），
    确保频繁访问的记忆衰减更慢，模拟人类"复习强化记忆"的机制。

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认东八区当前时间。

    Returns:
        衰减因子（0.05 ~ 1.0）。
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)
    # 使用 last_accessed 而非 created_at，让"复习"真正有效
    ref_time = entry.last_accessed or entry.created_at
    try:
        ref_dt = _parse_iso_datetime(ref_time)
    except (ValueError, TypeError):
        return 1.0
    elapsed = current_time - ref_dt
    elapsed_days = elapsed.total_seconds() / 86400.0
    if elapsed_days <= 0:
        return 1.0
    half_life = HALF_LIFE_DAYS.get(entry.memory_type, DEFAULT_HALF_LIFE)
    decay = math.exp(-math.log(2) * elapsed_days / half_life)
    boost = min(entry.access_count * ACCESS_BOOST_FACTOR, MAX_ACCESS_BOOST)
    decay = min(decay + boost, 1.0)
    decay = max(decay, 0.05)  # 最小衰减因子从 0.01 提升到 0.05
    return decay


def get_weight(entry: MemoryEntry, current_time: datetime | None = None) -> float:
    """计算记忆的综合检索权重。

    权重 = importance * decay * type_multiplier。
    始终实时计算衰减因子（而非依赖预计算的 decay_factor），
    确保每次检索时衰减值都是最新的。

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认东八区当前时间。

    Returns:
        综合权重（0.0 ~ 1.2）。
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)
    # 始终实时计算衰减，不依赖可能过期的 decay_factor
    decay = calculate_decay(entry, current_time)
    type_mult = TYPE_MULTIPLIERS.get(entry.memory_type, DEFAULT_TYPE_MULTIPLIER)
    weight = entry.importance * decay * type_mult
    return weight


def update_decay(entry: MemoryEntry, current_time: datetime | None = None) -> MemoryEntry:
    """更新记忆的衰减因子，已归档记忆跳过更新。

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认东八区当前时间。

    Returns:
        更新后的 MemoryEntry（已归档记忆原样返回）。
    """
    if entry.archived:
        return entry
    entry.decay_factor = calculate_decay(entry, current_time)
    return entry


def get_half_life(memory_type: str) -> float:
    return HALF_LIFE_DAYS.get(memory_type, DEFAULT_HALF_LIFE)


def get_type_multiplier(memory_type: str) -> float:
    return TYPE_MULTIPLIERS.get(memory_type, DEFAULT_TYPE_MULTIPLIER)


def _parse_iso_datetime(iso_str: str) -> datetime:
    """解析 ISO 8601 时间字符串，兼容3种常见格式。
    
    所有格式都失败时回退到当前时间，保证衰减计算不中断。
    """
    for fmt in [
        "%Y-%m-%dT%H:%M:%S.%f%z",  # 含微秒+时区
        "%Y-%m-%dT%H:%M:%S%z",     # 含时区
        "%Y-%m-%dT%H:%M:%S.%f",    # 含微秒，无时区
        "%Y-%m-%dT%H:%M:%S",       # 无时区，无微秒
    ]:
        try:
            dt = datetime.strptime(iso_str, fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TZ_CST)
            return dt
        except ValueError:
            continue
    # 所有格式都失败时回退到当前时间
    return datetime.now(TZ_CST)


# =============================================================================
# 时间感知功能：根据当前时间上下文调整记忆权重
# =============================================================================

def get_temporal_context(current_time: datetime | None = None) -> dict[str, Any]:
    """获取当前时间上下文。

    返回当前时间段的语义标签，用于调整记忆检索的时间相关权重。

    Args:
        current_time: 当前时间，默认东八区当前时间。

    Returns:
        时间上下文字典，包含:
            - weekday: 星期几（0=周一~6=周日）
            - day_period: 时段（morning/afternoon/evening/night）
            - is_weekend: 是否周末
            - is_workday: 是否工作日
            - hour: 当前小时
            - label: 人类可读的时间标签（如"周末下午"、"工作日晚间"）
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)

    hour = current_time.hour
    weekday = current_time.weekday()  # 0=周一, 6=周日
    is_weekend = weekday >= 5

    # 时段判断
    if 6 <= hour < 12:
        day_period = "morning"
    elif 12 <= hour < 14:
        day_period = "noon"
    elif 14 <= hour < 18:
        day_period = "afternoon"
    elif 18 <= hour < 22:
        day_period = "evening"
    else:
        day_period = "night"

    # 人类可读标签
    weekday_names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    period_names = {
        "morning": "上午", "noon": "中午",
        "afternoon": "下午", "evening": "晚间", "night": "深夜",
    }
    if is_weekend:
        label = f"周末{period_names.get(day_period, '')}"
    else:
        label = f"工作日{period_names.get(day_period, '')}"

    return {
        "weekday": weekday,
        "day_period": day_period,
        "is_weekend": is_weekend,
        "is_workday": not is_weekend,
        "hour": hour,
        "label": label,
    }


class TemporalWeight:
    """时间感知权重调整器。

    根据当前时间上下文，动态调整记忆的检索权重。
    核心思想：在相似时间上下文中产生的记忆，在相同上下文中应有更高权重。

    例如：
        - 周末产生的娱乐相关记忆，在周末检索时权重更高
        - 工作日产生的工作相关记忆，在工作日检索时权重更高
        - 晚间产生的记忆，在晚间检索时权重更高

    Attributes:
        context: 当前时间上下文（由 get_temporal_context() 返回）。
        boost: 时间上下文匹配时的加成系数（默认 0.15）。
    """

    def __init__(
        self,
        current_time: datetime | None = None,
        boost: float = 0.15,
    ) -> None:
        """初始化时间感知权重调整器。

        Args:
            current_time: 当前时间，默认东八区当前时间。
            boost: 时间上下文匹配时的加成系数。
        """
        self.context = get_temporal_context(current_time)
        self.boost = boost

    def adjust(
        self,
        entry: MemoryEntry,
        base_weight: float,
    ) -> float:
        """根据时间上下文调整记忆权重。

        调整策略：
            - 周末产生的记忆在周末检索时 +boost
            - 工作日产生的记忆在工作日检索时 +boost
            - 同时段产生的记忆 +boost（最多叠加一次）
            - 总加成上限为 boost * 2

        Args:
            entry: 记忆条目。
            base_weight: 基础权重（由 get_weight() 计算）。

        Returns:
            调整后的权重。
        """
        entry_dt = _parse_iso_datetime(entry.created_at)
        entry_weekday = entry_dt.weekday()
        entry_is_weekend = entry_weekday >= 5
        entry_hour = entry_dt.hour

        boost_total = 0.0

        # 周末/工作日匹配
        if self.context["is_weekend"] and entry_is_weekend:
            boost_total += self.boost
        elif self.context["is_workday"] and not entry_is_weekend:
            boost_total += self.boost

        # 时段匹配
        entry_period = self._get_day_period(entry_hour)
        if entry_period == self.context["day_period"]:
            # 时段匹配加成，但如果已经加了周末/工作日加成，减半
            period_boost = self.boost * 0.5
            boost_total += period_boost

        # 上限：boost * 2
        boost_total = min(boost_total, self.boost * 2)
        return min(base_weight + boost_total, 1.2)

    @staticmethod
    def _get_day_period(hour: int) -> str:
        """根据小时判断时段。"""
        if 6 <= hour < 12:
            return "morning"
        elif 12 <= hour < 14:
            return "noon"
        elif 14 <= hour < 18:
            return "afternoon"
        elif 18 <= hour < 22:
            return "evening"
        else:
            return "night"


def get_decay_stats(entries: list[MemoryEntry], current_time: datetime | None = None) -> dict:
    if current_time is None:
        current_time = datetime.now(TZ_CST)
    if not entries:
        return {"total": 0, "avg_decay": 0.0, "min_decay": 0.0, "max_decay": 0.0, "by_type": {}}
    decay_values: list[float] = []
    type_stats: dict[str, dict[str, float]] = {}
    for entry in entries:
        d = calculate_decay(entry, current_time)
        decay_values.append(d)
        if entry.memory_type not in type_stats:
            type_stats[entry.memory_type] = {"count": 0, "sum_decay": 0.0}
        type_stats[entry.memory_type]["count"] += 1
        type_stats[entry.memory_type]["sum_decay"] += d
    by_type: dict[str, dict] = {}
    for t, stats in type_stats.items():
        by_type[t] = {"count": int(stats["count"]), "avg_decay": round(stats["sum_decay"] / stats["count"], 4)}
    return {"total": len(entries), "avg_decay": round(sum(decay_values) / len(decay_values), 4), "min_decay": round(min(decay_values), 4), "max_decay": round(max(decay_values), 4), "by_type": by_type}