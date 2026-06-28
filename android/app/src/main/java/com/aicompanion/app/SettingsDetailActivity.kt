package com.aicompanion.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsDetail"
        private val MODEL_OPTIONS = arrayOf("deepseek-v4-flash（快速）", "deepseek-v4-pro（高质量）")
        private val MODEL_VALUES = arrayOf("deepseek-v4-flash", "deepseek-v4-pro")
        private val INTERVAL_OPTIONS = AppConfig.INTERVAL_OPTIONS
        private val INTERVAL_MS = AppConfig.INTERVAL_MS
    }

    internal val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    internal lateinit var contentLayout: LinearLayout

    // ── 备份/恢复文件选择器 ──
    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val success = DataBackupHelper.backup(this@SettingsDetailActivity, uri)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@SettingsDetailActivity, "备份完成", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsDetailActivity, "备份失败，请检查存储空间", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            showRestoreConfirmDialog(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ViewUtils.setupEdgeToEdge(this)
        setContentView(R.layout.activity_settings_detail)
        ViewUtils.applyInsets(findViewById(R.id.detail_root))

        contentLayout = findViewById(R.id.detail_content)

        var type = intent.getStringExtra("type") ?: "account"
        if (type == "proactive") type = "notification"

        val title = when (type) {
            "account" -> getString(R.string.section_account_settings)
            "chat" -> getString(R.string.section_chat_settings)
            "memory" -> getString(R.string.section_memory_settings)
            "voice" -> getString(R.string.section_voice_settings)
            "notification" -> getString(R.string.section_notification_settings)
            "about" -> getString(R.string.section_about)
            "world_book" -> getString(R.string.section_world_book)
            "data_management" -> getString(R.string.section_data_management)
            else -> getString(R.string.title_settings)
        }

        findViewById<TextView>(R.id.tvDetailTitle).text = title
        findViewById<View>(R.id.btnDetailBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnDetailSave).setOnClickListener {
            applyAllParams()
            Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        when (type) {
            "account" -> buildAccountPage()
            "chat" -> buildChatPage()
            "memory" -> buildMemoryPage()
            "voice" -> buildVoicePage()
            "notification" -> buildNotificationPage()
            "about" -> buildAboutPage()
            "world_book" -> buildWorldBookPage()
            "data_management" -> buildDataManagementPage()
        }
    }

    private fun buildAccountPage() {
        val apiKey = AppConfig.getApiKey(this)
        val apiKeyLabel = if (apiKey.isNotEmpty()) "${getString(R.string.status_configured)} (${apiKey.take(4)}...)" else getString(R.string.status_not_configured)
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }

        addSectionTitle(getString(R.string.section_api_config))
        addClickRow(getString(R.string.label_api_key), apiKeyLabel, iconRes = R.drawable.ic_key) { showApiKeyEditDialog() }
        addDivider()
        addSectionTitle(getString(R.string.section_model))
        addClickRow(getString(R.string.label_model_selection), model, iconRes = R.drawable.ic_model) { showModelSelectDialog() }
        addDivider()
        addSectionTitle(getString(R.string.section_language))
        addClickRow(getString(R.string.section_language), getLanguageName(), iconRes = R.drawable.ic_language) { showLanguageDialog() }
        addDivider()
        addSectionTitle(getString(R.string.title_character_manage))
        addClickRow(getString(R.string.title_character_manage), getString(R.string.label_manage_characters), iconRes = R.drawable.ic_settings_account) {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }
    }

    private fun showApiKeyEditDialog() {
        val currentKey = AppConfig.getApiKey(this)
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.hint_api_key_input)
            setText(currentKey)
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_api_key))
            .setView(edit)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val key = edit.text.toString().trim()
                AppConfig.setApiKey(this, key)
                // 使用独立线程同步 Python，避免 recreate() 取消协程
                Thread {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("set_api_key", key)
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_python_sync_failed, e.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
                Toast.makeText(this, getString(R.string.toast_api_key_saved), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showModelSelectDialog() {
        val currentModel = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        val idx = MODEL_VALUES.indexOf(currentModel).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_select_model))
            .setSingleChoiceItems(MODEL_OPTIONS, idx) { dialog, which ->
                val model = MODEL_VALUES[which]
                AppConfig.setModel(this, model)
                applyAllParams()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun buildChatPage() {
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> getString(R.string.temp_label_conservative); temp >= 0.9f -> getString(R.string.temp_label_creative); else -> getString(R.string.temp_label_moderate) }
        val maxTk = AppConfig.getMaxTokens(this)
        val tokenLabel = when (maxTk) { 500 -> getString(R.string.token_label_concise); 2000 -> getString(R.string.token_label_detailed); else -> getString(R.string.token_label_moderate) }
        val dialogues = AppConfig.getExampleDialogues(this)

        addSectionTitle(getString(R.string.section_chat_params))
        addClickRow(getString(R.string.label_context_window), "${ctxSize} token", iconRes = R.drawable.ic_context) { showContextSizeDialog() }
        addDivider()
        addClickRow(getString(R.string.label_temperature), tempLabel, iconRes = R.drawable.ic_creative) { showTemperatureDialog() }
        addDivider()
        addClickRow(getString(R.string.label_max_tokens), tokenLabel, iconRes = R.drawable.ic_detail) { showMaxTokensDialog() }
        addDivider()
        addClickRow(getString(R.string.label_example_dialogues), "${dialogues}条", iconRes = R.drawable.ic_example) { showExampleDialoguesDialog() }
        addDivider()
        addClickRow(getString(R.string.action_new_chat), getString(R.string.label_clear_history), iconRes = R.drawable.ic_new_chat) { showNewChatDialog() }
    }

    private fun showContextSizeDialog() {
        val current = AppConfig.getContextSize(this)
        val minCtx = 500; val maxCtx = 8000; val step = 500
        val steps = (maxCtx - minCtx) / step
        val currentStep = ((current - minCtx).coerceIn(0, maxCtx - minCtx)) / step

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.desc_context_size); textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 12)
        })
        val tvValue = TextView(this).apply {
            text = "${current} token"; textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val etInput = EditText(this).apply {
            setText(current.toString()); inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            setPadding(16, 8, 16, 8)
        }
        val seekBar = SeekBar(this).apply {
            max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = minCtx + progress * step
                    tvValue.text = "${value} token"; etInput.setText(value.toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf(getString(R.string.dialog_label_500), getString(R.string.dialog_label_4000), getString(R.string.dialog_label_8000)).withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8)
        }
        inputRow.addView(TextView(this).apply {
            text = getString(R.string.label_custom_input); textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        })
        inputRow.addView(etInput.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        inputRow.addView(TextView(this).apply {
            text = getString(R.string.suffix_token); textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        })
        layout.addView(inputRow)

        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.label_context_window)).setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                var value = etInput.text.toString().toIntOrNull()
                if (value == null || value < minCtx) value = minCtx
                if (value > maxCtx) value = maxCtx
                value = ((value - minCtx + step / 2) / step) * step + minCtx
                value = value.coerceIn(minCtx, maxCtx)
                AppConfig.setContextSize(this, value)
                applyAllParams()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showTemperatureDialog() {
        val values = floatArrayOf(0.5f, 0.7f, 0.9f)
        val labels = arrayOf(getString(R.string.temp_label_0_5), getString(R.string.temp_label_0_7), getString(R.string.temp_label_0_9))
        val descs = arrayOf(getString(R.string.desc_conservative), getString(R.string.desc_moderate), getString(R.string.desc_creative))
        val current = AppConfig.getTemperature(this)
        val idx = when { current <= 0.5f -> 0; current >= 0.9f -> 2; else -> 1 }
        showSliderDialog(getString(R.string.label_temperature), getString(R.string.desc_temperature), labels, descs, idx, 2) { which ->
            AppConfig.setTemperature(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showMaxTokensDialog() {
        val values = intArrayOf(500, 1000, 2000)
        val labels = arrayOf(getString(R.string.token_label_500), getString(R.string.token_label_1000), getString(R.string.token_label_2000))
        val descs = arrayOf(getString(R.string.desc_concise), getString(R.string.desc_moderate), getString(R.string.desc_detailed))
        val current = AppConfig.getMaxTokens(this)
        val idx = values.toList().indexOf(current).coerceAtLeast(1)
        showSliderDialog(getString(R.string.label_max_tokens), getString(R.string.desc_max_tokens), labels, descs, idx, 2) { which ->
            AppConfig.setMaxTokens(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showExampleDialoguesDialog() {
        val values = intArrayOf(0, 1, 2, 3)
        val labels = arrayOf("0", "1", "2", "3")
        val descs = arrayOf(getString(R.string.label_no_display), getString(R.string.label_one_item), getString(R.string.label_two_items), getString(R.string.label_three_items))
        val current = AppConfig.getExampleDialogues(this)
        val idx = current.coerceIn(0, 3)
        showSliderDialog(getString(R.string.label_example_dialogues), getString(R.string.desc_example_dialogues), labels, descs, idx, 3) { which ->
            AppConfig.setExampleDialogues(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showNewChatDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_new_chat))
            .setMessage(getString(R.string.msg_clear_chat_confirm))
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("reset")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_chat_cleared), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_clear_chat_failed, e.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun buildMemoryPage() {
        val memoryCapacity = AppConfig.getMemoryMaxCount(this)
        val dedupThreshold = AppConfig.getMemoryDedupThreshold(this)
        val decayHalfLife = AppConfig.getMemoryDecayHalfLife(this)

        addSectionTitle(getString(R.string.section_memory_params))
        addClickRow("记忆容量上限", "${memoryCapacity}条", iconRes = R.drawable.ic_settings_memory) {
            showMemoryCapacityDialog()
        }
        addDivider()
        addClickRow("去重相似度阈值", String.format("%.2f", dedupThreshold), iconRes = R.drawable.ic_settings_memory) {
            showDedupThresholdDialog()
        }
        addDivider()
        addClickRow("衰减半衰期", "${decayHalfLife}天", iconRes = R.drawable.ic_settings_memory) {
            showDecayHalfLifeDialog()
        }
        addDivider()
        addSectionTitle(getString(R.string.section_memory_manage_label))
        addClickRow(getString(R.string.label_view_memories), getString(R.string.label_browse_memories), iconRes = R.drawable.ic_settings_memory) {
            startActivity(Intent(this, MemoryManageActivity::class.java))
        }
        addDivider()
        addClickRow(getString(R.string.label_clear_memories), getString(R.string.label_delete_all_memories_data), iconRes = R.drawable.ic_settings_memory) {
            showClearMemoryDialog()
        }
    }

    private fun showClearMemoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_clear_long_term_memory))
            .setMessage(getString(R.string.msg_clear_long_term_memory_confirm))
            .setPositiveButton(getString(R.string.btn_confirm_clear)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("clear_memories")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_memory_cleared), Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_clear_memory_failed, e.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    // ── 记忆参数配置对话框 ──

    private fun showMemoryCapacityDialog() {
        val current = AppConfig.getMemoryMaxCount(this)
        val min = 100; val max = 5000; val step = 100
        val steps = (max - min) / step
        val currentStep = ((current - min).coerceIn(0, max - min)) / step

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = "超出上限时，旧记忆将自动归档或清理（单位：条）"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 12)
        })
        val tvValue = TextView(this).apply {
            text = "${current} 条"; textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    tvValue.text = "${value} 条"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf("100", "2500", "5000").withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)

        MaterialAlertDialogBuilder(this).setTitle("记忆容量上限").setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val value = min + seekBar.progress * step
                AppConfig.setMemoryMaxCount(this, value)
                syncMemoryConfig()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showDedupThresholdDialog() {
        val current = AppConfig.getMemoryDedupThreshold(this)
        val min = 0.3f; val max = 1.0f; val step = 0.05f
        val steps = ((max - min) / step).toInt()
        val currentStep = ((current - min) / step).toInt().coerceIn(0, steps)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = "相似度超过此阈值的记忆将被去重（值越低越严格）"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 12)
        })
        val tvValue = TextView(this).apply {
            text = String.format("%.2f", current); textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    tvValue.text = String.format("%.2f", value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf("0.30", "0.65", "1.00").withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)

        MaterialAlertDialogBuilder(this).setTitle("去重相似度阈值").setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val value = (min + seekBar.progress * step).coerceIn(min, max)
                AppConfig.setMemoryDedupThreshold(this, value)
                syncMemoryConfig()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showDecayHalfLifeDialog() {
        val current = AppConfig.getMemoryDecayHalfLife(this)
        val min = 1; val max = 365; val step = 1
        val steps = max - min
        val currentStep = (current - min).coerceIn(0, steps)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = "记忆权重衰减到一半所需的天数（值越大衰减越慢）"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 12)
        })
        val tvValue = TextView(this).apply {
            text = "${current} 天"; textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress
                    tvValue.text = "${value} 天"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf("1天", "183天", "365天").withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)

        MaterialAlertDialogBuilder(this).setTitle("衰减半衰期").setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val value = (min + seekBar.progress).coerceIn(min, max)
                AppConfig.setMemoryDecayHalfLife(this, value)
                syncMemoryConfig()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun syncMemoryConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val maxCount = AppConfig.getMemoryMaxCount(this@SettingsDetailActivity)
                val dedupThreshold = AppConfig.getMemoryDedupThreshold(this@SettingsDetailActivity).toDouble()
                val decayHalfLife = AppConfig.getMemoryDecayHalfLife(this@SettingsDetailActivity)
                module?.callAttr("set_memory_config", maxCount, dedupThreshold, decayHalfLife)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsDetailActivity, "同步记忆配置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── 恢复确认对话框 ──

    private fun showRestoreConfirmDialog(uri: Uri) {
        // 读取文件名用于显示
        var fileName = "备份文件"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        fileName = cursor.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取备份文件名失败: ${e.message}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("恢复数据")
            .setMessage("将从「$fileName」恢复数据，当前数据将被覆盖。\n恢复完成后建议重启应用。确定继续吗？")
            .setPositiveButton("确认恢复") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = DataBackupHelper.restore(this@SettingsDetailActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@SettingsDetailActivity, "数据已恢复，请重启应用", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@SettingsDetailActivity, "恢复失败，请检查备份文件", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun buildNotificationPage() {
        val enabled = AppConfig.getProactiveEnabled(this)
        val intervalMs = AppConfig.getProactiveInterval(this)
        val intervalIdx = INTERVAL_MS.indexOf(intervalMs)
        val intervalLabel = if (intervalIdx >= 0) {
            INTERVAL_OPTIONS[intervalIdx]
        } else {
            val hours = intervalMs / 3600000.0
            if (hours == hours.toLong().toDouble()) "每${hours.toLong()}小时" else "每${"%.1f".format(hours)}小时"
        }
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else getString(R.string.value_not_set)

        addSectionTitle(getString(R.string.section_proactive_push))
        // 玻璃态开关卡片
        val toggleCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_glass_card)
            setPadding(dip(16), dip(14), dip(16), dip(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dip(10) }
        }
        toggleCard.addView(TextView(this).apply {
            text = getString(R.string.label_proactive_toggle); textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _, isChecked ->
                AppConfig.setProactiveEnabled(this@SettingsDetailActivity, isChecked)
                if (isChecked) ProactiveService.schedule(this@SettingsDetailActivity) else ProactiveService.cancel(this@SettingsDetailActivity)
            }
        }
        toggleCard.addView(sw)
        contentLayout.addView(toggleCard)
        addDivider()

        addClickRow(getString(R.string.label_send_frequency), intervalLabel, iconRes = R.drawable.ic_frequency) {
            val optionsWithCustom = INTERVAL_OPTIONS.toMutableList().apply { add(getString(R.string.option_custom)) }
            val idx = if (INTERVAL_MS.contains(intervalMs)) INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0) else optionsWithCustom.size - 1
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_title_send_frequency))
                .setSingleChoiceItems(optionsWithCustom.toTypedArray(), idx) { dialog, which ->
                    if (which == optionsWithCustom.size - 1) { dialog.dismiss(); showCustomIntervalDialog() }
                    else {
                        AppConfig.setProactiveInterval(this@SettingsDetailActivity, INTERVAL_MS[which])
                        ProactiveService.reschedule(this@SettingsDetailActivity)
                        dialog.dismiss(); recreate()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null).show()
        }
        addDivider()

        addClickRow(getString(R.string.label_quiet_time), quietLabel, iconRes = R.drawable.ic_quiet) {
            val options = arrayOf(getString(R.string.value_not_set), getString(R.string.quiet_time_22_08), getString(R.string.quiet_time_23_07), getString(R.string.quiet_time_00_06), getString(R.string.option_custom))
            val idx = if (quietLabel == getString(R.string.value_not_set)) 0
                else if (quietLabel == getString(R.string.quiet_time_22_08)) 1
                else if (quietLabel == getString(R.string.quiet_time_23_07)) 2
                else if (quietLabel == getString(R.string.quiet_time_00_06)) 3
                else options.size - 1
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_title_quiet_hours))
                .setSingleChoiceItems(options, idx) { dialog, which ->
                    when (which) {
                        0 -> { AppConfig.clearQuietHours(this@SettingsDetailActivity); dialog.dismiss(); recreate() }
                        1, 2, 3 -> {
                            val parts = options[which].split(" - ")
                            AppConfig.setQuietHours(this@SettingsDetailActivity, parts[0].trim(), parts[1].trim())
                            dialog.dismiss(); recreate()
                        }
                        4 -> { dialog.dismiss(); showCustomQuietDialog() }
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null).show()
        }
    }

    private fun showCustomIntervalDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.hint_custom_interval)
            setText("1")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_custom_interval))
            .setMessage(getString(R.string.msg_min_interval))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val hours = input.text.toString().toDoubleOrNull()
                if (hours == null || hours <= 0) { Toast.makeText(this, getString(R.string.toast_invalid_number), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val intervalMs = (hours * 3600000L).toLong()
                if (intervalMs < AppConfig.MIN_INTERVAL_MS) { Toast.makeText(this, getString(R.string.toast_min_interval_warning), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                AppConfig.setProactiveInterval(this@SettingsDetailActivity, intervalMs)
                ProactiveService.reschedule(this@SettingsDetailActivity)
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showCustomQuietDialog() {
        val currentStart = AppConfig.getQuietStart(this)
        val currentEnd = AppConfig.getQuietEnd(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 16)
        }
        val startInput = EditText(this).apply {
            hint = getString(R.string.hint_start_time)
            setText(currentStart)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val endInput = EditText(this).apply {
            hint = getString(R.string.hint_end_time)
            setText(currentEnd)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        layout.addView(TextView(this).apply { text = getString(R.string.label_start_time); textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.text_primary)) })
        layout.addView(startInput)
        layout.addView(TextView(this).apply { text = getString(R.string.label_end_time); textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(0, 16, 0, 0) })
        layout.addView(endInput)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_custom_quiet))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val s = startInput.text.toString().trim()
                val e = endInput.text.toString().trim()
                if (s.isNotEmpty() && e.isNotEmpty() && s.matches(Regex("\\d{1,2}:\\d{2}")) && e.matches(Regex("\\d{1,2}:\\d{2}"))) {
                    AppConfig.setQuietHours(this@SettingsDetailActivity, s, e)
                    recreate()
                } else {
                    Toast.makeText(this, getString(R.string.toast_invalid_time_format), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun buildWorldBookPage() {
        WorldBookSection(this).build()
    }

    private fun buildVoicePage() {
        val speechRate = AppConfig.getTtsSpeechRate(this)
        val pitch = AppConfig.getTtsPitch(this)
        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) { "zh-CN" -> getString(R.string.lang_zh_cn_label); "en-US" -> "English (US)"; "ja-JP" -> "日本語"; else -> lang }
        val voiceTimbre = AppConfig.getTtsVoiceTimbre(this)
        val voiceTimbreLabel = getVoiceTimbreLabel(voiceTimbre)

        addSectionTitle(getString(R.string.section_tts))
        // 显示 TTS 模型类型
        val modelTypeLabel = getTtsModelTypeLabel()
        addClickRow("TTS 模型", modelTypeLabel, iconRes = R.drawable.ic_speed) {
            showTtsModelTypeDialog()
        }
        addDivider()
        addClickRow(getString(R.string.label_tts_speech_rate), "%.1fx".format(speechRate), iconRes = R.drawable.ic_speed) { showTtsRateDialog() }
        addDivider()
        addClickRow(getString(R.string.label_tts_pitch), "%.1f".format(pitch), iconRes = R.drawable.ic_pitch) { showTtsPitchDialog() }
        addDivider()
        addClickRow(getString(R.string.label_tts_voice_timbre), voiceTimbreLabel, iconRes = R.drawable.ic_speed) { showVoiceTimbreDialog() }
        addDivider()

        val toggleCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_glass_card)
            setPadding(dip(16), dip(14), dip(16), dip(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dip(10) }
        }
        toggleCard.addView(TextView(this).apply {
            text = getString(R.string.label_auto_read_aloud); textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = autoRead
            setOnCheckedChangeListener { _, isChecked -> AppConfig.setAutoReadAloud(this@SettingsDetailActivity, isChecked) }
        }
        toggleCard.addView(sw)
        contentLayout.addView(toggleCard)
        addDivider()

        addSectionTitle(getString(R.string.section_asr))
        addClickRow(getString(R.string.label_voice_recognition_lang), langLabel, iconRes = R.drawable.ic_language) { showVoiceLangDialog() }
    }

    private fun showTtsRateDialog() {
        val current = AppConfig.getTtsSpeechRate(this)
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val idx = values.indexOfFirst { it >= current - 0.01f }.coerceAtLeast(0)
        showSliderDialog(getString(R.string.dialog_title_tts_rate), getString(R.string.desc_tts_rate), labels, labels, idx, labels.size - 1) { which ->
            AppConfig.setTtsSpeechRate(this, values[which]); recreate()
        }
    }

    private fun showTtsPitchDialog() {
        val current = AppConfig.getTtsPitch(this)
        val labels = arrayOf("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val idx = values.indexOfFirst { it >= current - 0.01f }.coerceAtLeast(0)
        showSliderDialog(getString(R.string.dialog_title_tts_pitch), getString(R.string.desc_tts_pitch), labels, labels, idx, labels.size - 1) { which ->
            AppConfig.setTtsPitch(this, values[which]); recreate()
        }
    }

    private fun showVoiceLangDialog() {
        val current = AppConfig.getVoiceRecognitionLang(this)
        val options = arrayOf(getString(R.string.lang_zh_cn_label), "English (US)", "日本語")
        val values = arrayOf("zh-CN", "en-US", "ja-JP")
        val idx = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_voice_lang))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                AppConfig.setVoiceRecognitionLang(this, values[which]); dialog.dismiss(); recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    /** 获取音色标签文本 */
    private fun getVoiceTimbreLabel(timbre: String): String {
        return when (timbre) {
            "default" -> getString(R.string.voice_timbre_default)
            "soft" -> getString(R.string.voice_timbre_soft)
            "bright" -> getString(R.string.voice_timbre_bright)
            "deep" -> getString(R.string.voice_timbre_deep)
            else -> getString(R.string.voice_timbre_default)
        }
    }

    /** 显示音色选择对话框 */
    private fun showVoiceTimbreDialog() {
        val current = AppConfig.getTtsVoiceTimbre(this)
        val options = arrayOf(
            getString(R.string.voice_timbre_default),
            getString(R.string.voice_timbre_soft),
            getString(R.string.voice_timbre_bright),
            getString(R.string.voice_timbre_deep)
        )
        val values = arrayOf("default", "soft", "bright", "deep")
        val idx = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_voice_timbre))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                AppConfig.setTtsVoiceTimbre(this, values[which])
                Toast.makeText(this, getString(R.string.toast_voice_timbre_note), Toast.LENGTH_LONG).show()
                dialog.dismiss(); recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun buildAboutPage() {
        addSectionTitle(getString(R.string.section_app_info))
        addClickRow(getString(R.string.label_version), "v${BuildConfig.VERSION_NAME}", iconRes = R.drawable.ic_export) {}
        addDivider()
        addClickRow(getString(R.string.label_crash_logs), getString(R.string.label_view_crash_logs), iconRes = R.drawable.ic_error) {
            startActivity(Intent(this, CrashLogViewerActivity::class.java))
        }
        addDivider()
        addClickRow(getString(R.string.label_open_source_license), "查看开源组件许可", iconRes = R.drawable.ic_export) {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
        addDivider()
        addClickRow(getString(R.string.label_feedback), "向我们反馈问题或建议", iconRes = R.drawable.ic_export) {
            Toast.makeText(this, getString(R.string.toast_feedback_future), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDataManagementPage() {
        addSectionTitle(getString(R.string.section_data_management))
        addClickRow("备份数据", "将所有数据打包为 ZIP 备份文件", iconRes = R.drawable.ic_export) {
            backupLauncher.launch(DataBackupHelper.generateFileName())
        }
        addDivider()
        addClickRow("恢复数据", "从 ZIP 备份文件恢复数据（需重启）", iconRes = R.drawable.ic_export) {
            restoreLauncher.launch(arrayOf("application/zip"))
        }
        addDivider()
        addClickRow("导出对话记录", "将当前对话导出为文件", iconRes = R.drawable.ic_export) { showExportDialog() }
    }

    private fun showExportDialog() {
        val formats = arrayOf(getString(R.string.export_format_json), getString(R.string.export_format_txt))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_choose_export_format))
            .setItems(formats) { _, which ->
                val format = if (which == 0) "json" else "txt"
                exportConversation(format)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
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
                    withContext(Dispatchers.Main) { Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_export_no_messages), Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val character = CharacterStorage.getCurrent(this@SettingsDetailActivity)
                val content = when (format) { "json" -> ChatExporter.exportToJson(messages, character.name); else -> ChatExporter.exportToTxt(messages, character.name) }
                val fileName = ChatExporter.generateFileName(format, character.name)
                val uri = ChatExporter.saveToFile(content, fileName, this@SettingsDetailActivity)
                if (uri == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_export_file_failed), Toast.LENGTH_LONG).show() }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (format == "json") "application/json" else "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.chooser_share_conversation)))
                    } catch (e: Exception) {
                        Log.w(TAG, "分享文件失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun dip(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    internal fun dialogHeight(): Int = (resources.displayMetrics.heightPixels * 0.55).toInt()

    internal fun getSelectableItemBackground(): Int {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    internal fun addSectionTitle(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dip(4), dip(20), dip(4), dip(10))
            paint.isFakeBoldText = true
        })
    }

    internal fun addHintText(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setPadding(dip(4), 0, dip(4), dip(8))
        })
    }

    internal fun addEmptyHint(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setPadding(0, dip(8), 0, dip(8))
            gravity = android.view.Gravity.CENTER
        })
    }

    internal fun addClickRow(label: String, value: String, valueColor: Int = R.color.text_secondary, iconRes: Int = 0, onClick: () -> Unit) {
        // 玻璃态卡片容器
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_card)
            setPadding(dip(16), dip(14), dip(16), dip(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dip(10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        if (iconRes != 0) {
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dip(6))
            }
            labelRow.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dip(22), dip(22)).apply { marginEnd = dip(10) }
                setColorFilter(ContextCompat.getColor(context, R.color.primary))
            })
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                paint.isFakeBoldText = true
            })
            card.addView(labelRow)
        } else {
            card.addView(TextView(this).apply {
                text = label; textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                paint.isFakeBoldText = true
                setPadding(0, 0, 0, dip(6))
            })
        }
        card.addView(TextView(this).apply {
            text = value; textSize = 13f
            setTextColor(ContextCompat.getColor(context, valueColor))
        })
        contentLayout.addView(card)
    }

    internal fun addDivider() {
        // 卡片之间已有间距，分隔线改为更细的留白
        contentLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dip(2)
            )
        })
    }

    internal fun createDividerView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(1))
        setBackgroundColor(ContextCompat.getColor(context, R.color.glass_border))
    }

    private fun showSliderDialog(title: String, subtitle: String, labels: Array<String>, descs: Array<String>, currentIdx: Int, max: Int, onSelected: (Int) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0) }
        layout.addView(TextView(this).apply {
            text = subtitle; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 16)
        })
        val tvValue = TextView(this).apply {
            text = "${labels[currentIdx]} — ${descs[currentIdx]}"; textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 12)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = max; progress = currentIdx; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) { tvValue.text = "${labels[progress]} — ${descs[progress]}" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 0) }
        for (i in 0..max) {
            labelRow.addView(TextView(this).apply {
                text = descs[i]; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; max -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> onSelected(seekBar.progress) }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun applyAllParams() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val ctx = AppConfig.getContextSize(this@SettingsDetailActivity)
                val temp = AppConfig.getTemperature(this@SettingsDetailActivity).toDouble()
                val maxTk = AppConfig.getMaxTokens(this@SettingsDetailActivity)
                val dialogues = AppConfig.getExampleDialogues(this@SettingsDetailActivity)
                val model = AppConfig.getModel(this@SettingsDetailActivity).let { if (it.isBlank()) "deepseek-v4-flash" else it }
                module?.callAttr("apply_params", ctx, temp, maxTk, dialogues, model)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_params_apply_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 获取 TTS 模型类型标签文本 */
    private fun getTtsModelTypeLabel(): String {
        return when (AppConfig.getTtsModelType(this)) {
            AppConfig.TTS_MODEL_VITS -> "VITS (aishell3)"
            AppConfig.TTS_MODEL_MATCHA -> "Matcha (baker)"
            else -> "自动检测（VITS 优先）"
        }
    }

    /** 显示 TTS 模型类型选择对话框 */
    private fun showTtsModelTypeDialog() {
        val current = AppConfig.getTtsModelType(this)
        val options = arrayOf("自动检测（VITS 优先）", "VITS (aishell3)", "Matcha (baker)")
        val values = arrayOf(AppConfig.TTS_MODEL_AUTO, AppConfig.TTS_MODEL_VITS, AppConfig.TTS_MODEL_MATCHA)
        val idx = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("TTS 模型选择")
            .setMessage("切换后需重启应用生效。\n自动检测：优先使用 VITS，不存在时回退 Matcha。")
            .setSingleChoiceItems(options, idx) { dialog, which ->
                AppConfig.setTtsModelType(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun getLanguageName(): String {
        val langCode = LocaleHelper.getCurrentLanguage(this)
        return when (langCode) {
            "zh" -> "中文"
            "en" -> "English"
            "ja" -> "日本語"
            else -> langCode
        }
    }

    private fun showLanguageDialog() {
        val languages = LocaleHelper.SUPPORTED_LANGUAGES
        val langNames = languages.map { it.displayName }.toTypedArray()
        val currentLang = LocaleHelper.getCurrentLanguage(this)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_dialog_title))
            .setSingleChoiceItems(langNames, languages.indexOfFirst { it.code == currentLang }) { dialog, which ->
                val selectedLang = languages[which].code
                if (selectedLang != currentLang) {
                    LocaleHelper.setLanguage(this, selectedLang)
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.language_restart_message), Toast.LENGTH_LONG).show()
                    recreate()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}