"""基于 loguru 的日志封装模块。

提供统一的 get_logger() 函数，配置日志格式、级别和双路输出（控制台 + 文件）。
在 Settings 初始化后调用 configure_logger() 完成全局日志配置。
"""

import sys

from loguru import logger


def get_logger():
    """获取全局 logger 实例。

    返回 loguru 的全局 logger，所有模块共用同一个实例。
    在首次使用前需调用 configure_logger() 完成配置。

    Returns:
        loguru.Logger: 全局日志实例。
    """
    return logger


def configure_logger(
    level: str = "INFO",
    log_file: str | None = None,
    rotation: str = "10 MB",
    retention: str = "7 days",
) -> None:
    """配置全局日志格式和输出目标。

    移除默认 handler，添加彩色控制台输出和可选的滚动文件输出。

    Args:
        level: 日志级别（DEBUG / INFO / WARNING / ERROR）。
        log_file: 文件日志路径，为 None 则不输出文件。
        rotation: 日志文件轮转大小。
        retention: 旧日志保留时长。
    """
    # 移除所有默认 handler
    logger.remove()

    # 控制台输出：彩色格式
    logger.add(
        sys.stderr,
        level=level,
        format=(
            "<green>{time:YYYY-MM-DD HH:mm:ss}</green> | "
            "<level>{level: <8}</level> | "
            "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> | "
            "<level>{message}</level>"
        ),
        colorize=True,
    )

    # 文件输出（可选）
    if log_file:
        logger.add(
            log_file,
            level=level,
            format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} | {message}",
            rotation=rotation,
            retention=retention,
            encoding="utf-8",
        )
        logger.info(f"日志文件输出已启用: {log_file}")

    logger.debug(f"日志系统已配置，级别: {level}")


# 模块加载时使用默认配置（不输出文件），后续可由应用入口重新配置
configure_logger(level="INFO")