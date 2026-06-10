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

/**
 * AI 角色扮演聊天主界面 (P3)
 *
 * 通过 Chaquopy 调用 Python chat_bridge 模块，
 * 实现与 AI 角色（小美）的实时对话。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AICompanion"
        // 可用模型列表
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

    // ========================================================================
    // API Key 输入对话框
    // ========================================================================

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

    // ========================================================================
    // 设置对话框（Token 预设 + API Key 修改）
    // ========================================================================

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
                // 重新初始化以应用新预设
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

    // ========================================================================
    // Python 初始化
    // ========================================================================

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

                // 初始化记忆系统
                try {
                    initMemory()
                } catch (e: Exception) {
                    Log.w(TAG, "记忆系统初始化跳过: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Python 初始化失败: ${e.message}", e)
                setStatus("初始化失败: ${e.message}")
                isInitialized = false
                enableInput()  // 即使失败也启用输入，让用户能操作设置
            }
        }
    }

    // ========================================================================
    // 消息发送
    // ========================================================================

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
                // 检索相关记忆并注入 System Prompt
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
                    adapter.addMessage(Message("[错误] $error", isUser = false))
                    setStatus("错误: $error")
                } else {
                    adapter.addMessage(Message("[错误] 无法解析回复", isUser = false))
                    setStatus("解析错误")
                }
                scrollToBottom()
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败: ${e.message}", e)
                adapter.addMessage(Message("[错误] ${e.message}", isUser = false))
                setStatus("错误: ${e.message}")
                scrollToBottom()
            } finally {
                isWaitingReply = false
                enableInput()
            }
        }
    }

    // ========================================================================
    // 新对话
    // ========================================================================

    private suspend fun resetChat() {
        try {
            withContext(Dispatchers.IO) {
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("chat_bridge")
                module.callAttr("reset")
            }
            adapter.clear()
            setStatus("新对话已开始")
            Log.i(TAG, "对话已重置")
        } catch (e: Exception) {
            Log.e(TAG, "重置失败: ${e.message}", e)
            setStatus("重置失败: ${e.message}")
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private fun setStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun disableInput() {
        runOnUiThread {
            etInput.isEnabled = false
            btnSend.isEnabled = false
        }
    }

    private fun enableInput() {
        runOnUiThread {
            etInput.isEnabled = true
            btnSend.isEnabled = true
        }
    }

    private fun scrollToBottom() {
        rvMessages.post {
            rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun extractJsonValue(text: String, key: String): String? {
        return try {
            val obj = org.json.JSONObject(text)
            if (obj.has(key)) obj.getString(key) else null
        } catch (e: Exception) {
            Log.w(TAG, "extractJsonValue 解析失败 (key=$key): ${e.message}")
            null
        }
    }

    private fun extractJsonInt(text: String, key: String): Int {
        return try {
            val obj = org.json.JSONObject(text)
            obj.optInt(key, -1)
        } catch (e: Exception) {
            Log.w(TAG, "extractJsonInt 解析失败 (key=$key): ${e.message}")
            -1
        }
    }

    // ========================================================================
    // 记忆系统
    // ========================================================================

    /**
     * 初始化记忆系统（在 initPython 成功后调用）。
     * suspend 函数，与 initPython 在同一协程中执行。
     */
    private suspend fun initMemory() {
        try {
            val result = withContext(Dispatchers.IO) {
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("chat_bridge")
                // 传目录路径（filesDir），Python 端会在目录下创建 memories.db
                module.callAttr("init_memory", filesDir.absolutePath).toString()
            }
            // 检查 status 字段，区分成功和失败
            val status = extractJsonValue(result, "status")
            if (status == "ok") {
                val count = extractJsonInt(result, "memory_count")
                Log.i(TAG, "记忆系统初始化成功，已有 $count 条记忆")
                if (count > 0) {
                    setStatus("就绪 (已有 $count 条记忆)")
                }
            } else {
                val errorMsg = extractJsonValue(result, "message") ?: "未知错误"
                Log.w(TAG, "记忆系统初始化失败: $errorMsg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "记忆系统初始化失败（对话仍可用）: ${e.message}")
        }
    }

    /**
     * 检索相关记忆并注入到 System Prompt（在 sendMessage 前调用）。
     */
    private suspend fun injectMemories(queryText: String) {
        val result = withContext(Dispatchers.IO) {
            try {
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("chat_bridge")
                module.callAttr("inject_memories", queryText).toString()
            } catch (e: Exception) {
                Log.w(TAG, "记忆检索失败: ${e.message}")
                null
            }
        }
        if (result != null) {
            val count = extractJsonInt(result, "count")
            if (count > 0) {
                Log.d(TAG, "已注入 $count 条相关记忆")
            }
        }
    }

    /**
     * 记忆管理对话框。
     */
    private fun showMemoryDialog() {
        lifecycleScope.launch {
            val stats = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("get_memory_stats").toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取记忆统计失败: ${e.message}")
                null
            }

            val total = if (stats != null) extractJsonInt(stats, "total") else -1
            val message = if (stats != null && total >= 0) {
                val byTypeStr = try {
                    val obj = org.json.JSONObject(stats)
                    val byType = obj.getJSONObject("by_type")
                    val sb = StringBuilder()
                    byType.keys().forEach { key ->
                        sb.append("  $key: ${byType.getInt(key)}\n")
                    }
                    sb.toString()
                } catch (_: Exception) { "" }
                "总记忆数：$total 条\n\n按类型分布：\n$byTypeStr"
            } else {
                "记忆系统未初始化\n\n请确保 API Key 已配置并重启 APP。"
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("记忆管理")
                .setMessage(message)
                .setPositiveButton("同步统计") { _, _ -> setStatus("记忆统计已更新") }
                .setNeutralButton("重置记忆") { _, _ -> resetMemories() }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun resetMemories() {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("reset_memories").toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "重置记忆失败: ${e.message}")
                null
            }
            val deleted = if (result != null) extractJsonInt(result, "deleted") else -1
            if (deleted >= 0) {
                setStatus("已清除 $deleted 条记忆")
                Log.i(TAG, "记忆已重置，删除了 $deleted 条")
            } else {
                Log.w(TAG, "记忆重置失败: $result")
            }
        }
    }
}