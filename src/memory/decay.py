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

from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger

# 东八区时区
TZ_CST = timezone(timedelta(hours=8))


# =============================================================================
# 衰减参数配置
# =============================================================================

# 各记忆类型的半衰期（天数）
# 半衰期：衰减因子降至 0.5 所需的时间
HALF_LIFE_DAYS: dict[str, float] = {
    "episodic": 7.0,    # 事件记忆：衰减较快，半衰期约 7 天
    "semantic": 30.0,   # 语义记忆：衰减较慢，半衰期约 30 天
    "user_fact": 180.0,  # 用户事实：几乎不衰减，半衰期约 180 天
}

# 默认半衰期（未知类型）
DEFAULT_HALF_LIFE: float = 30.0

# 各记忆类型的权重乘数（用于综合评分）
# 不同类型的记忆在检索时的重要性不同
TYPE_MULTIPLIERS: dict[str, float] = {
    "episodic": 0.8,    # 事件记忆：权重略低（随时间快速衰减）
    "semantic": 1.0,    # 语义记忆：标准权重
    "user_fact": 1.2,   # 用户事实：权重最高（最重要）
}

# 默认类型乘数
DEFAULT_TYPE_MULTIPLIER: float = 1.0

# 访问频率增强因子（访问次数越多，有效衰减越慢）
# 每次访问可"恢复"一部分衰减
ACCESS_BOOST_FACTOR: float = 0.05  # 每次访问恢复 5% 的衰减


# =============================================================================
# 衰减计算函数
# =============================================================================


def calculate_decay(
    entry: MemoryEntry,
    current_time: datetime | None = None,
) -> float:
    """计算记忆的当前衰减因子。

    基于指数衰减模型:
        decay = exp(-ln(2) * elapsed_days / half_life)

    半衰期后衰减因子降至 0.5，两个半衰期后降至 0.25。

    访问频率增强:
        每次访问会恢复一部分衰减。access_count * ACCESS_BOOST_FACTOR
        作为衰减的"补偿"，但上限为 1.0。

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认为当前北京时间。

    Returns:
        新的衰减因子（0.0 ~ 1.0）。
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)

    # 解析创建时间
    try:
        created_at = _parse_iso_datetime(entry.created_at)
    except (ValueError, TypeError):
        # 如果无法解析时间，返回默认衰减因子
        return 1.0

    # 计算经过的天数
    elapsed = current_time - created_at
    elapsed_days = elapsed.total_seconds() / 86400.0

    if elapsed_days <= 0:
        return 1.0

    # 获取半衰期
    half_life = HALF_LIFE_DAYS.get(entry.memory_type, DEFAULT_HALF_LIFE)

    # 指数衰减公式: decay = e^(-λ * t)，其中 λ = ln(2) / half_life
    decay = math.exp(-math.log(2) * elapsed_days / half_life)

    # 访问频率增强：访问次数越多，有效衰减越慢
    boost = entry.access_count * ACCESS_BOOST_FACTOR
    decay = min(decay + boost, 1.0)

    # 确保下限
    decay = max(decay, 0.01)

    return decay


def get_weight(
    entry: MemoryEntry,
    current_time: datetime | None = None,
) -> float:
    """计算记忆的综合权重（用于检索排序）。

    权重 = importance * decay_factor * type_multiplier

    三类权重因素：
    1. importance: 记忆被提取时赋予的重要性（0.0~1.0）
    2. decay_factor: 时间衰减因子（0.0~1.0）
    3. type_multiplier: 记忆类型的基础权重乘数

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认为当前北京时间。

    Returns:
        综合权重值（0.0 ~ 1.5）。
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)

    # 重新计算衰减因子（确保使用最新时间）
    decay = calculate_decay(entry, current_time)
    type_mult = TYPE_MULTIPLIERS.get(entry.memory_type, DEFAULT_TYPE_MULTIPLIER)

    weight = entry.importance * decay * type_mult

    return weight


def update_decay(
    entry: MemoryEntry,
    current_time: datetime | None = None,
) -> MemoryEntry:
    """更新记忆条目的衰减因子并返回。

    在原地修改 entry 的 decay_factor 字段。

    Args:
        entry: 记忆条目。
        current_time: 当前时间，默认为当前北京时间。

    Returns:
        更新后的记忆条目（同一实例）。
    """
    entry.decay_factor = calculate_decay(entry, current_time)
    return entry


def get_half_life(memory_type: str) -> float:
    """获取指定记忆类型的半衰期（天数）。

    Args:
        memory_type: 记忆类型。

    Returns:
        半衰期天数。
    """
    return HALF_LIFE_DAYS.get(memory_type, DEFAULT_HALF_LIFE)


def get_type_multiplier(memory_type: str) -> float:
    """获取指定记忆类型的权重乘数。

    Args:
        memory_type: 记忆类型。

    Returns:
        类型权重乘数。
    """
    return TYPE_MULTIPLIERS.get(memory_type, DEFAULT_TYPE_MULTIPLIER)


# =============================================================================
# 内部工具函数
# =============================================================================


def _parse_iso_datetime(iso_str: str) -> datetime:
    """解析 ISO 8601 格式的时间字符串。

    支持带时区和不带时区的格式。

    Args:
        iso_str: ISO 8601 格式的时间字符串。

    Returns:
        datetime 对象（带时区信息）。

    Raises:
        ValueError: 无法解析时间字符串时。
    """
    # 尝试常见的 ISO 8601 格式
    for fmt in [
        "%Y-%m-%dT%H:%M:%S.%f%z",
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S.%f%z",
        "%Y-%m-%d %H:%M:%S%z",
        "%Y-%m-%d %H:%M:%S",
    ]:
        try:
            dt = datetime.strptime(iso_str, fmt)
            # 如果解析结果没有时区信息，假定为东八区
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TZ_CST)
            return dt
        except ValueError:
            continue

    raise ValueError(f"无法解析时间字符串: {iso_str}")


# =============================================================================
# 衰减统计
# =============================================================================


def get_decay_stats(
    entries: list[MemoryEntry],
    current_time: datetime | None = None,
) -> dict:
    """获取记忆列表的衰减统计信息。

    Args:
        entries: 记忆条目列表。
        current_time: 当前时间，默认为当前北京时间。

    Returns:
        统计信息字典，包含平均衰减因子、各类型分布等。
    """
    if current_time is None:
        current_time = datetime.now(TZ_CST)

    if not entries:
        return {
            "total": 0,
            "avg_decay": 0.0,
            "min_decay": 0.0,
            "max_decay": 0.0,
            "by_type": {},
        }

    # 更新所有条目的衰减因子并计算统计
    decay_values: list[float] = []
    type_stats: dict[str, dict[str, float]] = {}

    for entry in entries:
        d = calculate_decay(entry, current_time)
        decay_values.append(d)

        if entry.memory_type not in type_stats:
            type_stats[entry.memory_type] = {"count": 0, "sum_decay": 0.0}
        type_stats[entry.memory_type]["count"] += 1
        type_stats[entry.memory_type]["sum_decay"] += d

    # 计算各类型平均衰减
    by_type: dict[str, dict] = {}
    for t, stats in type_stats.items():
        by_type[t] = {
            "count": int(stats["count"]),
            "avg_decay": round(stats["sum_decay"] / stats["count"], 4),
        }

    return {
        "total": len(entries),
        "avg_decay": round(sum(decay_values) / len(decay_values), 4),
        "min_decay": round(min(decay_values), 4),
        "max_decay": round(max(decay_values), 4),
        "by_type": by_type,
    }
