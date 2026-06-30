package com.aicompanion.app.plugin

import android.content.Context

/**
 * Kotlin 端插件接口。
 *
 * @deprecated Kotlin 端插件体系已被 Python 端插件体系（src/plugins/）取代。
 * Python 端 BasePlugin 提供更丰富的钩子接口（pre_process/post_process/on_turn_end/on_memory_extracted），
 * 且由 PluginManager 统一管理生命周期和状态持久化。
 *
 * 当前 Kotlin 端插件（MemoryStatsPlugin、DailyGreetingPlugin、ConversationSummaryPlugin）
 * 均为空壳桩实现，实际功能已在 Python 端对应插件中实现。
 * PluginManageActivity 已正确合并两套体系，优先使用 Python 端插件。
 *
 * 迁移指南：
 * - 新插件请在 Python 端 src/plugins/ 中实现，继承 BasePlugin
 * - 使用 PluginManager 管理插件生命周期
 * - 使用 PluginItem 作为统一数据模型
 */
@Deprecated(
    message = "请使用 Python 端插件体系（BasePlugin + PluginManager）",
    replaceWith = ReplaceWith(
        "com.aicompanion.app.PluginItem",
        "com.aicompanion.app.PluginItem"
    ),
    level = DeprecationLevel.WARNING
)
interface IPlugin {
    @Deprecated("请使用 Python 端 PluginManager 管理插件生命周期")
    fun onInstall(context: Context)

    @Deprecated("请使用 Python 端 PluginManager 管理插件生命周期")
    fun onUninstall(context: Context)

    @Deprecated("请使用 Python 端 PluginManager 管理插件生命周期")
    fun onEnable(context: Context)

    @Deprecated("请使用 Python 端 PluginManager 管理插件生命周期")
    fun onDisable(context: Context)

    @Deprecated("请使用 PluginItem 替代 PluginInfo")
    fun getPluginInfo(): PluginInfo
}