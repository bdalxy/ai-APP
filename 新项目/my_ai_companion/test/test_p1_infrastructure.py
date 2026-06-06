"""P1 阶段 1 基础设施验证脚本。"""
import sys
import os

# 确保项目根目录在 sys.path 中
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 验证 Settings
from src.config.settings import Settings, settings
print("=== Settings 验证 ===")
print(f"DEEPSEEK_MODEL: {settings.DEEPSEEK_MODEL}")
print(f"DEEPSEEK_BASE_URL: {settings.DEEPSEEK_BASE_URL}")
print(f"LOG_LEVEL: {settings.LOG_LEVEL}")
print(f"DATA_DIR: {settings.DATA_DIR}")
print(f"项目根目录: {settings.PROJECT_ROOT}")

# 验证 UserSettings
from src.config.user_settings import UserSettings, user_settings, DEFAULT_USER_SETTINGS
print()
print("=== UserSettings 验证 ===")
all_settings = user_settings.get_all()
print(f"设置项总数: {len(all_settings)} / 默认 {len(DEFAULT_USER_SETTINGS)}")
for k, v in all_settings.items():
    print(f"  {k} = {v!r}")

# 测试 get/set/reset
user_settings.set("wake_time", "07:00")
assert user_settings.get("wake_time") == "07:00", "set 失败"
user_settings.reset("wake_time")
assert user_settings.get("wake_time") == "08:00", "reset 失败"
print("get/set/reset 测试通过")

# 测试 set_many
user_settings.set_many({"wake_time": "06:30", "economy_mode": True})
assert user_settings.get("wake_time") == "06:30"
assert user_settings.get("economy_mode") is True
user_settings.reset("wake_time")
user_settings.reset("economy_mode")
print("set_many 测试通过")

# 测试未知 key
try:
    user_settings.get("unknown_key")
    assert False, "应该抛出 KeyError"
except KeyError:
    print("未知 key 拦截测试通过")

# 测试 reset_all
user_settings.set("wake_time", "09:00")
user_settings.set("economy_mode", True)
user_settings.reset()  # 全部重置
assert user_settings.get("wake_time") == "08:00"
assert user_settings.get("economy_mode") is False
print("reset_all 测试通过")

# 验证异常
from src.exceptions import (
    APIException, APIKeyError, APIQuotaError, APITimeoutError,
    APIRateLimitError, APIServerError, APIContentFilterError,
    MemoryException, MemoryNotFoundError, MemoryStorageError,
)
print()
print("=== 异常验证 ===")
e1 = APIKeyError()
print(f"APIKeyError: {e1!r}")
e2 = APIRateLimitError()
print(f"APIRateLimitError: status_code={e2.status_code}")
e3 = MemoryNotFoundError(detail="mem_001")
print(f"MemoryNotFoundError: {e3!r}")
e4 = APIServerError(message="自定义错误", status_code=503)
print(f"APIServerError: {e4!r}")

# 验证工具函数
from src.utils import (
    get_logger, configure_logger,
    retry as retry_decorator,
    is_within_time_range, format_timestamp, format_timestamp_iso,
    time_diff_seconds, time_diff_human_readable, now_cst,
)
print()
print("=== 工具函数验证 ===")
print(f"now_cst: {now_cst()}")
print(f"format_timestamp: {format_timestamp()}")
print(f"format_timestamp_iso: {format_timestamp_iso()}")
print(f"is_within_time_range('08:00', '23:00'): {is_within_time_range('08:00', '23:00')}")
print(f"time_diff_human_readable(3661): {time_diff_human_readable(3661)}")
print(f"time_diff_human_readable(-3661): {time_diff_human_readable(-3661)}")
print(f"time_diff_human_readable(30): {time_diff_human_readable(30)}")

# 测试跨午夜时间段
print(f"is_within_time_range('23:00', '08:00'): {is_within_time_range('23:00', '08:00')}")

# 验证 time_diff_seconds
from datetime import datetime, timedelta, timezone
tz_cst = timezone(timedelta(hours=8))
t1 = datetime(2024, 1, 1, 8, 0, 0, tzinfo=tz_cst)
t2 = datetime(2024, 1, 1, 9, 0, 0, tzinfo=tz_cst)
diff = time_diff_seconds(t1, t2)
assert diff == 3600.0, f"time_diff_seconds 计算错误: {diff}"
print("time_diff_seconds 测试通过")

# 验证 retry 装饰器
print()
print("=== retry 装饰器验证 ===")
call_count = [0]  # 使用列表避免 nonlocal 问题

@retry_decorator(max_retries=2, base_delay=0.01, retryable_exceptions=(ValueError,))
def test_retry_success() -> str:
    call_count[0] += 1
    if call_count[0] < 3:
        raise ValueError("测试重试")
    return "成功"

result = test_retry_success()
assert result == "成功", f"重试失败: {result}"
assert call_count[0] == 3, f"重试次数不对: {call_count[0]}"
print(f"重试测试结果: {result}, 调用次数: {call_count[0]}")

# 验证重试耗尽
call_count[0] = 0

@retry_decorator(max_retries=1, base_delay=0.01, retryable_exceptions=(ValueError,))
def test_retry_exhausted() -> None:
    call_count[0] += 1
    raise ValueError("永远失败")

try:
    test_retry_exhausted()
    assert False, "应该抛出异常"
except ValueError:
    pass
assert call_count[0] == 2, f"重试耗尽次数不对: {call_count[0]}"
print("重试耗尽测试通过")

# 验证非重试异常直接抛出
call_count[0] = 0

@retry_decorator(max_retries=2, base_delay=0.01, retryable_exceptions=(ValueError,))
def test_non_retryable() -> None:
    call_count[0] += 1
    raise TypeError("非重试异常")

try:
    test_non_retryable()
    assert False, "应该抛出异常"
except TypeError:
    pass
assert call_count[0] == 1, "非重试异常不应该重试"
print("非重试异常测试通过")

# 验证 Settings 单例
s1 = Settings()
s2 = Settings()
assert s1 is s2, "Settings 不是单例"
print()
print("Settings 单例测试通过")

print()
print("=" * 60)
print("P1 阶段 1 基础设施验证全部通过！")
print("=" * 60)