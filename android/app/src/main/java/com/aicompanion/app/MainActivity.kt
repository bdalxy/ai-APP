package com.aicompanion.app

import android.os.Bundle
import android.util.Log
import android.view.View
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
    }

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnReset: Button
    private lateinit var adapter: ChatAdapter

    private var isInitialized = false
    private var isWaitingReply = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initPython()
    }

    private fun initViews() {
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)
        btnReset = findViewById(R.id.btnReset)

        adapter = ChatAdapter(mutableListOf())
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = adapter

        // 发送按钮
        btnSend.setOnClickListener { sendMessage() }

        // 键盘发送键
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // 新对话按钮
        btnReset.setOnClickListener {
            lifecycleScope.launch {
                resetChat()
            }
        }
    }

    // ========================================================================
    // Python 初始化
    // ========================================================================

    private fun initPython() {
        setStatus("正在初始化...")
        disableInput()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("init").toString()
                }
                Log.i(TAG, "Python 初始化结果: $result")
                isInitialized = true
                setStatus("就绪")
                enableInput()

                // 解析角色卡信息
                val name = extractJsonValue(result, "name")
                if (name != null) {
                    tvTitle.text = name
                }
            } catch (e: Exception) {
                Log.e(TAG, "Python 初始化失败: ${e.message}", e)
                setStatus("初始化失败: ${e.message}")
                isInitialized = false
            }
        }
    }

    // ========================================================================
    // 消息发送
    // ========================================================================

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || !isInitialized || isWaitingReply) return

        // 显示用户消息
        adapter.addMessage(Message(text, isUser = true))
        scrollToBottom()
        etInput.text.clear()
        disableInput()
        setStatus("思考中...")
        isWaitingReply = true

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("chat", text).toString()
                }
                Log.d(TAG, "AI 回复: $result")

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

    /**
     * 从 Python 返回的 dict str 中提取字符串值。
     * 简单解析 'key': 'value' 模式。
     */
    private fun extractJsonValue(text: String, key: String): String? {
        val pattern = "'$key':\\s*'([^']*)'".toRegex()
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }
}