"""插件管理桥接模块 — 为 Kotlin 端提供插件管理接口"""

import json
import inspect
import logging
from src.plugins.plugin_base import BasePlugin
from src.plugins.plugin_manager import get_plugin_manager

_logger = logging.getLogger("Bridge.Plugins")


def _get_implemented_hooks(plugin: BasePlugin) -> list:
    """获取插件实现的钩子列表"""
    hooks = []
    hook_names = ["pre_process", "post_process", "on_turn_end", "on_memory_extracted"]
    for name in hook_names:
        method = getattr(plugin, name, None)
        if method is not None:
            base_method = getattr(BasePlugin, name, None)
            if method.__func__ is not base_method:
                hooks.append(name)
    return hooks


def list_plugins() -> str:
    """列出所有已加载插件及其状态。

    Returns:
        JSON 字符串: {"status": "ok", "plugins": [...]}
    """
    try:
        pm = get_plugin_manager()
        plugins_data = []
        for p in pm.plugins:
            plugins_data.append({
                "name": p.name,
                "version": p.version,
                "description": p.description,
                "category": getattr(p, "category", "script"),
                "enabled": p.enabled,
                "author": getattr(p, "author", ""),
                "icon": getattr(p, "icon", "sparkle"),
                "dependencies": getattr(p, "dependencies", []),
                "conflicts": getattr(p, "conflicts", []),
                "hooks": _get_implemented_hooks(p),
                "stats": {
                    "call_count": getattr(p, "_call_count", 0),
                    "error_count": getattr(p, "_error_count", 0),
                    "install_time": getattr(p, "_install_time", 0),
                    "last_call_time": getattr(p, "_last_call_time", 0),
                    "last_error": getattr(p, "_last_error", ""),
                }
            })
        return json.dumps({"status": "ok", "plugins": plugins_data}, ensure_ascii=False)
    except Exception as e:
        _logger.error(f"list_plugins 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def toggle_plugin(name: str, enabled: bool) -> str:
    """启用/禁用指定插件。

    Args:
        name: 插件名称
        enabled: True 启用, False 禁用

    Returns:
        JSON: {"status": "ok", "name": "...", "enabled": true}
    """
    try:
        pm = get_plugin_manager()
        ok = pm.set_enabled(name, enabled)
        if ok:
            return json.dumps({"status": "ok", "name": name, "enabled": enabled}, ensure_ascii=False)
        else:
            return json.dumps({"status": "error", "message": f"插件 {name} 不存在"}, ensure_ascii=False)
    except Exception as e:
        _logger.error(f"toggle_plugin 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def get_plugin_detail(name: str) -> str:
    """获取指定插件的详细信息。

    Args:
        name: 插件名称

    Returns:
        JSON 字符串
    """
    try:
        pm = get_plugin_manager()
        p = pm.get_plugin(name)
        if p is None:
            return json.dumps({"status": "error", "message": f"插件 {name} 不存在"}, ensure_ascii=False)
        return json.dumps({
            "status": "ok",
            "plugin": {
                "name": p.name,
                "version": p.version,
                "description": p.description,
                "category": getattr(p, "category", "script"),
                "enabled": p.enabled,
                "author": getattr(p, "author", ""),
                "icon": getattr(p, "icon", "sparkle"),
                "dependencies": getattr(p, "dependencies", []),
                "conflicts": getattr(p, "conflicts", []),
                "hooks": _get_implemented_hooks(p),
                "stats": {
                    "call_count": getattr(p, "_call_count", 0),
                    "error_count": getattr(p, "_error_count", 0),
                    "install_time": getattr(p, "_install_time", 0),
                    "last_call_time": getattr(p, "_last_call_time", 0),
                    "last_error": getattr(p, "_last_error", ""),
                }
            }
        }, ensure_ascii=False)
    except Exception as e:
        _logger.error(f"get_plugin_detail 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def get_plugin_count() -> str:
    """获取插件数量统计。

    Returns:
        JSON: {"status": "ok", "total": 5, "enabled": 3}
    """
    try:
        pm = get_plugin_manager()
        all_plugins = pm.plugins
        enabled = pm.get_enabled_plugins()
        return json.dumps({
            "status": "ok",
            "total": len(all_plugins),
            "enabled": len(enabled),
            "disabled": len(all_plugins) - len(enabled),
        }, ensure_ascii=False)
    except Exception as e:
        _logger.error(f"get_plugin_count 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)