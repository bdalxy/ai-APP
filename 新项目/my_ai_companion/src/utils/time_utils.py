"""时间工具函数模块。

提供时间段判断、时间戳格式化、时间差计算等通用时间操作。
所有函数均为纯 Python 实现，Chaquopy 兼容。
"""

from datetime import datetime, timedelta, timezone

# 东八区时区常量（北京时间）
TZ_CST = timezone(timedelta(hours=8))


def is_within_time_range(
    start_time: str,
    end_time: str,
    now: datetime | None = None,
) -> bool:
    """判断当前时间是否在指定的时间段内。

    支持跨午夜的时间范围（如 23:00 ~ 08:00）。

    Args:
        start_time: 开始时间，格式 "HH:MM"（如 "08:00"）。
        end_time: 结束时间，格式 "HH:MM"（如 "23:00"）。
        now: 参考时间，默认为当前北京时间。

    Returns:
        是否在时间段内。

    Example:
        >>> is_within_time_range("08:00", "23:00")  # 白天
        >>> is_within_time_range("23:00", "08:00")  # 夜间（跨午夜）
    """
    if now is None:
        now = datetime.now(TZ_CST)

    start_h, start_m = map(int, start_time.split(":"))
    end_h, end_m = map(int, end_time.split(":"))

    start_minutes = start_h * 60 + start_m
    end_minutes = end_h * 60 + end_m
    now_minutes = now.hour * 60 + now.minute

    if start_minutes <= end_minutes:
        # 不跨午夜：如 08:00 ~ 23:00
        return start_minutes <= now_minutes <= end_minutes
    else:
        # 跨午夜：如 23:00 ~ 08:00
        return now_minutes >= start_minutes or now_minutes <= end_minutes


def format_timestamp(
    dt: datetime | None = None,
    fmt: str = "%Y-%m-%d %H:%M:%S",
    tz: timezone | None = TZ_CST,
) -> str:
    """格式化时间戳为字符串。

    Args:
        dt: datetime 对象，默认为当前北京时间。
        fmt: 格式化字符串。
        tz: 时区，默认为东八区。传入 None 保留原时区。

    Returns:
        格式化后的时间字符串。
    """
    if dt is None:
        dt = datetime.now(TZ_CST)
    elif tz is not None and dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz)

    return dt.strftime(fmt)


def format_timestamp_iso(dt: datetime | None = None) -> str:
    """格式化为 ISO 8601 格式字符串（含时区）。

    Args:
        dt: datetime 对象，默认为当前北京时间。

    Returns:
        ISO 8601 格式字符串，如 "2024-01-01T08:00:00+08:00"。
    """
    if dt is None:
        dt = datetime.now(TZ_CST)
    elif dt.tzinfo is None:
        dt = dt.replace(tzinfo=TZ_CST)

    return dt.isoformat()


def time_diff_seconds(
    start: datetime,
    end: datetime | None = None,
) -> float:
    """计算两个时间点之间的秒数差。

    Args:
        start: 起始时间。
        end: 结束时间，默认为当前北京时间。

    Returns:
        时间差（秒），正数表示 end 在 start 之后。
    """
    if end is None:
        end = datetime.now(TZ_CST)
    return (end - start).total_seconds()


def time_diff_human_readable(
    seconds: float,
) -> str:
    """将秒数转换为人类可读的时间差描述。

    Args:
        seconds: 秒数。

    Returns:
        人类可读的时间差，如 "3天2小时15分钟"。
    """
    if seconds < 0:
        seconds = abs(seconds)
        prefix = "之前"
    else:
        prefix = "之后"

    days, remainder = divmod(int(seconds), 86400)
    hours, remainder = divmod(remainder, 3600)
    minutes, secs = divmod(remainder, 60)

    parts: list[str] = []
    if days > 0:
        parts.append(f"{days}天")
    if hours > 0:
        parts.append(f"{hours}小时")
    if minutes > 0:
        parts.append(f"{minutes}分钟")
    if not parts:
        parts.append(f"{int(secs)}秒")

    return "".join(parts) + prefix


def now_cst() -> datetime:
    """获取当前北京时间。

    Returns:
        带东八区时区的当前 datetime 对象。
    """
    return datetime.now(TZ_CST)