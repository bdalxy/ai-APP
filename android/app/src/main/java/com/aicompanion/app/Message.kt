package com.aicompanion.app

import java.util.UUID

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
    val msgType: MsgType = MsgType.TEXT,
    val voiceFilePath: String = "",
    val voiceDurationMs: Long = 0L,
    val voicePlayed: Boolean = false
) {
    enum class MessageStatus {
        SENDING,
        SENT,
        DELIVERED,
        READ,
        ERROR
    }

    enum class MsgType {
        TEXT,
        VOICE
    }
}