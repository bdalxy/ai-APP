package com.aicompanion.app

/**
 * 世界书常识条目数据类（Kotlin 端唯一定义）。
 *
 * 对应 Python 端 world_book.WorldBookEntry 的核心字段。
 * 非人格化设计，强调"常识"而非"人格"。
 *
 * @param id        条目唯一标识（对应 Python 端 entry.id）
 * @param category  分类标签（如"世界观"、"物理规则"、"历史"，对应 Python 端 entry.comment）
 * @param content   常识正文内容
 * @param tags      匹配关键词列表（对应 Python 端 entry.keys）
 * @param priority  优先级（越高越优先，对应 Python 端 entry.priority）
 * @param createdAt 创建时间（ISO 格式字符串）
 * @param updatedAt 更新时间（ISO 格式字符串）
 */
data class WorldBookEntry(
    val id: String,
    val category: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val createdAt: String,
    val updatedAt: String
)