package com.aicompanion.app

/**
 * 插件数据模型。
 * 对应 Python 端 BasePlugin 的 JSON 序列化数据。
 */
data class PluginItem(
    val name: String,
    val version: String,
    val description: String,
    val category: String,       // chat / appearance / script
    val enabled: Boolean,
    val author: String,
    val icon: String,
    val dependencies: List<String>,   // 预留
    val conflicts: List<String>,      // 预留
    val hooks: List<String>,
    val callCount: Int,
    val errorCount: Int,
    val installTime: Long,
    val lastCallTime: Long,
    val lastError: String
) {
    /** 活跃度等级 1-5，基于调用次数 */
    val activityLevel: Int
        get() = when {
            callCount >= 1000 -> 5
            callCount >= 500  -> 4
            callCount >= 200  -> 3
            callCount >= 50   -> 2
            else              -> 1
        }

    /** 分类的中文显示名 */
    val categoryLabel: String
        get() = when (category) {
            "chat"       -> "对话增强"
            "appearance" -> "外观美化"
            "script"     -> "脚本工具"
            else         -> category
        }

    /** 状态文字 */
    val statusLabel: String
        get() = if (enabled) "已启用" else "已禁用"
}