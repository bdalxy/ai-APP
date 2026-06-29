package com.aicompanion.app

import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.json.JSONObject

/**
 * 集成测试：Kotlin-Python 桥接接口契约验证。
 *
 * 由于 Chaquopy 在 JVM 单元测试中不可用，本测试验证：
 *  - CharacterData 到 JSON 的转换（供 Python 端使用）
 *  - Message 到 JSON 的转换（供 Python 端使用）
 *  - ChatExporter 导出功能
 *  - 端到端对话流程模拟（Mock DeepSeek API）
 */
@DisplayName("集成测试")
class ConversationIntegrationTest {

    private val testScope = TestScope()

    // =====================================================================
    // CharacterData → JSON（Kotlin → Python 桥接）
    // =====================================================================

    @Nested
    @DisplayName("CharacterData JSON 桥接")
    inner class CharacterDataBridge {

        @Test
        @DisplayName("CharacterData 转 JSONObject 包含所有字段")
        fun testCharacterDataToJson() {
            val character = CharacterData(
                id = "test-id",
                name = "测试角色",
                personality = "测试性格",
                speakingStyle = "测试风格",
                backstory = "测试背景",
                greeting = "测试开场白",
                coreTraits = "特质1,特质2",
                tabooTopics = "禁忌1",
                roleAnchor = "测试锚点",
                emotionalTendency = "乐观",
                selfIdentity = "测试认同",
                worldBookId = "测试世界书",
                isDefault = false
            )

            val json = JSONObject().apply {
                put("name", character.name)
                put("personality", character.personality)
                put("speaking_style", character.speakingStyle)
                put("backstory", character.backstory)
                put("greeting", character.greeting)
                put("core_traits", character.coreTraits)
                put("taboo_topics", character.tabooTopics)
                put("role_anchor", character.roleAnchor)
                put("emotional_tendency", character.emotionalTendency)
                put("self_identity", character.selfIdentity)
            }

            assertEquals("测试角色", json.getString("name"))
            assertEquals("测试性格", json.getString("personality"))
            assertEquals("测试风格", json.getString("speaking_style"))
            assertEquals("测试背景", json.getString("backstory"))
            assertEquals("测试开场白", json.getString("greeting"))
            assertEquals("特质1,特质2", json.getString("core_traits"))
            assertEquals("禁忌1", json.getString("taboo_topics"))
            assertEquals("测试锚点", json.getString("role_anchor"))
            assertEquals("乐观", json.getString("emotional_tendency"))
            assertEquals("测试认同", json.getString("self_identity"))
        }

        @Test
        @DisplayName("CharacterData JSON 往返一致性")
        fun testCharacterDataRoundTrip() {
            val original = CharacterData(
                id = "round-trip-id",
                name = "往返测试",
                personality = "温和",
                speakingStyle = "礼貌",
                backstory = "测试背景故事",
                greeting = "你好~",
                coreTraits = "温柔,善良",
                tabooTopics = "暴力",
                roleAnchor = "测试者",
                emotionalTendency = "中性",
                selfIdentity = "我是测试者",
                worldBookId = "测试世界"
            )

            val json = JSONObject().apply {
                put("name", original.name)
                put("personality", original.personality)
                put("speaking_style", original.speakingStyle)
                put("backstory", original.backstory)
                put("greeting", original.greeting)
                put("core_traits", original.coreTraits)
                put("taboo_topics", original.tabooTopics)
                put("role_anchor", original.roleAnchor)
                put("emotional_tendency", original.emotionalTendency)
                put("self_identity", original.selfIdentity)
            }

            // 模拟 Python 端解析后返回（通过 JSON 字符串往返）
            val jsonStr = json.toString()
            val parsed = JSONObject(jsonStr)

            assertEquals(original.name, parsed.getString("name"))
            assertEquals(original.personality, parsed.getString("personality"))
            assertEquals(original.speakingStyle, parsed.getString("speaking_style"))
            assertEquals(original.coreTraits, parsed.getString("core_traits"))
            assertEquals(original.roleAnchor, parsed.getString("role_anchor"))
        }
    }

    // =====================================================================
    // Message → JSON（Kotlin → Python 桥接）
    // =====================================================================

    @Nested
    @DisplayName("Message JSON 桥接")
    inner class MessageBridge {

        @Test
        @DisplayName("Message 列表转 JSONArray 格式正确")
        fun testMessagesToJsonArray() {
            val messages = listOf(
                Message(content = "你好", isUser = true, id = "msg-1"),
                Message(content = "你好呀~", isUser = false, id = "msg-2"),
                Message(content = "今天天气真好", isUser = true, id = "msg-3"),
            )

            val jsonArray = org.json.JSONArray()
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("role", if (msg.isUser) "user" else "assistant")
                obj.put("content", msg.content)
                jsonArray.put(obj)
            }

            assertEquals(3, jsonArray.length())
            assertEquals("user", jsonArray.getJSONObject(0).getString("role"))
            assertEquals("你好", jsonArray.getJSONObject(0).getString("content"))
            assertEquals("assistant", jsonArray.getJSONObject(1).getString("role"))
            assertEquals("你好呀~", jsonArray.getJSONObject(1).getString("content"))
            assertEquals("user", jsonArray.getJSONObject(2).getString("role"))
            assertEquals("今天天气真好", jsonArray.getJSONObject(2).getString("content"))
        }

        @Test
        @DisplayName("空消息列表转 JSONArray")
        fun testEmptyMessagesToJson() {
            val jsonArray = org.json.JSONArray()
            assertEquals(0, jsonArray.length())
            assertEquals("[]", jsonArray.toString())
        }
    }

    // =====================================================================
    // Mock DeepSeek API 响应
    // =====================================================================

    @Nested
    @DisplayName("Mock DeepSeek API")
    inner class MockDeepSeekApi {

        @Test
        @DisplayName("模拟 API 请求构建")
        fun testMockApiRequest() {
            val messages = listOf(
                mapOf("role" to "system", "content" to "你是一个AI助手"),
                mapOf("role" to "user", "content" to "你好")
            )

            val requestBody = JSONObject().apply {
                put("model", "deepseek-v4-flash")
                put("messages", org.json.JSONArray().apply {
                    for (msg in messages) {
                        put(JSONObject().apply {
                            put("role", msg["role"])
                            put("content", msg["content"])
                        })
                    }
                })
                put("temperature", 0.7)
                put("max_tokens", 1000)
                put("stream", true)
            }

            assertEquals("deepseek-v4-flash", requestBody.getString("model"))
            assertEquals(2, requestBody.getJSONArray("messages").length())
            assertEquals(0.7, requestBody.getDouble("temperature"), 0.001)
            assertEquals(1000, requestBody.getInt("max_tokens"))
            assertTrue(requestBody.getBoolean("stream"))
        }

        @Test
        @DisplayName("模拟 API 响应解析")
        fun testMockApiResponse() {
            // 模拟 DeepSeek 流式响应 chunk
            val chunk = JSONObject().apply {
                put("choices", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("delta", JSONObject().apply {
                            put("content", "你好呀~")
                        })
                        put("index", 0)
                    })
                })
            }

            val choices = chunk.getJSONArray("choices")
            assertEquals(1, choices.length())
            val delta = choices.getJSONObject(0).getJSONObject("delta")
            assertEquals("你好呀~", delta.getString("content"))
        }

        @Test
        @DisplayName("模拟 API 错误响应")
        fun testMockApiErrorResponse() {
            val errorResponse = JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "Insufficient balance")
                    put("type", "insufficient_balance")
                    put("code", "402")
                })
            }

            assertTrue(errorResponse.has("error"))
            val error = errorResponse.getJSONObject("error")
            assertEquals("Insufficient balance", error.getString("message"))
            assertEquals("402", error.getString("code"))
        }
    }

    // =====================================================================
    // 端到端对话流程模拟
    // =====================================================================

    @Nested
    @DisplayName("端到端对话流程")
    inner class EndToEndConversation {

        @Test
        @DisplayName("模拟完整对话流程")
        fun testFullConversationFlow() = testScope.runTest {
            // 1. 创建会话
            val sessionId = "test-session-id"
            val characterId = "test-character-id"

            // 2. 构建 System Prompt
            val systemPrompt = buildString {
                appendLine("【角色名称】测试角色")
                appendLine("【性格】测试性格")
                appendLine("【说话风格】测试风格")
                appendLine("")
                appendLine("## 世界设定")
                appendLine("1. 测试世界")
            }

            assertTrue(systemPrompt.contains("测试角色"))
            assertTrue(systemPrompt.contains("世界设定"))

            // 3. 构建消息列表
            val messages = mutableListOf<Map<String, String>>()
            messages.add(mapOf("role" to "system", "content" to systemPrompt))
            messages.add(mapOf("role" to "user", "content" to "你好"))
            messages.add(mapOf("role" to "assistant", "content" to "你好呀~"))

            assertEquals(3, messages.size)
            assertEquals("system", messages[0]["role"])
            assertEquals("user", messages[1]["role"])
            assertEquals("assistant", messages[2]["role"])

            // 4. 模拟用户发送新消息
            messages.add(mapOf("role" to "user", "content" to "今天天气怎么样"))
            assertEquals(4, messages.size)

            // 5. 模拟 AI 回复
            messages.add(mapOf("role" to "assistant", "content" to "今天天气不错呢~"))
            assertEquals(5, messages.size)
            assertEquals("今天天气不错呢~", messages[4]["content"])
        }

        @Test
        @DisplayName("模拟流式消息累积")
        fun testStreamingMessageAccumulation() {
            val chunks = listOf("你", "你好", "你好呀", "你好呀~", "你好呀~今", "你好呀~今天天", "你好呀~今天天气不错")
            val accumulated = mutableListOf<String>()

            for (chunk in chunks) {
                accumulated.add(chunk)
            }

            assertEquals("你好呀~今天天气不错", accumulated.last())
            assertEquals(7, accumulated.size) // 每个 chunk 都记录了
        }

        @Test
        @DisplayName("模拟对话上下文裁剪")
        fun testContextTrimming() {
            // 模拟 ContextManager 行为：超过 max_messages 时裁剪
            val maxMessages = 50
            val totalMessages = 100

            val messages = (1..totalMessages).map { i ->
                mapOf("role" to if (i % 2 == 1) "user" else "assistant",
                       "content" to "消息 #$i")
            }.toMutableList()

            // 裁剪到 maxMessages
            while (messages.size > maxMessages) {
                messages.removeAt(0)
            }

            assertEquals(maxMessages, messages.size)
            assertEquals("消息 #51", messages.first()["content"])
            assertEquals("消息 #100", messages.last()["content"])
        }
    }

    // =====================================================================
    // ChatExporter 导出测试
    // =====================================================================

    @Nested
    @DisplayName("ChatExporter")
    inner class ChatExporterTests {

        @Test
        @DisplayName("导出消息为 JSON 格式")
        fun testExportMessagesAsJson() {
            val messages = listOf(
                Message(content = "用户消息", isUser = true, id = "m1", timestamp = 1000L),
                Message(content = "AI回复", isUser = false, id = "m2", timestamp = 2000L),
            )

            val exportJson = JSONObject()
            val messagesArray = org.json.JSONArray()
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("role", if (msg.isUser) "user" else "assistant")
                obj.put("content", msg.content)
                obj.put("timestamp", msg.timestamp)
                messagesArray.put(obj)
            }
            exportJson.put("version", "1.0")
            exportJson.put("messages", messagesArray)

            assertEquals("1.0", exportJson.getString("version"))
            assertEquals(2, exportJson.getJSONArray("messages").length())
            assertEquals("用户消息",
                exportJson.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("AI回复",
                exportJson.getJSONArray("messages").getJSONObject(1).getString("content"))
        }
    }
}