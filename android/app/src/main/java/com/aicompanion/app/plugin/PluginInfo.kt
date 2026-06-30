package com.aicompanion.app.plugin

import com.aicompanion.app.R

@Deprecated(
    message = "Kotlin 端插件体系已废弃，请使用 Python 端插件体系（src/plugins/）",
    replaceWith = ReplaceWith("com.aicompanion.app.PluginItem", "com.aicompanion.app.PluginItem"),
    level = DeprecationLevel.WARNING
)
enum class PluginType(val label: String, val category: String) {
    TOOL("工具", "tool"),
    GAME("小游戏", "game"),
    MEMORY("记忆增强", "memory"),
    CUSTOM("自定义", "custom")
}

/**
 * Kotlin 端插件信息模型。
 *
 * @deprecated 此模型已被 [com.aicompanion.app.PluginItem] 取代。
 * PluginItem 是 Python 端插件体系的统一数据模型，包含完整的元数据字段。
 * 请使用 PluginManageActivity 中的 PluginItem 来表示插件信息。
 */
@Deprecated(
    message = "请使用 PluginItem 替代 PluginInfo",
    replaceWith = ReplaceWith("PluginItem", "com.aicompanion.app.PluginItem"),
    level = DeprecationLevel.WARNING
)
data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    @Deprecated("请使用 category 字符串字段替代", ReplaceWith("category"))
    val type: PluginType,
    val icon: Int = R.drawable.ic_plugin,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val permissionRequired: List<String> = emptyList(),
    // ---- 新增字段（与 Python BasePlugin 对齐） ----
    val category: String = "",           // 插件分类字符串（chat/appearance/script，对应 Python category）
    val dependencies: List<String> = emptyList(),  // 依赖的其他插件
    val conflicts: List<String> = emptyList(),     // 冲突的插件
    val callCount: Int = 0,              // 调用次数
    val errorCount: Int = 0,             // 错误次数
    val installTime: Long = 0,           // 安装时间
    val lastCallTime: Long = 0,          // 最后调用时间
    val lastError: String = ""           // 最后错误信息
) {
    val typeLabel: String
        get() = type.label

    val categoryLabel: String
        get() = when (category.ifEmpty { type.category }) {
            "chat"       -> "对话增强"
            "appearance" -> "外观美化"
            "script"     -> "脚本工具"
            "tool"       -> "工具"
            "game"       -> "小游戏"
            "memory"     -> "记忆增强"
            "custom"     -> "自定义"
            else         -> category.ifEmpty { type.category }
        }

    val statusLabel: String
        get() = if (isEnabled) "已启用" else "已禁用"
}