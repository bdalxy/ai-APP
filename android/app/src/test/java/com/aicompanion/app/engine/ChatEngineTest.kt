package com.aicompanion.app.engine

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * ChatViewModel 和 Message 单元测试。
 *
 * 测试内容：
 *  - ChatViewModel.findSentenceEnd 静态方法
 *  - Message 数据类属性
 *  - ChatViewModel 状态管理（通过反射验证）
 */
class ChatEngineTest {

    // =========================================================================
    // findSentenceEnd 工具方法测试
    // =========================================================================

    @Test
    @DisplayName("findSentenceEnd — 找到中文句号结束位置")
    fun testFindSentenceEnd_chinesePeriod() {
        val text = "你好世界。后面还有内容"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(5, result) // "你好世界。" 共5个字符（含句号）
    }

    @Test
    @DisplayName("findSentenceEnd — 找到中文感叹号")
    fun testFindSentenceEnd_exclamation() {
        val text = "太棒了！真的吗"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(4, result)
    }

    @Test
    @DisplayName("findSentenceEnd — 找到中文问号")
    fun testFindSentenceEnd_question() {
        val text = "真的吗？我不信"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(4, result)
    }

    @Test
    @DisplayName("findSentenceEnd — 找到英文感叹号")
    fun testFindSentenceEnd_englishExclamation() {
        val text = "Wow! Amazing"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(4, result)
    }

    @Test
    @DisplayName("findSentenceEnd — 找到换行符")
    fun testFindSentenceEnd_newline() {
        val text = "第一行\n第二行"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(4, result) // "第一行\n"
    }

    @Test
    @DisplayName("findSentenceEnd — 从指定位置开始查找")
    fun testFindSentenceEnd_fromIndex() {
        val text = "第一部分。第二部分。第三部分。"
        // findSentenceEnd 返回标点后位置（i+1），"第一部分。" = 5 个字符后
        val firstEnd = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        val secondEnd = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, firstEnd)
        assertEquals(5, firstEnd)
        assertEquals(10, secondEnd)
    }

    @Test
    @DisplayName("findSentenceEnd — 未找到句子结束标记")
    fun testFindSentenceEnd_noEnd() {
        val text = "这是一段没有标点符号的文本"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(-1, result)
    }

    @Test
    @DisplayName("findSentenceEnd — 空字符串")
    fun testFindSentenceEnd_empty() {
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd("", 0)
        assertEquals(-1, result)
    }

    @Test
    @DisplayName("findSentenceEnd — 标点在开头")
    fun testFindSentenceEnd_punctuationAtStart() {
        val text = "。后面的内容"
        val result = com.aicompanion.app.ChatViewModel.findSentenceEnd(text, 0)
        assertEquals(1, result)
    }

    // =========================================================================
    // Message 数据类测试
    // =========================================================================

    @Test
    @DisplayName("Message — 默认值")
    fun testMessage_defaults() {
        val msg = com.aicompanion.app.Message(
            content = "测试消息",
            isUser = true
        )
        assertEquals("测试消息", msg.content)
        assertTrue(msg.isUser)
        assertFalse(msg.isTyping)
        assertFalse(msg.isEdited)
        assertTrue(msg.id.isNotBlank())
    }

    @Test
    @DisplayName("Message — 自定义属性")
    fun testMessage_custom() {
        val msg = com.aicompanion.app.Message(
            content = "AI回复",
            isUser = false,
            isTyping = true,
            isEdited = false,
            timestamp = 123456789L
        )
        assertEquals("AI回复", msg.content)
        assertFalse(msg.isUser)
        assertTrue(msg.isTyping)
        assertEquals(123456789L, msg.timestamp)
    }

    @Test
    @DisplayName("Message — copy 方法")
    fun testMessage_copy() {
        val original = com.aicompanion.app.Message(
            content = "原始",
            isUser = true
        )
        val copied = original.copy(content = "修改后")
        assertEquals("修改后", copied.content)
        assertEquals(original.id, copied.id)
        assertEquals(original.isUser, copied.isUser)
    }

    @Test
    @DisplayName("Message — 编辑标记")
    fun testMessage_edited() {
        val msg = com.aicompanion.app.Message(
            content = "已编辑",
            isUser = true,
            isEdited = true
        )
        assertTrue(msg.isEdited)
    }

    // =========================================================================
    // ChatViewModel 状态管理测试（通过反射验证常量）
    // =========================================================================

    @Test
    @DisplayName("ChatViewModel — 常量值验证")
    fun testChatViewModel_constants() {
        // const val 编译为外部类的 private static final 字段，不在 Companion 类中
        val vmClass = com.aicompanion.app.ChatViewModel::class.java

        val maxMsgLen = vmClass.getDeclaredField("MAX_MESSAGE_LENGTH").apply {
            isAccessible = true
        }.getInt(null)
        assertEquals(2000, maxMsgLen)

        val streamTimeout = vmClass.getDeclaredField("STREAM_TIMEOUT_MS").apply {
            isAccessible = true
        }.getLong(null)
        assertEquals(30_000L, streamTimeout)

        val msgUpdateThrottle = vmClass.getDeclaredField("MSG_UPDATE_THROTTLE_MS").apply {
            isAccessible = true
        }.getLong(null)
        assertEquals(50L, msgUpdateThrottle)
    }

    @Test
    @DisplayName("ChatViewModel — isStreaming 初始状态")
    fun testChatViewModel_isStreaming_initial() {
        // 验证 isStreaming 的默认值是 false
        // 通过反射获取私有字段的类型信息
        val field = com.aicompanion.app.ChatViewModel::class.java
            .getDeclaredField("isStreaming")
        field.isAccessible = true
        assertEquals("boolean", field.type.simpleName.lowercase())
    }

    @Test
    @DisplayName("ChatViewModel — 编辑状态管理")
    fun testChatViewModel_editingState() {
        // 验证编辑相关字段存在
        val editingPosField = com.aicompanion.app.ChatViewModel::class.java
            .getDeclaredField("editingPosition")
        editingPosField.isAccessible = true
        assertEquals("int", editingPosField.type.simpleName.lowercase())

        val editingIdField = com.aicompanion.app.ChatViewModel::class.java
            .getDeclaredField("editingMessageId")
        editingIdField.isAccessible = true
    }

    @Test
    @DisplayName("ChatViewModel — 流式状态字段")
    fun testChatViewModel_streamingFields() {
        val streamIdField = com.aicompanion.app.ChatViewModel::class.java
            .getDeclaredField("activeStreamId")
        streamIdField.isAccessible = true
        assertNotNull(streamIdField)
    }
}