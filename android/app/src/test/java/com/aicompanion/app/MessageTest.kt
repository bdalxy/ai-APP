package com.aicompanion.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Message 数据类单元测试。
 *
 * 覆盖：
 *  - 默认值验证
 *  - 自定义属性
 *  - copy() 方法
 *  - 边界条件（空字符串、特殊字符、长文本）
 *  - MsgType 枚举
 *  - MessageStatus 枚举
 *  - 序列化/反序列化等价性（通过 JSONObject 往返）
 */
@DisplayName("Message 数据类")
class MessageTest {

    // =====================================================================
    // 默认值与构造
    // =====================================================================

    @Nested
    @DisplayName("默认值")
    inner class Defaults {

        @Test
        @DisplayName("仅提供必填字段时，其余字段使用默认值")
        fun testDefaults() {
            val msg = Message(content = "测试消息", isUser = true)
            assertEquals("测试消息", msg.content)
            assertTrue(msg.isUser)
            assertFalse(msg.isTyping)
            assertFalse(msg.isEdited)
            assertTrue(msg.id.isNotBlank())
            assertTrue(msg.id.length == 36) // UUID 标准长度
            assertEquals(msg.id, msg.groupId) // groupId 默认等于 id
            assertTrue(msg.isGroupStart)
            assertTrue(msg.isGroupEnd)
            assertEquals(Message.MessageStatus.SENT, msg.status)
            assertEquals("", msg.senderName)
            assertEquals(Message.MsgType.TEXT, msg.msgType)
            assertEquals("", msg.voiceFilePath)
            assertEquals(0L, msg.voiceDurationMs)
            assertFalse(msg.voicePlayed)
        }

        @Test
        @DisplayName("isTyping 默认为 false")
        fun testIsTypingDefaultFalse() {
            val msg = Message(content = "消息", isUser = true)
            assertFalse(msg.isTyping)
        }

        @Test
        @DisplayName("isEdited 默认为 false")
        fun testIsEditedDefaultFalse() {
            val msg = Message(content = "消息", isUser = false)
            assertFalse(msg.isEdited)
        }
    }

    // =====================================================================
    // 自定义属性
    // =====================================================================

    @Nested
    @DisplayName("自定义属性")
    inner class Custom {

        @Test
        @DisplayName("所有属性均可自定义")
        fun testAllFieldsCustom() {
            val msg = Message(
                content = "AI回复",
                isUser = false,
                isTyping = true,
                timestamp = 123456789L,
                id = "custom-id",
                groupId = "group-1",
                isGroupStart = false,
                isGroupEnd = false,
                status = Message.MessageStatus.ERROR,
                senderName = "星遥",
                msgType = Message.MsgType.VOICE,
                voiceFilePath = "/sdcard/voice/rec_001.wav",
                voiceDurationMs = 5000L,
                voicePlayed = true,
                isEdited = true
            )
            assertEquals("AI回复", msg.content)
            assertFalse(msg.isUser)
            assertTrue(msg.isTyping)
            assertEquals(123456789L, msg.timestamp)
            assertEquals("custom-id", msg.id)
            assertEquals("group-1", msg.groupId)
            assertFalse(msg.isGroupStart)
            assertFalse(msg.isGroupEnd)
            assertEquals(Message.MessageStatus.ERROR, msg.status)
            assertEquals("星遥", msg.senderName)
            assertEquals(Message.MsgType.VOICE, msg.msgType)
            assertEquals("/sdcard/voice/rec_001.wav", msg.voiceFilePath)
            assertEquals(5000L, msg.voiceDurationMs)
            assertTrue(msg.voicePlayed)
            assertTrue(msg.isEdited)
        }
    }

    // =====================================================================
    // copy() 方法
    // =====================================================================

    @Nested
    @DisplayName("copy()")
    inner class Copy {

        @Test
        @DisplayName("copy 修改 content 保留其他字段")
        fun testCopyContent() {
            val original = Message(content = "原始", isUser = true)
            val copied = original.copy(content = "修改后")
            assertEquals("修改后", copied.content)
            assertEquals(original.id, copied.id)
            assertEquals(original.isUser, copied.isUser)
            assertEquals(original.timestamp, copied.timestamp)
        }

        @Test
        @DisplayName("copy 修改 isUser")
        fun testCopyIsUser() {
            val original = Message(content = "消息", isUser = true)
            val copied = original.copy(isUser = false)
            assertFalse(copied.isUser)
            assertEquals(original.content, copied.content)
        }

        @Test
        @DisplayName("copy 修改多个字段")
        fun testCopyMultiple() {
            val original = Message(content = "原始", isUser = true, isEdited = false)
            val copied = original.copy(content = "新内容", isEdited = true)
            assertEquals("新内容", copied.content)
            assertTrue(copied.isEdited)
            assertEquals(original.id, copied.id)
        }

        @Test
        @DisplayName("copy 不修改任何字段返回相同内容")
        fun testCopyNoChange() {
            val original = Message(content = "原始", isUser = true)
            val copied = original.copy()
            assertEquals(original, copied)
        }
    }

    // =====================================================================
    // 边界条件
    // =====================================================================

    @Nested
    @DisplayName("边界条件")
    inner class EdgeCases {

        @Test
        @DisplayName("空字符串 content")
        fun testEmptyContent() {
            val msg = Message(content = "", isUser = true)
            assertEquals("", msg.content)
        }

        @Test
        @DisplayName("极长文本 content")
        fun testVeryLongContent() {
            val longText = "长".repeat(10000)
            val msg = Message(content = longText, isUser = true)
            assertEquals(10000, msg.content.length)
        }

        @Test
        @DisplayName("特殊字符 content（emoji、换行、Unicode）")
        fun testSpecialCharacters() {
            val special = "你好\n世界🌟\temoji📱"
            val msg = Message(content = special, isUser = true)
            assertEquals(special, msg.content)
        }

        @Test
        @DisplayName("timestamp 为 0")
        fun testTimestampZero() {
            val msg = Message(content = "消息", isUser = true, timestamp = 0L)
            assertEquals(0L, msg.timestamp)
        }

        @Test
        @DisplayName("timestamp 为 Long.MAX_VALUE")
        fun testTimestampMax() {
            val msg = Message(content = "消息", isUser = true, timestamp = Long.MAX_VALUE)
            assertEquals(Long.MAX_VALUE, msg.timestamp)
        }

        @Test
        @DisplayName("isUser 为 true（用户消息）")
        fun testUserMessage() {
            val msg = Message(content = "我的消息", isUser = true, senderName = "我")
            assertTrue(msg.isUser)
            assertEquals("我", msg.senderName)
        }

        @Test
        @DisplayName("AI 消息")
        fun testAiMessage() {
            val msg = Message(content = "AI回复", isUser = false, senderName = "星遥")
            assertFalse(msg.isUser)
            assertEquals("星遥", msg.senderName)
        }
    }

    // =====================================================================
    // 枚举
    // =====================================================================

    @Nested
    @DisplayName("枚举")
    inner class Enums {

        @Test
        @DisplayName("MsgType 枚举 — TEXT")
        fun testMsgTypeText() {
            assertEquals("TEXT", Message.MsgType.TEXT.name)
        }

        @Test
        @DisplayName("MsgType 枚举 — VOICE")
        fun testMsgTypeVoice() {
            assertEquals("VOICE", Message.MsgType.VOICE.name)
        }

        @Test
        @DisplayName("MessageStatus 枚举 — SENDING")
        fun testStatusSending() {
            assertEquals("SENDING", Message.MessageStatus.SENDING.name)
        }

        @Test
        @DisplayName("MessageStatus 枚举 — SENT")
        fun testStatusSent() {
            assertEquals("SENT", Message.MessageStatus.SENT.name)
        }

        @Test
        @DisplayName("MessageStatus 枚举 — DELIVERED")
        fun testStatusDelivered() {
            assertEquals("DELIVERED", Message.MessageStatus.DELIVERED.name)
        }

        @Test
        @DisplayName("MessageStatus 枚举 — READ")
        fun testStatusRead() {
            assertEquals("READ", Message.MessageStatus.READ.name)
        }

        @Test
        @DisplayName("MessageStatus 枚举 — ERROR")
        fun testStatusError() {
            assertEquals("ERROR", Message.MessageStatus.ERROR.name)
        }
    }

    // =====================================================================
    // 序列化等价性（通过 JSONObject 往返）
    // =====================================================================

    @Nested
    @DisplayName("JSON 序列化往返")
    inner class JsonRoundTrip {

        @Test
        @DisplayName("TEXT 消息往返后属性一致")
        fun testTextMessageRoundTrip() {
            val original = Message(
                content = "测试消息",
                isUser = true,
                timestamp = 123456789L,
                id = "msg-001",
                msgType = Message.MsgType.TEXT,
                isEdited = false
            )
            val json = org.json.JSONObject().apply {
                put("id", original.id)
                put("content", original.content)
                put("isUser", original.isUser)
                put("isTyping", original.isTyping)
                put("timestamp", original.timestamp)
                put("msgType", original.msgType.name)
                put("voiceFilePath", original.voiceFilePath)
                put("voiceDurationMs", original.voiceDurationMs)
                put("voicePlayed", original.voicePlayed)
            }

            val restored = Message(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                content = json.getString("content"),
                isUser = json.getBoolean("isUser"),
                isTyping = json.optBoolean("isTyping", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                msgType = try {
                    Message.MsgType.valueOf(json.optString("msgType", "TEXT"))
                } catch (_: Exception) {
                    Message.MsgType.TEXT
                },
                voiceFilePath = json.optString("voiceFilePath", ""),
                voiceDurationMs = json.optLong("voiceDurationMs", 0L),
                voicePlayed = json.optBoolean("voicePlayed", false)
            )

            assertEquals(original.id, restored.id)
            assertEquals(original.content, restored.content)
            assertEquals(original.isUser, restored.isUser)
            assertEquals(original.isTyping, restored.isTyping)
            assertEquals(original.timestamp, restored.timestamp)
            assertEquals(original.msgType, restored.msgType)
        }

        @Test
        @DisplayName("VOICE 消息往返后属性一致")
        fun testVoiceMessageRoundTrip() {
            val original = Message(
                content = "语音描述",
                isUser = true,
                msgType = Message.MsgType.VOICE,
                voiceFilePath = "/sdcard/voice/rec.wav",
                voiceDurationMs = 3500L,
                voicePlayed = false
            )
            val json = org.json.JSONObject().apply {
                put("id", original.id)
                put("content", original.content)
                put("isUser", original.isUser)
                put("isTyping", original.isTyping)
                put("timestamp", original.timestamp)
                put("msgType", original.msgType.name)
                put("voiceFilePath", original.voiceFilePath)
                put("voiceDurationMs", original.voiceDurationMs)
                put("voicePlayed", original.voicePlayed)
            }

            val restored = Message(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                content = json.getString("content"),
                isUser = json.getBoolean("isUser"),
                msgType = try {
                    Message.MsgType.valueOf(json.optString("msgType", "TEXT"))
                } catch (_: Exception) {
                    Message.MsgType.TEXT
                },
                voiceFilePath = json.optString("voiceFilePath", ""),
                voiceDurationMs = json.optLong("voiceDurationMs", 0L),
                voicePlayed = json.optBoolean("voicePlayed", false)
            )

            assertEquals(original.msgType, restored.msgType)
            assertEquals(Message.MsgType.VOICE, restored.msgType)
            assertEquals(original.voiceFilePath, restored.voiceFilePath)
            assertEquals(original.voiceDurationMs, restored.voiceDurationMs)
            assertEquals(original.voicePlayed, restored.voicePlayed)
        }

        @Test
        @DisplayName("未知 MsgType 回退为 TEXT")
        fun testUnknownMsgTypeFallback() {
            val json = org.json.JSONObject().apply {
                put("id", "test-id")
                put("content", "测试")
                put("isUser", true)
                put("msgType", "UNKNOWN_TYPE")
                put("voiceFilePath", "")
                put("voiceDurationMs", 0L)
                put("voicePlayed", false)
            }

            val msgType = try {
                Message.MsgType.valueOf(json.optString("msgType", "TEXT"))
            } catch (_: Exception) {
                Message.MsgType.TEXT
            }

            assertEquals(Message.MsgType.TEXT, msgType)
        }
    }

    // =====================================================================
    // data class 行为
    // =====================================================================

    @Nested
    @DisplayName("data class 行为")
    inner class DataClassBehavior {

        @Test
        @DisplayName("equals 相同属性返回 true")
        fun testEqualsSame() {
            val msg1 = Message(content = "相同", isUser = true, id = "id-1")
            val msg2 = Message(content = "相同", isUser = true, id = "id-1")
            assertEquals(msg1, msg2)
        }

        @Test
        @DisplayName("equals 不同属性返回 false")
        fun testEqualsDifferent() {
            val msg1 = Message(content = "消息1", isUser = true, id = "id-1")
            val msg2 = Message(content = "消息2", isUser = true, id = "id-1")
            assertNotEquals(msg1, msg2)
        }

        @Test
        @DisplayName("hashCode 相同属性相同值")
        fun testHashCodeSame() {
            val msg1 = Message(content = "相同", isUser = true, id = "id-1")
            val msg2 = Message(content = "相同", isUser = true, id = "id-1")
            assertEquals(msg1.hashCode(), msg2.hashCode())
        }

        @Test
        @DisplayName("toString 包含所有属性")
        fun testToString() {
            val msg = Message(content = "测试", isUser = true)
            val str = msg.toString()
            assertTrue(str.contains("测试"))
            assertTrue(str.contains("isUser=true"))
            assertTrue(str.contains("Message"))
        }
    }
}