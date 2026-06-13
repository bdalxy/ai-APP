package com.aicompanion.app

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.databinding.ActivityMainBinding
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
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var gestureDetector: GestureDetector

    /** 等待发送的消息队列 */
    private val pendingMessages = mutableListOf<String>()
    private var isProcessing = false

    /** 打字指示器位置。
     * -1 表示没有正在显示的打字指示器。
     */
    private var typingMsgPosition = -1

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

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        if (text.isEmpty() || !::pythonModule.isInitialized) return
        binding.etInput.text.clear()

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
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun addAIBubble(text: String) {
        val msg = Message(
            content = text,
            isUser = false
        )
        adapter.addMessage(msg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
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
        binding.rvMessages.smoothScrollToPosition(typingMsgPosition)
    }

    private fun hideTypingIndicator() {
        if (typingMsgPosition < 0) return
        adapter.removeTypingAt(typingMsgPosition)
        typingMsgPosition = -1
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
}