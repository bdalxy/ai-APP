package com.aicompanion.app

/**
 * 插件数据模型。
 * 对应 Python 端 BasePlugin 的 JSON 序列化数据。
 */
data class PluginItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val author: String,
    val icon: String,
    val isBuiltIn: Boolean = false,
    val dependencies: List<String>,
    val conflicts: List<String>,
    val hooks: List<String>,
    val callCount: Int,
    val errorCount: Int,
    val installTime: Long,
    val lastCallTime: Long,
    val lastError: String
) {
    val activityLevel: Int
        get() = when {
            callCount >= 1000 -> 5
            callCount >= 500  -> 4
            callCount >= 200  -> 3
            callCount >= 50   -> 2
            else              -> 1
        }

    val categoryLabel: String
        get() = when (category) {
            "chat"       -> "对话增强"
            "appearance" -> "外观美化"
            "script"     -> "脚本工具"
            else         -> category
        }

    val statusLabel: String
        get() = if (enabled) "已启用" else "已禁用"
}