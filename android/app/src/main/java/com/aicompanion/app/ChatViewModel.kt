package com.aicompanion.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.databinding.ActivityMainBinding
import com.aicompanion.app.utils.UiThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 消息状态管理器。
 *
 * 负责消息列表管理、流式对话控制、Python 桥接调用、搜索和导出。
 * 通过 [ChatCallback] 通知 MainActivity 执行 UI 无关的操作（保存会话、TTS 等）。
 */
class ChatViewModel(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val adapter: ChatAdapter,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val STREAM_TIMEOUT_MS = 30_000L
        private const val MSG_UPDATE_THROTTLE_MS = 50L
        private const val SCROLL_THROTTLE_MS = 200L
        private const val POLL_WAIT_MS = 30L
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MULTI_PART_DELAY_MS = 300L

        /**
         * 从指定位置查找句子结束位置。
         * 句子结束标记：。！？!?\n（中文标点 + 英文感叹号/问号 + 换行）
         * @return 句子结束位置（含标点），未找到返回 -1
         */
        fun findSentenceEnd(text: String, startIndex: Int): Int {
            for (i in startIndex until text.length) {
                val ch = text[i]
                if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '\n') {
                    return i + 1
                }
            }
            return -1
        }
    }

    /** 回调接口：通知 MainActivity 执行协调整操作 */
    interface ChatCallback {
        /** 通知 MainActivity 保存当前会话 */
        fun onConversationNeedSave()
        /** 流式对话完成，传递完整回复内容（用于触发 TTS 朗读剩余内容） */
        fun onStreamComplete(fullContent: String)
        /** 流式对话中检测到完整句子，逐句触发 TTS 朗读 */
        fun onStreamSentence(sentence: String)
        /** 用户发送新消息时，通知停止当前 TTS 播放 */
        fun onNewMessageSent()
    }

    var callback: ChatCallback? = null

    // ======================== Python 桥接 ========================

    /** Python chat_bridge 模块引用（由 MainActivity 在初始化完成后设置） */
    @Volatile
    var pythonModule: com.chaquo.python.PyObject? = null

    // ======================== 流式状态 ========================

    /** 是否正在流式输出中（防止重复发送） */
    @Volatile
    var isStreaming = false
        private set

    /** 上次用户输入（用于网络中断后重试） */
    private var lastUserInput: String = ""

    /** 编辑模式：正在编辑的消息位置，-1 表示非编辑模式 */
    private var editingPosition: Int = -1
    /** 编辑模式：正在编辑的消息 ID */
    private var editingMessageId: String? = null

    /** 流式输出滚动节流时间戳 */
    private var lastScrollTime = 0L
    /** 流式消息更新节流时间戳（50ms） */
    private var lastMessageUpdateTime = 0L

    /** 当前活跃的流式对话 ID（用于 onDestroy 清理 Python 流资源） */
    @Volatile
    private var activeStreamId: String? = null

    /** 流式句子边界追踪：已处理到的字符位置（用于逐句 TTS） */
    private var lastSentenceEnd = 0

    // ======================== 搜索相关 ========================

    /** 是否处于搜索模式 */
    private var isSearchMode = false
    /** 进入搜索模式前的原始消息列表（用于退出搜索后恢复） */
    private var originalMessages = listOf<Message>()
    /** 搜索防抖 Handler */
    private val searchHandler = Handler(Looper.getMainLooper())
    /** 多段消息拆分 Handler（需在 destroy 中清理） */
    private var multiPartHandler: Handler? = null

    // ======================== 初始化 ========================

    /** 搜索辅助类（内部类，访问外部成员） */
    inner class SearchHelper {
        fun setupSearchListeners() {
            var searchRunnable: Runnable? = null
            binding.etSearch?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    searchRunnable = Runnable {
                        performSearch(s?.toString() ?: "")
                    }
                    searchRunnable?.let { searchHandler.postDelayed(it, SEARCH_DEBOUNCE_MS) }
                }
            })

            binding.btnCancel?.setOnClickListener {
                if (binding.etSearch?.text?.isNotEmpty() == true) {
                    binding.etSearch?.setText("")
                } else {
                    toggleSearchMode()
                }
            }
        }

        fun toggleSearchMode() {
            if (isSearchMode) {
                exitSearchMode()
            } else {
                enterSearchMode()
            }
        }

        fun enterSearchMode() {
            isSearchMode = true
            originalMessages = adapter.getMessages().toList()
            binding.layoutSearch?.visibility = View.VISIBLE
            binding.etSearch?.requestFocus()
            binding.etSearch?.let { showKeyboard(it) }
        }

        fun exitSearchMode() {
            isSearchMode = false
            binding.layoutSearch?.visibility = View.GONE
            binding.tvSearchResultCount?.visibility = View.GONE
            binding.etSearch?.setText("")
            // 使用当前实际消息列表，而非缓存的旧快照（防止搜索期间新消息丢失）
            adapter.replaceAll(adapter.getMessages().toList())
            hideKeyboard()
            if (adapter.itemCount > 0) {
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
        }

        fun performSearch(keyword: String) {
            val module = pythonModule ?: return

            if (keyword.isBlank()) {
                adapter.replaceAll(originalMessages.toList())
                binding.tvSearchResultCount?.visibility = View.GONE
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonArray = JSONArray()
                    for (msg in originalMessages) {
                        val obj = JSONObject()
                        obj.put("content", msg.content)
                        obj.put("isUser", msg.isUser)
                        obj.put("timestamp", msg.timestamp)
                        jsonArray.put(obj)
                    }

                    val result = withTimeout(STREAM_TIMEOUT_MS) {
                        module.callAttr(
                            "search_conversation", keyword, jsonArray.toString()
                        ).toString()
                    }
                    val resultJson = JSONObject(result)

                    if (resultJson.optString("status") == "ok") {
                        val matches = resultJson.optJSONArray("matches") ?: JSONArray()
                        val total = resultJson.optInt("total", 0)

                        val matchedMessages = mutableListOf<Message>()
                        for (i in 0 until matches.length()) {
                            val match = matches.getJSONObject(i)
                            matchedMessages.add(Message(
                                content = match.optString("content", ""),
                                isUser = match.optBoolean("isUser", false),
                                timestamp = match.optLong("timestamp", System.currentTimeMillis())
                            ))
                        }

                        withContext(Dispatchers.Main) {
                            adapter.replaceAll(matchedMessages)
                            binding.tvSearchResultCount?.text =
                                context.getString(R.string.search_result_count, total)
                            binding.tvSearchResultCount?.visibility =
                                if (total > 0) View.VISIBLE else View.GONE
                            if (matchedMessages.isNotEmpty()) {
                                binding.rvMessages.scrollToPosition(0)
                            }
                        }
                    } else {
                        val errorMsg = resultJson.optString("message", context.getString(R.string.error_unknown))
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_search_failed, errorMsg),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "搜索对话超时", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_timeout),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "搜索对话失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_search_failed, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    val searchHelper = SearchHelper()

    init {
        searchHelper.setupSearchListeners()
    }

    // ======================== 发送消息 ========================

    /** 发送文本消息入口 */
    fun sendMessage() {
        try {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) return

            if (pythonModule == null) {
                Toast.makeText(context, R.string.toast_python_not_ready, Toast.LENGTH_SHORT).show()
                return
            }

            if (isStreaming) return

            // 消息长度限制（2000字符）
            if (text.length > MAX_MESSAGE_LENGTH) {
                Toast.makeText(
                    context, R.string.toast_message_too_long, Toast.LENGTH_SHORT
                ).show()
                return
            }

            // 通知停止当前 TTS 播放
            callback?.onNewMessageSent()

            // 编辑模式：替换原消息
            if (editingPosition >= 0) {
                val messages = adapter.getMessages().toMutableList()
                if (editingPosition < messages.size && messages[editingPosition].isUser) {
                    val editedMsg = messages[editingPosition].copy(
                        content = text,
                        isEdited = true,
                        timestamp = System.currentTimeMillis()
                    )
                    messages[editingPosition] = editedMsg
                    adapter.replaceAll(messages)
                    binding.etInput.text.clear()
                    updateSendButton(false)
                    // 保存编辑位置（在重置前）
                    val editPos = editingPosition
                    // 退出编辑模式
                    editingPosition = -1
                    editingMessageId = null
                    // 如果编辑位置后面有 AI 回复，移除它并重新发送
                    if (editPos + 1 < messages.size && !messages[editPos + 1].isUser) {
                        val remaining = messages.subList(0, editPos + 1)
                        adapter.replaceAll(remaining)
                        streamingHelper.sendMessageStream(text)
                    } else {
                        callback?.onConversationNeedSave()
                    }
                }
                return
            }

            binding.etInput.text.clear()
            updateSendButton(false)
            streamingHelper.sendMessageStream(text)
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
            Toast.makeText(
                context,
                context.getString(R.string.toast_conversation_failed, e.message ?: context.getString(R.string.error_unknown)),
                Toast.LENGTH_SHORT
            ).show()
            // 恢复输入状态
            if (isStreaming) {
                isStreaming = false
                enableInput()
            }
        }
    }

    // ======================== 流式消息发送 ========================

    /** 流式对话辅助类（内部类，访问外部成员） */
    inner class StreamingHelper {
        /** 当前流式协程的 Job 引用，用于 destroy 时取消 */
        private var streamJob: kotlinx.coroutines.Job? = null

        /**
         * 流式发送消息（callbackFlow 方案）。
         * 使用 chat_stream_start() 启动后台生成线程，
         * 然后通过 callbackFlow 包装 chat_stream_poll() 轮询获取 token 并实时更新 UI。
         */
        fun sendMessageStream(userInput: String) {
            // 搜索模式下先退出，防止流式消息操作搜索结果列表导致崩溃
            if (isSearchMode) {
                exitSearchMode()
            }
            val module = pythonModule
            if (module == null) {
                UiThread.run {
                    Toast.makeText(context, R.string.toast_python_not_ready, Toast.LENGTH_SHORT).show()
                }
                return
            }
            isStreaming = true
            lastUserInput = userInput  // 保存用于重试
            updateSendButton(false)
            disableInput()

            // 添加用户消息气泡
            addUserBubble(userInput)

            // 添加占位 AI 消息（空内容，后续逐 token 填充）
            val aiMsg = Message(content = "", isUser = false)
            val aiMsgIndex = adapter.itemCount
            adapter.addMessage(aiMsg)
            binding.rvMessages.smoothScrollToPosition(aiMsgIndex)

            streamJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 重置句子边界
                    lastSentenceEnd = 0

                    // 启动流式对话（30秒超时保护）
                    val streamId = withTimeout(STREAM_TIMEOUT_MS) {
                        module.callAttr("chat_stream_start", userInput)?.toString()
                            ?: """{"status":"error","message":"Python 模块返回 null"}"""
                    }
                    activeStreamId = streamId

                    // 检查是否返回了错误
                    if (streamId.startsWith("{")) {
                        val errorJson = JSONObject(streamId)
                        Log.w(TAG, "流式对话降级为非流式: ${errorJson.optString("message", context.getString(R.string.error_unknown))}")
                        UiThread.run {
                            adapter.updateMessage(
                                aiMsgIndex,
                                aiMsg.copy(content = "${context.getString(R.string.label_error_prefix)} ${errorJson.optString("message", context.getString(R.string.error_unknown))}")
                            )
                        }
                        return@launch
                    }

                    val fullReply = StringBuilder()

                    // 使用 callbackFlow 包装轮询，替代 while 循环
                    callbackFlow {
                        var isDone = false
                        while (!isDone && isStreaming) {
                            val pollResult = withTimeout(STREAM_TIMEOUT_MS) {
                                module.callAttr("chat_stream_poll", streamId)?.toString()
                                    ?: """{"status":"error","message":"poll 返回 null"}"""
                            }
                            trySend(pollResult)
                            val pollJson = JSONObject(pollResult)
                            when (pollJson.optString("status", "error")) {
                                "done", "error" -> {
                                    close()
                                    isDone = true
                                }
                                "waiting" -> delay(POLL_WAIT_MS)
                            }
                        }
                        awaitClose { }
                    }.collect { pollResult ->
                        val pollJson = JSONObject(pollResult)
                        val status = pollJson.optString("status", "error")

                        when (status) {
                            "streaming" -> {
                                val token = pollJson.optString("token", "")
                                fullReply.append(token)
                                val now = System.currentTimeMillis()
                                if (now - lastMessageUpdateTime > MSG_UPDATE_THROTTLE_MS) {
                                    UiThread.run {
                                        adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = fullReply.toString()))
                                    }
                                    lastMessageUpdateTime = now
                                }
                                if (now - lastScrollTime > SCROLL_THROTTLE_MS) {
                                    UiThread.run {
                                        if (!binding.rvMessages.canScrollVertically(1)) {
                                            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                        }
                                    }
                                    lastScrollTime = now
                                }
                                // 检测完整句子，逐句触发 TTS
                                val currentText = fullReply.toString()
                                while (lastSentenceEnd < currentText.length) {
                                    val sentenceEnd = findSentenceEnd(currentText, lastSentenceEnd)
                                    if (sentenceEnd < 0) break
                                    val sentence = currentText.substring(lastSentenceEnd, sentenceEnd)
                                    lastSentenceEnd = sentenceEnd
                                    if (sentence.isNotBlank()) {
                                        UiThread.run {
                                            callback?.onStreamSentence(sentence)
                                        }
                                    }
                                }
                            }
                            "done" -> {
                                val reply = pollJson.optString("reply", fullReply.toString())
                                val finalContent = reply.ifEmpty { fullReply.toString() }
                                // 发送最后可能残留的句子
                                val remaining = finalContent.substring(
                                    lastSentenceEnd.coerceAtMost(finalContent.length)
                                )
                                if (remaining.isNotBlank()) {
                                    UiThread.run {
                                        callback?.onStreamSentence(remaining)
                                    }
                                }
                                // 拆分多条消息
                                val parts = finalContent
                                    .replace("\r\n", "\n")
                                    .split("\n\\s*\n".toRegex())
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                if (parts.size > 1) {
                                    UiThread.run {
                                        adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = parts[0]))
                                        multiPartHandler?.removeCallbacksAndMessages(null)
                                        val handler = Handler(Looper.getMainLooper())
                                        multiPartHandler = handler
                                        parts.drop(1).forEachIndexed { idx, part ->
                                            handler.postDelayed({
                                                adapter.addMessage(Message(content = part, isUser = false))
                                                binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                            }, ((idx + 1) * MULTI_PART_DELAY_MS).toLong())
                                        }
                                        handler.postDelayed({
                                            callback?.onConversationNeedSave()
                                            callback?.onStreamComplete(finalContent)
                                        }, (parts.size * MULTI_PART_DELAY_MS).toLong())
                                    }
                                } else {
                                    UiThread.run {
                                        adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = finalContent))
                                        callback?.onConversationNeedSave()
                                        callback?.onStreamComplete(finalContent)
                                    }
                                }
                            }
                            "error" -> {
                                val errorMsg = pollJson.optString("message", context.getString(R.string.error_unknown))
                                UiThread.run {
                                    adapter.updateMessage(
                                        aiMsgIndex,
                                        aiMsg.copy(content = "${context.getString(R.string.label_error_prefix)} $errorMsg")
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_conversation_failed, errorMsg),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Python调用超时", e)
                    UiThread.run {
                        adapter.updateMessage(
                            aiMsgIndex,
                            aiMsg.copy(content = "${context.getString(R.string.label_error_timeout_prefix)} ${context.getString(R.string.error_timeout_message)}")
                        )
                        showRetrySnackbar(context.getString(R.string.error_timeout_short))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "流式对话失败", e)
                    UiThread.run {
                        adapter.updateMessage(
                            aiMsgIndex,
                            aiMsg.copy(content = "${context.getString(R.string.label_error_prefix)} ${e.message}")
                        )
                        showRetrySnackbar(context.getString(R.string.error_send_failed))
                    }
                } finally {
                    isStreaming = false
                    streamJob = null
                    UiThread.run {
                        enableInput()
                        updateSendButton(binding.etInput.text?.isNotBlank() == true)
                    }
                }
            }
        }

        /** 取消当前活跃的流式对话，释放 Python 流资源和协程 */
        fun cancelActiveStream() {
            // 取消流式协程（如果还在运行）
            streamJob?.cancel()
            streamJob = null
            // 取消 Python 流（必须在 IO 线程，Python.getInstance() 不应在主线程调用）
            val sid = activeStreamId
            if (sid != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        com.chaquo.python.Python.getInstance()
                            .getModule("chat_bridge")
                            ?.callAttr("chat_stream_cancel", sid)
                        Log.d(TAG, "cancelActiveStream: 已取消流 $sid")
                    } catch (e: Exception) {
                        Log.w(TAG, "cancelActiveStream: 取消流失败 ${e.message}")
                    } finally {
                        activeStreamId = null
                        isStreaming = false
                    }
                }
            }
        }
    }

    val streamingHelper = StreamingHelper()

    // ======================== 消息气泡辅助 ========================

    private fun addUserBubble(text: String) {
        val msg = Message(content = text, isUser = true)
        adapter.addMessage(msg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
    }

    // ======================== 输入状态控制 ========================

    /** 流式输出期间禁用输入框和发送按钮 */
    private fun disableInput() {
        binding.etInput.isEnabled = false
        updateSendButton(false)
    }

    /** 流式输出完成后恢复输入框和发送按钮 */
    private fun enableInput() {
        binding.etInput.isEnabled = true
        updateSendButton(binding.etInput.text?.isNotBlank() == true)
        if (!isSearchMode) {
            try {
                binding.etInput.requestFocus()
            } catch (_: Exception) {
                // Activity 可能已销毁，忽略
            }
        }
    }

    /** 更新发送按钮状态 */
    fun updateSendButton(hasText: Boolean) {
        if (isStreaming) {
            binding.btnSend.isEnabled = false
            binding.btnSend.setBackgroundResource(R.drawable.bg_send_inactive)
            binding.btnSend.text = context.getString(R.string.icon_loading)
            return
        }
        binding.btnSend.isEnabled = hasText
        binding.btnSend.setBackgroundResource(
            if (hasText) R.drawable.bg_send_active else R.drawable.bg_send_inactive
        )
    }

    // ======================== 消息长按上下文菜单 ========================

    /** 显示消息长按上下文菜单 */
    fun showMessageContextMenu(message: Message, position: Int) {
        if (message.isTyping) return
        val items = mutableListOf(context.getString(R.string.action_copy))
        if (message.isUser) {
            items.add(context.getString(R.string.action_edit))
            items.add(context.getString(R.string.btn_delete))
        }

        MaterialAlertDialogBuilder(context)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (message.isUser) {
                            // 编辑
                            startEditingMessage(message, position)
                        } else {
                            // 删除
                            val messages = adapter.getMessages().toMutableList()
                            if (position in messages.indices) {
                                Log.d(TAG, "删除消息: position=$position, content=${messages[position].content.take(30)}")
                                messages.removeAt(position)
                                adapter.replaceAll(messages)
                            }
                        }
                    }
                    2 -> {
                        // 删除（用户消息的第3项）
                        val messages = adapter.getMessages().toMutableList()
                        if (position in messages.indices) {
                            Log.d(TAG, "删除消息: position=$position, content=${messages[position].content.take(30)}")
                            messages.removeAt(position)
                            adapter.replaceAll(messages)
                        }
                    }
                }
            }
            .show()
    }

    /** 进入消息编辑模式 */
    fun startEditingMessage(message: Message, position: Int) {
        editingPosition = position
        editingMessageId = message.id
        binding.etInput.setText(message.content)
        binding.etInput.setSelection(message.content.length)
        binding.etInput.requestFocus()
        showKeyboard(binding.etInput)
        updateSendButton(true)
        Toast.makeText(context, R.string.label_editing_message, Toast.LENGTH_SHORT).show()
    }

    // ======================== 对话搜索 ========================

    /** 切换搜索模式（显示/隐藏搜索栏），委托给 SearchHelper */
    fun toggleSearchMode() = searchHelper.toggleSearchMode()

    /** 退出搜索模式，委托给 SearchHelper */
    fun exitSearchMode() = searchHelper.exitSearchMode()

    // ======================== 键盘管理 ========================

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val focusedView = (context as? android.app.Activity)?.currentFocus
        if (focusedView != null) {
            imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
        } else {
            imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
        }
    }

    private fun showKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // ======================== 对话导出 ========================

    /** 显示导出格式选择对话框 */
    fun showExportDialog() {
        val formats = arrayOf(
            context.getString(R.string.export_format_json),
            context.getString(R.string.export_format_txt)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.label_choose_export_format))
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton(context.getString(R.string.btn_cancel), null)
            .show()
    }

    /** 导出对话历史到文件并分享 */
    private fun exportConversation(format: String) {
        val messages = adapter.getMessages()
        if (messages.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_export_no_messages), Toast.LENGTH_SHORT).show()
            return
        }

        val character = CharacterStorage.getCurrent(context)
        val characterName = character.name

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = when (format) {
                    "json" -> ChatExporter.exportToJson(messages, characterName)
                    else -> ChatExporter.exportToTxt(messages, characterName)
                }
                val fileName = ChatExporter.generateFileName(format, characterName)
                val uri = ChatExporter.saveToFile(content, fileName, context)
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_export_file_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                    shareExportedFile(uri, format)
                }
            } catch (e: Exception) {
                Log.e(TAG, "导出失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_export_failed, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 分享导出文件 */
    private fun shareExportedFile(uri: android.net.Uri, format: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (format == "json") "application/json" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, context.getString(R.string.chooser_share_conversation))
            )
        } catch (e: Exception) {
            Log.w(TAG, "分享失败: ${e.message}")
        }
    }

    // ======================== 生命周期清理 ========================

    /** 释放资源（在 Activity onDestroy 中调用），确保协程和 Python 流被清理 */
    fun destroy() {
        Log.d(TAG, "destroy: 开始清理资源")
        isStreaming = false
        searchHandler.removeCallbacksAndMessages(null)
        multiPartHandler?.removeCallbacksAndMessages(null)
        multiPartHandler = null
        streamingHelper.cancelActiveStream()
        callback = null
        Log.d(TAG, "destroy: 资源清理完成")
    }

    /** 取消当前活跃的流式对话（用于 onStop 等场景），委托给 StreamingHelper */
    fun cancelActiveStream() = streamingHelper.cancelActiveStream()

    /** 获取当前消息数量 */
    fun getMessageCount(): Int = adapter.itemCount

    /**
     * 重试发送上一条消息（网络中断恢复）。
     * 可在流式失败后通过 Snackbar 或外部调用触发。
     */
    fun retryLastMessage() {
        val input = lastUserInput
        if (input.isNotEmpty() && !isStreaming && pythonModule != null) {
            // 移除最后一条 AI 错误消息
            val messages = adapter.getMessages().toMutableList()
            if (messages.isNotEmpty() && !messages.last().isUser) {
                messages.removeAt(messages.lastIndex)
                adapter.replaceAll(messages)
            }
            streamingHelper.sendMessageStream(input)
        }
    }

    /** 显示带重试按钮的 Snackbar */
    private fun showRetrySnackbar(message: String) {
        try {
            Snackbar.make(binding.root, context.getString(R.string.snackbar_retry_format, message), Snackbar.LENGTH_LONG)
                .setAction(context.getString(R.string.action_retry)) { retryLastMessage() }
                .show()
        } catch (_: Exception) {
            // Activity 可能已销毁，忽略
        }
    }

    // ======================== 便捷方法 ========================

    /** 获取当前消息列表（供 ConversationCoordinator 保存） */
    fun getMessages(): List<Message> = adapter.getMessages()

    /** 替换全部消息（供 ConversationCoordinator 加载） */
    fun replaceMessages(messages: List<Message>) {
        adapter.replaceMessages(messages)
        binding.tvArchiveHint.visibility = View.GONE
    }

    /** 清空消息列表 */
    fun clearMessages() {
        adapter.clear()
        binding.tvArchiveHint.visibility = View.GONE
    }

    /**
     * 开始新对话：清空消息、重置流式状态、添加角色欢迎语。
     * 供 ConversationCoordinator 创建新会话后调用。
     */
    fun startNewConversation(greeting: String) {
        // 取消正在进行的流式对话
        if (isStreaming) {
            cancelActiveStream()
        }
        // 清空消息列表
        adapter.clear()
        binding.tvArchiveHint.visibility = View.GONE
        // 重置编辑模式
        editingPosition = -1
        editingMessageId = null
        // 重置流式状态
        lastUserInput = ""
        lastSentenceEnd = 0
        // 添加角色欢迎语
        if (greeting.isNotBlank()) {
            adapter.addMessage(Message(content = greeting, isUser = false))
        }
        // 刷新 UI
        if (adapter.itemCount > 0) {
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }
}