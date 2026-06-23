"""插件系统 — 可扩展的对话管道钩子"""
from .plugin_base import BasePlugin
from .plugin_manager import PluginManager
from .logger_plugin import LoggerPlugin
from .weather_plugin import WeatherPlugin
from .joke_plugin import JokePlugin
from .reminder_plugin import ReminderPlugin
from .memory_stats_plugin import MemoryStatsPlugin

__all__ = [
    "BasePlugin",
    "PluginManager",
    "LoggerPlugin",
    "WeatherPlugin",
    "JokePlugin",
    "ReminderPlugin",
    "MemoryStatsPlugin",
]