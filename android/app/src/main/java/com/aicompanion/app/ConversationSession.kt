package com.aicompanion.app

/**
 * 多会话数据模型。
 *
 * 每个会话关联一个角色卡，拥有独立的消息文件 (session_{id}.json)，
 * 支持会话列表预览（最后一条消息摘要、消息数量）。
 *
 * @param id              会话唯一标识（UUID）
 * @param name            会话名称（用户可自定义）
 * @param characterCardId 关联的角色卡ID
 * @param lastMessage     最后一条消息的文本预览（截取前50字符）
 * @param messageCount    会话中的消息总数
 * @param createdAt       创建时间戳（毫秒）
 * @param updatedAt       最后更新时间戳（毫秒）
 */
data class ConversationSession(
    val id: String,
    val name: String,
    val characterCardId: String,
    val lastMessage: String,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)