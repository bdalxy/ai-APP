package com.aicompanion.app

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
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
                    binding.tvStatus.text = "正在初始化..."
                }

                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")

                // 1. 注入 API Key
                val apiKey = AppConfig.getApiKey(this@MainActivity)
                if (apiKey.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "初始化失败：API Key 未配置"
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
                    binding.tvStatus.text = "初始化失败：${e.message}"
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
     * 流式发送消息。
     * 调用 Python chat_bridge.chat_stream()，逐 token 实时更新 UI。
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

        // 在后台线程调用 Python 流式接口（使用协程，受 Lifecycle 管理）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val generator = pythonModule.callAttr("chat_stream", userInput)

                if (generator != null) {
                    val fullReply = StringBuilder()
                    for (item in generator) {
                        val json = JSONObject(item.toString())
                        val status = json.optString("status", "error")

                        when (status) {
                            "streaming" -> {
                                val token = json.optString("token", "")
                                fullReply.append(token)
                                // 在 UI 线程逐 token 更新
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
                            "done" -> {
                                val reply = json.optString("reply", fullReply.toString())
                                runOnUiThread {
                                    val finalContent = reply.ifEmpty { fullReply.toString() }
                                    adapter.updateMessage(
                                        aiMsgIndex,
                                        aiMsg.copy(content = finalContent)
                                    )
                                    saveConversation()
                                }
                            }
                            "error" -> {
                                val errorMsg = json.optString("message", "未知错误")
                                runOnUiThread {
                                    adapter.updateMessage(
                                        aiMsgIndex,
                                        aiMsg.copy(content = "[错误] $errorMsg")
                                    )
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "对话失败: $errorMsg",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        adapter.updateMessage(
                            aiMsgIndex,
                            aiMsg.copy(content = "[错误] Python 返回为空")
                        )
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
                        "对话失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isStreaming = false
                withContext(Dispatchers.Main) { enableInput() }
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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    // ======================== 对话导出 ========================

    /** 显示导出格式选择对话框。 */
    private fun showExportDialog() {
        val formats = arrayOf("JSON (结构化)", "TXT (纯文本)")
        AlertDialog.Builder(this)
            .setTitle("导出对话历史")
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出对话历史到文件并分享。 */
    private fun exportConversation(format: String) {
        if (!::pythonModule.isInitialized) {
            Toast.makeText(this, "引擎未初始化，无法导出", Toast.LENGTH_SHORT).show()
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
                            "导出失败: ${json.optString("message", "未知错误")}",
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
                        "已导出到: ${file.absolutePath}",
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
                        startActivity(Intent.createChooser(shareIntent, "分享对话记录"))
                    } catch (e: Exception) {
                        Log.w("MainActivity", "分享失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "导出失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "导出失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}