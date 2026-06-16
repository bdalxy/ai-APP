package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 角色扮演聊天主界面 (P3)
 *
 * 通过 Chaquopy 调用 Python chat_bridge 模块，
 * 实现与 AI 角色（小美）的实时对话。
 *
 * P3.1 流式消息输出：
 * - 调用 chat_bridge.chat_stream() 获取 generator
 * - 逐 token 实时更新 UI，无需等待完整回复
 * - 输出期间禁用输入防止重复发送，完成后自动恢复
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pythonModule: com.chaquo.python.PyObject
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var gestureDetector: GestureDetector

    /** 是否正在流式输出中（防止重复发送） */
@Volatile
private var isStreaming = false

/** 流式输出滚动节流时间戳 */
private var lastScrollTime = 0L
/** 流式消息更新节流时间戳（50ms） */
private var lastMessageUpdateTime = 0L

// ======================== 搜索相关 ========================
/** 是否处于搜索模式 */
private var isSearchMode = false
/** 进入搜索模式前的原始消息列表（用于退出搜索后恢复） */
private var originalMessages = listOf<Message>()
/** 搜索防抖 Handler（需要在 onDestroy 中清理） */
private val searchHandler = Handler(Looper.getMainLooper())
/** 当前活跃的流式对话 ID（用于 onDestroy 清理 Python 流资源） */
@Volatile
private var activeStreamId: String? = null

/** 角色选择回调（CharacterSelectActivity 返回后同步角色到 Python） */
private val characterSelectLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        syncCharacterToPython()
    }
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 适配刘海屏/挖孔屏/状态栏
        ViewUtils.setupEdgeToEdge(this)
        applyInsets(binding.mainRoot)

        adapter = ChatAdapter(mutableListOf())
        binding.rvMessages.adapter = adapter
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        // 自定义消息入场动画（P2 UI 优化）
        binding.rvMessages.itemAnimator = MessageItemAnimator()

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 新对话按钮
        binding.btnNewChat?.setOnClickListener {
            showNewChatDialog()
        }
        // 长按设置按钮 → 导出对话
        binding.btnSettings.setOnLongClickListener {
            showExportDialog()
            true
        }

        // 搜索按钮：切换搜索模式
        binding.btnSearch?.setOnClickListener {
            toggleSearchMode()
        }

        // 搜索输入监听（防抖300ms，searchHandler 已提升为类字段）
        var searchRunnable: Runnable? = null
        binding.etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    performSearch(s?.toString() ?: "")
                }
                searchHandler.postDelayed(searchRunnable!!, 300)
            }
        })

        // 搜索清除按钮：清空内容；若已为空则退出搜索模式
        binding.btnClearSearch?.setOnClickListener {
            if (binding.etSearch?.text?.isNotEmpty() == true) {
                binding.etSearch?.setText("")
            } else {
                toggleSearchMode()
            }
        }

        // 点击聊天消息区域空白处收起键盘
        binding.rvMessages.setOnTouchListener { _, _ ->
            hideKeyboard()
            false
        }

        // 点击角色名称 → 弹出会话列表（支持切换/新建/删除会话）
        binding.tvTitle.setOnClickListener {
            showSessionListDialog()
        }
        // 长按角色名称 → 跳转角色选择页
        binding.tvTitle.setOnLongClickListener {
            openCharacterSelect()
            true
        }
        // 点击头像 → 跳转角色选择页
        binding.ivAvatar.setOnClickListener { openCharacterSelect() }

        // 左滑手势进入角色选择页（通过 dispatchTouchEvent 拦截，确保不会被子视图消费）
        gestureDetector = GestureDetector(this, object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent): Boolean = false
            override fun onShowPress(e: MotionEvent) {}
            override fun onSingleTapUp(e: MotionEvent): Boolean = false
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
            override fun onLongPress(e: MotionEvent) {}
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                // 左滑（X位移 > 150，且水平速度远大于垂直速度）
                if (diffX > 150 && Math.abs(velocityX) > Math.abs(velocityY) * 1.5f && velocityX > 0) {
                    openCharacterSelect()
                    return true
                }
                return false
            }
        })

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // 初始化多会话管理器
        ConversationSessionManager.init(this)

        // 异步初始化 Python
        initializePythonAsync()
    }

    private fun initializePythonAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = this@MainActivity.getString(R.string.status_initializing)
                }

                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")

                // 1. 注入 API Key
                val apiKey = AppConfig.getApiKey(this@MainActivity)
                if (apiKey.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = this@MainActivity.getString(R.string.error_init_api_key)
                    }
                    return@launch
                }
                module.callAttr("set_api_key", apiKey)

                // 2. 初始化聊天引擎（独立参数）
                val ctxSize = AppConfig.getContextSize(this@MainActivity)
                val temp = AppConfig.getTemperature(this@MainActivity).toDouble()
                val maxTk = AppConfig.getMaxTokens(this@MainActivity)
                val dialogues = AppConfig.getExampleDialogues(this@MainActivity)
                val model = AppConfig.getModel(this@MainActivity).let {
                    if (it.isBlank()) "" else it
                }
                val initResult = module.callAttr("init", ctxSize, temp, maxTk, dialogues, model).toString()
                Log.d("MainActivity", "init 返回: $initResult")

                // 3. 初始化记忆系统
                val dbDir = filesDir.absolutePath
                module.callAttr("init_memory", dbDir)

                // 4. 加载角色卡（完整JSON同步）
                val character = CharacterStorage.getCurrent(this@MainActivity)
                val charJson = JSONObject().apply {
                    put("name", character.name)
                    put("personality", character.personality)
                    put("speaking_style", character.speakingStyle)
                    put("backstory", character.backstory)
                    put("greeting", character.greeting)
                }.toString()
                module.callAttr("set_character_card", charJson)

                // 5. 恢复已启用的世界书
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val savedBooks = prefs.getString("enabled_world_books", "") ?: ""
                if (savedBooks.isNotBlank()) {
                    savedBooks.split(",").filter { it.isNotBlank() }.forEach { name ->
                        val result = module.callAttr("enable_world_book", name.trim()).toString()
                        Log.d("MainActivity", "恢复世界书 $name: $result")
                    }
                }

                withContext(Dispatchers.Main) {
                    pythonModule = module
                    binding.tvStatus.text = ""
                    binding.btnSend.isEnabled = true
                    binding.btnSend.setBackgroundResource(R.drawable.bg_send_active)
                    // 显示角色名
                    binding.tvTitle.text = character.name

                    // 初始化主动消息通知渠道并调度 Worker
                    NotificationHelper.createChannel(this@MainActivity)
                    ProactiveService.schedule(this@MainActivity)

                    // 恢复上次对话历史
                    loadConversation()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Python 初始化失败", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = this@MainActivity.getString(R.string.error_init_failed, e.message)
                }
            }
        }
    }

    /** 打开角色选择页面（从左边滑入动画）。 */
    private fun openCharacterSelect() {
        val intent = Intent(this, CharacterSelectActivity::class.java)
        characterSelectLauncher.launch(intent, ActivityOptionsCompat.makeCustomAnimation(
            this, R.anim.slide_in_left, R.anim.slide_out_right
        ))
    }

    /** 将当前角色卡同步到 Python 引擎（角色切换后调用） */
    private fun syncCharacterToPython() {
        if (!::pythonModule.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val character = CharacterStorage.getCurrent(this@MainActivity)
                val charJson = JSONObject().apply {
                    put("name", character.name)
                    put("personality", character.personality)
                    put("speaking_style", character.speakingStyle)
                    put("backstory", character.backstory)
                    put("greeting", character.greeting)
                }.toString()
                pythonModule.callAttr("set_character_card", charJson)
                pythonModule.callAttr("reload_card")
                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = character.name
                    Log.d("MainActivity", "角色卡已同步: ${character.name}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "角色卡同步失败", e)
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty() || !::pythonModule.isInitialized || isStreaming) return

        // 消息长度限制（2000字符，防止 API 异常）
        if (text.length > 2000) {
            android.widget.Toast.makeText(
                this, R.string.toast_message_too_long, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.etInput.text.clear()
        sendMessageStream(text)
    }

    // ======================== 流式消息发送 ========================

    /**
     * 流式发送消息（队列+轮询方案）。
     *
     * 使用 chat_stream_start() 启动后台生成线程，
     * 然后通过 chat_stream_poll() 轮询获取 token 并实时更新 UI。
     * 解决了 Chaquopy 17.0.0 无法迭代 Python 生成器的问题。
     */
    private fun sendMessageStream(userInput: String) {
        isStreaming = true
        disableInput()

        // 添加用户消息气泡
        addUserBubble(userInput)

        // 添加占位 AI 消息（空内容，后续逐 token 填充）
        val aiMsg = Message(content = "", isUser = false)
        val aiMsgIndex = adapter.itemCount
        adapter.addMessage(aiMsg)
        binding.rvMessages.smoothScrollToPosition(aiMsgIndex)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 启动流式对话
                val streamId = pythonModule.callAttr("chat_stream_start", userInput).toString()
                activeStreamId = streamId  // 记录活跃流ID，供 onDestroy 清理

                // 检查是否返回了错误（chat_stream_start 在失败时返回 JSON 错误字符串）
                if (streamId.startsWith("{")) {
                    val errorJson = JSONObject(streamId)
                    runOnUiThread {
                        adapter.updateMessage(
                            aiMsgIndex,
                            aiMsg.copy(content = "[错误] ${errorJson.optString("message", "未知错误")}")
                        )
                    }
                    return@launch
                }

                val fullReply = StringBuilder()
                var isDone = false
                var shouldContinueLoop = true

                while (!isDone && isStreaming && shouldContinueLoop) {
                    val pollResult = pythonModule.callAttr("chat_stream_poll", streamId).toString()
                    val pollJson = JSONObject(pollResult)
                    val status = pollJson.optString("status", "error")
                    Log.d("MainActivity", "stream_poll: status=$status")

                    when (status) {
                        "batch" -> {
                            val events = pollJson.optJSONArray("events")
                            if (events != null) {
                                var batchHasComplete = false
                                var batchErrorMsg: String? = null
                                for (i in 0 until events.length()) {
                                    val eventStr = events.getString(i)
                                    val eventJson = JSONObject(eventStr)
                                    val eventStatus = eventJson.optString("status", "")
                                    when (eventStatus) {
                                        "streaming" -> {
                                            val token = eventJson.optString("token", "")
                                            fullReply.append(token)
                                        }
                                        "done" -> {
                                            batchHasComplete = true
                                            val reply = eventJson.optString("reply", fullReply.toString())
                                            fullReply.clear()
                                            fullReply.append(reply)
                                        }
                                        "error" -> {
                                            batchHasComplete = true
                                            batchErrorMsg = eventJson.optString("message", "未知错误")
                                        }
                                    }
                                }
                                if (batchHasComplete) {
                                    // batch 中包含 done/error 事件，直接处理最终结果
                                    if (batchErrorMsg != null) {
                                        runOnUiThread {
                                            adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = "[错误] $batchErrorMsg"))
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                this@MainActivity.getString(R.string.toast_conversation_failed, batchErrorMsg),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        shouldContinueLoop = false
                                        isDone = true
                                    } else {
                                        // done 事件：处理最终消息（含多段拆分）
                                        val finalContent = fullReply.toString()
                                        val parts = finalContent
                                            .replace("\r\n", "\n")
                                            .split("\\n\\s*\\n".toRegex())
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                        Log.d("MainActivity", "batchDone: parts=${parts.size}, contentLen=${finalContent.length}")
                                        if (parts.size > 1) {
                                            runOnUiThread {
                                                adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = parts[0]))
                                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                                parts.drop(1).forEachIndexed { idx, part ->
                                                    handler.postDelayed({
                                                        adapter.addMessage(Message(content = part, isUser = false))
                                                        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                                    }, ((idx + 1) * 300).toLong())
                                                }
                                                handler.postDelayed({
                                                    saveConversation()
                                                }, (parts.size * 300).toLong())
                                            }
                                        } else {
                                            runOnUiThread {
                                                adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = finalContent))
                                                saveConversation()
                                            }
                                        }
                                        shouldContinueLoop = false
                                        isDone = true
                                    }
                                } else {
                                    // 仅 streaming 事件：流式更新（节流 50ms）
                                    val now = System.currentTimeMillis()
                                    if (now - lastMessageUpdateTime > 50) {
                                        runOnUiThread {
                                            adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = fullReply.toString()))
                                        }
                                        lastMessageUpdateTime = now
                                    }
                                    // 滚动节流：仅当用户在底部时自动滚动
                                    if (now - lastScrollTime > 200) {
                                        runOnUiThread {
                                            if (!binding.rvMessages.canScrollVertically(1)) {
                                                binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                            }
                                        }
                                        lastScrollTime = now
                                    }
                                }
                            }
                        }
                        "done" -> {
                            val reply = pollJson.optString("reply", fullReply.toString())
                            val finalContent = reply.ifEmpty { fullReply.toString() }
                            // 拆分多条消息：AI 用空行分隔的话，拆成多条依次显示
                            val parts = finalContent
                                .replace("\r\n", "\n")
                                .split("\\n\\s*\\n".toRegex())
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            Log.d("MainActivity", "splitMultiMsg: parts=${parts.size}, contentLen=${finalContent.length}")
                            if (parts.size > 1) {
                                runOnUiThread {
                                    adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = parts[0]))
                                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                    parts.drop(1).forEachIndexed { idx, part ->
                                        handler.postDelayed({
                                            adapter.addMessage(Message(content = part, isUser = false))
                                            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                        }, ((idx + 1) * 300).toLong())
                                    }
                                    handler.postDelayed({
                                        saveConversation()
                                    }, (parts.size * 300).toLong())
                                }
                            } else {
                                runOnUiThread {
                                    adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = finalContent))
                                    saveConversation()
                                }
                            }
                            shouldContinueLoop = false
                            isDone = true
                        }
                        "error" -> {
                            val errorMsg = pollJson.optString("message", "未知错误")
                            runOnUiThread {
                                adapter.updateMessage(
                                    aiMsgIndex,
                                    aiMsg.copy(content = "[错误] $errorMsg")
                                )
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    this@MainActivity.getString(R.string.toast_conversation_failed, errorMsg),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            shouldContinueLoop = false
                            isDone = true
                        }
                        "waiting" -> {
                            // 暂无新 token，短暂等待后继续轮询（30ms 间隔）
                            delay(30)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "流式对话失败", e)
                runOnUiThread {
                    adapter.updateMessage(
                        aiMsgIndex,
                        aiMsg.copy(content = "[错误] ${e.message}")
                    )
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        this@MainActivity.getString(R.string.toast_conversation_failed, e.message),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isStreaming = false
                runOnUiThread { enableInput() }
            }
        }
    }

    /** 流式输出期间禁用输入框和发送按钮 */
    private fun disableInput() {
        binding.etInput.isEnabled = false
        binding.btnSend.isEnabled = false
        binding.btnSend.setBackgroundResource(R.drawable.bg_send_inactive_v2)
    }

    /** 流式输出完成后恢复输入框和发送按钮 */
    private fun enableInput() {
        binding.etInput.isEnabled = true
        binding.btnSend.isEnabled = true
        binding.btnSend.setBackgroundResource(R.drawable.bg_send_active)
        // 搜索模式下不抢夺焦点，避免键盘弹出打断搜索
        if (!isSearchMode) {
            binding.etInput.requestFocus()
        }
    }

    /** 保存当前会话消息到会话管理器（原子写入） */
    private fun saveConversation() {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = adapter.getMessages()
                ConversationSessionManager.saveMessages(sessionId, messages)
                Log.d("MainActivity", "会话 $sessionId 已保存，共 ${messages.size} 条消息")
            } catch (e: Exception) {
                Log.e("MainActivity", "保存对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 从会话管理器加载当前会话的消息历史 */
    private fun loadConversation() {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = ConversationSessionManager.loadMessages(sessionId)
                withContext(Dispatchers.Main) {
                    adapter.replaceMessages(messages)
                    if (messages.isNotEmpty()) {
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                    Log.d("MainActivity", "会话 $sessionId 已恢复，共 ${messages.size} 条消息")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "恢复对话失败: ${e.message}")
            }
        }
    }

    /** 显示新建会话对话框 */
    private fun showNewChatDialog() {
        // 弹出输入框让用户输入会话名称
        val input = android.widget.EditText(this).apply {
            hint = "输入会话名称"
            setSingleLine(true)
            // 默认名称：新会话 + 序号
            val sessions = ConversationSessionManager.getSessions()
            val newIndex = sessions.count { it.name.startsWith("新会话") } + 1
            setText("新会话 $newIndex")
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建会话")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "新会话" }
                createNewSession(name)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 自动弹出键盘
        input.postDelayed({
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /** 创建新会话并切换到该会话 */
    private fun createNewSession(name: String) {
        // 在主线程捕获当前消息列表，避免跨线程访问 adapter
        val currentMessages = adapter.getMessages()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 先保存当前会话
                val currentId = ConversationSessionManager.getCurrentSessionId()
                if (currentId.isNotEmpty()) {
                    ConversationSessionManager.saveMessages(currentId, currentMessages)
                }

                // 重置 Python 引擎
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                module?.callAttr("reset")

                // 创建新会话
                val character = CharacterStorage.getCurrent(this@MainActivity)
                val session = ConversationSessionManager.createSession(name, character.id)

                withContext(Dispatchers.Main) {
                    adapter.clear()
                    Toast.makeText(this@MainActivity, "已创建会话「${session.name}」", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "创建会话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ======================== 消息气泡辅助方法 ========================

    private fun addUserBubble(text: String) {
        val msg = Message(
            content = text,
            isUser = true
        )
        adapter.addMessage(msg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
    }

    // ======================== 屏幕适配 ========================

    private fun applyInsets(root: android.view.ViewGroup) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 状态栏顶部间距
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                0
            )
            // 键盘 + 导航栏底部间距 — 施加到内容 LinearLayout 的 margin
            // 注意：手势导航手机 navBar 高度=0，键盘收起时 bottomInset=0 也需要更新
            val contentLayout = (v as? android.view.ViewGroup)?.getChildAt(1) as? android.view.ViewGroup
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            (contentLayout?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != bottomInset) {
                    lp.bottomMargin = bottomInset
                    contentLayout?.requestLayout()
                }
            }
            insets
        }
    }

    // 将触摸事件分发给手势检测器（在子视图消费之前拦截）
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ======================== 对话搜索 ========================

    /** 切换搜索模式（显示/隐藏搜索栏） */
    private fun toggleSearchMode() {
        if (isSearchMode) {
            // 退出搜索模式：隐藏搜索栏，恢复原始消息列表
            isSearchMode = false
            binding.layoutSearch?.visibility = View.GONE
            binding.tvSearchResultCount?.visibility = View.GONE
            binding.etSearch?.setText("")
            adapter.replaceAll(originalMessages.toList())
            hideKeyboard()
            // 滚动到最新消息
            if (adapter.itemCount > 0) {
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
        } else {
            // 进入搜索模式：显示搜索栏，保存当前消息列表
            isSearchMode = true
            originalMessages = adapter.getMessages().toList()
            binding.layoutSearch?.visibility = View.VISIBLE
            binding.etSearch?.requestFocus()
            showKeyboard(binding.etSearch!!)
        }
    }

    /** 显示软键盘 */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 执行搜索（在协程中调用 Python search_conversation） */
    private fun performSearch(keyword: String) {
        if (!::pythonModule.isInitialized) return

        if (keyword.isBlank()) {
            // 空搜索：恢复原始消息列表
            adapter.replaceAll(originalMessages.toList())
            binding.tvSearchResultCount?.visibility = View.GONE
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 构建对话 JSON
                val jsonArray = JSONArray()
                for (msg in originalMessages) {
                    val obj = JSONObject()
                    obj.put("content", msg.content)
                    obj.put("isUser", msg.isUser)
                    obj.put("timestamp", msg.timestamp)
                    jsonArray.put(obj)
                }

                val result = pythonModule.callAttr(
                    "search_conversation", keyword, jsonArray.toString()
                ).toString()
                val resultJson = JSONObject(result)

                if (resultJson.optString("status") == "ok") {
                    val matches = resultJson.optJSONArray("matches") ?: JSONArray()
                    val total = resultJson.optInt("total", 0)

                    val matchedMessages = mutableListOf<Message>()
                    for (i in 0 until matches.length()) {
                        val match = matches.getJSONObject(i)
                        val content = match.optString("content", "")
                        val isUser = match.optBoolean("isUser", false)
                        val timestamp = match.optLong("timestamp", System.currentTimeMillis())
                        matchedMessages.add(
                            Message(content = content, isUser = isUser, timestamp = timestamp)
                        )
                    }

                    withContext(Dispatchers.Main) {
                        adapter.replaceAll(matchedMessages)
                        binding.tvSearchResultCount?.text =
                            getString(R.string.search_result_count, total)
                        binding.tvSearchResultCount?.visibility =
                            if (total > 0) View.VISIBLE else View.GONE
                        if (matchedMessages.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(0)
                        }
                    }
                } else {
                    val errorMsg = resultJson.optString("message", "未知错误")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.toast_search_failed, errorMsg), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "搜索对话失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity, this@MainActivity.getString(R.string.toast_search_failed, e.message), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        // 从当前焦点 View 收起键盘（兼容聊天输入和搜索输入）
        val focusedView = currentFocus
        if (focusedView != null) {
            imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
        } else {
            imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
        }
    }

    // ======================== 对话导出 ========================

    /** 显示导出格式选择对话框。 */
    private fun showExportDialog() {
        val formats = arrayOf(getString(R.string.export_format_json), getString(R.string.export_format_txt))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_export_dialog))
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 导出对话历史到文件并分享。 */
    private fun exportConversation(format: String) {
        if (!::pythonModule.isInitialized) {
            Toast.makeText(this, getString(R.string.toast_engine_not_init_export), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = pythonModule.callAttr("export_history", format).toString()
                val json = JSONObject(result)

                if (json.optString("status") != "ok") {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            this@MainActivity.getString(R.string.toast_export_failed, json.optString("message", "未知错误")),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val content = json.getString("content")
                val filename = json.optString("filename", "对话记录.txt")

                // 保存到 Downloads 目录
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val file = File(downloadsDir, filename)
                file.writeText(content, Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        this@MainActivity.getString(R.string.toast_exported_to, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()

                    // 分享文件
                    try {
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (format == "json") "application/json" else "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.chooser_share_conversation)))
                    } catch (e: Exception) {
                        Log.w("MainActivity", "分享失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "导出失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        this@MainActivity.getString(R.string.toast_export_failed, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ======================== 多会话管理 ========================

    /** 显示会话列表对话框（支持切换/重命名/删除/新建） */
    private fun showSessionListDialog() {
        val sessions = ConversationSessionManager.getSessions()
        val currentId = ConversationSessionManager.getCurrentSessionId()

        if (sessions.isEmpty()) {
            // 无会话时直接提示创建
            showNewChatDialog()
            return
        }

        // 构建会话名称列表（带当前标记）
        val displayNames = sessions.map { session ->
            val marker = if (session.id == currentId) " [当前]" else ""
            val preview = if (session.lastMessage.isNotEmpty()) {
                " — ${session.lastMessage}"
            } else ""
            val time = formatTimestamp(session.updatedAt)
            "${session.name}$marker\n$preview\n$time"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("会话列表")
            .setItems(displayNames) { _, which ->
                val selected = sessions[which]
                if (selected.id != currentId) {
                    switchToSession(selected.id)
                }
            }
            .setPositiveButton("新建会话") { _, _ ->
                showNewChatDialog()
            }
            .setNeutralButton("重命名") { _, _ ->
                // 重命名当前会话
                val currentSession = sessions.find { it.id == currentId }
                if (currentSession != null) {
                    showRenameDialog(currentSession)
                }
            }
            .setNegativeButton("删除") { _, _ ->
                // 删除确认：仅当会话数 > 1 时允许删除
                if (sessions.size <= 1) {
                    Toast.makeText(this, "至少保留一个会话", Toast.LENGTH_SHORT).show()
                    return@setNegativeButton
                }
                // 让用户选择要删除的会话
                showDeleteSessionDialog(sessions, currentId)
            }
            .show()
    }

    /** 显示删除会话选择对话框 */
    private fun showDeleteSessionDialog(
        sessions: List<ConversationSession>,
        currentId: String
    ) {
        val names = sessions.map { s ->
            "${s.name} (${s.messageCount}条消息)" +
            if (s.id == currentId) " [当前]" else ""
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要删除的会话")
            .setItems(names) { _, which ->
                val session = sessions[which]
                showDeleteConfirmDialog(session)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 显示删除确认对话框 */
    private fun showDeleteConfirmDialog(session: ConversationSession) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定要删除「${session.name}」吗？\n该会话的 ${session.messageCount} 条消息将被永久删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteSessionAndSwitch(session.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除会话并自动切换到其他会话 */
    private fun deleteSessionAndSwitch(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ConversationSessionManager.deleteSession(sessionId)

                // 如果删除的是当前会话，切换到另一个会话
                val newId = ConversationSessionManager.getCurrentSessionId()
                if (newId.isNotEmpty() && newId != sessionId) {
                    val messages = ConversationSessionManager.loadMessages(newId)
                    withContext(Dispatchers.Main) {
                        adapter.replaceMessages(messages)
                        if (messages.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(messages.size - 1)
                        }
                        Toast.makeText(this@MainActivity, "会话已删除", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        adapter.clear()
                        Toast.makeText(this@MainActivity, "会话已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "删除会话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 显示重命名对话框 */
    private fun showRenameDialog(session: ConversationSession) {
        val input = android.widget.EditText(this).apply {
            setText(session.name)
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != session.name) {
                    ConversationSessionManager.renameSession(session.id, newName)
                    Toast.makeText(this, "已重命名为「${newName}」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切换到指定会话（保存当前会话，加载目标会话） */
    private fun switchToSession(targetSessionId: String) {
        // 在主线程捕获当前消息列表，避免跨线程访问 adapter
        val currentMessages = adapter.getMessages()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 保存当前会话
                val currentId = ConversationSessionManager.getCurrentSessionId()
                if (currentId.isNotEmpty() && currentId != targetSessionId) {
                    ConversationSessionManager.saveMessages(currentId, currentMessages)
                }

                // 2. 重置 Python 引擎
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("reset")
                } catch (e: Exception) {
                    Log.w("MainActivity", "重置 Python 引擎失败: ${e.message}")
                }

                // 3. 切换会话
                ConversationSessionManager.setCurrentSessionId(targetSessionId)

                // 4. 加载目标会话消息
                val messages = ConversationSessionManager.loadMessages(targetSessionId)

                withContext(Dispatchers.Main) {
                    adapter.replaceMessages(messages)
                    if (messages.isNotEmpty()) {
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                    Log.d("MainActivity", "已切换到会话 $targetSessionId，共 ${messages.size} 条消息")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "切换会话失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "切换会话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 格式化时间戳为可读字符串（今天/昨天/日期）。 */
    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000}小时前"
            diff < 172_800_000 -> "昨天"
            else -> {
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }

    // ======================== 生命周期清理 ========================

    override fun onDestroy() {
        super.onDestroy()
        // 停止流式轮询
        isStreaming = false
        // 清理搜索防抖 Handler
        searchHandler.removeCallbacksAndMessages(null)
        // 清理 Python 流资源
        val sid = activeStreamId
        if (sid != null) {
            try {
                com.chaquo.python.Python.getInstance()
                    .getModule("chat_bridge")
                    .callAttr("chat_stream_cancel", sid)
                Log.d("MainActivity", "onDestroy: 已取消流 $sid")
            } catch (e: Exception) {
                Log.w("MainActivity", "onDestroy: 取消流失败 ${e.message}")
            } finally {
                activeStreamId = null
            }
        }
    }
}