package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页面 Activity。
 * 提供账户设置、对话设置、主动消息、记忆管理、关于等入口。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private val TOKEN_PRESETS = arrayOf(
            "聊天体验优先", "短文本指令模式",
            "翻译模式", "长文本理解", "极限性能模式", "自定义"
        )
        private val MODEL_OPTIONS = arrayOf(
            "跟随预设", "deepseek-chat", "deepseek-reasoner"
        )
        private val INTERVAL_OPTIONS = arrayOf(
            "每1小时", "每2小时", "每3小时", "每6小时", "每12小时", "每天"
        )
        private val INTERVAL_MS = longArrayOf(
            3600000L, 7200000L, 10800000L, 21600000L, 43200000L, 86400000L
        )
    }

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 适配刘海屏/挖孔屏/状态栏——解决"靠太上"的问题
        setupEdgeToEdge()
        applyInsets(findViewById(R.id.settings_root))

        // 返回按钮
        findViewById<TextView>(R.id.btnBack)?.setOnClickListener { finish() }

        // —— 账户设置 ——
        setupApiKey()
        setupRolePreset()
        setupModelSelect()

        // —— 对话设置 ——
        setupTokenPreset()
        setupNewChat()
        setupClearMemory()

        // —— 主动消息 ——
        setupProactiveToggle()
        setupProactiveInterval()
        setupQuietTime()

        // —— 记忆管理 ——
        setupMemoryManage()

        refreshUI()
    }

    // ======================== 屏幕适配 ========================

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom + systemBars.bottom
            )
            insets
        }
    }

    // ======================== 账户设置 ========================

    private fun setupApiKey() {
        findViewById<View>(R.id.itemApiKey).setOnClickListener {
            val currentKey = AppConfig.getApiKey(this@SettingsActivity)
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "输入 DeepSeek API Key"
                setText(currentKey)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("API Key")
                .setView(edit)
                .setPositiveButton("保存") { _, _ ->
                    val key = edit.text.toString().trim()
                    // 使用 AppConfig 加密存储 API Key
                    AppConfig.setApiKey(this@SettingsActivity, key)
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("set_api_key", key)
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Python 同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    refreshUI()
                    Toast.makeText(this@SettingsActivity, "API Key 已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupRolePreset() {
        findViewById<View>(R.id.itemRolePreset).setOnClickListener {
            val characters = CharacterStorage.loadAll(this)
            val names = characters.map { it.name }.toTypedArray()
            val currentId = prefs.getString("current_character_id", null)
            val currentIdx = characters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("选择角色")
                .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                    val char = characters[which]
                    CharacterStorage.setCurrent(this, char.id)
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupModelSelect() {
        findViewById<View>(R.id.itemModel).setOnClickListener {
            val current = AppConfig.getModel(this@SettingsActivity).let {
                if (it.isBlank()) MODEL_OPTIONS[0] else it
            }
            val idx = MODEL_OPTIONS.indexOf(current).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("选择模型")
                .setSingleChoiceItems(MODEL_OPTIONS, idx) { dialog, which ->
                    val selected = MODEL_OPTIONS[which]
                    AppConfig.setModel(this@SettingsActivity, selected)
                    // 同步到 Python — 如果选择的是"跟随预设"，不传模型参数
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        val preset = AppConfig.getTokenPreset(this@SettingsActivity)
                        val model = if (selected == "跟随预设") "" else selected
                        module?.callAttr("init", preset, model)
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "切换模型失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ======================== 对话设置 ========================

    private fun setupTokenPreset() {
        findViewById<View>(R.id.itemTokenPreset).setOnClickListener {
            val current = prefs.getString("token_preset", TOKEN_PRESETS[0]) ?: TOKEN_PRESETS[0]
            val idx = TOKEN_PRESETS.indexOf(current).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Token 预设")
                .setSingleChoiceItems(TOKEN_PRESETS, idx) { dialog, which ->
                    val selected = TOKEN_PRESETS[which]
                    prefs.edit().putString("token_preset", selected).apply()
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("set_token_preset", selected)
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "预设切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupNewChat() {
        findViewById<View>(R.id.itemNewChat).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("开始新对话")
                .setMessage("将清空当前对话历史。确定继续吗？")
                .setPositiveButton("确定") { _, _ ->
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("reset")  // 正确的方法名是 reset
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "清空对话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    Toast.makeText(this, "对话已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupClearMemory() {
        findViewById<View>(R.id.itemClearMemory).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空长期记忆")
                .setMessage("将删除所有长期记忆数据，无法恢复。确定吗？")
                .setPositiveButton("确认清空") { _, _ ->
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("clear_memories")  // 正确的方法名是 clear_memories
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "清空记忆失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    Toast.makeText(this, "记忆已清空", Toast.LENGTH_SHORT).show()
                    refreshUI()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ======================== 主动消息 ========================

    private fun setupProactiveToggle() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swProactive)
        sw.isChecked = prefs.getBoolean("proactive_enabled", false)
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("proactive_enabled", isChecked).apply()
        }
    }

    private fun setupProactiveInterval() {
        findViewById<View>(R.id.itemProactiveInterval).setOnClickListener {
            val currentMs = prefs.getLong("proactive_interval", INTERVAL_MS[2])
            val idx = INTERVAL_MS.indexOf(currentMs).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("发送频率")
                .setSingleChoiceItems(INTERVAL_OPTIONS, idx) { dialog, which ->
                    prefs.edit().putLong("proactive_interval", INTERVAL_MS[which]).apply()
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupQuietTime() {
        findViewById<View>(R.id.itemQuietTime).setOnClickListener {
            val start = prefs.getString("quiet_start", "") ?: ""
            val end = prefs.getString("quiet_end", "") ?: ""
            val current = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else "不设置"

            val options = arrayOf("不设置", "22:00 - 08:00", "23:00 - 07:00", "00:00 - 06:00")
            val idx = options.indexOfFirst { it == current || (current != "不设置" && it != "不设置" && it.take(5) == start.take(5)) }.coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("静默时段")
                .setSingleChoiceItems(options, idx) { dialog, which ->
                    if (options[which] == "不设置") {
                        prefs.edit().remove("quiet_start").remove("quiet_end").apply()
                    } else {
                        val parts = options[which].split(" - ")
                        prefs.edit().putString("quiet_start", parts[0].trim()).putString("quiet_end", parts[1].trim()).apply()
                    }
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ======================== 记忆管理 ========================

    private fun setupMemoryManage() {
        findViewById<View>(R.id.itemMemoryManage).setOnClickListener {
            startActivity(Intent(this, MemoryManageActivity::class.java))
        }
    }

    // ======================== UI刷新 ========================

    private fun refreshUI() {
        // API Key 从加密存储读取
        val apiKey = AppConfig.getApiKey(this)
        findViewById<TextView>(R.id.tvApiKeyStatus)?.apply {
            if (apiKey.isNotEmpty()) {
                text = "已配置 (${apiKey.take(4)}...)"
                setTextColor(getColor(android.R.color.holo_green_light))
            } else {
                text = "未配置"
                setTextColor(getColor(android.R.color.holo_red_light))
            }
        }

        val char = CharacterStorage.getCurrent(this)
        findViewById<TextView>(R.id.tvRolePreset)?.text = char.name

        val model = AppConfig.getModel(this@SettingsActivity).let {
            if (it.isBlank()) MODEL_OPTIONS[0] else it
        }
        findViewById<TextView>(R.id.tvModel)?.text = model

        val tokenPreset = prefs.getString("token_preset", TOKEN_PRESETS[0]) ?: TOKEN_PRESETS[0]
        findViewById<TextView>(R.id.tvTokenPreset)?.text = tokenPreset

        val intervalMs = prefs.getLong("proactive_interval", INTERVAL_MS[2])
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        findViewById<TextView>(R.id.tvProactiveInterval)?.text = intervalLabel

        val start = prefs.getString("quiet_start", "") ?: ""
        val end = prefs.getString("quiet_end", "") ?: ""
        findViewById<TextView>(R.id.tvQuietTime)?.text =
            if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else "不设置"

        findViewById<TextView>(R.id.tvVersion)?.text = "v${BuildConfig.VERSION_NAME}"
    }
}
