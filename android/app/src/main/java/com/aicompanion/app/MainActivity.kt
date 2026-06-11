package com.aicompanion.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.random.Random
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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

    /** 主线程 Handler，用于打字延迟 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 待发送的消息队列。
     * 用户在打字延迟期间可以连发多条消息，这些消息会被累积并合并为一次 Python 调用。
     */
    private val pendingMessages = mutableListOf<String>()

    /**
     * 当前打字指示器在 RecyclerView 中的位置（adapterPosition）。
     * -1 表示没有正在显示的打字指示器。
     */
    private var typingMsgPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)
        btnReset = findViewById(R.id.btnReset)
        btnSettings = findViewById(R.id.btnSettings)

        adapter = ChatAdapter(mutableListOf())
        rvMessages.adapter = adapter
        rvMessages.layoutManager = LinearLayoutManager(this)

        btnSend.setOnClickListener { sendMessage() }
        btnReset.setOnClickListener { resetConversation() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        lifecycleScope.launch {
            initPython()
        }
    }

    private suspend fun initPython() {
        setStatus("正在初始化...")
        try {
            withContext(Dispatchers.IO) {
                val py = com.chaquo.python.Python.getInstance()
                val module = py.getModule("chat_bridge")
                module.callAttr("initialize")
            }
            isInitialized = true
            setStatus("小美已就绪")
            Log.i(TAG, "Python chat_bridge 初始化成功")
            setupProactiveWorker()
        } catch (e: Exception) {
            isInitialized = false
            setStatus("初始化失败: ${e.message}")
            Log.e(TAG, "Python chat_bridge 初始化失败: ${e.message}")
        }
    }

    /**
     * 配置 WorkManager 定期任务，用于主动消息推送。
     * 如果用户关闭主动消息，则取消已有任务；
     * 如果开启，则按配置的间隔周期调度 ProactiveWorker。
     */
    private fun setupProactiveWorker() {
        val enabled = AppConfig.isProactiveEnabled(this)
        if (!enabled) {
            WorkManager.getInstance(this).cancelUniqueWork("proactive_check")
            return
        }

        val intervalHours = AppConfig.getProactiveInterval(this) // 默认 3
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
            intervalHours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "proactive_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
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
            "主动消息设置",
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
                    4 -> showProactiveSettingsDialog()
                    5 -> showMemoryDialog()
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

    private fun showPresetDialog(
        presetNames: Array<String>,
        presetValues: Array<String>,
        currentIndex: Int
    ) {
        AlertDialog.Builder(this)
            .setTitle("Token 预设")
            .setSingleChoiceItems(presetNames, currentIndex) { dialog, which ->
                dialog.dismiss()
                AppConfig.setTokenPreset(this, presetValues[which])
                setStatus("Token 预设已切换为：${presetNames[which]}，下次对话生效")
                lifecycleScope.launch {
                    isInitialized = false
                    initPython()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val editText = EditText(this)
        editText.hint = "输入 API Key"
        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("修改 API Key")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val key = editText.text.toString().trim()
                if (key.isNotBlank()) {
                    AppConfig.setApiKey(this, key)
                    setStatus("API Key 已保存，下次启动生效")
                    lifecycleScope.launch {
                        isInitialized = false
                        initPython()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCurrentApiKey() {
        val key = AppConfig.getApiKey(this)
        val message = if (key.isNotBlank()) {
            "当前 API Key：${key.substring(0, 4)}****${key.takeLast(4)}"
        } else {
            "尚未配置 API Key"
        }
        AlertDialog.Builder(this)
            .setTitle("当前 API Key")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.text.clear()

        // 添加用户消息
        adapter.addMessage(Message(text, isUser = true))
        rvMessages.scrollToPosition(adapter.itemCount - 1)

        // 注入相关记忆
        injectMemories(text)

        // 开始打字延迟 + 连发逻辑
        startTypingDelay(text)
    }

    /**
     * 打字延迟 + 连发消息机制。
     *
     * 发送消息后：
     * 1. 显示"对方正在输入..."
     * 2. 根据消息复杂度计算延迟时间（1.5s ~ 4s）
     * 3. 延迟期间用户可以继续发送消息，消息会被累积
     * 4. 延迟结束后，将所有累积的消息合并为一次 API 调用
     */
    private fun startTypingDelay(firstMessage: String) {
        pendingMessages.add(firstMessage)

        // 如果已经有一个延迟等待中，只需追加消息，不重新计时
        if (typingMsgPosition >= 0) {
            Log.d(TAG, "已追加到待发送队列: $firstMessage (队列大小=${pendingMessages.size})")
            return
        }

        // 添加打字指示器
        adapter.addMessage(Message("", isUser = false, isTyping = true))
        typingMsgPosition = adapter.itemCount - 1
        rvMessages.scrollToPosition(typingMsgPosition)

        // 根据消息复杂度计算延迟
        val delayMs = calculateTypingDelay(firstMessage)
        Log.d(TAG, "打字延迟: ${delayMs}ms, 消息: $firstMessage")

        handler.postDelayed({
            // 收集所有待发送的消息
            val messages = pendingMessages.toList()
            pendingMessages.clear()

            // 移除打字指示器
            val removed = adapter.removeTypingAt(typingMsgPosition)
            typingMsgPosition = -1

            if (messages.isNotEmpty()) {
                callPythonChat(messages)
            }
        }, delayMs)
    }

    /**
     * 根据消息复杂度计算打字延迟。
     * 短消息 1.5~2s，长消息 2.5~4s。
     */
    private fun calculateTypingDelay(message: String): Long {
        val len = message.length
        return when {
            len <= 10 -> Random.nextLong(1500, 2000)
            len <= 30 -> Random.nextLong(2000, 3000)
            else -> Random.nextLong(2500, 4000)
        }
    }

    /**
     * 调用 Python chat_bridge 发送消息。
     * 将多条累积消息用 "\n" 连接。
     */
    private fun callPythonChat(messages: List<String>) {
        val combined = messages.joinToString("\n")

        lifecycleScope.launch {
            isWaitingReply = true
            setStatus("小美正在思考...")

            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("chat", combined).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Python 调用失败: ${e.message}")
                "[错误] ${e.message}"
            }

            adapter.addMessage(Message(result, isUser = false))
            rvMessages.scrollToPosition(adapter.itemCount - 1)
            isWaitingReply = false
            setStatus("小美已就绪")

            Log.d(TAG, "AI 回复: ${result.take(50)}...")
        }
    }

    /**
     * 注入相关记忆到当前对话上下文。
     */
    private fun injectMemories(userMessage: String) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("inject_memories", userMessage).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "注入记忆失败: ${e.message}")
                null
            }

            if (result != null) {
                val count = extractJsonInt(result, "count")
                if (count > 0) {
                    Log.d(TAG, "已注入 $count 条相关记忆")
                }
            }
        }
    }

    /**
     * 记忆管理 — 打开独立的记忆管理页面。
     */
    private fun showMemoryDialog() {
        startActivity(Intent(this, MemoryManageActivity::class.java))
    }

    /**
     * 主动消息设置主面板。
     * 显示当前状态、频率、静默时段，并提供开关/修改入口。
     */
    private fun showProactiveSettingsDialog() {
        val enabled = AppConfig.isProactiveEnabled(this)
        val interval = AppConfig.getProactiveInterval(this)
        val quietStart = AppConfig.getProactiveQuietStart(this)
        val quietEnd = AppConfig.getProactiveQuietEnd(this)

        val intervalLabels = arrayOf("每1小时", "每3小时", "每6小时", "每12小时")
        val intervalValues = intArrayOf(1, 3, 6, 12)
        val intervalIdx = intervalValues.indexOf(interval).coerceAtLeast(1) // 默认 index 1 = 3小时

        val msg = "当前状态：${if (enabled) "已开启" else "已关闭"}\n" +
                "发送频率：${intervalLabels[intervalIdx]}\n" +
                "静默时段：$quietStart ~ $quietEnd\n\n" +
                "选择操作："

        val actions = arrayOf(
            if (enabled) "关闭主动消息" else "开启主动消息",
            "修改发送频率",
            "修改静默时段"
        )

        AlertDialog.Builder(this)
            .setTitle("主动消息设置")
            .setMessage(msg)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        AppConfig.setProactiveEnabled(this, !enabled)
                        setStatus("主动消息已${if (!enabled) "开启" else "关闭"}")
                        setupProactiveWorker()
                    }
                    1 -> showIntervalDialog(intervalLabels, intervalValues, intervalIdx)
                    2 -> showQuietTimeDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 选择主动消息发送频率。
     */
    private fun showIntervalDialog(labels: Array<String>, values: IntArray, current: Int) {
        AlertDialog.Builder(this)
            .setTitle("选择发送频率")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppConfig.setProactiveInterval(this, values[which])
                setStatus("发送频率已设为：${labels[which]}")
                setupProactiveWorker()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 选择静默时段（不发送主动消息的时间段）。
     */
    private fun showQuietTimeDialog() {
        val options = arrayOf("不设置静默", "23:00 ~ 07:00", "22:00 ~ 08:00", "00:00 ~ 06:00")
        AlertDialog.Builder(this)
            .setTitle("静默时段")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        AppConfig.setProactiveQuietStart(this, "")
                        AppConfig.setProactiveQuietEnd(this, "")
                    }
                    1 -> {
                        AppConfig.setProactiveQuietStart(this, "23:00")
                        AppConfig.setProactiveQuietEnd(this, "07:00")
                    }
                    2 -> {
                        AppConfig.setProactiveQuietStart(this, "22:00")
                        AppConfig.setProactiveQuietEnd(this, "08:00")
                    }
                    3 -> {
                        AppConfig.setProactiveQuietStart(this, "00:00")
                        AppConfig.setProactiveQuietEnd(this, "06:00")
                    }
                }
                setStatus("静默时段已更新")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun resetConversation() {
        AlertDialog.Builder(this)
            .setTitle("新对话")
            .setMessage("开始新对话将清除当前聊天记录")
            .setPositiveButton("确定") { _, _ ->
                adapter.clear()
                pendingMessages.clear()
                typingMsgPosition = -1
                setStatus("新对话已开始")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun extractJsonInt(jsonStr: String, key: String): Int {
        return try {
            org.json.JSONObject(jsonStr).getInt(key)
        } catch (_: Exception) { -1 }
    }

    private fun extractJsonValue(jsonStr: String, key: String): String? {
        return try {
            org.json.JSONObject(jsonStr).optString(key, null)
        } catch (_: Exception) { null }
    }
}