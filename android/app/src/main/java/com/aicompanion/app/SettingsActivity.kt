package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicompanion.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设置主页。
 * 整合为六大分组卡片：账号设置、对话设置、记忆设置、语音设置、通知设置、关于。
 * 底部保留世界书、插件管理、导出对话等快捷入口。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private val INTERVAL_OPTIONS = AppConfig.INTERVAL_OPTIONS
        private val INTERVAL_MS = AppConfig.INTERVAL_MS
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.settingsRoot)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // ===== 六大分组卡片 =====
        // 一、账号设置
        binding.cardAccount.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "account"))
        }
        // 二、对话设置
        binding.cardChat.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "chat"))
        }
        // 三、记忆设置
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "memory"))
        }
        // 四、语音设置
        binding.cardVoice.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "voice"))
        }
        // 五、通知设置
        binding.cardNotification.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "notification"))
        }
        // 六、关于
        binding.cardAbout.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "about"))
        }

        // ===== 快捷入口 =====
        // 世界书
        binding.cardWorldBook.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "world_book"))
        }
        // 插件管理
        binding.cardPlugin.setOnClickListener {
            startActivity(Intent(this, PluginManageActivity::class.java))
        }
        // 导出对话
        binding.cardExport.setOnClickListener {
            showExportDialog()
        }

        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        // 账户摘要
        val apiKey = AppConfig.getApiKey(this)
        val apiStatus = if (apiKey.isNotEmpty()) "已配置" else "未配置"
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        binding.tvAccountSummary.text = "$apiStatus · $model"

        // 对话摘要
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> "保守"; temp >= 0.9f -> "创意"; else -> "中等" }
        val maxTk = AppConfig.getMaxTokens(this)
        binding.tvChatSummary.text = "${ctxSize} token · 创意度${tempLabel} · 回复${maxTk}字"

        // 通知摘要（原主动消息）
        val enabled = AppConfig.getProactiveEnabled(this)
        val intervalMs = AppConfig.getProactiveInterval(this)
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "免打扰 $start-$end" else "无免打扰"
        binding.tvNotificationSummary.text = if (enabled) "已开启 · $intervalLabel · $quietLabel" else "已关闭"

        // 记忆摘要、世界书摘要、插件摘要 -- 在后台线程调用 Python
        lifecycleScope.launch(Dispatchers.IO) {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            // 记忆摘要
            try {
                val result = module?.callAttr("get_memory_stats")?.toString() ?: "{}"
                val json = JSONObject(result)
                val count = json.optInt("total", 0)
                withContext(Dispatchers.Main) {
                    binding.tvMemorySummary.text = "${count}条长期记忆"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvMemorySummary.text = "加载中..."
                }
            }

            // 世界书摘要
            try {
                val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
                val json = JSONObject(result)
                val enabled = json.optJSONArray("enabled")
                val count = enabled?.length() ?: 0
                withContext(Dispatchers.Main) {
                    binding.tvWorldBookSummary.text = if (count > 0) "已启用${count}本" else "未启用"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvWorldBookSummary.text = "未启用"
                }
            }

            // 插件摘要
            try {
                val result = module?.callAttr("get_plugin_count")?.toString() ?: "{}"
                val json = JSONObject(result)
                val total = json.optInt("total", 0)
                val enabled = json.optInt("enabled", 0)
                withContext(Dispatchers.Main) {
                    binding.tvPluginSummary.text = if (total > 0) "已安装${total}个 · 已启用${enabled}个" else "暂无插件"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvPluginSummary.text = "暂无插件"
                }
            }
        }

        // 语音摘要（本地读取）
        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) {
            "zh-CN" -> "中文"
            "en-US" -> "English"
            "ja-JP" -> "日本語"
            else -> lang
        }
        binding.tvVoiceSummary.text = if (autoRead) "自动朗读 · ${langLabel}" else "未开启自动朗读 · ${langLabel}"

        // 版本号（关于卡片摘要）
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    // ======================== 对话导出 ========================

    /** 显示导出格式选择对话框。 */
    private fun showExportDialog() {
        val formats = arrayOf(
            getString(R.string.export_format_json),
            getString(R.string.export_format_txt)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.label_choose_export_format))
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 从当前会话加载消息并导出。 */
    private fun exportConversation(format: String) {
        val sessionId = ConversationSessionManager.getCurrentSessionId()
        if (sessionId.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_export_no_messages), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messages = ConversationSessionManager.loadMessages(sessionId)
                if (messages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.toast_export_no_messages),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val character = CharacterStorage.getCurrent(this@SettingsActivity)
                val characterName = character.name

                val content = when (format) {
                    "json" -> ChatExporter.exportToJson(messages, characterName)
                    else -> ChatExporter.exportToTxt(messages, characterName)
                }

                val fileName = ChatExporter.generateFileName(format, characterName)

                val uri = ChatExporter.saveToFile(content, fileName, this@SettingsActivity)
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.toast_export_file_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_export_success),
                        Toast.LENGTH_SHORT
                    ).show()

                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (format == "json") "application/json" else "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                getString(R.string.chooser_share_conversation)
                            )
                        )
                    } catch (e: Exception) {
                        // 分享失败不单独提示
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_export_failed, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
