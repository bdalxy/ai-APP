"""基于 loguru 的日志封装模块。

提供统一的 get_logger() 函数，配置日志格式、级别和双路输出（控制台 + 文件）。
在 Settings 初始化后调用 configure_logger() 完成全局日志配置。

Android 兼容性：如果 loguru 不可用，自动降级为 Python 标准库 logging。

敏感信息过滤：自动脱敏 API Key、Bearer Token 等模式，防止泄露。
"""

import re
import sys
import logging as std_logging

# ── 敏感信息过滤 ──

# 需要脱敏的正则模式：(模式, 替换文本)
_SENSITIVE_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    # Bearer Token: sk-xxxx → sk-***[8位]***
    (re.compile(r'(sk-[a-zA-Z0-9]{2})[a-zA-Z0-9]+([a-zA-Z0-9]{6})'), r'\1***\2'),
    # API Key 出现在 URL 参数中: ?key=xxx → ?key=***REDACTED***
    (re.compile(r'([?&](?:api_key|key|token|secret)=)[^&\s]+'), r'\1***REDACTED***'),
    # Authorization header value: Bearer xxx → Bearer ***REDACTED***
    (re.compile(r'(Authorization:\s*Bearer\s+)[^\s,]+'), r'\1***REDACTED***'),
    # 通用 API Key 模式: 长度>=20 的字母数字串在 "key" 上下文
    (re.compile(r'(api[_-]?key["\s:=]+)[a-zA-Z0-9_-]{20,}'), r'\1***REDACTED***'),
]


def _redact_sensitive(message: str) -> str:
    """对日志消息中的敏感信息进行脱敏处理。

    Args:
        message: 原始日志消息

    Returns:
        脱敏后的消息
    """
    for pattern, replacement in _SENSITIVE_PATTERNS:
        message = pattern.sub(replacement, str(message))
    return message


class SensitiveFilter:
    """敏感信息过滤器，用于 loguru 和标准 logging。

    在消息被写入日志前自动脱敏 API Key、Token 等。
    """

    def __call__(self, record: dict) -> bool:
        """过滤 loguru 记录（就地修改 message）。"""
        record["message"] = _redact_sensitive(record["message"])
        return True


class SensitiveLoggingFilter(std_logging.Filter):
    """标准 logging 敏感信息过滤器。"""

    def filter(self, record: std_logging.LogRecord) -> bool:
        record.msg = _redact_sensitive(record.msg)
        if record.args:
            record.args = tuple(_redact_sensitive(str(a)) for a in record.args)
        return True

try:
    from loguru import logger as _loguru_logger
    _HAS_LOGURU = True
except ImportError:
    _HAS_LOGURU = False
    _loguru_logger = None


def get_logger():
    """获取全局 logger 实例。

    PC 端返回 loguru logger，Android 端返回标准 logging logger。

    Returns:
        loguru.Logger 或 logging.Logger: 全局日志实例。
    """
    if _HAS_LOGURU:
        return _loguru_logger
    return std_logging.getLogger("ai_companion")


def configure_logger(
    level: str = "INFO",
    log_file: str | None = None,
    rotation: str = "10 MB",
    retention: str = "7 days",
) -> None:
    """配置全局日志格式和输出目标。

    Args:
        level: 日志级别（DEBUG / INFO / WARNING / ERROR）。
        log_file: 文件日志路径，为 None 则不输出文件。
        rotation: 日志文件轮转大小。
        retention: 旧日志保留时长。
    """
    if _HAS_LOGURU:
        _configure_loguru(level, log_file, rotation, retention)
    else:
        _configure_std_logging(level)


def _configure_loguru(level, log_file, rotation, retention):
    """配置 loguru 日志（PC 端）"""
    _loguru_logger.remove()
    _loguru_logger.add(
        sys.stderr,
        level=level,
        format=(
            "<green>{time:YYYY-MM-DD HH:mm:ss}</green> | "
            "<level>{level: <8}</level> | "
            "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> | "
            "<level>{message}</level>"
        ),
        colorize=True,
        filter=SensitiveFilter(),  # 敏感信息脱敏
    )
    if log_file:
        _loguru_logger.add(
            log_file,
            level=level,
            format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} | {message}",
            rotation=rotation,
            retention=retention,
            encoding="utf-8",
            filter=SensitiveFilter(),  # 敏感信息脱敏
        )
        _loguru_logger.info(f"日志文件输出已启用: {log_file}")
    _loguru_logger.debug(f"日志系统已配置（loguru），级别: {level}")


def _configure_std_logging(level):
    """配置标准 logging（Android 端）"""
    std_logger = std_logging.getLogger("ai_companion")
    std_logger.setLevel(getattr(std_logging, level.upper(), std_logging.INFO))
    if not std_logger.handlers:
        handler = std_logging.StreamHandler(sys.stderr)
        handler.setFormatter(std_logging.Formatter(
            "%(asctime)s | %(levelname)-8s | %(name)s | %(message)s"
        ))
        handler.addFilter(SensitiveLoggingFilter())  # 敏感信息脱敏
        std_logger.addHandler(handler)
    std_logger.debug(f"日志系统已配置（标准 logging），级别: {level}")


# 模块加载时使用默认配置
configure_logger(level="INFO")
