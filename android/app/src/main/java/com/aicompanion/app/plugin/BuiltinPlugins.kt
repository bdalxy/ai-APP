package com.aicompanion.app.plugin

import android.content.Context
import android.util.Log
import com.aicompanion.app.R

/**
 * Kotlin 端内置插件集合。
 *
 * @deprecated Kotlin 端内置插件均为空壳桩实现，实际功能已在 Python 端 src/plugins/ 中实现。
 * 对应关系：
 * - MemoryStatsPlugin → Python 端 memory_stats_plugin.py
 * - DailyGreetingPlugin → 无对应（功能已内置于对话引擎）
 * - ConversationSummaryPlugin → 无对应（功能已内置于对话引擎）
 *
 * PluginManageActivity 已正确加载 Python 端插件，Kotlin 端插件仅作为向后兼容保留。
 */
@Deprecated(
    message = "请使用 Python 端插件体系（src/plugins/）",
    replaceWith = ReplaceWith(
        "com.aicompanion.app.PluginItem",
        "com.aicompanion.app.PluginItem"
    ),
    level = DeprecationLevel.WARNING
)
object BuiltinPlugins {

    private const val TAG = "BuiltinPlugins"

    @Deprecated("请使用 Python 端 PluginManager.load_all()")
    fun registerAll(context: Context) {
        Log.d(TAG, "开始注册内置插件...")
        PluginRegistry.registerPlugin(context, MemoryStatsPlugin())
        PluginRegistry.registerPlugin(context, DailyGreetingPlugin())
        PluginRegistry.registerPlugin(context, ConversationSummaryPlugin())
        Log.d(TAG, "内置插件注册完成，共 ${PluginRegistry.getPluginCount()} 个")
    }
}

@Deprecated("请使用 Python 端 memory_stats_plugin.py")
class MemoryStatsPlugin : IPlugin {

    private val info = PluginInfo(
        id = "memory_stats",
        name = "记忆统计",
        description = "显示当前角色记忆的数量、分类统计和活跃度分析",
        version = "1.0.0",
        author = "AI Companion",
        type = PluginType.MEMORY,
        icon = R.drawable.ic_plugin,
        isEnabled = true,
        isBuiltIn = true,
        permissionRequired = listOf("memory_read")
    )

    override fun getPluginInfo(): PluginInfo = info
    override fun onInstall(context: Context) { Log.d("MemoryStatsPlugin", "记忆统计插件已安装") }
    override fun onUninstall(context: Context) { Log.d("MemoryStatsPlugin", "记忆统计插件已卸载") }
    override fun onEnable(context: Context) { Log.d("MemoryStatsPlugin", "记忆统计插件已启用") }
    override fun onDisable(context: Context) { Log.d("MemoryStatsPlugin", "记忆统计插件已禁用") }
}

@Deprecated("此功能已内置于对话引擎，无需独立插件")
class DailyGreetingPlugin : IPlugin {

    private val info = PluginInfo(
        id = "daily_greeting",
        name = "每日一句",
        description = "每天自动生成一句 AI 问候语，温暖你的每一天",
        version = "1.0.0",
        author = "AI Companion",
        type = PluginType.TOOL,
        icon = R.drawable.ic_plugin,
        isEnabled = true,
        isBuiltIn = true,
        permissionRequired = emptyList()
    )

    override fun getPluginInfo(): PluginInfo = info
    override fun onInstall(context: Context) { Log.d("DailyGreetingPlugin", "每日一句插件已安装") }
    override fun onUninstall(context: Context) { Log.d("DailyGreetingPlugin", "每日一句插件已卸载") }
    override fun onEnable(context: Context) { Log.d("DailyGreetingPlugin", "每日一句插件已启用") }
    override fun onDisable(context: Context) { Log.d("DailyGreetingPlugin", "每日一句插件已禁用") }
}

@Deprecated("此功能已内置于对话引擎，无需独立插件")
class ConversationSummaryPlugin : IPlugin {

    private val info = PluginInfo(
        id = "conversation_summary",
        name = "对话总结",
        description = "自动生成当前对话的摘要，快速回顾重点内容",
        version = "1.0.0",
        author = "AI Companion",
        type = PluginType.TOOL,
        icon = R.drawable.ic_plugin,
        isEnabled = true,
        isBuiltIn = true,
        permissionRequired = listOf("chat_read")
    )

    override fun getPluginInfo(): PluginInfo = info
    override fun onInstall(context: Context) { Log.d("ConversationSummaryPlugin", "对话总结插件已安装") }
    override fun onUninstall(context: Context) { Log.d("ConversationSummaryPlugin", "对话总结插件已卸载") }
    override fun onEnable(context: Context) { Log.d("ConversationSummaryPlugin", "对话总结插件已启用") }
    override fun onDisable(context: Context) { Log.d("ConversationSummaryPlugin", "对话总结插件已禁用") }
}