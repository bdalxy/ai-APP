package com.aicompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ConversationSessionManager 单元测试（Robolectric + 协程测试）。
 *
 * 覆盖：
 *  - 初始化/默认会话创建
 *  - 会话 CRUD（创建/获取/重命名/删除）
 *  - 当前会话切换
 *  - 消息持久化（保存/加载）
 *  - 会话预览更新
 *  - 旧会话迁移（conversation.json）
 *  - 边界条件（空会话列表、删除当前会话等）
 */
@RunWith(RobolectricTestRunner::class)
@DisplayName("ConversationSessionManager")
class ConversationSessionManagerTest {

    private lateinit var context: Context
    private val testScope = TestScope()

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清理持久化数据
        val filesDir = context.filesDir
        File(filesDir, "sessions.json").delete()
        filesDir.listFiles()?.filter { it.name.startsWith("session_") }?.forEach { it.delete() }
        File(filesDir, "conversation.json").delete()
        File(filesDir, "conversation.json.bak").delete()
        // 清理 app_prefs
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        // 初始化
        ConversationSessionManager.init(context)
    }

    @AfterEach
    fun tearDown() {
        val filesDir = context.filesDir
        File(filesDir, "sessions.json").delete()
        filesDir.listFiles()?.filter { it.name.startsWith("session_") }?.forEach { it.delete() }
        File(filesDir, "conversation.json").delete()
        File(filesDir, "conversation.json.bak").delete()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // =====================================================================
    // 初始化
    // =====================================================================

    @Nested
    @DisplayName("初始化")
    inner class Initialization {

        @Test
        @DisplayName("init 后自动创建默认会话")
        fun testInitCreatesDefaultSession() {
            val sessions = ConversationSessionManager.getSessions()
            assertEquals(1, sessions.size)
            assertEquals("默认会话", sessions[0].name)
        }

        @Test
        @DisplayName("init 后当前会话 ID 不为空")
        fun testInitHasCurrentSessionId() {
            val id = ConversationSessionManager.getCurrentSessionId()
            assertTrue(id.isNotBlank())
        }

        @Test
        @DisplayName("init 后会话 ID 为 UUID 格式")
        fun testInitSessionIdIsUUID() {
            val sessions = ConversationSessionManager.getSessions()
            assertEquals(36, sessions[0].id.length)
        }

        @Test
        @DisplayName("重复 init 不创建重复会话")
        fun testDoubleInit() {
            ConversationSessionManager.init(context)
            val sessions = ConversationSessionManager.getSessions()
            assertEquals(1, sessions.size)
        }
    }

    // =====================================================================
    // 会话 CRUD
    // =====================================================================

    @Nested
    @DisplayName("会话 CRUD")
    inner class SessionCrud {

        @Test
        @DisplayName("createSession 创建新会话")
        fun testCreateSession() {
            val session = ConversationSessionManager.createSession("新会话", "char-001")
            assertEquals("新会话", session.name)
            assertEquals("char-001", session.characterCardId)
            assertTrue(session.id.isNotBlank())
            assertTrue(session.createdAt > 0)
            assertTrue(session.updatedAt > 0)
        }

        @Test
        @DisplayName("createSession 自动切换当前会话")
        fun testCreateSessionSwitchesCurrent() {
            val oldId = ConversationSessionManager.getCurrentSessionId()
            val newSession = ConversationSessionManager.createSession("新会话", "char-001")
            assertEquals(newSession.id, ConversationSessionManager.getCurrentSessionId())
            assertNotEquals(oldId, newSession.id)
        }

        @Test
        @DisplayName("createSession 后会话列表包含新会话")
        fun testCreateSessionInList() {
            ConversationSessionManager.createSession("会话A", "char-a")
            ConversationSessionManager.createSession("会话B", "char-b")
            val sessions = ConversationSessionManager.getSessions()
            assertEquals(3, sessions.size) // 默认 + 2 个新建
        }

        @Test
        @DisplayName("getSessions 按更新时间倒序")
        fun testGetSessionsSortedByUpdatedAt() {
            val s1 = ConversationSessionManager.createSession("会话A", "char-a")
            Thread.sleep(5) // 确保时间戳不同
            val s2 = ConversationSessionManager.createSession("会话B", "char-b")

            val sessions = ConversationSessionManager.getSessions()
            // 最新创建的排在前面
            assertEquals(s2.id, sessions[0].id)
        }

        @Test
        @DisplayName("renameSession 修改名称")
        fun testRenameSession() {
            val sessions = ConversationSessionManager.getSessions()
            val id = sessions[0].id
            ConversationSessionManager.renameSession(id, "新名称")
            val renamed = ConversationSessionManager.getSessions().find { it.id == id }
            assertEquals("新名称", renamed?.name)
        }

        @Test
        @DisplayName("renameSession 不存在的 ID 不报错")
        fun testRenameNonExistent() {
            assertDoesNotThrow {
                ConversationSessionManager.renameSession("non-existent", "新名称")
            }
        }

        @Test
        @DisplayName("deleteSession 删除会话")
        fun testDeleteSession() {
            val sessions = ConversationSessionManager.getSessions()
            val id = sessions[0].id
            ConversationSessionManager.deleteSession(id)
            assertEquals(0, ConversationSessionManager.getSessions().size)
        }

        @Test
        @DisplayName("deleteSession 删除当前会话时切换到下一个")
        fun testDeleteCurrentSessionSwitches() {
            val s1 = ConversationSessionManager.createSession("会话A", "char-a")
            val s2 = ConversationSessionManager.createSession("会话B", "char-b")

            // 当前是 s2
            assertEquals(s2.id, ConversationSessionManager.getCurrentSessionId())

            ConversationSessionManager.deleteSession(s2.id)
            // 应切换到 s1（剩余会话中第一个）
            val newCurrent = ConversationSessionManager.getCurrentSessionId()
            assertTrue(newCurrent.isNotBlank())
            assertNotEquals(s2.id, newCurrent)
        }

        @Test
        @DisplayName("deleteSession 删除所有会话后 currentSessionId 为空")
        fun testDeleteAllSessions() {
            val sessions = ConversationSessionManager.getSessions()
            ConversationSessionManager.deleteSession(sessions[0].id)
            assertEquals(0, ConversationSessionManager.getSessions().size)
            assertTrue(ConversationSessionManager.getCurrentSessionId().isEmpty())
        }
    }

    // =====================================================================
    // 当前会话管理
    // =====================================================================

    @Nested
    @DisplayName("当前会话管理")
    inner class CurrentSession {

        @Test
        @DisplayName("setCurrentSessionId 切换当前会话")
        fun testSetCurrentSessionId() {
            val s1 = ConversationSessionManager.createSession("会话A", "char-a")
            val s2 = ConversationSessionManager.createSession("会话B", "char-b")

            ConversationSessionManager.setCurrentSessionId(s1.id)
            assertEquals(s1.id, ConversationSessionManager.getCurrentSessionId())
        }

        @Test
        @DisplayName("setCurrentSessionId 持久化到 SharedPreferences")
        fun testSetCurrentSessionIdPersisted() {
            val s1 = ConversationSessionManager.createSession("会话A", "char-a")
            ConversationSessionManager.setCurrentSessionId(s1.id)

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            assertEquals(s1.id, prefs.getString("current_session_id", null))
        }
    }

    // =====================================================================
    // 消息持久化
    // =====================================================================

    @Nested
    @DisplayName("消息持久化")
    inner class MessagePersistence {

        @Test
        @DisplayName("saveMessages 保存消息")
        fun testSaveMessages() = testScope.runTest {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            val messages = listOf(
                Message(content = "你好", isUser = true, id = "msg-1"),
                Message(content = "你好呀~", isUser = false, id = "msg-2")
            )
            ConversationSessionManager.saveMessages(sessionId, messages)

            val loaded = ConversationSessionManager.loadMessages(sessionId)
            assertEquals(2, loaded.size)
            assertEquals("你好", loaded[0].content)
            assertTrue(loaded[0].isUser)
            assertEquals("你好呀~", loaded[1].content)
            assertFalse(loaded[1].isUser)
        }

        @Test
        @DisplayName("loadMessages 不存在的会话返回空列表")
        fun testLoadMessagesNonExistent() = testScope.runTest {
            val messages = ConversationSessionManager.loadMessages("non-existent")
            assertTrue(messages.isEmpty())
        }

        @Test
        @DisplayName("saveMessages 空列表")
        fun testSaveEmptyMessages() = testScope.runTest {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            ConversationSessionManager.saveMessages(sessionId, emptyList())
            val loaded = ConversationSessionManager.loadMessages(sessionId)
            assertTrue(loaded.isEmpty())
        }

        @Test
        @DisplayName("saveMessages 大量消息")
        fun testSaveManyMessages() = testScope.runTest {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            val messages = (1..100).map { i ->
                Message(content = "消息 #$i", isUser = i % 2 == 1, id = "msg-$i")
            }
            ConversationSessionManager.saveMessages(sessionId, messages)

            val loaded = ConversationSessionManager.loadMessages(sessionId)
            assertEquals(100, loaded.size)
        }

        @Test
        @DisplayName("saveMessages 覆盖旧消息")
        fun testSaveMessagesOverwrite() = testScope.runTest {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            val first = listOf(Message(content = "旧消息", isUser = true, id = "old"))
            ConversationSessionManager.saveMessages(sessionId, first)

            val second = listOf(Message(content = "新消息", isUser = true, id = "new"))
            ConversationSessionManager.saveMessages(sessionId, second)

            val loaded = ConversationSessionManager.loadMessages(sessionId)
            assertEquals(1, loaded.size)
            assertEquals("新消息", loaded[0].content)
        }

        @Test
        @DisplayName("saveMessages 更新会话预览")
        fun testSaveMessagesUpdatesPreview() = testScope.runTest {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            val messages = listOf(
                Message(content = "这是一条很长很长的消息用于测试预览截断功能", isUser = true)
            )
            ConversationSessionManager.saveMessages(sessionId, messages)

            val session = ConversationSessionManager.getSessions().find { it.id == sessionId }
            assertNotNull(session)
            assertTrue(session!!.lastMessage.isNotBlank())
            assertTrue(session.lastMessage.length <= 53) // 50 + "..."
            assertEquals(1, session.messageCount)
        }
    }

    // =====================================================================
    // 会话预览
    // =====================================================================

    @Nested
    @DisplayName("会话预览")
    inner class SessionPreview {

        @Test
        @DisplayName("updateSessionPreview 更新预览")
        fun testUpdateSessionPreview() {
            val sessionId = ConversationSessionManager.getCurrentSessionId()
            ConversationSessionManager.updateSessionPreview(sessionId, "预览文本", 10)

            val session = ConversationSessionManager.getSessions().find { it.id == sessionId }
            assertNotNull(session)
            assertEquals("预览文本", session!!.lastMessage)
            assertEquals(10, session.messageCount)
        }

        @Test
        @DisplayName("updateSessionPreview 不存在的 ID 不报错")
        fun testUpdateSessionPreviewNonExistent() {
            assertDoesNotThrow {
                ConversationSessionManager.updateSessionPreview("non-existent", "预览", 0)
            }
        }
    }

    // =====================================================================
    // 旧会话迁移
    // =====================================================================

    @Nested
    @DisplayName("旧会话迁移")
    inner class OldConversationMigration {

        @Test
        @DisplayName("存在 conversation.json 时自动迁移")
        fun testMigrateOldConversation() = testScope.runTest {
            // 清理已创建的默认会话
            val sessions = ConversationSessionManager.getSessions()
            sessions.forEach { ConversationSessionManager.deleteSession(it.id) }

            // 创建旧格式的 conversation.json
            val oldFile = File(context.filesDir, "conversation.json")
            val jsonArray = org.json.JSONArray()
            val msg1 = org.json.JSONObject().apply {
                put("content", "旧消息")
                put("isUser", true)
            }
            jsonArray.put(msg1)
            oldFile.writeText(jsonArray.toString())

            // 重新初始化触发迁移
            ConversationSessionManager.init(context)

            val newSessions = ConversationSessionManager.getSessions()
            assertEquals(1, newSessions.size)

            val loaded = ConversationSessionManager.loadMessages(newSessions[0].id)
            assertEquals(1, loaded.size)
            assertEquals("旧消息", loaded[0].content)
            assertTrue(loaded[0].isUser)

            // 旧文件应被重命名为 .bak
            assertFalse(oldFile.exists())
            assertTrue(File(context.filesDir, "conversation.json.bak").exists())
        }
    }
}