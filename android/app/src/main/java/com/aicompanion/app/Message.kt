package com.aicompanion.app

import java.util.UUID

/**
 * 聊天消息数据类。
 *
 * @param content  消息文本内容。
 * @param isUser   是否为用户发送的消息（false 表示 AI 回复）。
 * @param isTyping 是否为"对方正在输入..."打字指示器。
 * @param timestamp 消息时间戳（毫秒）。
 * @param id       消息唯一标识符，用于 DiffUtil 精确比较。
 * @param groupId  消息组 ID，同一发送者连续发送的消息共享同一 groupId。
 * @param isGroupStart 是否为本组第一条消息（显示头像和昵称）。
 * @param isGroupEnd   是否为本组最后一条消息（显示时间戳）。
 * @param status   消息状态：sending / sent / delivered / read / error。
 * @param senderName 发送者名称（AI 角色名 / "我"）。
 */
data class Message(
    val content: String,
    val isUser: Boolean,
    val isTyping: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val groupId: String = id,
    val isGroupStart: Boolean = true,
    val isGroupEnd: Boolean = true,
    val status: MessageStatus = MessageStatus.SENT,
    val senderName: String = "",
    /** 消息类型（文本/语音），默认文本 */
    val msgType: MsgType = MsgType.TEXT,
    /** 语音文件路径（仅语音消息有效） */
    val voiceFilePath: String = "",
    /** 语音时长（毫秒），仅语音消息有效 */
    val voiceDurationMs: Long = 0L,
    /** 语音是否已播放过（用于未读红点） */
    val voicePlayed: Boolean = false
) {
    /** 消息状态枚举 */
    enum class MessageStatus {
        SENDING,
        SENT,
        DELIVERED,
        READ,
        ERROR
    }

    /** 消息类型枚举 */
    enum class MsgType {
        /** 文本消息 */
        TEXT,
        /** 语音消息 */
        VOICE
    }
}