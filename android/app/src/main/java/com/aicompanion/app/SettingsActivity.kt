package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicompanion.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设置主页。
 * 卡片点击跳转到独立的设置子页面。
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

        binding.btnBack.setOnClickListener { finish() }

        binding.cardAccount.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "account"))
        }
        binding.cardChat.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "chat"))
        }
        binding.cardProactive.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "proactive"))
        }
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, MemoryManageActivity::class.java))
        }
        binding.cardWorldBook.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "world_book"))
        }
        binding.cardPlugin.setOnClickListener {
            startActivity(Intent(this, PluginManageActivity::class.java))
        }
        binding.cardVoice.setOnClickListener {
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra("type", "voice"))
        }

        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        // 账户摘要（不再显示角色预设）
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

        // 主动消息摘要（统一使用 AppConfig 读取，避免 SharedPreferences 分裂）
        val enabled = AppConfig.getProactiveEnabled(this)
        val intervalMs = AppConfig.getProactiveInterval(this)
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "免打扰 $start-$end" else "无免打扰"
        binding.tvProactiveSummary.text = if (enabled) "已开启 · $intervalLabel · $quietLabel" else "已关闭"

        // 记忆摘要、世界书摘要、插件摘要 -- 在后台线程调用 Python，避免 UI 卡顿
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

        // 语音摘要（本地读取，无需后台线程）
        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) {
            "zh-CN" -> "中文"
            "en-US" -> "English"
            "ja-JP" -> "日本語"
            else -> lang
        }
        binding.tvVoiceSummary.text = if (autoRead) "自动朗读 · ${langLabel}" else "未开启自动朗读 · ${langLabel}"

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }
}