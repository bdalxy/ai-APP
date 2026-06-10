package com.aicompanion.app

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AICompanion"
        private val MODELS = arrayOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-lite")
        private val MODEL_LABELS = arrayOf("v4-pro (最佳体验)", "v4-flash (平衡)", "v4-lite (最省)")
    }

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnReset: Button
    private lateinit var btnSettings: Button
    private lateinit var adapter: ChatAdapter

    private var isInitialized = false
    private var isWaitingReply = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()

        if (!AppConfig.hasApiKey(this)) {
            showApiKeyDialog()
        } else {
            initPython()
        }
    }

    private fun initViews() {
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)
        btnReset = findViewById(R.id.btnReset)
        btnSettings = findViewById(R.id.btnSettings)

        adapter = ChatAdapter(mutableListOf())
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = adapter

        btnSend.setOnClickListener { sendMessage() }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        btnReset.setOnClickListener {
            lifecycleScope.launch { resetChat() }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "输入 DeepSeek API Key (sk-...)"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("配置 API Key")
            .setMessage("请输入你的 DeepSeek API Key，\n可在 platform.deepseek.com 获取。\n\nKey 仅存储在本机，不会上传。")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确认") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    AppConfig.setApiKey(this, key)
                    initPython()
                } else {
                    setStatus("API Key 不能为空，请重新设置")
                    showApiKeyDialog()
                }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun showSettingsDialog() {
        val currentPreset = AppConfig.getTokenPreset(this)
        val presetNames = arrayOf("聊天体验优先", "平衡", "省Token优先")
        val presetValues = arrayOf("quality", "balanced", "economy")
        val currentIndex = presetValues.indexOf(currentPreset).coerceAtLeast(0)

        val currentModel = AppConfig.getModel(this)
        val modelLabel = if (currentModel.isNotBlank()) {
            val idx = MODELS.indexOf(currentModel)
            if (idx >= 0) MODEL_LABELS[idx] else currentModel
        } else "跟随预设"

        val items = arrayOf(
            "Token 预设：${presetNames[currentIndex]}",
            "模型：$modelLabel",
            "修改 API Key",
            "查看当前 API Key",
            "记忆管理"
        )

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPresetDialog(presetNames, presetValues, currentIndex)
                    1 -> showModelDialog()
                    2 -> showApiKeyDialog()
                    3 -> showCurrentApiKey()
                    4 -> showMemoryDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showModelDialog() {
        val currentModel = AppConfig.getModel(this)
        val currentIdx = if (currentModel.isNotBlank()) {
            MODELS.indexOf(currentModel).coerceAtLeast(0)
        } else -1

        val items = MODEL_LABELS + arrayOf("跟随预设（默认）")

        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setSingleChoiceItems(items, if (currentIdx >= 0) currentIdx else 3) { dialog, which ->
                dialog.dismiss()
                if (which < MODELS.size) {
                    AppConfig.setModel(this, MODELS[which])
                    setStatus("模型已切换为：${MODEL_LABELS[which]}，下次对话生效")
                } else {
                    AppConfig.setModel(this, "")
                    setStatus("模型已切换为：跟随预设，下次对话生效")
                }
                lifecycleScope.launch {
                    isInitialized = false
                    initPython()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPresetDialog(names: Array<String>, values: Array<String>, current: Int) {
        AlertDialog.Builder(this)
            .setTitle("Token 预设")
            .setSingleChoiceItems(names, current) { dialog, which ->
                AppConfig.setTokenPreset(this, values[which])
                dialog.dismiss()
                setStatus("Token 预设已切换为：${names[which]}，下次对话生效")
                lifecycleScope.launch {
                    isInitialized = false
                    initPython()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCurrentApiKey() {
        val key = AppConfig.getApiKey(this)
        val masked = if (key.length > 10) {
            "${key.take(7)}...${key.takeLast(4)}"
        } else {
            key
        }
        AlertDialog.Builder(this)
            .setTitle("当前 API Key")
            .setMessage("$masked\n\nKey 仅存储在本机，不会上传。")
            .setPositiveButton("修改") { _, _ -> showApiKeyDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun initPython() {
        setStatus("正在初始化...")
        disableInput()

        val apiKey = AppConfig.getApiKey(this)
        val preset = AppConfig.getTokenPreset(this)
        val model = AppConfig.getModel(this)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")

                    module.callAttr("set_api_key", apiKey)
                    module.callAttr("init", preset, model).toString()
                }
                Log.i(TAG, "Python 初始化结果 (预设=$preset): ${result.take(80)}")
                isInitialized = true
                setStatus("就绪")
                enableInput()

                val name = extractJsonValue(result, "name")
                if (name != null) tvTitle.text = name

                try {
                    initMemory()
                } catch (e: Exception) {
                    Log.w(TAG, "记忆系统初始化跳过: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Python 初始化失败: ${e.message}", e)
                setStatus("初始化失败: ${e.message}")
                isInitialized = false
                enableInput()
            }
        }
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || !isInitialized || isWaitingReply) return

        adapter.addMessage(Message(text, isUser = true))
        scrollToBottom()
        etInput.text.clear()
        disableInput()
        setStatus("思考中...")
        isWaitingReply = true

        lifecycleScope.launch {
            try {
                try {
                    injectMemories(text)
                } catch (e: Exception) {
                    Log.w(TAG, "记忆注入跳过: ${e.message}")
                }

                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("chat", text).toString()
                }
                Log.d(TAG, "AI 回复: ${result.take(100)}")

                val reply = extractJsonValue(result, "reply")
                val error = extractJsonValue(result, "message")

                if (reply != null) {
                    adapter.addMessage(Message(reply, isUser = false))
                    setStatus("就绪")
                } else if (error != null) {
                    adapter.addMessage(Message("错误: $error", isUser = false))
                    setStatus("出错: $error")
                } else {
                    adapter.addMessage(Message("收到未知回复格式: ${result.take(100)}", isUser = false))
                    setStatus("解析失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "消息发送失败: ${e.message}", e)
                adapter.addMessage(Message("发送失败: ${e.message}", isUser = false))
                setStatus("出错: ${e.message}")
            } finally {
                enableInput()
                isWaitingReply = false
                scrollToBottom()
            }
        }
    }

    private suspend fun resetChat() {
        try {
            val result = withContext(Dispatchers.IO) {
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("chat_bridge")
                module.callAttr("reset").toString()
            }
            setStatus("会话已重置")
        } catch (e: Exception) {
            Log.e(TAG, "重置失败: ${e.message}", e)
        }
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun disableInput() {
        etInput.isEnabled = false
        btnSend.isEnabled = false
    }

    private fun enableInput() {
        etInput.isEnabled = true
        btnSend.isEnabled = true
        etInput.requestFocus()
    }

    private fun scrollToBottom() {
        rvMessages.postDelayed({
            adapter.itemCount.let { count ->
                if (count > 0) rvMessages.smoothScrollToPosition(count - 1)
            }
        }, 100)
    }

    private fun extractJsonValue(text: String, key: String): String? {
        try {
            val obj = org.json.JSONObject(text)
            return obj.optString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析失败 (key=$key): ${e.message}")
            return null
        }
    }

    private fun extractJsonInt(text: String, key: String): Int {
        try {
            val obj = org.json.JSONObject(text)
            return obj.optInt(key, -1)
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析失败 (key=$key): ${e.message}")
            return -1
        }
    }

    private suspend fun initMemory() {
        val dbPath = filesDir.absolutePath
        val result = withContext(Dispatchers.IO) {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("chat_bridge")
            module.callAttr("init_memory", dbPath).toString()
        }
        val count = extractJsonInt(result, "memory_count")
        Log.i(TAG, "记忆系统初始化完成，现有记忆: $count 条")
    }

    private suspend fun injectMemories(query: String) {
        val result = withContext(Dispatchers.IO) {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("chat_bridge")
            module.callAttr("inject_memories", query).toString()
        }
        val count = extractJsonInt(result, "count")
        if (count > 0) {
            val memories = result.let { text ->
                try {
                    val obj = org.json.JSONObject(text)
                    val arr = obj.optJSONArray("memories")
                    if (arr != null) {
                        (0 until arr.length()).map { arr.getString(it) }
                    } else emptyList()
                } catch (e: Exception) { emptyList() }
            }
            Log.i(TAG, "已注入 $count 条记忆到 System Prompt")
        }
    }

    private fun showMemoryDialog() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("get_memory_stats").toString()
                }
                val total = extractJsonInt(result, "total")
                val status = extractJsonValue(result, "status")

                if (status == "error") {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("记忆管理")
                        .setMessage("记忆系统未初始化\n\n请先进行对话以初始化记忆系统。")
                        .setPositiveButton("确定", null)
                        .show()
                    return@launch
                }

                val message = if (total > 0) {
                    "当前共有 $total 条记忆"
                } else {
                    "暂无记忆。\n\n进行对话后，系统会自动提取并存储用户的个人信息和偏好。"
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("记忆管理")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .setNegativeButton(if (total > 0) "清除所有记忆" else null) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val py = com.chaquo.python.Python.getInstance()
                                    val module = py.getModule("chat_bridge")
                                    module.callAttr("reset_memories").toString()
                                }
                                setStatus("记忆已清除")
                            } catch (e: Exception) {
                                Log.e(TAG, "清除记忆失败: ${e.message}", e)
                            }
                        }
                    }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "获取记忆统计失败: ${e.message}", e)
            }
        }
    }
}