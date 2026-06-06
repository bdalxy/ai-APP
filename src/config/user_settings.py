"""用户设置管理 - 16 项用户偏好设置的 JSON 持久化存储。"""
import json
import threading
from pathlib import Path
from typing import Any
from loguru import logger
from src.config.settings import settings

_USER_SETTINGS_FILENAME = "user_settings.json"
DEFAULT_USER_SETTINGS: dict[str, object] = {
    "wake_time": "08:00", "sleep_time": "23:00", "proactive_enabled": False,
    "proactive_interval_minutes": 120, "short_term_memory_count": 20, "long_term_memory_count": 500,
    "memory_extraction_mode": "auto", "daily_budget_limit": 0, "economy_mode": False,
    "web_search_enabled": False, "selected_card": "", "selected_world_book": "reality_world",
    "auto_export_enabled": False, "export_format": "JSON", "device_tier": "auto",
}

class UserSettings:
    def __init__(self, file_path: Path | None = None) -> None:
        if file_path is None:
            file_path = settings.DATA_DIR / _USER_SETTINGS_FILENAME
        self._file_path = Path(file_path)
        self._lock = threading.Lock()
        self._data: dict[str, object] = {}
        self._file_path.parent.mkdir(parents=True, exist_ok=True)
        self._load()

    def _load(self) -> None:
        if self._file_path.exists():
            try:
                with open(self._file_path, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                self._data = dict(DEFAULT_USER_SETTINGS)
                for key in loaded:
                    if key in DEFAULT_USER_SETTINGS:
                        self._data[key] = loaded[key]
            except (json.JSONDecodeError, OSError):
                self._data = dict(DEFAULT_USER_SETTINGS)
        else:
            self._data = dict(DEFAULT_USER_SETTINGS)
            self._save()

    def _save(self) -> None:
        temp_path = self._file_path.with_suffix(".tmp")
        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, indent=2)
        temp_path.replace(self._file_path)

    def get(self, key: str) -> object:
        if key not in DEFAULT_USER_SETTINGS:
            raise KeyError(f"未知设置项: {key}")
        return self._data.get(key)

    def set(self, key: str, value: object) -> None:
        if key not in DEFAULT_USER_SETTINGS:
            raise KeyError(f"未知设置项: {key}")
        with self._lock:
            self._data[key] = value
            self._save()

    def get_all(self) -> dict[str, object]:
        return dict(self._data)

user_settings = UserSettings()
