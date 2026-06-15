package com.aicompanion.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件管理 ViewModel。
 * 通过 Chaquopy 调用 Python 插件管理系统。
 */
class PluginViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PluginViewModel"
    }

    /** 插件列表更新事件 */
    data class PluginListState(
        val plugins: List<PluginItem> = emptyList(),
        val isLoading: Boolean = true,
        val errorMessage: String? = null
    )

    private var _state = PluginListState()
    val state get() = _state

    /** 状态变化回调 */
    var onStateChanged: ((PluginListState) -> Unit)? = null

    private fun updateState(newState: PluginListState) {
        _state = newState
        onStateChanged?.invoke(_state)
    }

    /** 加载所有插件 */
    fun loadPlugins() {
        updateState(_state.copy(isLoading = true, errorMessage = null))
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("list_plugins")?.toString() ?: "{}"
                }
                val json = JSONObject(result)
                if (json.optString("status") == "ok") {
                    val arr: JSONArray = json.getJSONArray("plugins")
                    val plugins = mutableListOf<PluginItem>()
                    for (i in 0 until arr.length()) {
                        plugins.add(parsePluginItem(arr.getJSONObject(i)))
                    }
                    updateState(PluginListState(plugins = plugins, isLoading = false))
                } else {
                    updateState(PluginListState(errorMessage = json.optString("message", "未知错误"), isLoading = false))
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPlugins 失败", e)
                updateState(PluginListState(errorMessage = "加载失败: ${e.message}", isLoading = false))
            }
        }
    }

    /** 切换插件启用/禁用状态 */
    fun togglePlugin(name: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("toggle_plugin", name, enabled)?.toString() ?: "{}"
                }
                val json = JSONObject(result)
                if (json.optString("status") == "ok") {
                    // 刷新列表
                    loadPlugins()
                } else {
                    updateState(_state.copy(errorMessage = json.optString("message", "操作失败")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "togglePlugin 失败", e)
                updateState(_state.copy(errorMessage = "操作失败: ${e.message}"))
            }
        }
    }

    /** 获取插件详情 */
    fun getPluginDetail(name: String, callback: (PluginItem?) -> Unit) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("get_plugin_detail", name)?.toString() ?: "{}"
                }
                val json = JSONObject(result)
                if (json.optString("status") == "ok") {
                    callback(parsePluginItem(json.getJSONObject("plugin")))
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPluginDetail 失败", e)
                callback(null)
            }
        }
    }

    private fun parsePluginItem(json: JSONObject): PluginItem {
        val stats = json.optJSONObject("stats") ?: JSONObject()
        return PluginItem(
            name = json.optString("name", ""),
            version = json.optString("version", ""),
            description = json.optString("description", ""),
            category = json.optString("category", "script"),
            enabled = json.optBoolean("enabled", false),
            author = json.optString("author", ""),
            icon = json.optString("icon", "sparkle"),
            dependencies = jsonArrayToList(json.optJSONArray("dependencies")),
            conflicts = jsonArrayToList(json.optJSONArray("conflicts")),
            hooks = jsonArrayToList(json.optJSONArray("hooks")),
            callCount = stats.optInt("call_count", 0),
            errorCount = stats.optInt("error_count", 0),
            installTime = stats.optLong("install_time", 0),
            lastCallTime = stats.optLong("last_call_time", 0),
            lastError = stats.optString("last_error", "")
        )
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}