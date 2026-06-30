package com.aicompanion.app

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话协调器。
 *
 * 负责多会话切换、保存/加载、新建/删除/重命名等会话管理操作。
 * 通过 [ConversationCallback] 通知 MainActivity 更新 UI。
 */
class ConversationCoordinator(
    private val context: Context,
    private val adapter: ChatAdapter,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    companion object {
        private const val TAG = "ConvCoordinator"
    }

    /** ISS-090: 使用 ApplicationContext 避免持有 Activity 生命周期引用 */
    private val appContext: Context = context.applicationContext

    /** 回调接口：通知 MainActivity 执行 UI 更新和 Python 重置 */
    interface ConversationCallback {
        /** 会话已切换，需要更新消息列表 */
        fun onSessionChanged(messages: List<Message>)
        /** 会话已创建 */
        fun onSessionCreated(name: String)
        /** 会话已删除 */
        fun onSessionDeleted()
        /** 错误 */
        fun onError(error: String)
        /** 需要重置 Python 引擎 */
        fun onPythonResetNeeded()
    }

    var callback: ConversationCallback? = null

    // ======================== 保存/加载 ========================

    /** 保存当前会话消息（公开方法，供 ChatViewModel 调用） */
    fun saveCurrentSession() {
        saveConversation()
    }

    /** 保存当前会话消息 */
    fun saveConversation() {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = adapter.getMessages()
                ConversationSessionManager.saveMessages(sessionId, messages)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 加载当前会话消息历史 */
    fun loadConversation() {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = ConversationSessionManager.loadMessages(sessionId)
                withContext(Dispatchers.Main) {
                    callback?.onSessionChanged(messages)
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载会话失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.toast_load_session_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 仅刷新会话元数据（不替换消息列表），用于 onResume 时避免覆盖当前对话 */
    fun refreshSessionMeta() {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) return
        val messages = adapter.getMessages()
        if (messages.isEmpty()) return
        val lastMsg = messages.last()
        val preview = if (lastMsg.content.length > 30) lastMsg.content.take(30) + "..." else lastMsg.content
        ConversationSessionManager.updateSessionPreview(sessionId, preview, messages.size)
    }

    // ======================== 新建会话 ========================

    /** 显示新建会话对话框 */
    fun showNewChatDialog() {
        val input = EditText(context).apply {
            hint = appContext.getString(R.string.dialog_session_name)
            setSingleLine(true)
            val sessions = ConversationSessionManager.getSessions()
            val newIndex = sessions.count { it.name.startsWith(appContext.getString(R.string.default_session_name)) } + 1
            setText(appContext.getString(R.string.default_session_name) + " $newIndex")
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.default_session_name)
            .setView(input)
            .setPositiveButton(R.string.dialog_create_session) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { appContext.getString(R.string.default_session_name) }
                createNewSession(name)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.show()
        input.postDelayed({
            input.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /** 创建新会话并切换到该会话（公开方法，供 ChatViewModel 调用） */
    fun createNewSession(name: String) {
        val currentMessages = adapter.getMessages()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 先保存当前会话
                val currentId = ConversationSessionManager.getCurrentSessionId()
                if (currentId.isNotEmpty()) {
                    ConversationSessionManager.saveMessages(currentId, currentMessages)
                }

                // 重置 Python 引擎
                callback?.onPythonResetNeeded()

                // 创建新会话
                val character = CharacterStorage.getCurrent(context)
                val session = ConversationSessionManager.createSession(name, character.id)

                withContext(Dispatchers.Main) {
                    adapter.clear()
                    // 添加角色欢迎语
                    if (character.greeting.isNotBlank()) {
                        adapter.addMessage(Message(
                            content = character.greeting,
                            isUser = false
                        ))
                    }
                    callback?.onSessionCreated(session.name)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onError(appContext.getString(R.string.error_create_session_failed, e.message))
                }
            }
        }
    }

    // ======================== 会话列表对话框 ========================

    /** 显示会话列表对话框（支持切换/重命名/删除/新建） */
    fun showSessionListDialog() {
        val sessions = ConversationSessionManager.getSessions()
        val currentId = ConversationSessionManager.getCurrentSessionId()

        if (sessions.isEmpty()) {
            showNewChatDialog()
            return
        }

        val displayNames = sessions.map { session ->
            val marker = if (session.id == currentId) appContext.getString(R.string.label_current_session) else ""
            val preview = if (session.lastMessage.isNotEmpty()) " — ${session.lastMessage}" else ""
            val time = formatTimestamp(session.updatedAt)
            "${session.name}$marker\n$preview\n$time"
        }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.drawer_session_list_title)
            .setItems(displayNames) { _, which ->
                val selected = sessions[which]
                if (selected.id != currentId) {
                    switchToSession(selected.id)
                }
            }
            .setPositiveButton(R.string.drawer_new_chat) { _, _ ->
                showNewChatDialog()
            }
            .setNeutralButton(R.string.dialog_rename) { _, _ ->
                val currentSession = sessions.find { it.id == currentId }
                if (currentSession != null) {
                    showRenameDialog(currentSession)
                }
            }
            .setNegativeButton(R.string.btn_delete) { _, _ ->
                if (sessions.size <= 1) {
                    Toast.makeText(context, R.string.toast_keep_one_session, Toast.LENGTH_SHORT).show()
                    return@setNegativeButton
                }
                showDeleteSessionDialog(sessions, currentId)
            }
            .show()
    }

    // ======================== 删除会话 ========================

    /** 显示删除会话选择对话框 */
    private fun showDeleteSessionDialog(
        sessions: List<ConversationSession>,
        currentId: String
    ) {
        val names = sessions.map { s ->
            "${s.name} (${s.messageCount}条消息)" +
            if (s.id == currentId) appContext.getString(R.string.label_current_session) else ""
        }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_title_select_session_delete)
            .setItems(names) { _, which ->
                val session = sessions[which]
                showDeleteConfirmDialog(session)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 显示删除确认对话框 */
    private fun showDeleteConfirmDialog(session: ConversationSession) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_title_delete_session)
            .setMessage(appContext.getString(R.string.msg_delete_session_confirm, session.name, session.messageCount))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                deleteSessionAndSwitch(session.id)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 删除会话并自动切换到其他会话 */
    /** 外部调用：切换到指定会话 */
    fun switchToSession(sessionId: String) {
        switchToSessionInternal(sessionId)
    }

    /** 外部调用：删除指定会话 */
    fun deleteSession(sessionId: String) {
        deleteSessionAndSwitch(sessionId)
    }

    private fun deleteSessionAndSwitch(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ConversationSessionManager.deleteSession(sessionId)

                val newId = ConversationSessionManager.getCurrentSessionId()
                if (newId.isNotEmpty() && newId != sessionId) {
                    val messages = ConversationSessionManager.loadMessages(newId)
                    withContext(Dispatchers.Main) {
                        callback?.onSessionChanged(messages)
                        callback?.onSessionDeleted()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        adapter.clear()
                        callback?.onSessionDeleted()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onError(appContext.getString(R.string.error_delete_session_failed, e.message))
                }
            }
        }
    }

    // ======================== 重命名会话 ========================

    /** 显示重命名对话框 */
    private fun showRenameDialog(session: ConversationSession) {
        val input = EditText(context).apply {
            setText(session.name)
            setSingleLine(true)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_title_rename_session)
            .setView(input)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != session.name) {
                    ConversationSessionManager.renameSession(session.id, newName)
                    Toast.makeText(appContext, appContext.getString(R.string.toast_session_renamed, newName), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ======================== 切换会话 ========================

    /** 切换到指定会话（保存当前会话，加载目标会话） */
    private fun switchToSessionInternal(targetSessionId: String) {
        val currentMessages = adapter.getMessages()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 保存当前会话
                val currentId = ConversationSessionManager.getCurrentSessionId()
                if (currentId.isNotEmpty() && currentId != targetSessionId) {
                    ConversationSessionManager.saveMessages(currentId, currentMessages)
                }

                // 2. 重置 Python 引擎
                callback?.onPythonResetNeeded()

                // 3. 切换会话
                ConversationSessionManager.setCurrentSessionId(targetSessionId)

                // 4. 加载目标会话消息
                val messages = ConversationSessionManager.loadMessages(targetSessionId)

                withContext(Dispatchers.Main) {
                    callback?.onSessionChanged(messages)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onError(appContext.getString(R.string.error_switch_session_failed, e.message))
                }
            }
        }
    }

    // ======================== 工具 ========================

    /** 格式化时间戳为可读字符串 */
    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> appContext.getString(R.string.time_just_now)
            diff < 3_600_000 -> appContext.getString(R.string.time_minutes_ago, diff / 60_000)
            diff < 86_400_000 -> appContext.getString(R.string.time_hours_ago, diff / 3_600_000)
            diff < 172_800_000 -> appContext.getString(R.string.time_yesterday)
            else -> {
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}