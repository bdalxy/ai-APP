package com.aicompanion.app

/**
 * 聊天消息数据类。
 *
 * @param content  消息文本内容。
 * @param isUser   是否为用户发送的消息（false 表示 AI 回复）。
 * @param isTyping 是否为"对方正在输入..."打字指示器。
 * @param timestamp 消息时间戳（毫秒）。
 */
data class Message(
    val content: String,
    val isUser: Boolean,
    val isTyping: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)