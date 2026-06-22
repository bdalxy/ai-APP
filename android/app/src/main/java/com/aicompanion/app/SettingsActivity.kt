package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicompanion.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

        binding.btnBack.setOnClickListener { finish() }

        binding.cardAccount.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "account"))
        }
        binding.cardChat.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "chat"))
        }
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "memory"))
        }
        binding.cardVoice.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "voice"))
        }
        binding.cardNotification.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "notification"))
        }
        binding.cardAbout.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "about"))
        }

        binding.cardWorldBook.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "world_book"))
        }
        binding.cardPlugin.setOnClickListener {
            startActivity(Intent(this, PluginManageActivity::class.java))
        }
        binding.cardExport.setOnClickListener {
            showExportDialog()
        }

        // 主题切换开关
        val isDark = AppConfig.getThemeMode(this) == AppConfig.THEME_DARK
        binding.switchTheme.isChecked = isDark
        binding.tvDisplaySummary.text = if (isDark) getString(R.string.theme_dark) else getString(R.string.theme_light)
        binding.switchTheme.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            AppCompatDelegate.setDefaultNightMode(mode)
            AppConfig.setThemeMode(this, if (checked) AppConfig.THEME_DARK else AppConfig.THEME_LIGHT)
            binding.tvDisplaySummary.text = if (checked) getString(R.string.theme_dark) else getString(R.string.theme_light)
        }

        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        val apiKey = AppConfig.getApiKey(this)
        val apiStatus = if (apiKey.isNotEmpty()) "已配置" else "未配置"
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        binding.tvAccountSummary.text = "$apiStatus · $model"

        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> "保守"; temp >= 0.9f -> "创意"; else -> "中等" }
        val maxTk = AppConfig.getMaxTokens(this)
        binding.tvChatSummary.text = "${ctxSize} token · 创意度${tempLabel} · 回复${maxTk}字"

        val enabled = AppConfig.getProactiveEnabled(this)
        val intervalMs = AppConfig.getProactiveInterval(this)
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "免打扰 $start-$end" else "无免打扰"
        binding.tvNotificationSummary.text = if (enabled) "已开启 · $intervalLabel · $quietLabel" else "已关闭"

        lifecycleScope.launch(Dispatchers.IO) {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            try {
                val result = module?.callAttr("get_memory_stats")?.toString() ?: "{}"
                val json = JSONObject(result)
                val count = json.optInt("total", 0)
                withContext(Dispatchers.Main) { binding.tvMemorySummary.text = "${count}条长期记忆" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvMemorySummary.text = "加载中..." }
            }

            try {
                val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
                val json = JSONObject(result)
                val enabled = json.optJSONArray("enabled")
                val count = enabled?.length() ?: 0
                withContext(Dispatchers.Main) { binding.tvWorldBookSummary.text = if (count > 0) "已启用${count}本" else "未启用" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvWorldBookSummary.text = "未启用" }
            }

            try {
                val result = module?.callAttr("get_plugin_count")?.toString() ?: "{}"
                val json = JSONObject(result)
                val total = json.optInt("total", 0)
                val enabled = json.optInt("enabled", 0)
                withContext(Dispatchers.Main) { binding.tvPluginSummary.text = if (total > 0) "已安装${total}个 · 已启用${enabled}个" else "暂无插件" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvPluginSummary.text = "暂无插件" }
            }
        }

        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) { "zh-CN" -> "中文"; "en-US" -> "English"; "ja-JP" -> "日本語"; else -> lang }
        binding.tvVoiceSummary.text = if (autoRead) "自动朗读 · ${langLabel}" else "未开启自动朗读 · ${langLabel}"

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun showExportDialog() {
        val formats = arrayOf(getString(R.string.export_format_json), getString(R.string.export_format_txt))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.label_choose_export_format))
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

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
                        Toast.makeText(this@SettingsActivity, getString(R.string.toast_export_no_messages), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val character = CharacterStorage.getCurrent(this@SettingsActivity)
                val characterName = character.name
                val content = when (format) { "json" -> ChatExporter.exportToJson(messages, characterName); else -> ChatExporter.exportToTxt(messages, characterName) }
                val fileName = ChatExporter.generateFileName(format, characterName)
                val uri = ChatExporter.saveToFile(content, fileName, this@SettingsActivity)
                if (uri == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@SettingsActivity, getString(R.string.toast_export_file_failed), Toast.LENGTH_LONG).show() }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (format == "json") "application/json" else "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.chooser_share_conversation)))
                    } catch (e: Exception) {
                        Log.w("SettingsActivity", "分享导出失败: ${e.message}", e)
                        Toast.makeText(this@SettingsActivity, getString(R.string.toast_export_failed, "分享失败"), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.toast_export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}