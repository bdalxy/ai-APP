"""时间工具函数 - 时间段判断、时间戳格式化、时间差计算。"""
from datetime import datetime, timedelta, timezone
TZ_CST = timezone(timedelta(hours=8))

def is_within_time_range(start_time: str, end_time: str, now: datetime | None = None) -> bool:
    if now is None:
        now = datetime.now(TZ_CST)
    start_h, start_m = map(int, start_time.split(":"))
    end_h, end_m = map(int, end_time.split(":"))
    start_minutes = start_h * 60 + start_m
    end_minutes = end_h * 60 + end_m
    now_minutes = now.hour * 60 + now.minute
    if start_minutes <= end_minutes:
        return start_minutes <= now_minutes <= end_minutes
    else:
        return now_minutes >= start_minutes or now_minutes <= end_minutes

def format_timestamp(dt: datetime | None = None, fmt: str = "%Y-%m-%d %H:%M:%S", tz: timezone | None = TZ_CST) -> str:
    if dt is None:
        dt = datetime.now(TZ_CST)
    elif tz is not None and dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz)
    return dt.strftime(fmt)

def format_timestamp_iso(dt: datetime | None = None) -> str:
    if dt is None:
        dt = datetime.now(TZ_CST)
    elif dt.tzinfo is None:
        dt = dt.replace(tzinfo=TZ_CST)
    return dt.isoformat()

def time_diff_seconds(start: datetime, end: datetime | None = None) -> float:
    if end is None:
        end = datetime.now(TZ_CST)
    return (end - start).total_seconds()

def time_diff_human_readable(seconds: float) -> str:
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
    return datetime.now(TZ_CST)
