"""主动消息调度器模块（简化版）。

P1 阶段为手动触发模式，调度器仅负责记录上次发送时间到 JSON 文件。
P2 阶段将实现定时推送功能。

状态文件路径: data/proactive_state.json
状态文件结构:
    {
        "last_sent_time": "2024-01-01T08:00:00+08:00",
        "total_sent_count": 5,
        "version": "1.0"
    }

依赖：
    - src.config.settings: Settings 单例（获取 DATA_DIR）
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: now_cst, format_timestamp_iso
"""

from __future__ import annotations

import json
import threading
from datetime import datetime
from pathlib import Path

from src.config.settings import settings
from src.utils.logger import get_logger
from src.utils.time_utils import now_cst, format_timestamp_iso


_STATE_FILENAME = "proactive_state.json"

_DEFAULT_STATE: dict[str, object] = {
    "last_sent_time": None,
    "total_sent_count": 0,
    "version": "1.0",
}


class ProactiveScheduler:
    """主动消息调度器。

    P1 阶段：记录上次发送时间，提供状态查询和更新功能。
    P2 阶段：将扩展定时检查与自动推送功能。
    """

    def __init__(self) -> None:
        self._file_path: Path = settings.DATA_DIR / _STATE_FILENAME
        self._lock: threading.Lock = threading.Lock()
        self._log = get_logger()
        self._file_path.parent.mkdir(parents=True, exist_ok=True)
        self._state: dict[str, object] = self._load()
        self._log.info(f"ProactiveScheduler 初始化完成: total_sent={self._state['total_sent_count']}")

    def _load(self) -> dict[str, object]:
        if not self._file_path.exists():
            self._log.info("状态文件不存在，使用默认状态")
            return dict(_DEFAULT_STATE)
        try:
            with open(self._file_path, "r", encoding="utf-8") as f:
                loaded: dict[str, object] = json.load(f)
            state = dict(_DEFAULT_STATE)
            state.update(loaded)
            self._log.debug(f"状态已加载: {self._file_path}")
            return state
        except (json.JSONDecodeError, OSError) as e:
            self._log.error(f"加载状态文件失败: {e}，使用默认状态")
            return dict(_DEFAULT_STATE)

    def _save(self) -> None:
        try:
            temp_path = self._file_path.with_suffix(".tmp")
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self._state, f, ensure_ascii=False, indent=2)
            temp_path.replace(self._file_path)
            self._log.debug(f"状态已保存: {self._file_path}")
        except OSError as e:
            self._log.error(f"保存状态文件失败: {e}")
            raise

    def get_last_sent_time(self) -> datetime | None:
        raw = self._state.get("last_sent_time")
        if raw is None or not isinstance(raw, str):
            return None
        try:
            return datetime.fromisoformat(raw)
        except (ValueError, TypeError):
            self._log.warning(f"无法解析上次发送时间: {raw}")
            return None

    def update_last_sent_time(self, sent_time: datetime | None = None) -> None:
        if sent_time is None:
            sent_time = now_cst()
        iso_time = format_timestamp_iso(sent_time)
        with self._lock:
            self._state["last_sent_time"] = iso_time
            count = self._state.get("total_sent_count", 0)
            if isinstance(count, (int, float)):
                self._state["total_sent_count"] = int(count) + 1
            else:
                self._state["total_sent_count"] = 1
            self._save()
        self._log.info(f"[调度器] 上次发送时间已更新: {iso_time}, 累计发送: {self._state['total_sent_count']}")

    def get_next_scheduled_time(self) -> datetime | None:
        return None

    def check_and_send(self) -> bool:
        self._log.debug("[调度器] P1 阶段仅支持手动触发，check_and_send() 返回 False")
        return False

    def get_total_sent_count(self) -> int:
        count = self._state.get("total_sent_count", 0)
        if isinstance(count, (int, float)):
            return int(count)
        return 0

    def get_state(self) -> dict[str, object]:
        return dict(self._state)

    def reset(self) -> None:
        with self._lock:
            self._state = dict(_DEFAULT_STATE)
            self._save()
        self._log.info("[调度器] 状态已重置")
