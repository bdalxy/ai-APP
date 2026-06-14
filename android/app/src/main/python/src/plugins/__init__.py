"""插件系统 — 可扩展的对话管道钩子"""
from .plugin_base import BasePlugin
from .plugin_manager import PluginManager

__all__ = ["BasePlugin", "PluginManager"]