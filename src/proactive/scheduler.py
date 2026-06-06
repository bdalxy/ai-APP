"""主动消息调度器 - P1 记录发送时间，P2 定时推送。"""
from __future__ import annotations
import json
import threading
from datetime import datetime
from pathlib import Path
from src.config.settings import settings
from src.utils.logger import get_logger
from src.utils.time_utils import now_cst, format_timestamp_iso

_STATE_FILENAME = "proactive_state.json"
_DEFAULT_STATE: dict[str, object] = {"last_sent_time": None, "total_sent_count": 0, "version": "1.0"}

class ProactiveScheduler:
    def __init__(self) -> None:
        self._file_path: Path = settings.DATA_DIR / _STATE_FILENAME
        self._lock = threading.Lock()
        self._log = get_logger()
        self._file_path.parent.mkdir(parents=True, exist_ok=True)
        self._state = self._load()

    def _load(self) -> dict[str, object]:
        if not self._file_path.exists():
            return dict(_DEFAULT_STATE)
        try:
            with open(self._file_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            state = dict(_DEFAULT_STATE)
            state.update(loaded)
            return state
        except (json.JSONDecodeError, OSError):
            return dict(_DEFAULT_STATE)

    def _save(self) -> None:
        temp_path = self._file_path.with_suffix(".tmp")
        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(self._state, f, ensure_ascii=False, indent=2)
        temp_path.replace(self._file_path)

    def get_last_sent_time(self) -> datetime | None:
        raw = self._state.get("last_sent_time")
        if raw is None or not isinstance(raw, str):
            return None
        try:
            return datetime.fromisoformat(raw)
        except (ValueError, TypeError):
            return None

    def update_last_sent_time(self, sent_time: datetime | None = None) -> None:
        if sent_time is None:
            sent_time = now_cst()
        iso_time = format_timestamp_iso(sent_time)
        with self._lock:
            self._state["last_sent_time"] = iso_time
            count = self._state.get("total_sent_count", 0)
            self._state["total_sent_count"] = int(count) + 1 if isinstance(count, (int, float)) else 1
            self._save()

    def get_total_sent_count(self) -> int:
        count = self._state.get("total_sent_count", 0)
        return int(count) if isinstance(count, (int, float)) else 0

    def get_state(self) -> dict[str, object]:
        return dict(self._state)

    def reset(self) -> None:
        with self._lock:
            self._state = dict(_DEFAULT_STATE)
            self._save()
