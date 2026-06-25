package com.aicompanion.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 多会话管理器（单例）。
 *
 * 管理会话列表元数据和每个会话的消息持久化：
 * - sessions.json：会话列表元数据（名称、预览、时间等）
 * - session_{id}.json：每个会话的完整消息历史
 *
 * 线程安全：所有文件 I/O 操作在调用方协程中通过 Dispatchers.IO 执行，
 * 但会话列表的读写通过 synchronized 保护。
 */
object ConversationSessionManager {

    private const val TAG = "SessionManager"
    private const val SESSIONS_FILE = "sessions.json"
    private const val SESSION_FILE_PREFIX = "session_"
    private const val SESSION_FILE_SUFFIX = ".json"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_CURRENT_SESSION = "current_session_id"
    /** 最后一条消息预览的最大字符数 */
    private const val PREVIEW_MAX_LENGTH = 50

    /** 会话列表（主线程读写，通过 synchronized 保护） */
    private val sessions = mutableListOf<ConversationSession>()
    private val lock = Any()

    /** 当前会话 ID（内存缓存） */
    @Volatile
    private var currentSessionId: String? = null

    /** 文件目录（在 init 中设置） */
    private var filesDir: File? = null
    private var prefs: android.content.SharedPreferences? = null

    // ======================== 初始化 ========================

    /**
     * 初始化会话管理器。
     * 从 sessions.json 加载会话列表，从 SharedPreferences 恢复当前会话 ID。
     * 首次启动时自动创建默认会话并尝试迁移旧的 conversation.json。
     */
    fun init(context: Context) {
        filesDir = context.filesDir
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentSessionId = prefs?.getString(KEY_CURRENT_SESSION, null)
        loadSessionsFromFile()

        // 首次启动：无会话时创建默认会话
        if (sessions.isEmpty()) {
            val character = CharacterStorage.getCurrent(context)
            val defaultSession = createSessionInternal("默认会话", character.id)
            // 尝试迁移旧的 conversation.json
            migrateOldConversation(defaultSession.id)
        } else if (currentSessionId == null || sessions.none { it.id == currentSessionId }) {
            // 当前会话 ID 无效时回退到第一个会话
            setCurrentSessionId(sessions.first().id)
        }
    }

    // ======================== 会话列表操作 ========================

    /** 获取所有会话列表（按更新时间倒序）。 */
    fun getSessions(): List<ConversationSession> = synchronized(lock) {
        sessions.sortedByDescending { it.updatedAt }
    }

    /** 获取当前会话 ID。 */
    fun getCurrentSessionId(): String = synchronized(lock) {
        currentSessionId ?: sessions.firstOrNull()?.id ?: ""
    }

    /** 设置当前会话 ID（持久化到 SharedPreferences）。 */
    fun setCurrentSessionId(id: String) {
        synchronized(lock) {
            currentSessionId = id
        }
        prefs?.edit()?.putString(KEY_CURRENT_SESSION, id)?.apply()
    }

    /**
     * 创建新会话。
     * @param name            会话名称
     * @param characterCardId 关联的角色卡ID
     * @return 新创建的会话对象
     */
    fun createSession(name: String, characterCardId: String): ConversationSession {
        val session = createSessionInternal(name, characterCardId)
        setCurrentSessionId(session.id)
        return session
    }

    /** 删除会话及其消息文件。当前会话被删除时自动切换到第一个剩余会话。 */
    fun deleteSession(id: String) {
        synchronized(lock) {
            sessions.removeAll { it.id == id }
            // 删除消息文件
            getSessionFile(id).delete()
            // 如果删除的是当前会话，切换到第一个剩余会话
            if (currentSessionId == id) {
                currentSessionId = sessions.firstOrNull()?.id
                currentSessionId?.let { newId ->
                    prefs?.edit()?.putString(KEY_CURRENT_SESSION, newId)?.apply()
                } ?: prefs?.edit()?.remove(KEY_CURRENT_SESSION)?.apply()
            }
            saveSessionsToFile()
        }
    }

    /** 重命名会话。 */
    fun renameSession(id: String, newName: String) {
        synchronized(lock) {
            val index = sessions.indexOfFirst { it.id == id }
            if (index >= 0) {
                sessions[index] = sessions[index].copy(name = newName)
                saveSessionsToFile()
            }
        }
    }

    // ======================== 消息持久化 ========================

    /**
     * 保存会话消息到 session_{id}.json（原子写入）。
     * @param sessionId 会话ID
     * @param messages  消息列表
     */
    suspend fun saveMessages(sessionId: String, messages: List<Message>) {
        withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject()
                    obj.put("id", msg.id)
                    obj.put("content", msg.content)
                    obj.put("isUser", msg.isUser)
                    obj.put("isTyping", msg.isTyping)
                    obj.put("timestamp", msg.timestamp)
                    // 语音字段
                    obj.put("msgType", msg.msgType.name)
                    obj.put("voiceFilePath", msg.voiceFilePath)
                    obj.put("voiceDurationMs", msg.voiceDurationMs)
                    obj.put("voicePlayed", msg.voicePlayed)
                    jsonArray.put(obj)
                }
                val jsonStr = jsonArray.toString()

                // 原子写入：先写临时文件，验证后再替换
                val sessionFile = getSessionFile(sessionId)
                val tmpFile = File(sessionFile.parent, "${sessionFile.name}.tmp")
                tmpFile.writeText(jsonStr, Charsets.UTF_8)
                // 验证写入的 JSON 可解析
                JSONArray(tmpFile.readText(Charsets.UTF_8))
                // 原子替换
                if (!tmpFile.renameTo(sessionFile)) {
                    sessionFile.writeText(jsonStr, Charsets.UTF_8)
                    tmpFile.delete()
                }

                // 更新会话预览
                val lastMsg = messages.lastOrNull()
                val preview = lastMsg?.content?.let {
                    if (it.length > PREVIEW_MAX_LENGTH) it.substring(0, PREVIEW_MAX_LENGTH) + "..." else it
                } ?: ""
                updateSessionPreview(sessionId, preview, messages.size)

                Log.d(TAG, "会话 $sessionId 消息已保存，共 ${messages.size} 条")
            } catch (e: Exception) {
                Log.e(TAG, "保存会话消息失败: ${e.message}", e)
            }
        }
    }

    /**
     * 加载会话消息。
     * @param sessionId 会话ID
     * @return 消息列表，文件不存在时返回空列表
     */
    suspend fun loadMessages(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        try {
            val file = getSessionFile(sessionId)
            if (!file.exists()) return@withContext emptyList<Message>()

            val json = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(json)
            val messages = mutableListOf<Message>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                messages.add(Message(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    content = obj.getString("content"),
                    isUser = obj.getBoolean("isUser"),
                    isTyping = obj.optBoolean("isTyping", false),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    // 语音字段（向后兼容：旧数据无此字段则默认 TEXT）
                    msgType = try {
                        Message.MsgType.valueOf(obj.optString("msgType", "TEXT"))
                    } catch (_: Exception) {
                        Message.MsgType.TEXT
                    },
                    voiceFilePath = obj.optString("voiceFilePath", ""),
                    voiceDurationMs = obj.optLong("voiceDurationMs", 0L),
                    voicePlayed = obj.optBoolean("voicePlayed", false)
                ))
            }
            Log.d(TAG, "会话 $sessionId 消息已加载，共 ${messages.size} 条")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "加载会话消息失败: ${e.message}", e)
            emptyList()
        }
    }

    /** 更新会话预览信息（最后一条消息和时间戳）。 */
    fun updateSessionPreview(sessionId: String, lastMessage: String, messageCount: Int) {
        synchronized(lock) {
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index >= 0) {
                sessions[index] = sessions[index].copy(
                    lastMessage = lastMessage,
                    messageCount = messageCount,
                    updatedAt = System.currentTimeMillis()
                )
                saveSessionsToFile()
            }
        }
    }

    // ======================== 内部方法 ========================

    /** 获取指定会话的消息文件。 */
    private fun getSessionFile(sessionId: String): File {
        val dir = filesDir ?: throw IllegalStateException("ConversationSessionManager 未初始化，请先调用 init()")
        return File(dir, "$SESSION_FILE_PREFIX$sessionId$SESSION_FILE_SUFFIX")
    }

    /** 内部创建会话方法（不切换当前会话）。 */
    private fun createSessionInternal(name: String, characterCardId: String): ConversationSession {
        val now = System.currentTimeMillis()
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            name = name,
            characterCardId = characterCardId,
            lastMessage = "",
            messageCount = 0,
            createdAt = now,
            updatedAt = now
        )
        synchronized(lock) {
            sessions.add(session)
            saveSessionsToFile()
        }
        Log.d(TAG, "会话已创建: ${session.name} (${session.id})")
        return session
    }

    /** 从 sessions.json 加载会话列表。 */
    private fun loadSessionsFromFile() {
        synchronized(lock) {
            val file = File(filesDir, SESSIONS_FILE)
            if (!file.exists()) {
                sessions.clear()
                return
            }
            try {
                val json = file.readText(Charsets.UTF_8)
                val arr = JSONArray(json)
                sessions.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sessions.add(ConversationSession(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        characterCardId = obj.getString("character_card_id"),
                        lastMessage = obj.optString("last_message", ""),
                        messageCount = obj.optInt("message_count", 0),
                        createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
                    ))
                }
                Log.d(TAG, "会话列表已加载，共 ${sessions.size} 个会话")
            } catch (e: Exception) {
                Log.e(TAG, "加载会话列表失败: ${e.message}", e)
                sessions.clear()
            }
        }
    }

    /** 保存会话列表到 sessions.json（原子写入）。 */
    private fun saveSessionsToFile() {
        val file = File(filesDir, SESSIONS_FILE)
        try {
            val arr = JSONArray()
            for (s in sessions) {
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("name", s.name)
                obj.put("character_card_id", s.characterCardId)
                obj.put("last_message", s.lastMessage)
                obj.put("message_count", s.messageCount)
                obj.put("created_at", s.createdAt)
                obj.put("updated_at", s.updatedAt)
                arr.put(obj)
            }
            val jsonStr = arr.toString()
            val tmpFile = File(filesDir, "${SESSIONS_FILE}.tmp")
            tmpFile.writeText(jsonStr, Charsets.UTF_8)
            JSONArray(tmpFile.readText(Charsets.UTF_8))
            if (!tmpFile.renameTo(file)) {
                file.writeText(jsonStr, Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存会话列表失败: ${e.message}", e)
        }
    }

    /** 尝试将旧的 conversation.json 迁移到新会话。 */
    private fun migrateOldConversation(sessionId: String) {
        val oldFile = File(filesDir, "conversation.json")
        if (!oldFile.exists()) return

        try {
            val sessionFile = getSessionFile(sessionId)
            if (sessionFile.exists()) {
                // 目标文件已存在，不覆盖
                Log.d(TAG, "迁移跳过：目标会话文件已存在")
                return
            }

            // 验证旧文件内容
            val json = oldFile.readText(Charsets.UTF_8)
            val arr = JSONArray(json)
            if (arr.length() > 0) {
                // 迁移消息（补充 id 字段）
                val newArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val oldObj = arr.getJSONObject(i)
                    val newObj = JSONObject()
                    newObj.put("id", oldObj.optString("id", UUID.randomUUID().toString()))
                    newObj.put("content", oldObj.getString("content"))
                    newObj.put("isUser", oldObj.getBoolean("isUser"))
                    newObj.put("isTyping", oldObj.optBoolean("isTyping", false))
                    newObj.put("timestamp", oldObj.optLong("timestamp", System.currentTimeMillis()))
                    newObj.put("msgType", "TEXT")
                    newObj.put("voiceFilePath", "")
                    newObj.put("voiceDurationMs", 0L)
                    newObj.put("voicePlayed", false)
                    newArr.put(newObj)
                }
                sessionFile.writeText(newArr.toString(), Charsets.UTF_8)
                Log.d(TAG, "已迁移旧会话文件 conversation.json，共 ${arr.length()} 条消息")

                // 更新会话预览
                val lastObj = arr.getJSONObject(arr.length() - 1)
                val preview = lastObj.optString("content", "").let {
                    if (it.length > PREVIEW_MAX_LENGTH) it.substring(0, PREVIEW_MAX_LENGTH) + "..." else it
                }
                synchronized(lock) {
                    val index = sessions.indexOfFirst { it.id == sessionId }
                    if (index >= 0) {
                        sessions[index] = sessions[index].copy(
                            lastMessage = preview,
                            messageCount = arr.length(),
                            updatedAt = System.currentTimeMillis()
                        )
                        saveSessionsToFile()
                    }
                }
            }

            // 迁移完成，删除旧文件（重命名为 .bak 备份）
            oldFile.renameTo(File(filesDir, "conversation.json.bak"))
        } catch (e: Exception) {
            Log.e(TAG, "迁移旧会话文件失败: ${e.message}", e)
        }
    }
}