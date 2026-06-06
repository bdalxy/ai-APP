"""重试装饰器 - 指数退避 + 随机抖动。"""
import functools
import random
import time
from collections.abc import Callable
from typing import Any, TypeVar
from loguru import logger

F = TypeVar("F", bound=Callable[..., Any])

def retry(max_retries: int = 3, base_delay: float = 1.0, max_delay: float = 60.0, backoff_factor: float = 2.0, jitter: bool = True, retryable_exceptions: tuple[type[Exception], ...] = (Exception,)) -> Callable[[F], F]:
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
                        raise
                    delay = min(base_delay * (backoff_factor ** attempt), max_delay)
                    if jitter:
                        delay *= 0.5 + random.random() * 0.5
                    logger.warning(f"[重试 {attempt+1}/{max_retries}] {func.__name__} 失败: {exc}，{delay:.2f}秒后重试...")
                    time.sleep(delay)
            if last_exception is not None:
                raise last_exception
            return None
        return wrapper
    return decorator
