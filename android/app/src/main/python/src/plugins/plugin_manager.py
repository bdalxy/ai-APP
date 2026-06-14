"""插件管理器 — 发现、加载、管理插件"""

import importlib
import logging
import os
import threading
from typing import List, Optional

from .plugin_base import BasePlugin

_logger = logging.getLogger("PluginManager")


class PluginManager:
    """插件管理器。
    
    负责：
    - 扫描 plugins/ 目录发现插件
    - 加载/卸载插件
    - 管理插件生命周期
    - 在管道钩子中调用插件
    """

    def __init__(self):
        self._plugins: List[BasePlugin] = []
        self._lock = threading.Lock()
        self._plugin_dir = os.path.dirname(os.path.abspath(__file__))

    @property
    def plugins(self) -> List[BasePlugin]:
        """返回当前已加载的插件列表（只读副本）"""
        with self._lock:
            return list(self._plugins)

    def discover(self) -> List[str]:
        """扫描插件目录，发现可用插件模块。
        
        Returns:
            发现的插件模块名列表（不含 .py 后缀）
        """
        modules = []
        if not os.path.isdir(self._plugin_dir):
            return modules
        
        for fname in os.listdir(self._plugin_dir):
            if fname.startswith("_") or fname.startswith("."):
                continue
            if fname.endswith(".py") and fname != "plugin_base.py" and fname != "plugin_manager.py":
                modules.append(fname[:-3])  # 去掉 .py
        return modules

    def load_plugin(self, module_name: str) -> Optional[BasePlugin]:
        """加载指定插件模块。
        
        Args:
            module_name: 插件模块名（不含 .py 后缀）
        
        Returns:
            加载的插件实例，失败返回 None
        """
        try:
            mod = importlib.import_module(f".{module_name}", package="src.plugins")
            # 查找模块中第一个 BasePlugin 子类
            plugin = None
            for attr_name in dir(mod):
                attr = getattr(mod, attr_name)
                if (isinstance(attr, type) and 
                    issubclass(attr, BasePlugin) and 
                    attr is not BasePlugin):
                    plugin = attr()
                    break
            
            if plugin is None:
                _logger.warning(f"插件模块 {module_name} 中未找到 BasePlugin 子类")
                return None
            
            with self._lock:
                # 避免重复加载
                existing = self.get_plugin(plugin.name)
                if existing:
                    _logger.info(f"插件 {plugin.name} 已加载，跳过")
                    return existing
                self._plugins.append(plugin)
            
            _logger.info(f"插件已加载: {plugin.name} v{plugin.version}")
            return plugin
        except Exception as e:
            _logger.error(f"加载插件 {module_name} 失败: {e}")
            return None

    def load_all(self) -> int:
        """发现并加载所有可用插件。
        
        Returns:
            成功加载的插件数量
        """
        modules = self.discover()
        count = 0
        for mod_name in modules:
            if self.load_plugin(mod_name):
                count += 1
        return count

    def unload_plugin(self, name: str) -> bool:
        """卸载指定插件。
        
        Args:
            name: 插件名称
        
        Returns:
            是否卸载成功
        """
        with self._lock:
            for i, p in enumerate(self._plugins):
                if p.name == name:
                    self._plugins.pop(i)
                    _logger.info(f"插件已卸载: {name}")
                    return True
        return False

    def get_plugin(self, name: str) -> Optional[BasePlugin]:
        """获取指定插件实例。
        
        Args:
            name: 插件名称
        
        Returns:
            插件实例，不存在返回 None
        """
        with self._lock:
            for p in self._plugins:
                if p.name == name:
                    return p
        return None

    def get_enabled_plugins(self) -> List[BasePlugin]:
        """获取所有已启用的插件。
        
        Returns:
            已启用的插件列表
        """
        with self._lock:
            return [p for p in self._plugins if p.enabled]

    def set_enabled(self, name: str, enabled: bool) -> bool:
        """设置插件启用/禁用状态。
        
        Args:
            name: 插件名称
            enabled: True 启用, False 禁用
        
        Returns:
            是否设置成功
        """
        plugin = self.get_plugin(name)
        if plugin:
            plugin.enabled = enabled
            _logger.info(f"插件 {name} {'启用' if enabled else '禁用'}")
            return True
        return False

    # ===== 管道钩子方法 =====

    def pre_process(self, user_input: str) -> str:
        """依次调用所有已启用插件的 pre_process 钩子。
        
        Args:
            user_input: 原始用户输入
        
        Returns:
            处理后的用户输入
        """
        result = user_input
        for plugin in self.get_enabled_plugins():
            try:
                modified = plugin.pre_process(result)
                if modified is not None:
                    result = modified
            except Exception as e:
                _logger.error(f"插件 {plugin.name}.pre_process() 异常: {e}")
        return result

    def post_process(self, ai_reply: str) -> str:
        """依次调用所有已启用插件的 post_process 钩子。
        
        Args:
            ai_reply: 原始 AI 回复
        
        Returns:
            处理后的 AI 回复
        """
        result = ai_reply
        for plugin in self.get_enabled_plugins():
            try:
                modified = plugin.post_process(result)
                if modified is not None:
                    result = modified
            except Exception as e:
                _logger.error(f"插件 {plugin.name}.post_process() 异常: {e}")
        return result

    def on_turn_end(self, user_input: str, ai_reply: str) -> None:
        """依次调用所有已启用插件的 on_turn_end 钩子。"""
        for plugin in self.get_enabled_plugins():
            try:
                plugin.on_turn_end(user_input, ai_reply)
            except Exception as e:
                _logger.error(f"插件 {plugin.name}.on_turn_end() 异常: {e}")

    def on_memory_extracted(self, memory: dict) -> None:
        """依次调用所有已启用插件的 on_memory_extracted 钩子。"""
        for plugin in self.get_enabled_plugins():
            try:
                plugin.on_memory_extracted(memory)
            except Exception as e:
                _logger.error(f"插件 {plugin.name}.on_memory_extracted() 异常: {e}")

    def reload(self) -> int:
        """重新加载所有插件（先卸载再加载）。
        
        Returns:
            重新加载的插件数量
        """
        self.unload_all()
        return self.load_all()

    def unload_all(self) -> None:
        """卸载所有插件。"""
        with self._lock:
            self._plugins.clear()


# 全局单例
_plugin_manager = PluginManager()


def get_plugin_manager() -> PluginManager:
    """获取全局插件管理器实例。"""
    return _plugin_manager