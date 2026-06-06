"""基于 loguru 的日志封装 - 控制台 + 文件双路输出。"""
import sys
from loguru import logger

def get_logger():
    return logger

def configure_logger(level: str = "INFO", log_file: str | None = None, rotation: str = "10 MB", retention: str = "7 days") -> None:
    logger.remove()
    logger.add(sys.stderr, level=level, format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> | <level>{message}</level>", colorize=True)
    if log_file:
        logger.add(log_file, level=level, format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} | {message}", rotation=rotation, retention=retention, encoding="utf-8")

configure_logger(level="INFO")
