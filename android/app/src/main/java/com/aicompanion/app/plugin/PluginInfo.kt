package com.aicompanion.app.plugin

import com.aicompanion.app.R

enum class PluginType(val label: String, val category: String) {
    TOOL("工具", "tool"),
    GAME("小游戏", "game"),
    MEMORY("记忆增强", "memory"),
    CUSTOM("自定义", "custom")
}

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val type: PluginType,
    val icon: Int = R.drawable.ic_plugin,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val permissionRequired: List<String> = emptyList()
) {
    val typeLabel: String
        get() = type.label

    val statusLabel: String
        get() = if (isEnabled) "已启用" else "已禁用"
}