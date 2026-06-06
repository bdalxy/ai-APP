# -*- coding: utf-8 -*-
"""
用户设置系统
管理用户可配置的 Python 侧设置项，支持热更新和 Kotlin 同步

存储方案: JSON 文件 (settings.json)
- 优势: 纯 Python 可读写, 无需 SQLite 依赖, 人类可读, 易于调试
- 劣势: 并发写入需加锁 (本项目为单用户场景, 影响极小)

同步机制: Python 侧提供 get/set 接口, Kotlin 侧通过 Chaquopy 调用
"""

import json
import logging
import threading
from typing import Dict, Any, Optional, Union, List, Callable
from pathlib import Path
from dataclasses import dataclass, field, asdict
from enum import Enum

logger = logging.getLogger(__name__)


# ============================================================
# 设置项枚举
# ============================================================

class MemoryExtractionMode(Enum):
    """记忆提取方式"""
    MANUAL = "manual"       # 手动提取 (用户主动触发)
    AUTO = "auto"           # 自动提取 (每轮对话后自动提取)
    HYBRID = "hybrid"       # 混合模式 (手动 + 自动)

class EconomyMode(Enum):
    """省钱模式"""
    OFF = "off"             # 关闭
    LOW = "low"             # 低 (使用 Flash 模型)
    AGGRESSIVE = "aggressive"  # 激进 (使用 Flash 模型 + 减少记忆检索)


# ============================================================
# 设置数据模型
# ============================================================

@dataclass
class UserSettings:
    """用户设置数据类 — 所有设置项的集中定义"""

    # ========== 日常作息 ==========
    wake_up_time: str = "08:00"          # 起床时间 (HH:MM)
    sleep_time: str = "23:00"            # 睡觉时间 (HH:MM)

    # ========== 主动消息 ==========
    proactive_message_enabled: bool = False  # 主动消息开关
    proactive_interval_minutes: int = 120    # 主动消息间隔 (分钟)

    # ========== 记忆设置 ==========
    short_term_memory_count: int = 20    # 短期记忆条数 (对话上下文)
    long_term_memory_count: int = 500    # 长期记忆条数 (向量检索)
    memory_extraction_mode: str = "auto" # 记忆提取方式: manual/auto/hybrid

    # ========== 预算与省钱 ==========
    daily_budget_limit: float = 0.0      # 日预算上限 (元, 0=无限制)
    economy_mode: str = "off"            # 省钱模式: off/low/aggressive

    # ========== 联网搜索 ==========
    web_search_enabled: bool = False     # 联网搜索开关

    # ========== 角色与世界 ==========
    selected_character_card: str = ""    # 角色卡选择 (文件名)
    selected_world_book: str = ""        # 世界书选择 (名称)

    # ========== 对话导出 ==========
    auto_export_enabled: bool = False    # 自动导出开关
    export_format: str = "json"          # 导出格式: json/text

    # ========== 数据管理 ==========
    data_clear_enabled: bool = False     # 数据清除开关 (需二次确认)

    # ========== 设备适配 (由设备检测模块自动设置) ==========
    device_profile: str = "auto"         # 设备配置: auto/low/medium/high

    # ========== 元数据 ==========
    version: str = "1.0"
    last_modified: str = ""


# ============================================================
# 设置管理器
# ============================================================

class SettingsManager:
    """
    设置管理器

    功能:
    1. 持久化存储 (JSON 文件)
    2. 热更新: 修改后立即生效, 通知监听器
    3. 默认值体系: 每个设置项都有合理的默认值
    4. 验证: 设置值范围检查
    5. 与 Kotlin 同步: 提供 get/set 接口
    """

    # 设置项验证规则
    VALIDATION_RULES = {
        "wake_up_time": {"type": str, "pattern": r"^\d{2}:\d{2}$"},
        "sleep_time": {"type": str, "pattern": r"^\d{2}:\d{2}$"},
        "proactive_message_enabled": {"type": bool},
        "proactive_interval_minutes": {"type": int, "range": (10, 1440)},
        "short_term_memory_count": {"type": int, "range": (5, 100)},
        "long_term_memory_count": {"type": int, "range": (50, 20000)},
        "memory_extraction_mode": {"type": str, "values": ["manual", "auto", "hybrid"]},
        "daily_budget_limit": {"type": (int, float), "range": (0, 10000)},
        "economy_mode": {"type": str, "values": ["off", "low", "aggressive"]},
        "web_search_enabled": {"type": bool},
        "selected_character_card": {"type": str},
        "selected_world_book": {"type": str},
        "auto_export_enabled": {"type": bool},
        "export_format": {"type": str, "values": ["json", "text"]},
        "data_clear_enabled": {"type": bool},
        "device_profile": {"type": str, "values": ["auto", "low", "medium", "high"]},
    }

    # 设备配置对应的推荐值
    DEVICE_PRESETS = {
        "low": {
            "short_term_memory_count": 10,
            "long_term_memory_count": 200,
            "proactive_interval_minutes": 240,
            "economy_mode": "aggressive",
            "web_search_enabled": False,
        },
        "medium": {
            "short_term_memory_count": 20,
            "long_term_memory_count": 1000,
            "proactive_interval_minutes": 120,
            "economy_mode": "low",
            "web_search_enabled": True,
        },
        "high": {
            "short_term_memory_count": 50,
            "long_term_memory_count": 5000,
            "proactive_interval_minutes": 60,
            "economy_mode": "off",
            "web_search_enabled": True,
        },
    }

    def __init__(self, settings_file: str = "data/settings.json"):
        self._file = Path(settings_file)
        self._file.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._settings: UserSettings = UserSettings()
        self._listeners: List[Callable[[str, Any, Any], None]] = []  # (key, old_val, new_val)

        # 加载或创建默认设置
        self._load()

        logger.info("设置管理器初始化完成")

    # ---- 持久化 ----

    def _load(self) -> None:
        """从文件加载设置"""
        if not self._file.exists():
            logger.info("设置文件不存在，使用默认设置")
            self._save()
            return

        try:
            with open(self._file, 'r', encoding='utf-8') as f:
                data = json.load(f)

            # 合并默认值 (处理新增字段)
            defaults = asdict(UserSettings())
            for key, default_val in defaults.items():
                if key not in data:
                    data[key] = default_val

            self._settings = UserSettings(**{k: v for k, v in data.items()
                                             if k in defaults})
            logger.info(f"设置已加载: {self._file}")
        except Exception as e:
            logger.error(f"加载设置失败: {e}, 使用默认设置")
            self._save()

    def _save(self) -> None:
        """保存设置到文件"""
        try:
            from datetime import datetime
            self._settings.last_modified = datetime.now().isoformat()
            data = asdict(self._settings)
            with open(self._file, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.error(f"保存设置失败: {e}")

    # ---- 读取 ----

    def get(self, key: str) -> Any:
        """获取单个设置项"""
        if not hasattr(self._settings, key):
            raise KeyError(f"未知设置项: {key}")
        return getattr(self._settings, key)

    def get_all(self) -> Dict[str, Any]:
        """获取所有设置项"""
        return asdict(self._settings)

    def get_public(self) -> Dict[str, Any]:
        """获取可公开的设置项 (排除敏感信息)"""
        return self.get_all()

    # ---- 写入 (热更新) ----

    def set(self, key: str, value: Any) -> bool:
        """
        设置单个设置项 (热更新)

        Args:
            key: 设置项名称
            value: 新值

        Returns:
            是否设置成功
        """
        with self._lock:
            if not hasattr(self._settings, key):
                logger.warning(f"未知设置项: {key}")
                return False

            # 验证
            error = self._validate(key, value)
            if error:
                logger.warning(f"设置验证失败 [{key}={value}]: {error}")
                return False

            old_value = getattr(self._settings, key)

            # 值未变化则跳过
            if old_value == value:
                return True

            # 更新
            setattr(self._settings, key, value)
            self._save()

            # 通知监听器
            self._notify_listeners(key, old_value, value)

            logger.info(f"设置已更新: {key} = {value}")
            return True

    def set_batch(self, updates: Dict[str, Any]) -> Dict[str, bool]:
        """
        批量设置 (原子操作)

        Args:
            updates: {key: value, ...}

        Returns:
            {key: success, ...}
        """
        results = {}
        with self._lock:
            for key, value in updates.items():
                results[key] = self.set(key, value)
        return results

    def reset_to_default(self) -> None:
        """重置为默认设置"""
        with self._lock:
            self._settings = UserSettings()
            self._save()
            logger.info("设置已重置为默认值")

    def apply_device_preset(self, profile: str) -> bool:
        """
        应用设备预设配置

        Args:
            profile: 设备配置 (low/medium/high)

        Returns:
            是否成功
        """
        if profile not in self.DEVICE_PRESETS:
            logger.warning(f"未知设备配置: {profile}")
            return False

        preset = self.DEVICE_PRESETS[profile]
        self.set_batch(preset)
        self.set("device_profile", profile)
        logger.info(f"已应用设备预设: {profile}")
        return True

    # ---- 验证 ----

    def _validate(self, key: str, value: Any) -> Optional[str]:
        """验证设置值"""
        if key not in self.VALIDATION_RULES:
            return None  # 无验证规则则放行

        rules = self.VALIDATION_RULES[key]

        # 类型检查
        expected_type = rules.get("type")
        if expected_type:
            if isinstance(expected_type, tuple):
                if not isinstance(value, expected_type):
                    return f"类型错误: 期望 {expected_type}, 实际 {type(value).__name__}"
            elif not isinstance(value, expected_type):
                return f"类型错误: 期望 {expected_type.__name__}, 实际 {type(value).__name__}"

        # 范围检查
        if "range" in rules and isinstance(value, (int, float)):
            min_val, max_val = rules["range"]
            if value < min_val or value > max_val:
                return f"值超出范围: [{min_val}, {max_val}]"

        # 枚举值检查
        if "values" in rules and value not in rules["values"]:
            return f"无效值: {value}, 有效值: {rules['values']}"

        # 正则检查
        if "pattern" in rules:
            import re
            if not re.match(rules["pattern"], str(value)):
                return f"格式不匹配: {rules['pattern']}"

        return None

    # ---- 监听器 ----

    def add_listener(self, callback: Callable[[str, Any, Any], None]) -> None:
        """
        添加设置变更监听器

        Args:
            callback: 回调函数 (key, old_value, new_value)
        """
        self._listeners.append(callback)

    def remove_listener(self, callback: Callable) -> None:
        """移除监听器"""
        if callback in self._listeners:
            self._listeners.remove(callback)

    def _notify_listeners(self, key: str, old_value: Any, new_value: Any) -> None:
        """通知所有监听器"""
        for callback in self._listeners:
            try:
                callback(key, old_value, new_value)
            except Exception as e:
                logger.error(f"监听器回调异常: {e}")


# ============================================================
# 示例: 设置变更监听器 (热更新记忆数量)
# ============================================================

class MemorySettingsListener:
    """记忆设置变更监听器 — 当记忆数量设置变更时自动更新 MemoryRetriever"""

    def __init__(self, retriever=None, context_manager=None):
        self.retriever = retriever
        self.context_manager = context_manager

    def on_settings_changed(self, key: str, old_value: Any, new_value: Any) -> None:
        """处理设置变更"""
        if key == "short_term_memory_count" and self.context_manager:
            self.context_manager.max_history = new_value
            logger.info(f"短期记忆数量已更新: {new_value}")

        elif key == "long_term_memory_count" and self.retriever:
            self.retriever.top_k = min(new_value, 100)
            logger.info(f"长期记忆检索数量已更新: {new_value}")

        elif key == "economy_mode":
            # 省钱模式切换时调整模型
            logger.info(f"省钱模式已切换: {new_value}")


# ============================================================
# 工厂函数
# ============================================================

def get_settings_manager(settings_file: str = "data/settings.json") -> SettingsManager:
    """获取设置管理器实例"""
    return SettingsManager(settings_file=settings_file)