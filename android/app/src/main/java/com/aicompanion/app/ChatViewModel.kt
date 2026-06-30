package com.aicompanion.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
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

/**
 * 一次性事件包装器，防止配置变更时重复消费。
 */
class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) null
        else { hasBeenHandled = true; content }
    }

    fun peekContent(): T = content
}

/** 发送按钮 UI 状态 */
data class SendButtonState(
    val enabled: Boolean = false,
    val text: String = "",
    val bgRes: Int = R.drawable.bg_send_inactive,
    val triggerAnimation: Boolean = false
)

/** 滚动事件 */
data class ScrollEvent(
    val position: Int,
    val smooth: Boolean = false
)

/** Snackbar 数据 */
data class SnackbarData(
    val message: String,
    val showRetry: Boolean = false
)

/** 输入框文本操作 */
sealed class InputTextAction {
    data class Set(val text: String, val selection: Int = 0) : InputTextAction()
}

/**
 * 消息状态管理器（继承 AndroidViewModel）。
 *
 * 负责消息列表管理、流式对话控制、Python 桥接调用、搜索和导出。
 * 通过 [ChatCallback] 通知 MainActivity 执行 UI 无关的操作（保存会话、TTS 等）。
 * UI 状态通过 LiveData 暴露给 MainActivity。
 */
class ChatViewModel(
    application: Application,
    val adapter: ChatAdapter
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val STREAM_TIMEOUT_MS = 30_000L
        private const val MSG_UPDATE_THROTTLE_MS = 50L
        private const val SCROLL_THROTTLE_MS = 200L
        private const val POLL_WAIT_MS = 30L
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MULTI_PART_DELAY_MS = 300L
        /** 429 限流最大重试次数 */
        private const val MAX_RATE_LIMIT_RETRIES = 3

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

    /**
     * 429 限流重试异常，用于在流式回调中触发指数退避重试。
     * 不显示错误 UI，由 finally 块负责清理并启动重试协程。
     */
    private class RateLimitRetryException : Exception()

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
        /** 请求显示导出格式选择对话框 */
        fun onShowExportFormatDialog()
        /** 请求分享导出文件 */
        fun onShareFile(uri: android.net.Uri, format: String)
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

    // ======================== 网络监控 ========================

    /** 网络连接管理器（用于自动重连） */
    private var connectivityManager: ConnectivityManager? = null
    /** 网络状态回调 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** 是否有待重试的请求（网络恢复后自动重试） */
    @Volatile
    private var pendingRetryOnReconnect = false

    // ======================== UI 状态 LiveData ========================

    private val _inputEnabled = MutableLiveData(true)
    val inputEnabled: LiveData<Boolean> = _inputEnabled

    private val _sendButtonState = MutableLiveData(SendButtonState())
    val sendButtonState: LiveData<SendButtonState> = _sendButtonState

    private val _searchBarVisible = MutableLiveData(false)
    val searchBarVisible: LiveData<Boolean> = _searchBarVisible

    private val _searchResultCountText = MutableLiveData("")
    val searchResultCountText: LiveData<String> = _searchResultCountText

    private val _searchResultCountVisible = MutableLiveData(false)
    val searchResultCountVisible: LiveData<Boolean> = _searchResultCountVisible

    private val _archiveHintVisible = MutableLiveData(false)
    val archiveHintVisible: LiveData<Boolean> = _archiveHintVisible

    private val _scrollToPosition = MutableLiveData<ScrollEvent>()
    val scrollToPosition: LiveData<ScrollEvent> = _scrollToPosition

    private val _toastEvent = MutableLiveData<Event<String>>()
    val toastEvent: LiveData<Event<String>> = _toastEvent

    private val _snackbarEvent = MutableLiveData<Event<SnackbarData>>()
    val snackbarEvent: LiveData<Event<SnackbarData>> = _snackbarEvent

    private val _inputTextAction = MutableLiveData<Event<InputTextAction>>()
    val inputTextAction: LiveData<Event<InputTextAction>> = _inputTextAction

    private val _clearInput = MutableLiveData<Event<Boolean>>()
    val clearInput: LiveData<Event<Boolean>> = _clearInput

    private val _requestInputFocus = MutableLiveData<Event<Boolean>>()
    val requestInputFocus: LiveData<Event<Boolean>> = _requestInputFocus

    private val _requestSearchFocus = MutableLiveData<Event<Boolean>>()
    val requestSearchFocus: LiveData<Event<Boolean>> = _requestSearchFocus

    private val _hideKeyboard = MutableLiveData<Event<Boolean>>()
    val hideKeyboard: LiveData<Event<Boolean>> = _hideKeyboard

    private val _clearSearchText = MutableLiveData<Event<Boolean>>()
    val clearSearchText: LiveData<Event<Boolean>> = _clearSearchText

    /** 消息上下文菜单事件（message, position） */
    private val _contextMenuEvent = MutableLiveData<Event<Pair<Message, Int>>>()
    val contextMenuEvent: LiveData<Event<Pair<Message, Int>>> = _contextMenuEvent

    /** RecyclerView 是否在底部（由 Activity 更新，流式输出时用于判断是否自动滚动） */
    @Volatile
    var isAtBottom: Boolean = true

    // ======================== 发送按钮状态追踪 ========================

    /** 发送按钮上一次是否处于激活态（用于缩放动画判断） */
    private var wasSendActive = false

    // ======================== 搜索辅助 ========================

    /** 搜索辅助类（内部类，访问外部成员） */
    inner class SearchHelper {
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
            _searchBarVisible.value = true
            _requestSearchFocus.value = Event(true)
        }

        fun exitSearchMode() {
            isSearchMode = false
            _searchBarVisible.value = false
            _searchResultCountVisible.value = false
            _clearSearchText.value = Event(true)
            // 恢复原始消息列表（搜索期间 adapter 中存的是过滤后的结果，必须用 originalMessages 恢复）
            adapter.replaceAll(originalMessages.toList())
            _hideKeyboard.value = Event(true)
            if (adapter.itemCount > 0) {
                _scrollToPosition.value = ScrollEvent(adapter.itemCount - 1)
            }
        }

        fun performSearch(keyword: String) {
            val module = pythonModule ?: return

            if (keyword.isBlank()) {
                adapter.replaceAll(originalMessages.toList())
                _searchResultCountVisible.value = false
                return
            }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val jsonArray = JSONArray()
                    for (msg in originalMessages) {
                        val obj = JSONObject()
                        obj.put("content", msg.content)
                        obj.put("isUser", msg.isUser)
                        obj.put("timestamp", msg.timestamp)
                        jsonArray.put(obj)
                    }

                    // v2.0: 搜索对话直接调用 Python 模块（待 ChatModule 接口定义后迁移到 ModuleRegistry.get<ChatModule>()）
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
                            _searchResultCountText.value =
                                getApplication<Application>().getString(R.string.search_result_count, total)
                            _searchResultCountVisible.value = total > 0
                            if (matchedMessages.isNotEmpty()) {
                                _scrollToPosition.value = ScrollEvent(0)
                            }
                        }
                    } else {
                        val errorMsg = resultJson.optString("message", getApplication<Application>().getString(R.string.error_unknown))
                        withContext(Dispatchers.Main) {
                            _toastEvent.value = Event(
                                getApplication<Application>().getString(R.string.toast_search_failed, errorMsg)
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "搜索对话超时")
                    withContext(Dispatchers.Main) {
                        _toastEvent.value = Event(
                            getApplication<Application>().getString(R.string.toast_timeout)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "搜索对话失败")
                    withContext(Dispatchers.Main) {
                        _toastEvent.value = Event(
                            getApplication<Application>().getString(R.string.toast_search_failed, e.message)
                        )
                    }
                }
            }
        }
    }

    val searchHelper = SearchHelper()

    // ======================== 发送消息 ========================

    /** 发送文本消息入口 */
    fun sendMessage(text: String) {
        try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return

            if (pythonModule == null) {
                // 引擎未就绪时仍显示用户消息，避免聊天框空白
                addUserBubble(trimmed)
                _clearInput.value = Event(true)
                _toastEvent.value = Event(getApplication<Application>().getString(R.string.toast_python_not_ready))
                return
            }

            if (isStreaming) return

            // 消息长度限制（2000字符）
            if (trimmed.length > MAX_MESSAGE_LENGTH) {
                _toastEvent.value = Event(getApplication<Application>().getString(R.string.toast_message_too_long))
                return
            }

            // 通知停止当前 TTS 播放
            callback?.onNewMessageSent()

            // 编辑模式：替换原消息
            if (editingPosition >= 0) {
                val messages = adapter.getMessages().toMutableList()
                if (editingPosition < messages.size && messages[editingPosition].isUser) {
                    val editedMsg = messages[editingPosition].copy(
                        content = trimmed,
                        isEdited = true,
                        timestamp = System.currentTimeMillis()
                    )
                    messages[editingPosition] = editedMsg
                    adapter.replaceAll(messages)
                    _clearInput.value = Event(true)
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
                        streamingHelper.sendMessageStream(trimmed)
                    } else {
                        callback?.onConversationNeedSave()
                    }
                }
                return
            }

            _clearInput.value = Event(true)
            updateSendButton(false)
            streamingHelper.sendMessageStream(trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "发送消息失败")
            _toastEvent.value = Event(
                getApplication<Application>().getString(
                    R.string.toast_conversation_failed,
                    e.message ?: getApplication<Application>().getString(R.string.error_unknown)
                )
            )
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
        /** 流式输出时 AI 消息在 adapter 中的索引，用于取消时清理残留（-1 表示无活跃流） */
        private var streamingAiMsgIndex: Int = -1
        /** 流式输出时打字指示器在 adapter 中的索引（-1 表示已移除） */
        private var streamingTypingIndex: Int = -1
        /** 429 限流重试计数（每次新流重置为 0） */
        private var rateLimitRetryCount = 0

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
                    _toastEvent.postValue(Event(getApplication<Application>().getString(R.string.toast_python_not_ready)))
                }
                return
            }
            isStreaming = true
            lastUserInput = userInput  // 保存用于重试
            rateLimitRetryCount = 0    // 重置 429 限流重试计数
            updateSendButton(false)
            disableInput()

            // 添加用户消息气泡
            addUserBubble(userInput)

            // 添加打字指示器（addMessage 返回新消息的索引）
            val typingMsg = Message(content = "", isUser = false, isTyping = true)
            streamingTypingIndex = adapter.addMessage(typingMsg)
            _scrollToPosition.postValue(ScrollEvent(streamingTypingIndex, smooth = true))

            // 占位 AI 消息（打字结束后填充）
            val aiMsg = Message(content = "", isUser = false)
            streamingAiMsgIndex = adapter.addMessage(aiMsg)

            streamJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    // 重置句子边界
                    lastSentenceEnd = 0

                    // 启动流式对话（30秒超时保护）
                    // v2.0: 流式对话启动直接调用 Python 模块（待 ChatModule 接口定义后迁移到 ModuleRegistry.get<ChatModule>()）
                    val streamId = withTimeout(STREAM_TIMEOUT_MS) {
                        module.callAttr("chat_stream_start", userInput)?.toString()
                            ?: """{"status":"error","message":"Python 模块返回 null"}"""
                    }
                    activeStreamId = streamId

                    val app = getApplication<Application>()

                    // 检查是否返回了错误
                    if (streamId.startsWith("{")) {
                        val errorJson = JSONObject(streamId)
                        Log.w(TAG, "流式对话降级为非流式: ${errorJson.optString("message", app.getString(R.string.error_unknown))}")
                        UiThread.run {
                            removeTypingIndicator()
                            adapter.updateMessage(
                                streamingAiMsgIndex,
                                aiMsg.copy(content = "${app.getString(R.string.label_error_prefix)} ${errorJson.optString("message", app.getString(R.string.error_unknown))}")
                            )
                        }
                        return@launch
                    }

                    val fullReply = StringBuilder()

                    // 使用 callbackFlow 包装轮询，替代 while 循环
                    // v2.0: 流式轮询直接调用 Python 模块（待 ChatModule 接口定义后迁移到 ModuleRegistry.get<ChatModule>()）
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
                                // 首次收到 token：移除打字指示器
                                UiThread.run {
                                    removeTypingIndicator()
                                }
                                val token = pollJson.optString("token", "")
                                fullReply.append(token)
                                val now = System.currentTimeMillis()
                                if (now - lastMessageUpdateTime > MSG_UPDATE_THROTTLE_MS) {
                                    UiThread.run {
                                        adapter.updateMessage(streamingAiMsgIndex, aiMsg.copy(content = fullReply.toString()))
                                    }
                                    lastMessageUpdateTime = now
                                }
                                if (now - lastScrollTime > SCROLL_THROTTLE_MS && isAtBottom) {
                                    UiThread.run {
                                        _scrollToPosition.postValue(ScrollEvent(adapter.itemCount - 1, smooth = true))
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
                                        adapter.updateMessage(streamingAiMsgIndex, aiMsg.copy(content = parts[0]))
                                        multiPartHandler?.removeCallbacksAndMessages(null)
                                        val handler = Handler(Looper.getMainLooper())
                                        multiPartHandler = handler
                                        parts.drop(1).forEachIndexed { idx, part ->
                                            handler.postDelayed({
                                                adapter.addMessage(Message(content = part, isUser = false))
                                                _scrollToPosition.postValue(ScrollEvent(adapter.itemCount - 1, smooth = true))
                                            }, ((idx + 1) * MULTI_PART_DELAY_MS).toLong())
                                        }
                                        handler.postDelayed({
                                            callback?.onConversationNeedSave()
                                            callback?.onStreamComplete(finalContent)
                                        }, (parts.size * MULTI_PART_DELAY_MS).toLong())
                                    }
                                } else {
                                    UiThread.run {
                                        adapter.updateMessage(streamingAiMsgIndex, aiMsg.copy(content = finalContent))
                                        callback?.onConversationNeedSave()
                                        callback?.onStreamComplete(finalContent)
                                    }
                                }
                            }
                            "error" -> {
                                // 解析错误类型（S2.2.4 增强：区分连接超时、读取超时、DNS、429 等）
                                val errorType = pollJson.optString("error_type", "unknown")
                                val errorMsg = pollJson.optString("message", app.getString(R.string.error_unknown))

                                // 429 限流：指数退避重试（1s → 2s → 4s，最多3次）
                                if (errorType == "rate_limit" && rateLimitRetryCount < MAX_RATE_LIMIT_RETRIES) {
                                    rateLimitRetryCount++
                                    UiThread.run {
                                        _toastEvent.postValue(Event(
                                            app.getString(R.string.error_rate_limit_retrying, rateLimitRetryCount, MAX_RATE_LIMIT_RETRIES)
                                        ))
                                    }
                                    throw RateLimitRetryException()
                                }

                                // 根据错误类型获取用户友好的提示文本
                                val errorDisplayText = getErrorDisplayText(errorType, errorMsg)
                                UiThread.run {
                                    adapter.updateMessage(
                                        streamingAiMsgIndex,
                                        aiMsg.copy(content = "${app.getString(R.string.label_error_prefix)} $errorDisplayText")
                                    )
                                    // 网络类错误：显示带重试的 Snackbar + 注册网络恢复自动重连
                                    if (isNetworkErrorType(errorType)) {
                                        showRetrySnackbar(errorDisplayText)
                                        registerNetworkCallback()
                                    } else {
                                        _toastEvent.postValue(Event(
                                            app.getString(R.string.toast_conversation_failed, errorDisplayText)
                                        ))
                                    }
                                }
                            }
                            else -> {
                                if (status != "waiting") {
                                    Log.w(TAG, "流式轮询返回未知状态: $status, 原始数据: ${pollJson.toString().take(200)}")
                                }
                                // waiting 是正常状态，继续轮询；未知状态也继续等待有效状态
                            }
                        }
                    }
                    // 流式结束后兜底检查：如果 AI 消息仍为空，替换为错误提示
                    if (streamingAiMsgIndex >= 0 && streamingAiMsgIndex < adapter.itemCount) {
                        val finalMsg = adapter.getMessages()[streamingAiMsgIndex]
                        if (finalMsg.content.isBlank()) {
                            UiThread.run {
                                adapter.updateMessage(
                                    streamingAiMsgIndex,
                                    aiMsg.copy(content = app.getString(R.string.error_ai_empty_response))
                                )
                            }
                        }
                    }
                } catch (e: RateLimitRetryException) {
                    // 429 限流：不显示错误 UI，由 finally 块处理指数退避重试
                    Log.d(TAG, "429 限流，准备指数退避重试 (${rateLimitRetryCount}/$MAX_RATE_LIMIT_RETRIES)")
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Python调用超时")
                    val app = getApplication<Application>()
                    UiThread.run {
                        adapter.updateMessage(
                            streamingAiMsgIndex,
                            aiMsg.copy(content = "${app.getString(R.string.label_error_timeout_prefix)} ${app.getString(R.string.error_timeout_message)}")
                        )
                        showRetrySnackbar(app.getString(R.string.error_timeout_short))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "流式对话失败")
                    val app = getApplication<Application>()
                    UiThread.run {
                        adapter.updateMessage(
                            streamingAiMsgIndex,
                            aiMsg.copy(content = "${app.getString(R.string.label_error_prefix)} ${e.message}")
                        )
                        showRetrySnackbar(app.getString(R.string.error_send_failed))
                    }
                } finally {
                    // 检查是否需要 429 限流退避重试
                    val needsRetry = rateLimitRetryCount > 0
                    val retryDelayMs = if (needsRetry) {
                        1000L * (1 shl (rateLimitRetryCount - 1))  // 1s, 2s, 4s
                    } else 0L
                    val aiIndex = streamingAiMsgIndex

                    isStreaming = false
                    streamJob = null
                    streamingAiMsgIndex = -1
                    UiThread.run {
                        removeTypingIndicator()
                        if (needsRetry) {
                            // 移除被标记为错误的消息
                            val messages = adapter.getMessages().toMutableList()
                            if (aiIndex >= 0 && aiIndex < messages.size && !messages[aiIndex].isUser) {
                                messages.removeAt(aiIndex)
                                adapter.replaceAll(messages)
                            }
                            // 启动延迟重试协程：指数退避后重新发送
                            viewModelScope.launch(Dispatchers.IO) {
                                delay(retryDelayMs)
                                sendMessageStream(lastUserInput)
                            }
                        } else {
                            enableInput()
                            updateSendButton(false)  // 输入框已清空，发送按钮应为失活
                        }
                    }
                }
            }
        }

        /** 移除打字指示器 */
        private fun removeTypingIndicator() {
            val idx = streamingTypingIndex
            if (idx >= 0) {
                streamingTypingIndex = -1
                adapter.removeTypingAt(idx)
            }
        }

        /**
         * 根据错误类型返回用户友好的显示文本。
         * 对应 Python 端传递的 error_type 字段。
         *
         * @param errorType Python 端传递的错误类型标识
         * @param fallback 原始错误消息（兜底用）
         * @return 用户友好的错误提示文本
         */
        private fun getErrorDisplayText(errorType: String, fallback: String): String {
            val app = getApplication<Application>()
            return when (errorType) {
                "connect_timeout" -> app.getString(R.string.error_connection_timeout)
                "read_timeout" -> app.getString(R.string.error_read_timeout)
                "timeout" -> app.getString(R.string.error_timeout_short)
                "dns" -> app.getString(R.string.error_dns_failure)
                "connection" -> app.getString(R.string.error_connection_refused)
                "rate_limit" -> app.getString(R.string.error_rate_limit_exhausted)
                "server_error" -> app.getString(R.string.error_server_error)
                "auth" -> app.getString(R.string.error_auth_failed)
                "quota" -> app.getString(R.string.error_quota_exceeded)
                "content_filter" -> app.getString(R.string.error_content_filtered)
                else -> fallback  // 未知类型显示原始消息
            }
        }

        /**
         * 判断错误类型是否为网络相关（需要自动重连）。
         * 网络错误包括：连接超时、读取超时、DNS 失败、连接拒绝、通用超时。
         */
        private fun isNetworkErrorType(errorType: String): Boolean {
            return errorType in setOf("connect_timeout", "read_timeout", "timeout", "dns", "connection")
        }

        /** 取消当前活跃的流式对话，释放 Python 流资源和协程 */
        fun cancelActiveStream() {
            // 取消流式协程（如果还在运行）
            streamJob?.cancel()
            streamJob = null

            // 清理打字指示器
            UiThread.run { removeTypingIndicator() }

            // 清理部分 AI 消息（流式输出被取消时残留的空/部分内容消息）
            val aiIndex = streamingAiMsgIndex
            if (aiIndex >= 0) {
                streamingAiMsgIndex = -1
                UiThread.run {
                    val messages = adapter.getMessages()
                    if (aiIndex < messages.size) {
                        val msg = messages[aiIndex]
                        if (!msg.isUser) {
                            val mutableList = messages.toMutableList()
                            mutableList.removeAt(aiIndex)
                            adapter.replaceAll(mutableList)
                        }
                    }
                }
            }

            // 取消 Python 流（必须在 IO 线程，Python.getInstance() 不应在主线程调用）
            // v2.0: 取消流直接调用 Python 模块（待 ChatModule 接口定义后迁移到 ModuleRegistry.get<ChatModule>()）
            val sid = activeStreamId
            if (sid != null) {
                viewModelScope.launch(Dispatchers.IO) {
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

        /**
         * 当消息列表被裁剪（removeAt(0)）时，调整流式消息索引。
         * 必须在 ChatAdapter.onMessagesTrimmed 回调中调用。
         */
        fun onMessagesTrimmed() {
            if (streamingAiMsgIndex > 0) streamingAiMsgIndex--
            if (streamingTypingIndex > 0) streamingTypingIndex--
        }
    }

    val streamingHelper = StreamingHelper()

    // ======================== 消息气泡辅助 ========================

    private fun addUserBubble(text: String) {
        val msg = Message(content = text, isUser = true)
        val position = adapter.addMessage(msg)
        _scrollToPosition.value = ScrollEvent(position, smooth = true)
    }

    // ======================== 输入状态控制 ========================

    /** 流式输出期间禁用输入框和发送按钮 */
    private fun disableInput() {
        _inputEnabled.value = false
        updateSendButton(false)
    }

    /** 流式输出完成后恢复输入框和发送按钮 */
    private fun enableInput() {
        _inputEnabled.value = true
        if (!isSearchMode) {
            _requestInputFocus.value = Event(true)
        }
    }

    /** 更新发送按钮状态 */
    fun updateSendButton(hasText: Boolean) {
        if (isStreaming) {
            _sendButtonState.value = SendButtonState(
                enabled = false,
                text = getApplication<Application>().getString(R.string.icon_loading),
                bgRes = R.drawable.bg_send_inactive,
                triggerAnimation = wasSendActive  // 从激活变为失活，触发动画
            )
            wasSendActive = false
            return
        }
        val bgRes = if (hasText) R.drawable.bg_send_active else R.drawable.bg_send_inactive
        val triggerAnim = hasText != wasSendActive
        wasSendActive = hasText
        _sendButtonState.value = SendButtonState(
            enabled = hasText,
            text = "",
            bgRes = bgRes,
            triggerAnimation = triggerAnim
        )
    }

    // ======================== 消息长按上下文菜单 ========================

    /** 触发消息上下文菜单事件（由 Adapter 长按回调调用） */
    fun showMessageContextMenu(message: Message, position: Int) {
        if (message.isTyping) return
        _contextMenuEvent.value = Event(Pair(message, position))
    }

    /** 复制消息到剪贴板 */
    fun copyMessage(text: String) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        _toastEvent.value = Event(getApplication<Application>().getString(R.string.toast_copied))
    }

    /** 进入消息编辑模式 */
    fun startEditingMessage(message: Message, position: Int) {
        editingPosition = position
        editingMessageId = message.id
        _inputTextAction.value = Event(InputTextAction.Set(message.content, message.content.length))
        _requestInputFocus.value = Event(true)
        updateSendButton(true)
        _toastEvent.value = Event(getApplication<Application>().getString(R.string.label_editing_message))
    }

    /** 删除指定位置的消息 */
    fun deleteMessage(position: Int) {
        val messages = adapter.getMessages().toMutableList()
        if (position in messages.indices) {
            Log.d(TAG, "删除消息: position=$position, id=${messages[position].id}, len=${messages[position].content.length}")
            messages.removeAt(position)
            adapter.replaceAll(messages)
        }
    }

    // ======================== 对话搜索 ========================

    /** 切换搜索模式（显示/隐藏搜索栏），委托给 SearchHelper */
    fun toggleSearchMode() = searchHelper.toggleSearchMode()

    /** 退出搜索模式，委托给 SearchHelper */
    fun exitSearchMode() = searchHelper.exitSearchMode()

    /** 搜索文本变化回调（由 MainActivity 的 TextWatcher 调用） */
    fun onSearchTextChanged(keyword: String) {
        searchHandler.removeCallbacksAndMessages(null)
        searchHandler.postDelayed({
            searchHelper.performSearch(keyword)
        }, SEARCH_DEBOUNCE_MS)
    }

    /** 搜索取消按钮点击回调 */
    fun onSearchCancelClicked() {
        searchHelper.toggleSearchMode()
    }

    // ======================== 键盘管理 ========================

    fun requestHideKeyboard() {
        _hideKeyboard.value = Event(true)
    }

    // ======================== 对话导出 ========================

    /** 触发导出格式选择对话框（通过回调通知 MainActivity） */
    fun showExportDialog() {
        val messages = adapter.getMessages()
        if (messages.isEmpty()) {
            _toastEvent.value = Event(getApplication<Application>().getString(R.string.toast_export_no_messages))
            return
        }
        callback?.onShowExportFormatDialog()
    }

    /** 导出对话历史到文件并分享 */
    fun exportConversation(format: String) {
        val messages = adapter.getMessages()
        if (messages.isEmpty()) {
            _toastEvent.value = Event(getApplication<Application>().getString(R.string.toast_export_no_messages))
            return
        }

        val app = getApplication<Application>()
        val character = CharacterStorage.getCurrent(app)
        val characterName = character.name

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = when (format) {
                    "json" -> ChatExporter.exportToJson(messages, characterName, app)
                    else -> ChatExporter.exportToTxt(messages, characterName, app)
                }
                val fileName = ChatExporter.generateFileName(format, characterName)
                val uri = ChatExporter.saveToFile(content, fileName, app)
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        _toastEvent.value = Event(app.getString(R.string.toast_export_file_failed))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    _toastEvent.value = Event(app.getString(R.string.toast_export_success))
                    callback?.onShareFile(uri, format)
                }
            } catch (e: Exception) {
                Log.w(TAG, "导出失败")
                withContext(Dispatchers.Main) {
                    _toastEvent.value = Event(app.getString(R.string.toast_export_failed, e.message))
                }
            }
        }
    }

    // ======================== 生命周期清理 ========================

    /**
     * AndroidViewModel 生命周期回调：当 ViewModel 不再使用且即将被销毁时自动调用。
     *
     * 自动清理以下资源：
     * - 协程 job（viewModelScope 自动取消）
     * - Handler 回调（searchHandler、multiPartHandler）
     * - 网络监听器（ConnectivityManager.NetworkCallback）
     * - Python 流资源（chat_stream_cancel）
     * - 回调引用（防止泄漏）
     *
     * 注意：此时 viewModelScope 已关闭，Python 流取消使用独立 Thread 执行。
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: 开始清理资源")
        isStreaming = false
        searchHandler.removeCallbacksAndMessages(null)
        multiPartHandler?.removeCallbacksAndMessages(null)
        multiPartHandler = null
        unregisterNetworkCallback()
        callback = null

        // 取消 Python 流（使用 Thread 代替 viewModelScope，因为此时 viewModelScope 已关闭）
        val sid = activeStreamId
        if (sid != null) {
            activeStreamId = null
            Thread {
                try {
                    com.chaquo.python.Python.getInstance()
                        .getModule("chat_bridge")
                        ?.callAttr("chat_stream_cancel", sid)
                    Log.d(TAG, "onCleared: 已取消 Python 流 $sid")
                } catch (e: Exception) {
                    Log.w(TAG, "onCleared: 取消 Python 流失败 ${e.message}")
                }
            }.start()
        }

        Log.d(TAG, "onCleared: 资源清理完成")
    }

    /** 释放资源（保留用于向后兼容，实际清理由 onCleared() 自动完成） */
    @Deprecated("清理由 onCleared() 自动完成，无需手动调用")
    fun destroy() {
        Log.d(TAG, "destroy: 已由 onCleared() 自动处理，无需手动调用")
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

    /** 显示带重试按钮的 Snackbar（通过 LiveData 事件通知 MainActivity） */
    private fun showRetrySnackbar(message: String) {
        _snackbarEvent.value = Event(
            SnackbarData(
                message = getApplication<Application>().getString(R.string.snackbar_retry_format, message),
                showRetry = true
            )
        )
    }

    // ======================== 网络恢复自动重连 ========================

    /**
     * 注册网络状态回调，在网络恢复后自动重试失败的请求。
     * 当流式对话因网络错误失败时调用，网络恢复后自动触发 retryLastMessage()。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return  // 已注册
        pendingRetryOnReconnect = true

        try {
            val app = getApplication<Application>()
            connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "网络已恢复，触发自动重连")
                    if (pendingRetryOnReconnect) {
                        pendingRetryOnReconnect = false
                        unregisterNetworkCallback()
                        UiThread.run {
                            _toastEvent.postValue(Event(app.getString(R.string.toast_network_restored_retrying)))
                        }
                        retryLastMessage()
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "网络连接丢失")
                    // 不在此处清理，等待 onAvailable 恢复
                }
            }
            networkCallback = callback
            connectivityManager?.registerDefaultNetworkCallback(callback)
            Log.d(TAG, "网络恢复监听已注册")
        } catch (e: Exception) {
            Log.w(TAG, "注册网络监听失败: ${e.message}")
        }
    }

    /** 取消网络状态监听 */
    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // 回调可能已被系统清理
        }
        networkCallback = null
        pendingRetryOnReconnect = false
        Log.d(TAG, "网络恢复监听已取消")
    }

    // ======================== 便捷方法 ========================

    /** 当消息列表被裁剪时（由 Adapter 回调触发），更新归档提示并调整流式索引 */
    fun onMessagesTrimmed() {
        _archiveHintVisible.value = true
        streamingHelper.onMessagesTrimmed()
    }

    /** 获取当前消息列表（供 ConversationCoordinator 保存） */
    fun getMessages(): List<Message> = adapter.getMessages()

    /** 替换全部消息（供 ConversationCoordinator 加载） */
    fun replaceMessages(messages: List<Message>) {
        adapter.replaceMessages(messages)
        _archiveHintVisible.value = false
    }

    /** 清空消息列表 */
    fun clearMessages() {
        adapter.clear()
        _archiveHintVisible.value = false
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
        _archiveHintVisible.value = false
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
            _scrollToPosition.value = ScrollEvent(adapter.itemCount - 1)
        }
    }
}