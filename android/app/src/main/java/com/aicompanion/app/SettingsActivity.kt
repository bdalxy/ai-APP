package com.aicompanion.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.aicompanion.app.plugin.PluginRegistry
import com.aicompanion.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private val INTERVAL_OPTIONS = AppConfig.INTERVAL_OPTIONS
        private val INTERVAL_MS = AppConfig.INTERVAL_MS
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.settingsRoot)

        binding.btnBack.setOnClickListener { finish() }

        binding.cardCharacter.setOnClickListener {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }
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
        binding.cardDisplay.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "display"))
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
        binding.cardDataManagement.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "data_management"))
        }
        binding.cardExport.setOnClickListener {
            showExportDialog()
        }
        // 语言设置已移到「账户设置」详情页
        binding.cardLanguage.visibility = android.view.View.GONE
        binding.cardLanguage.setOnClickListener(null)

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
        val character = CharacterStorage.getCurrent(this)
        binding.tvCharacterSummary.text = character.name.ifEmpty { getString(R.string.character_option_3) }

        val apiKey = AppConfig.getApiKey(this)
        val apiStatus = if (apiKey.isNotEmpty()) getString(R.string.status_configured) else getString(R.string.status_not_configured)
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        binding.tvAccountSummary.text = "$apiStatus · $model"

        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> getString(R.string.temp_conservative); temp >= 0.9f -> getString(R.string.temp_creative); else -> getString(R.string.temp_moderate) }
        val maxTk = AppConfig.getMaxTokens(this)
        binding.tvChatSummary.text = getString(R.string.summary_chat_format, ctxSize.toString(), tempLabel, maxTk.toString())

        val enabled = AppConfig.getProactiveEnabled(this)
        val intervalMs = AppConfig.getProactiveInterval(this)
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) getString(R.string.summary_quiet_time_format, start, end) else getString(R.string.summary_quiet_time_none)
        binding.tvNotificationSummary.text = if (enabled) getString(R.string.summary_proactive_on_format, intervalLabel, quietLabel) else getString(R.string.status_disabled)

        lifecycleScope.launch(Dispatchers.IO) {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            try {
                val result = module?.callAttr("get_memory_stats")?.toString() ?: "{}"
                val json = JSONObject(result)
                val count = json.optInt("total", 0)
                withContext(Dispatchers.Main) { binding.tvMemorySummary.text = getString(R.string.summary_memory_format, count) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvMemorySummary.text = getString(R.string.status_loading) }
            }

            try {
                val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
                val json = JSONObject(result)
                val enabled = json.optJSONArray("enabled")
                val count = enabled?.length() ?: 0
                withContext(Dispatchers.Main) { binding.tvWorldBookSummary.text = if (count > 0) getString(R.string.summary_world_book_enabled, count) else getString(R.string.status_not_enabled) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvWorldBookSummary.text = getString(R.string.status_not_enabled) }
            }

            try {
                val result = module?.callAttr("get_plugin_count")?.toString() ?: "{}"
                val json = JSONObject(result)
                val pyTotal = json.optInt("total", 0)
                val pyEnabled = json.optInt("enabled", 0)
                val nativeTotal = PluginRegistry.getPluginCount()
                val nativeEnabled = PluginRegistry.getEnabledPluginCount()
                val total = pyTotal + nativeTotal
                val enabled = pyEnabled + nativeEnabled
                withContext(Dispatchers.Main) { binding.tvPluginSummary.text = if (total > 0) getString(R.string.summary_plugin_format, total, enabled) else getString(R.string.status_no_plugins) }
            } catch (e: Exception) {
                val nativeTotal = PluginRegistry.getPluginCount()
                val nativeEnabled = PluginRegistry.getEnabledPluginCount()
                withContext(Dispatchers.Main) { binding.tvPluginSummary.text = if (nativeTotal > 0) getString(R.string.summary_plugin_format, nativeTotal, nativeEnabled) else getString(R.string.status_no_plugins) }
            }
        }

        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) { "zh-CN" -> getString(R.string.lang_zh); "en-US" -> getString(R.string.lang_en); "ja-JP" -> getString(R.string.lang_ja); else -> lang }
        binding.tvVoiceSummary.text = if (autoRead) getString(R.string.summary_voice_auto_read_on, langLabel) else getString(R.string.summary_voice_auto_read_off, langLabel)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun showExportDialog() {
        val formats = arrayOf(getString(R.string.export_format_json), getString(R.string.export_format_txt))
        MaterialAlertDialogBuilder(this)
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