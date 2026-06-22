package com.aicompanion.app

/**
 * 记忆条目数据类。
 *
 * @param rowid      SQLite 行 ID，用于删除操作。
 * @param id         记忆唯一标识。
 * @param type       记忆类型（13细粒度分类之一）。
 * @param parentType 父级类型（episodic / semantic / user_fact / emotional / summary）。
 * @param content    记忆内容文本。
 * @param createdAt  创建时间字符串。
 * @param importance 重要性评分（0.0 ~ 1.0）。
 */
data class MemoryItem(
    val rowid: Int,
    val id: String,
    val type: String,
    val parentType: String = "",
    val content: String,
    val createdAt: String,
    val importance: Double
)
