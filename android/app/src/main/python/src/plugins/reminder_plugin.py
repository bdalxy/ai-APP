"""提醒插件 — 简单的时间提醒"""

import re, threading, time
from typing import Optional
from .plugin_base import BasePlugin


class ReminderPlugin(BasePlugin):
    """时间提醒插件。使用 /remind 时间 内容 触发。"""

    name = "reminder"
    version = "1.0.0"
    description = "提醒 — 设置定时提醒，到时间后通知"
    category = "script"
    icon = "alarm"

    def __init__(self):
        self._pending: list = []
        self._lock = threading.Lock()

    def pre_process(self, user_input: str) -> Optional[str]:
        with self._lock:
            pending = self._pending.copy()
            self._pending.clear()
        if pending:
            return "\n".join(pending)

        m = re.match(r"^/remind\s+(.+)", user_input.strip())
        if not m:
            return None

        rest = m.group(1).strip()
        tm = re.match(r"(\d+)\s*(分钟|秒|小时|分)\s*后\s*(.+)", rest)
        if not tm:
            return "[提醒插件] 格式：/remind 5分钟后 喝水"

        num, unit, msg = int(tm.group(1)), tm.group(2), tm.group(3).strip()
        delay = num if unit in ("秒",) else num * 60 if unit in ("分钟", "分") else num * 3600

        def _on_reminder(msg=msg):
            """提醒回调：线程安全地将提醒消息添加到待处理列表。"""
            with self._lock:
                self._pending.append(f"[提醒插件] ⏰ 提醒时间到：「{msg}」")

        t = threading.Timer(delay, _on_reminder)
        t.daemon = True
        t.start()
        return f"[提醒插件] ⏰ 已设置提醒：{delay}秒后「{msg}」"