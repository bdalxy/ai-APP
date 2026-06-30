package com.aicompanion.app.plugin

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin 端插件注册中心。
 *
 * @deprecated 此注册中心已被 Python 端 PluginManager 取代。
 * Python 端 PluginManager 提供完整的插件发现、加载、生命周期管理和状态持久化。
 * PluginManageActivity 已正确合并两套体系，Kotlin 端插件仅作为向后兼容保留。
 *
 * 迁移指南：
 * - 使用 Python 端 PluginManager 管理插件
 * - 通过 chat_bridge 桥接模块调用 Python 端插件 API
 * - 使用 PluginItem 作为统一数据模型
 */
@Deprecated(
    message = "请使用 Python 端 PluginManager 管理插件",
    replaceWith = ReplaceWith(
        "com.aicompanion.app.PluginItem",
        "com.aicompanion.app.PluginItem"
    ),
    level = DeprecationLevel.WARNING
)
object PluginRegistry {

    private const val TAG = "PluginRegistry"
    private const val PREFS_NAME = "plugin_registry"
    private const val KEY_ENABLED_STATES = "plugin_enabled_states"

    private val plugins = LinkedHashMap<String, IPlugin>()
    private val enabledStates = LinkedHashMap<String, Boolean>()
    private var initialized = false

    @Deprecated("请使用 Python 端 PluginManager 初始化")
    fun init(context: Context) {
        if (initialized) { Log.d(TAG, "插件注册中心已初始化，跳过重复初始化"); return }
        loadEnabledStates(context)
        initialized = true
        Log.d(TAG, "插件注册中心初始化完成，已加载 ${enabledStates.size} 条状态记录")
    }

    @Deprecated("请使用 Python 端 PluginManager.load_plugin()")
    fun registerPlugin(context: Context, plugin: IPlugin): Boolean {
        val info = plugin.getPluginInfo()
        val pluginId = info.id

        if (plugins.containsKey(pluginId)) { Log.w(TAG, "插件已存在，跳过注册: $pluginId"); return false }

        val isEnabled = enabledStates.getOrDefault(pluginId, info.isEnabled)
        val updatedInfo = info.copy(isEnabled = isEnabled)

        plugins[pluginId] = plugin
        enabledStates[pluginId] = isEnabled

        try {
            plugin.onInstall(context)
            if (isEnabled) { plugin.onEnable(context) }
            Log.d(TAG, "插件注册成功: $pluginId (${info.name}), 启用=$isEnabled, 内置=${info.isBuiltIn}")
        } catch (e: Exception) {
            Log.e(TAG, "插件注册失败: $pluginId, 错误: ${e.message}", e)
            plugins.remove(pluginId)
            enabledStates.remove(pluginId)
            return false
        }

        return true
    }

    @Deprecated("请使用 Python 端 PluginManager.unload_plugin()")
    fun unregisterPlugin(context: Context, pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: run { Log.w(TAG, "插件不存在，无法注销: $pluginId"); return false }
        val info = plugin.getPluginInfo()
        if (info.isBuiltIn) { Log.w(TAG, "内置插件不可卸载: $pluginId"); return false }

        try { plugin.onDisable(context); plugin.onUninstall(context) }
        catch (e: Exception) { Log.e(TAG, "插件卸载回调失败: $pluginId, 错误: ${e.message}", e) }

        plugins.remove(pluginId)
        enabledStates.remove(pluginId)
        Log.d(TAG, "插件已注销: $pluginId")
        return true
    }

    @Deprecated("请使用 Python 端 PluginManager.get_plugin()")
    fun getPlugin(pluginId: String): IPlugin? = plugins[pluginId]

    @Deprecated("请使用 Python 端 PluginManager.plugins")
    fun getAllPlugins(): List<PluginInfo> {
        return plugins.values.map { plugin ->
            val info = plugin.getPluginInfo()
            val isEnabled = enabledStates[info.id] ?: info.isEnabled
            info.copy(isEnabled = isEnabled)
        }
    }

    @Deprecated("请使用 Python 端 PluginManager.get_enabled_plugins()")
    fun getEnabledPlugins(): List<PluginInfo> = getAllPlugins().filter { it.isEnabled }

    @Deprecated("请使用 Python 端 PluginManager.set_enabled()")
    fun enablePlugin(context: Context, pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: run { Log.w(TAG, "插件不存在，无法启用: $pluginId"); return false }
        if (enabledStates[pluginId] == true) { Log.d(TAG, "插件已处于启用状态: $pluginId"); return true }
        try {
            plugin.onEnable(context)
            enabledStates[pluginId] = true
            persistEnabledStates(context)
            Log.d(TAG, "插件已启用: $pluginId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "启用插件失败: $pluginId, 错误: ${e.message}", e)
            return false
        }
    }

    @Deprecated("请使用 Python 端 PluginManager.set_enabled()")
    fun disablePlugin(context: Context, pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: run { Log.w(TAG, "插件不存在，无法禁用: $pluginId"); return false }
        if (enabledStates[pluginId] == false) { Log.d(TAG, "插件已处于禁用状态: $pluginId"); return true }
        try {
            plugin.onDisable(context)
            enabledStates[pluginId] = false
            persistEnabledStates(context)
            Log.d(TAG, "插件已禁用: $pluginId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "禁用插件失败: $pluginId, 错误: ${e.message}", e)
            return false
        }
    }

    @Deprecated("请使用 Python 端 PluginManager")
    fun getPluginCount(): Int = plugins.size

    @Deprecated("请使用 Python 端 PluginManager")
    fun getEnabledPluginCount(): Int = enabledStates.count { it.value }

    private fun loadEnabledStates(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ENABLED_STATES, null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            enabledStates.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val enabled = obj.optBoolean("enabled", true)
                enabledStates[id] = enabled
            }
            Log.d(TAG, "已从本地加载 ${enabledStates.size} 条插件状态")
        } catch (e: Exception) { Log.e(TAG, "加载插件状态失败: ${e.message}", e) }
    }

    private fun persistEnabledStates(context: Context) {
        try {
            val jsonArray = JSONArray()
            for ((id, enabled) in enabledStates) {
                val obj = JSONObject()
                obj.put("id", id)
                obj.put("enabled", enabled)
                jsonArray.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ENABLED_STATES, jsonArray.toString()).apply()
            Log.d(TAG, "插件状态已持久化: ${enabledStates.size} 条")
        } catch (e: Exception) { Log.e(TAG, "持久化插件状态失败: ${e.message}", e) }
    }
}