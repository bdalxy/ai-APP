"""重试装饰器模块。

支持指数退避（exponential backoff）、随机抖动（jitter）、
可配置最大重试次数和可重试异常列表。

默认只重试网络相关错误（timeout、connection error、服务端错误），
不重试认证错误（401）、请求错误（400）等不可恢复错误。
"""

import functools
import random
import time
from collections.abc import Callable
from typing import Any, ParamSpec, TypeVar

from src.utils.logger import get_logger

logger = get_logger()

P = ParamSpec("P")
R = TypeVar("R")

# 默认可重试异常：网络超时、连接错误、服务端错误（5xx）、频率限制（429）
_DEFAULT_RETRYABLE = (
    TimeoutError,
    ConnectionError,
    OSError,
)


def retry(
    max_retries: int = 3,
    base_delay: float = 1.0,
    max_delay: float = 60.0,
    backoff_factor: float = 2.0,
    jitter: bool = True,
    retryable_exceptions: tuple[type[Exception], ...] = _DEFAULT_RETRYABLE,
) -> Callable[[Callable[P, R]], Callable[P, R]]:
    """重试装饰器。

    对被装饰函数在发生可重试异常时自动重试，使用指数退避策略。
    退避公式: delay = min(base_delay * (backoff_factor ** attempt), max_delay)
    若启用 jitter，实际延迟 = delay * (0.5 + random(0, 0.5))

    Args:
        max_retries: 最大重试次数（不含首次执行）。
        base_delay: 基础延迟（秒）。
        max_delay: 最大延迟上限（秒）。
        backoff_factor: 退避倍数，每次重试延迟乘以该值。
        jitter: 是否添加随机抖动以避免雷群效应。
        retryable_exceptions: 可触发重试的异常类型元组。

    Returns:
        装饰后的函数。
    """

    def decorator(func: Callable[P, R]) -> Callable[P, R]:
        @functools.wraps(func)
        def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            last_exception: Exception | None = None

            for attempt in range(max_retries + 1):
                try:
                    return func(*args, **kwargs)
                except retryable_exceptions as exc:
                    last_exception = exc

                    if attempt >= max_retries:
                        logger.error(
                            f"[重试耗尽] {func.__name__} 已重试 {max_retries} 次，最终失败: {exc}"
                        )
                        raise

                    delay = min(base_delay * (backoff_factor ** attempt), max_delay)
                    if jitter:
                        delay *= 0.5 + random.random() * 0.5

                    logger.warning(
                        f"[重试 {attempt + 1}/{max_retries}] {func.__name__} 失败: {exc}，"
                        f"{delay:.2f}秒后重试..."
                    )
                    time.sleep(delay)

            if last_exception is not None:
                raise last_exception
            # 理论上不会到达这里，但保留兜底
            raise RuntimeError("重试逻辑异常：未预期到达")

        return wrapper

    return decorator
