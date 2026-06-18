package com.aicompanion.app

/**
 * 世界书常识条目数据类。
 *
 * 对应"共享的常识根基"中的每一条常识。
 * 非人格化设计，强调"常识"而非"人格"。
 *
 * @param id        条目唯一标识
 * @param category  分类标签（如"世界观"、"物理规则"、"历史"）
 * @param content   常识正文内容
 * @param createdAt 创建时间（ISO 格式字符串）
 * @param updatedAt 更新时间（ISO 格式字符串）
 */
data class WorldBookEntry(
    val id: String,
    val category: String,
    val content: String,
    val createdAt: String,
    val updatedAt: String
)