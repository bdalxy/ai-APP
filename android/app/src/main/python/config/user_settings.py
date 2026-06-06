"""用户设置管理模块。

使用 JSON 文件持久化存储 16 项用户偏好设置。
提供 get/set/reset 方法，所有读写操作均为文件安全（写临时文件 + 原子替换）。
"""

import json
import threading
from pathlib import Path
from typing import Any

from utils.logger import get_logger

logger = get_logger()

from src.config.settings import settings


# 用户设置文件的默认存储路径
_USER_SETTINGS_FILENAME = "user_settings.json"

# 全部 16 项设置的默认值
DEFAULT_USER_SETTINGS: dict[str, object] = {
    "wake_time": "08:00",
    "sleep_time": "23:00",
    "proactive_enabled": False,
    "proactive_interval_minutes": 120,
    "short_term_memory_count": 20,
    "long_term_memory_count": 500,
    "memory_extraction_mode": "auto",
    "daily_budget_limit": 0,
    "economy_mode": False,
    "web_search_enabled": False,
    "selected_card": "",
    "selected_world_book": "reality_world",
    "auto_export_enabled": False,
    "export_format": "JSON",
    "device_tier": "auto",
}


class UserSettings:
    """用户设置管理器。

    以 JSON 文件形式存储在 data 目录下，提供线程安全的读写操作。
    所有设置项均在 __init__ 的默认值字典中声明，不得凭空新增键。

    Attributes:
        _file_path: JSON 文件绝对路径
        _lock: 线程锁，保证并发安全
        _data: 当前设置的内存缓存
    """

    def __init__(self, file_path: Path | None = None) -> None:
        """初始化用户设置管理器。

        Args:
            file_path: JSON 文件路径，默认使用 Settings.DATA_DIR / user_settings.json
        """
        if file_path is None:
            file_path = settings.DATA_DIR / _USER_SETTINGS_FILENAME

        self._file_path: Path = Path(file_path)
        self._lock: threading.Lock = threading.Lock()
        self._data: dict[str, object] = {}

        # 确保父目录存在
        self._file_path.parent.mkdir(parents=True, exist_ok=True)

        # 加载已有设置或使用默认值
        self._load()
        logger.debug(f"UserSettings 初始化完成，共 {len(self._data)} 项设置")

    def _load(self) -> None:
        """从 JSON 文件加载设置，若文件不存在则使用默认值。"""
        if self._file_path.exists():
            try:
                with open(self._file_path, "r", encoding="utf-8") as f:
                    loaded: dict[str, object] = json.load(f)
                self._data = dict(DEFAULT_USER_SETTINGS)
                for key in loaded:
                    if key in DEFAULT_USER_SETTINGS:
                        self._data[key] = loaded[key]
                    else:
                        logger.warning(f"忽略未知设置项: {key}")
                logger.info(f"已加载用户设置: {self._file_path}")
            except (json.JSONDecodeError, OSError) as exc:
                logger.error(f"加载用户设置失败: {exc}，将使用默认值")
                self._data = dict(DEFAULT_USER_SETTINGS)
        else:
            logger.info("用户设置文件不存在，使用默认值")
            self._data = dict(DEFAULT_USER_SETTINGS)
            self._save()

    def _save(self) -> None:
        """原子化保存当前设置到 JSON 文件（写临时文件 + 重命名）。"""
        try:
            temp_path = self._file_path.with_suffix(".tmp")
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self._data, f, ensure_ascii=False, indent=2)
            temp_path.replace(self._file_path)
            logger.debug(f"用户设置已保存: {self._file_path}")
        except OSError as exc:
            logger.error(f"保存用户设置失败: {exc}")
            raise

    def get(self, key: str) -> object:
        """获取单项设置。

        Args:
            key: 设置项名称

        Returns:
            设置值；若 key 不存在则返回 None。

        Raises:
            KeyError: key 不在已注册的设置项中。
        """
        if key not in DEFAULT_USER_SETTINGS:
            raise KeyError(f"未知设置项: {key}")
        return self._data.get(key)

    def set(self, key: str, value: object) -> None:
        """设置单项值并自动保存。

        Args:
            key: 设置项名称
            value: 新值

        Raises:
            KeyError: key 不在已注册的设置项中。
        """
        if key not in DEFAULT_USER_SETTINGS:
            raise KeyError(f"未知设置项: {key}")

        with self._lock:
            self._data[key] = value
            self._save()

        safe_value = "***" if key in ("webhook_url", "custom_api_endpoint") else repr(value)
        logger.info(f"用户设置已更新: {key} = {safe_value}")

    def get_all(self) -> dict[str, object]:
        """获取所有设置项的副本。

        Returns:
            所有设置项的浅拷贝字典。
        """
        return dict(self._data)

    def set_many(self, updates: dict[str, object]) -> None:
        """批量更新设置项，一次保存。

        Args:
            updates: 要更新的键值对字典。

        Raises:
            KeyError: 任一 key 不在已注册的设置项中。
        """
        for key in updates:
            if key not in DEFAULT_USER_SETTINGS:
                raise KeyError(f"未知设置项: {key}")

        with self._lock:
            self._data.update(updates)
            self._save()

        logger.info(f"用户设置已批量更新: {list(updates.keys())}")

    def reset(self, key: str | None = None) -> None:
        """重置设置项为默认值。

        Args:
            key: 要重置的单项 key；若为 None 则重置全部。

        Raises:
            KeyError: key 不在已注册的设置项中（仅当 key 不为 None）。
        """
        with self._lock:
            if key is None:
                self._data = dict(DEFAULT_USER_SETTINGS)
                logger.info("所有用户设置已重置为默认值")
            else:
                if key not in DEFAULT_USER_SETTINGS:
                    raise KeyError(f"未知设置项: {key}")
                self._data[key] = DEFAULT_USER_SETTINGS[key]
                logger.info(f"用户设置已重置: {key} = {DEFAULT_USER_SETTINGS[key]!r}")

            self._save()


# 模块级用户设置单例
user_settings = UserSettings()
