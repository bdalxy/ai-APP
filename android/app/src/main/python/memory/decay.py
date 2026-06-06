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
HALF_LIFE_DAYS: dict[str, float] = {
    "episodic": 7.0,
    "semantic": 30.0,
    "user_fact": 180.0,
}

DEFAULT_HALF_LIFE: float = 30.0

TYPE_MULTIPLIERS: dict[str, float] = {
    "episodic": 0.8,
    "semantic": 1.0,
    "user_fact": 1.2,
}

DEFAULT_TYPE_MULTIPLIER: float = 1.0

ACCESS_BOOST_FACTOR: float = 0.05


def calculate_decay(entry: MemoryEntry, current_time: datetime | None = None) -> float:
    if current_time is None:
        current_time = datetime.now(TZ_CST)
    try:
        created_at = _parse_iso_datetime(entry.created_at)
    except (ValueError, TypeError):
        return 1.0
    elapsed = current_time - created_at
    elapsed_days = elapsed.total_seconds() / 86400.0
    if elapsed_days <= 0:
        return 1.0
    half_life = HALF_LIFE_DAYS.get(entry.memory_type, DEFAULT_HALF_LIFE)
    decay = math.exp(-math.log(2) * elapsed_days / half_life)
    boost = entry.access_count * ACCESS_BOOST_FACTOR
    decay = min(decay + boost, 1.0)
    decay = max(decay, 0.01)
    return decay


def get_weight(entry: MemoryEntry, current_time: datetime | None = None) -> float:
    if current_time is None:
        current_time = datetime.now(TZ_CST)
    decay = calculate_decay(entry, current_time)
    type_mult = TYPE_MULTIPLIERS.get(entry.memory_type, DEFAULT_TYPE_MULTIPLIER)
    weight = entry.importance * decay * type_mult
    return weight


def update_decay(entry: MemoryEntry, current_time: datetime | None = None) -> MemoryEntry:
    entry.decay_factor = calculate_decay(entry, current_time)
    return entry


def get_half_life(memory_type: str) -> float:
    return HALF_LIFE_DAYS.get(memory_type, DEFAULT_HALF_LIFE)


def get_type_multiplier(memory_type: str) -> float:
    return TYPE_MULTIPLIERS.get(memory_type, DEFAULT_TYPE_MULTIPLIER)


def _parse_iso_datetime(iso_str: str) -> datetime:
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
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TZ_CST)
            return dt
        except ValueError:
            continue
    raise ValueError(f"无法解析时间字符串: {iso_str}")


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
