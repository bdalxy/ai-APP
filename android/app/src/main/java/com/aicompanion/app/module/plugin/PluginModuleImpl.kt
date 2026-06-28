package com.aicompanion.app.module.plugin

import android.content.Context
import android.util.Log
import com.aicompanion.app.module.ModuleEventBus
import com.aicompanion.app.plugin.PluginInfo
import com.aicompanion.app.plugin.PluginRegistry
import org.json.JSONObject

/**
 * 插件模块实现。
 *
 * 桥接 [PluginRegistry]（Kotlin 原生插件系统）和 Python chat_bridge._plugins 模块。
 * 合并两边的插件列表，统一管理启停状态。
 *
 * 数据流：
 *   Kotlin 插件: PluginRegistry → PluginInfo → PluginMeta
 *   Python 插件: chat_bridge.list_plugins() → JSON → PluginMeta
 *   合并: 去重后统一返回
 */
class PluginModuleImpl(private val context: Context) : PluginModule {

    companion object {
        private const val TAG = "PluginModule"
    }

    // ======================== Python 桥接 ========================

    /**
     * 获取 Python chat_bridge 模块引用。
     *
     * @return PyObject 或 null（Python 未就绪时）
     */
    private fun getPythonModule(): com.chaquo.python.PyObject? {
        return try {
            com.chaquo.python.Python.getInstance().getModule("chat_bridge")
        } catch (e: Exception) {
            Log.w(TAG, "Python chat_bridge 模块未就绪: ${e.message}")
            null
        }
    }

    // ======================== PluginInfo <-> PluginMeta 映射 ========================

    /** 将 Kotlin 端 [PluginInfo] 转换为接口 [PluginMeta] */
    private fun PluginInfo.toMeta(): PluginMeta {
        return PluginMeta(
            id = this.id,
            name = this.name,
            version = this.version,
            isEnabled = this.isEnabled,
            isBuiltIn = this.isBuiltIn
        )
    }

    /** 将 Python 端返回的 JSON 对象转换为 [PluginMeta] */
    private fun jsonToMeta(json: JSONObject): PluginMeta {
        return PluginMeta(
            id = json.optString("name", ""),
            name = json.optString("name", ""),
            version = json.optString("version", "1.0.0"),
            isEnabled = json.optBoolean("enabled", false),
            isBuiltIn = false  // Python 插件均非内置
        )
    }

    // ======================== PluginModule 接口实现 ========================

    override fun getPlugins(): List<PluginMeta> {
        try {
            val merged = LinkedHashMap<String, PluginMeta>()

            // 1. 加载 Kotlin 原生插件
            val nativePlugins = PluginRegistry.getAllPlugins()
            for (info in nativePlugins) {
                merged[info.id] = info.toMeta()
            }

            // 2. 加载 Python 插件（如果引擎已就绪）
            val module = getPythonModule()
            if (module != null) {
                try {
                    val result = module.callAttr("list_plugins").toString()
                    val json = JSONObject(result)
                    if (json.optString("status") == "ok") {
                        val plugins = json.optJSONArray("plugins") ?: org.json.JSONArray()
                        for (i in 0 until plugins.length()) {
                            val meta = jsonToMeta(plugins.getJSONObject(i))
                            // Python 插件不覆盖已存在的 Kotlin 插件（Kotlin 优先）
                            if (!merged.containsKey(meta.id)) {
                                merged[meta.id] = meta
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "加载 Python 插件列表失败: ${e.message}")
                }
            }

            return merged.values.toList()
        } catch (e: Exception) {
            Log.e(TAG, "获取插件列表失败", e)
            return emptyList()
        }
    }

    override fun enablePlugin(pluginId: String): Boolean {
        return try {
            // 先尝试 Kotlin 原生插件
            val registeredPlugin = PluginRegistry.getPlugin(pluginId)
            if (registeredPlugin != null) {
                val success = PluginRegistry.enablePlugin(context, pluginId)
                if (success) {
                    Log.d(TAG, "Kotlin 插件已启用: $pluginId")
                    ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, pluginId)
                }
                return success
            }

            // 再尝试 Python 插件
            val module = getPythonModule()
            if (module != null) {
                val result = module.callAttr("toggle_plugin", pluginId, true).toString()
                val json = JSONObject(result)
                if (json.optString("status") == "ok") {
                    Log.d(TAG, "Python 插件已启用: $pluginId")
                    ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, pluginId)
                    return true
                }
            }

            Log.w(TAG, "插件不存在，无法启用: $pluginId")
            false
        } catch (e: Exception) {
            Log.e(TAG, "启用插件失败: $pluginId", e)
            false
        }
    }

    override fun disablePlugin(pluginId: String): Boolean {
        return try {
            // 先尝试 Kotlin 原生插件
            val registeredPlugin = PluginRegistry.getPlugin(pluginId)
            if (registeredPlugin != null) {
                val success = PluginRegistry.disablePlugin(context, pluginId)
                if (success) {
                    Log.d(TAG, "Kotlin 插件已禁用: $pluginId")
                    ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, pluginId)
                }
                return success
            }

            // 再尝试 Python 插件
            val module = getPythonModule()
            if (module != null) {
                val result = module.callAttr("toggle_plugin", pluginId, false).toString()
                val json = JSONObject(result)
                if (json.optString("status") == "ok") {
                    Log.d(TAG, "Python 插件已禁用: $pluginId")
                    ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, pluginId)
                    return true
                }
            }

            Log.w(TAG, "插件不存在，无法禁用: $pluginId")
            false
        } catch (e: Exception) {
            Log.e(TAG, "禁用插件失败: $pluginId", e)
            false
        }
    }

    override fun isPluginEnabled(pluginId: String): Boolean {
        // 先检查 Kotlin 插件
        val registeredPlugin = PluginRegistry.getPlugin(pluginId)
        if (registeredPlugin != null) {
            return PluginRegistry.getAllPlugins().any { it.id == pluginId && it.isEnabled }
        }

        // 再检查 Python 插件
        return getPlugins().any { it.id == pluginId && it.isEnabled }
    }

    override fun getPluginCount(): Int {
        return getPlugins().size
    }
}