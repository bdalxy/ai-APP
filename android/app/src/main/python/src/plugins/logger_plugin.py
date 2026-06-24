"""示例插件：对话日志记录器。"""

import logging
from typing import Optional

from .plugin_base import BasePlugin

_log = logging.getLogger(__name__)


class LoggerPlugin(BasePlugin):
    """记录对话日志的示例插件。"""

    name = "logger"
    version = "1.0.0"
    description = "对话日志记录器 — 记录每轮对话的输入和输出"
    enabled = True  # 默认启用

    def pre_process(self, user_input: str) -> Optional[str]:
        _log.info(f"[对话日志] 用户输入: {user_input[:100]}...")
        return None  # 不修改用户输入

    def post_process(self, ai_reply: str) -> Optional[str]:
        _log.info(f"[对话日志] AI 回复: {ai_reply[:100]}...")
        return None  # 不修改 AI 回复

    def on_turn_end(self, user_input: str, ai_reply: str) -> None:
        _log.info(f"[对话日志] 回合结束: 用户({len(user_input)}字) → AI({len(ai_reply)}字)")

    def on_memory_extracted(self, memory: dict) -> None:
        _log.info(f"[对话日志] 新记忆: {memory.get('memory_type', 'unknown')} - {memory.get('content', '')[:50]}")