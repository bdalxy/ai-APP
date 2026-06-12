package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

/**
 * AI 角色扮演聊天主界面 (P3)
 *
 * 通过 Chaquopy 调用 Python chat_bridge 模块，
 * 实现与 AI 角色（小美）的实时对话。
 *
 * P3.1 打字延迟 + 连发消息：
 * - 发送消息后显示"对方正在输入..."，根据消息复杂度变速延迟后调用 Python
 * - 支持连发多条消息，在延迟期间积累的消息合并为一次 API 调用
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pythonModule: com.chaquo.python.PyObject
    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnSettings: TextView
    private lateinit var ivAvatar: ImageView
    private lateinit var adapter: ChatAdapter

    /** 等待发送的消息队列 */
    private val pendingMessages = mutableListOf<String>()
    private var isProcessing = false

    /** 打字指示器位置。
     * -1 表示没有正在显示的打字指示器。
     */
    private var typingMsgPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 适配刘海屏/挖孔屏/状态栏
        setupEdgeToEdge()
        applyInsets(findViewById<android.view.ViewGroup>(R.id.main_root))

        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)
        btnSettings = findViewById(R.id.btnSettings)
        ivAvatar = findViewById(R.id.ivAvatar)

        adapter = ChatAdapter(mutableListOf())
        rvMessages.adapter = adapter
        rvMessages.layoutManager = LinearLayoutManager(this)

        btnSend.setOnClickListener { sendMessage() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 点击角色名称或头像，跳转角色管理页
        tvTitle.setOnClickListener {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }
        ivAvatar.setOnClickListener {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
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
                    tvStatus.text = "正在初始化..."
                }

                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")

                // 1. 注入 API Key
                val apiKey = AppConfig.getApiKey(this@MainActivity)
                if (apiKey.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "初始化失败：API Key 未配置"
                    }
                    return@launch
                }
                module.callAttr("set_api_key", apiKey)

                // 2. 初始化聊天引擎
                val preset = AppConfig.getTokenPreset(this@MainActivity)
                val model = AppConfig.getModel(this@MainActivity)
                module.callAttr("init", preset, model)

                // 3. 初始化记忆系统
                val dbDir = filesDir.absolutePath
                module.callAttr("init_memory", dbDir)

                // 4. 加载角色卡
                val character = CharacterStorage.getCurrent(this@MainActivity)
                module.callAttr("set_character_card", character.name, character.personality, character.speakingStyle, character.backstory)

                withContext(Dispatchers.Main) {
                    pythonModule = module
                    tvStatus.text = ""
                    btnSend.isEnabled = true
                    btnSend.setBackgroundResource(R.drawable.bg_send_active)
                    // 显示角色名
                    tvTitle.text = character.name
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Python 初始化失败", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "初始化失败：${e.message}"
                }
            }
        }
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || !::pythonModule.isInitialized) return
        etInput.text.clear()

        addUserBubble(text)
        showTypingIndicator()
        pendingMessages.add(text)

        if (!isProcessing) processMessages()
    }

    private fun processMessages() {
        if (pendingMessages.isEmpty()) {
            isProcessing = false
            return
        }
        isProcessing = true

        val combinedMessage = pendingMessages.joinToString("\n")
        pendingMessages.clear()

        val delay = calculateDelay(combinedMessage)

        Handler(Looper.getMainLooper()).postDelayed({
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val replyJson = pythonModule.callAttr("chat", combinedMessage).toString()

                    // 解析 JSON 提取 reply 字段
                    val replyText = try {
                        val json = JSONObject(replyJson)
                        if (json.optString("status") == "error") {
                            "错误：${json.optString("message")}"
                        } else {
                            json.optString("reply", "（无回复内容）")
                        }
                    } catch (e: Exception) {
                        // 如果不是 JSON，直接使用原字符串（兼容旧格式）
                        replyJson
                    }

                    // 按双空行拆分为多条
                    val parts = replyText.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }

                    withContext(Dispatchers.Main) {
                        hideTypingIndicator()

                        if (parts.size <= 1) {
                            addAIBubble(replyText.trim())
                        } else {
                            var accumDelay = 0L
                            for ((i, part) in parts.withIndex()) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    addAIBubble(part.trim())
                                }, accumDelay)
                                accumDelay += 800 + Random.nextLong(400, 1200)
                            }
                        }

                        isProcessing = false
                        processMessages()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Python 调用失败", e)
                    withContext(Dispatchers.Main) {
                        hideTypingIndicator()
                        addAIBubble("唔...刚刚走神了，你说什么来着？")
                        isProcessing = false
                        processMessages()
                    }
                }
            }
        }, delay)
    }

    private fun calculateDelay(message: String): Long {
        return when {
            message.length < 5 -> Random.nextLong(1000, 3000)
            message.contains("?") || message.contains("？") -> Random.nextLong(5000, 12000)
            else -> Random.nextLong(3000, 7000)
        }
    }

    private fun addUserBubble(text: String) {
        val msg = Message(
            content = text,
            isUser = true
        )
        adapter.addMessage(msg)
        rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun addAIBubble(text: String) {
        val msg = Message(
            content = text,
            isUser = false
        )
        adapter.addMessage(msg)
        rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun showTypingIndicator() {
        if (typingMsgPosition >= 0) return
        val msg = Message(
            content = "",
            isUser = false,
            isTyping = true
        )
        adapter.addMessage(msg)
        typingMsgPosition = adapter.itemCount - 1
        rvMessages.smoothScrollToPosition(typingMsgPosition)
    }

    private fun hideTypingIndicator() {
        if (typingMsgPosition < 0) return
        adapter.removeTypingAt(typingMsgPosition)
        typingMsgPosition = -1
    }

    // ======================== 屏幕适配 ========================

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    private fun applyInsets(root: ViewGroup) {
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
            val contentLayout = (v as? ViewGroup)?.getChildAt(1) as? ViewGroup
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            (contentLayout?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != bottomInset) {
                    lp.bottomMargin = bottomInset
                    contentLayout?.requestLayout()
                }
            }
            insets
        }
    }
}