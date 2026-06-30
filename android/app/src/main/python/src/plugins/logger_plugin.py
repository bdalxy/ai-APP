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
    enabled = False  # 安全：默认禁用，Release 构建不记录用户对话

    def pre_process(self, user_input: str) -> Optional[str]:
        # 安全：仅记录 debug 级别，且只记录长度（不记录用户输入内容）
        _log.debug(f"[对话日志] 用户输入: len={len(user_input)}")
        return None  # 不修改用户输入

    def post_process(self, ai_reply: str) -> Optional[str]:
        # 安全：仅记录 debug 级别，且只记录长度（不记录 AI 回复内容）
        _log.debug(f"[对话日志] AI 回复: len={len(ai_reply)}")
        return None  # 不修改 AI 回复

    def on_turn_end(self, user_input: str, ai_reply: str) -> None:
        # 安全：仅记录字数和类型，不记录具体内容
        _log.debug(f"[对话日志] 回合结束: 用户({len(user_input)}字) → AI({len(ai_reply)}字)")

    def on_memory_extracted(self, memory: dict) -> None:
        # 安全：仅记录记忆类型，不记录记忆内容
        _log.debug(f"[对话日志] 新记忆: type={memory.get('memory_type', 'unknown')}")