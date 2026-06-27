package com.aicompanion.app.module.plugin

data class PluginMeta(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val isEnabled: Boolean = false,
    val isBuiltIn: Boolean = false
)

interface PluginModule {

    /** 获取所有已注册插件 */
    fun getPlugins(): List<PluginMeta>

    /** 启用指定插件 */
    fun enablePlugin(pluginId: String): Boolean

    /** 禁用指定插件 */
    fun disablePlugin(pluginId: String): Boolean

    /** 检查插件是否已启用 */
    fun isPluginEnabled(pluginId: String): Boolean

    /** 获取插件数量 */
    fun getPluginCount(): Int
}