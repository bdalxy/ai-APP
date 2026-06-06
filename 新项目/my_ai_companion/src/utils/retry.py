"""重试装饰器模块。

支持指数退避（exponential backoff）、随机抖动（jitter）、
可配置最大重试次数和可重试异常列表。
"""

import functools
import random
import time
from collections.abc import Callable
from typing import Any, TypeVar

from loguru import logger

# 泛型类型变量，保留被装饰函数的签名
F = TypeVar("F", bound=Callable[..., Any])


def retry(
    max_retries: int = 3,
    base_delay: float = 1.0,
    max_delay: float = 60.0,
    backoff_factor: float = 2.0,
    jitter: bool = True,
    retryable_exceptions: tuple[type[Exception], ...] = (Exception,),
) -> Callable[[F], F]:
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

    Example:
        @retry(max_retries=3, retryable_exceptions=(APITimeoutError, APIServerError))
        def call_api():
            ...
    """

    def decorator(func: F) -> F:
        @functools.wraps(func)
        def wrapper(*args: object, **kwargs: object) -> object:
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

                    # 计算退避延迟
                    delay = min(base_delay * (backoff_factor ** attempt), max_delay)
                    if jitter:
                        delay *= 0.5 + random.random() * 0.5  # 0.5 ~ 1.0 倍随机

                    logger.warning(
                        f"[重试 {attempt + 1}/{max_retries}] {func.__name__} 失败: {exc}，"
                        f"{delay:.2f}秒后重试..."
                    )
                    time.sleep(delay)

            # 理论上不会到达此处，但保持类型安全检查
            if last_exception is not None:
                raise last_exception
            return None

        return wrapper  # type: ignore[return-value]

    return decorator