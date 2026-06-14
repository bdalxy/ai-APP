package com.aicompanion.app

import android.app.ActivityOptions
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

// ======================== 搜索相关 ========================
/** 是否处于搜索模式 */
private var isSearchMode = false
/** 进入搜索模式前的原始消息列表（用于退出搜索后恢复） */
private var originalMessages = listOf<Message>()

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
        // 长按设置按钮 → 导出对话
        binding.btnSettings.setOnLongClickListener {
            showExportDialog()
            true
        }

        // 搜索按钮：切换搜索模式
        binding.btnSearch?.setOnClickListener {
            toggleSearchMode()
        }

        // 搜索输入监听（防抖300ms）
        val searchHandler = Handler(Looper.getMainLooper())
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

        // 搜索清除按钮
        binding.btnClearSearch?.setOnClickListener {
            binding.etSearch?.setText("")
        }

        // 点击聊天消息区域空白处收起键盘
        binding.rvMessages.setOnTouchListener { _, _ ->
            hideKeyboard()
            false
        }

        // 点击角色名称或头像，跳转角色选择页（从左边滑入）
        val openCharacterSelect = {
            val intent = Intent(this, CharacterSelectActivity::class.java)
            val options = ActivityOptions.makeCustomAnimation(
                this, R.anim.slide_in_left, R.anim.slide_out_right
            )
            startActivity(intent, options.toBundle())
        }
        binding.tvTitle.setOnClickListener { openCharacterSelect() }
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

                // 4. 加载角色卡
                val character = CharacterStorage.getCurrent(this@MainActivity)
                module.callAttr("set_character_card", character.name, character.personality, character.speakingStyle, character.backstory)

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

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty() || !::pythonModule.isInitialized || isStreaming) return

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

                // 检查是否返回了错误（chat_stream_start 在失败时返回 JSON 错误字符串）
                if (streamId.startsWith("{")) {
                    val errorJson = JSONObject(streamId)
                    runOnUiThread {
                        adapter.updateMessage(
                            aiMsgIndex,
                            aiMsg.copy(content = "[错误] ${errorJson.optString("message", "未知错误")}")
                        )
                        enableInput()
                    }
                    return@launch
                }

                val fullReply = StringBuilder()
                var isDone = false

                while (!isDone && isStreaming) {
                    val pollResult = pythonModule.callAttr("chat_stream_poll", streamId).toString()
                    val pollJson = JSONObject(pollResult)
                    val status = pollJson.optString("status", "error")

                    when (status) {
                        "batch" -> {
                            val events = pollJson.optJSONArray("events")
                            if (events != null) {
                                for (i in 0 until events.length()) {
                                    val eventStr = events.getString(i)
                                    val eventJson = JSONObject(eventStr)
                                    val eventStatus = eventJson.optString("status", "")
                                    if (eventStatus == "streaming") {
                                        val token = eventJson.optString("token", "")
                                        fullReply.append(token)
                                    }
                                }
                                runOnUiThread {
                                    adapter.updateMessage(
                                        aiMsgIndex,
                                        aiMsg.copy(content = fullReply.toString())
                                    )
                                    // 滚动节流：每 200ms 最多滚动一次，减少 CPU 消耗
                                    val now = System.currentTimeMillis()
                                    if (now - lastScrollTime > 200) {
                                        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                                        lastScrollTime = now
                                    }
                                }
                            }
                        }
                        "done" -> {
                            val reply = pollJson.optString("reply", fullReply.toString())
                            runOnUiThread {
                                val finalContent = reply.ifEmpty { fullReply.toString() }
                                adapter.updateMessage(aiMsgIndex, aiMsg.copy(content = finalContent))
                                saveConversation()
                            }
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
        binding.etInput.text.clear()
        binding.btnSend.isEnabled = true
        binding.btnSend.setBackgroundResource(R.drawable.bg_send_active)
        binding.etInput.requestFocus()
    }

    /** 持久化文件名 */
    private val conversationFile: File by lazy {
        File(filesDir, "conversation.json")
    }

    /** 保存当前对话历史到本地 JSON 文件 */
    private fun saveConversation() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = adapter.getMessages()
                val jsonArray = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject()
                    obj.put("content", msg.content)
                    obj.put("isUser", msg.isUser)
                    obj.put("timestamp", msg.timestamp)
                    jsonArray.put(obj)
                }
                conversationFile.writeText(jsonArray.toString(), Charsets.UTF_8)
                Log.d("MainActivity", "对话已保存，共 ${messages.size} 条消息")
            } catch (e: Exception) {
                Log.e("MainActivity", "保存对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 从本地 JSON 文件恢复对话历史 */
    private fun loadConversation() {
        if (!conversationFile.exists()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = conversationFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(json)
                val messages = mutableListOf<Message>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    messages.add(Message(
                        content = obj.getString("content"),
                        isUser = obj.getBoolean("isUser"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    ))
                }
                withContext(Dispatchers.Main) {
                    adapter.replaceAll(messages)
                    if (messages.isNotEmpty()) {
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                    Log.d("MainActivity", "对话已恢复，共 ${messages.size} 条消息")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "恢复对话失败: ${e.message}")
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
}