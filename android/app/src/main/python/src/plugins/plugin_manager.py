"""插件管理器 — 发现、加载、管理插件"""

import importlib
import json
import logging
import os
import threading
import time
from typing import List, Optional

from .plugin_base import BasePlugin
from src.utils import crypto_utils

_log = logging.getLogger(__name__)


class PluginManager:
    """插件管理器。
    
    负责：
    - 扫描 plugins/ 目录发现插件
    - 加载/卸载插件
    - 管理插件生命周期
    - 在管道钩子中调用插件

    使用单例模式，确保全局只有一个实例。
    """

    _instance: "PluginManager | None" = None
    _initialized: bool = False

    def __new__(cls) -> "PluginManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if PluginManager._initialized:
            return
        PluginManager._initialized = True
        self._plugins: List[BasePlugin] = []
        self._lock = threading.Lock()
        self._plugin_dir = os.path.dirname(os.path.abspath(__file__))
        # 状态持久化
        self._state_dir = os.path.join(self._plugin_dir, "data")
        os.makedirs(self._state_dir, exist_ok=True)
        self._state_file = os.path.join(self._state_dir, "plugin_state.json")
        self._loaded_state: dict = {}
        self._dirty = False

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
                _log.warning(f"插件模块 {module_name} 中未找到 BasePlugin 子类")
                return None
            
            with self._lock:
                # 避免重复加载
                existing = self.get_plugin(plugin.name)
                if existing:
                    _log.info(f"插件 {plugin.name} 已加载，跳过")
                    return existing
                self._plugins.append(plugin)

            # 恢复持久化状态
            self._restore_plugin_state(plugin)
            
            _log.info(f"插件已加载: {plugin.name} v{plugin.version}")
            return plugin
        except Exception as e:
            _log.error(f"加载插件 {module_name} 失败: {e}")
            return None

    def load_all(self) -> int:
        """发现并加载所有可用插件，并恢复持久化状态。

        Returns:
            成功加载的插件数量
        """
        # 先读取持久化状态，以便 load_plugin() 中恢复
        self._loaded_state = self._load_state()
        modules = self.discover()
        count = 0
        for mod_name in modules:
            if self.load_plugin(mod_name):
                count += 1
        # 加载完成后保存一次状态（确保新增插件被记录）
        self._save_state()
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
                    _log.info(f"插件已卸载: {name}")
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

    # ===== 状态持久化 =====

    def _save_state(self) -> None:
        """将所有插件状态保存到 data/plugin_state.json（ISS-063: 加密存储）。

        线程安全：使用 _lock 保护 _plugins 的遍历。
        """
        with self._lock:
            state = {"plugins": {}}
            for p in self._plugins:
                state["plugins"][p.name] = {
                    "enabled": p.enabled,
                    "call_count": p._call_count,
                    "error_count": p._error_count,
                    "install_time": p._install_time,
                    "last_call_time": p._last_call_time,
                    "last_error": p._last_error,
                }
        try:
            json_str = json.dumps(state, ensure_ascii=False, indent=2)
            encrypted = crypto_utils.encrypt(json_str, crypto_utils.get_device_password())
            with open(self._state_file, "wb") as f:
                f.write(encrypted)
            self._dirty = False
            _log.debug("插件状态已保存到 %s", self._state_file)
        except Exception as e:
            _log.error("保存插件状态失败: %s", e)

    def _load_state(self) -> dict:
        """从 data/plugin_state.json 读取插件状态（ISS-063: 解密读取）。

        Returns:
            {"plugin_name": {"enabled": ..., ...}, ...}
            文件不存在或解密失败时返回空字典。
        """
        if not os.path.exists(self._state_file):
            _log.debug("插件状态文件不存在，使用默认状态")
            return {}
        try:
            with open(self._state_file, "rb") as f:
                encrypted = f.read()
            json_str = crypto_utils.decrypt(encrypted, crypto_utils.get_device_password())
            if json_str is None:
                _log.warning("插件状态解密失败（密码错误或数据损坏），使用默认状态")
                return {}
            data = json.loads(json_str)
            plugins_state = data.get("plugins", {})
            _log.info("已加载 %d 个插件的持久化状态", len(plugins_state))
            return plugins_state
        except json.JSONDecodeError as e:
            _log.warning("插件状态 JSON 解析失败: %s，使用默认状态", e)
            return {}
        except Exception as e:
            _log.warning("读取插件状态失败: %s，使用默认状态", e)
            return {}

    def _restore_plugin_state(self, plugin: BasePlugin) -> None:
        """从已加载的状态数据中恢复单个插件的状态。

        Args:
            plugin: 要恢复状态的插件实例
        """
        state = self._loaded_state.get(plugin.name)
        if not state:
            return
        try:
            plugin.enabled = state.get("enabled", plugin.enabled)
            plugin._call_count = state.get("call_count", 0)
            plugin._error_count = state.get("error_count", 0)
            plugin._install_time = state.get("install_time", plugin._install_time)
            plugin._last_call_time = state.get("last_call_time", 0.0)
            plugin._last_error = state.get("last_error", "")
            _log.debug("插件 %s 状态已恢复: enabled=%s, call_count=%d",
                          plugin.name, plugin.enabled, plugin._call_count)
        except Exception as e:
            _log.warning("恢复插件 %s 状态失败: %s", plugin.name, e)

    # ===== 启用/禁用 =====

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
            self._save_state()
            _log.info(f"插件 {name} {'启用' if enabled else '禁用'}")
            return True
        return False

    # ===== 管道钩子方法 =====

    def pre_process(self, user_input: str) -> str:
        """依次调用所有已启用插件的 pre_process 钩子。"""
        result = user_input
        for plugin in self.get_enabled_plugins():
            try:
                modified = plugin.pre_process(result)
                plugin._call_count += 1
                plugin._last_call_time = time.time()
                self._dirty = True
                if modified is not None:
                    result = modified
            except Exception as e:
                plugin._error_count += 1
                plugin._last_error = str(e)
                self._dirty = True
                _log.error(f"插件 {plugin.name}.pre_process() 异常: {e}")
        return result

    def post_process(self, ai_reply: str) -> str:
        """依次调用所有已启用插件的 post_process 钩子。"""
        result = ai_reply
        for plugin in self.get_enabled_plugins():
            try:
                modified = plugin.post_process(result)
                plugin._call_count += 1
                plugin._last_call_time = time.time()
                self._dirty = True
                if modified is not None:
                    result = modified
            except Exception as e:
                plugin._error_count += 1
                plugin._last_error = str(e)
                self._dirty = True
                _log.error(f"插件 {plugin.name}.post_process() 异常: {e}")
        return result

    def on_turn_end(self, user_input: str, ai_reply: str) -> None:
        """依次调用所有已启用插件的 on_turn_end 钩子。"""
        for plugin in self.get_enabled_plugins():
            try:
                plugin.on_turn_end(user_input, ai_reply)
                plugin._call_count += 1
                plugin._last_call_time = time.time()
                self._dirty = True
            except Exception as e:
                plugin._error_count += 1
                plugin._last_error = str(e)
                self._dirty = True
                _log.error(f"插件 {plugin.name}.on_turn_end() 异常: {e}")

    def on_memory_extracted(self, memory: dict) -> None:
        """依次调用所有已启用插件的 on_memory_extracted 钩子。"""
        for plugin in self.get_enabled_plugins():
            try:
                plugin.on_memory_extracted(memory)
                plugin._call_count += 1
                plugin._last_call_time = time.time()
                self._dirty = True
            except Exception as e:
                plugin._error_count += 1
                plugin._last_error = str(e)
                self._dirty = True
                _log.error(f"插件 {plugin.name}.on_memory_extracted() 异常: {e}")

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