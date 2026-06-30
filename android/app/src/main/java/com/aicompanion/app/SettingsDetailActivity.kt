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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsDetail"
        // MODEL_OPTIONS 改为在 showModelSelectDialog 中动态获取资源
        private val MODEL_VALUES = arrayOf("deepseek-v4-flash", "deepseek-v4-pro")
        private val INTERVAL_MS = AppConfig.INTERVAL_MS
    }

    internal val prefs by lazy { SecureStorage.getEncryptedPrefs(this, "app_prefs") }
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
                        Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_backup_complete), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_backup_failed), Toast.LENGTH_SHORT).show()
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
            "display" -> getString(R.string.section_display_settings)
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
            "display" -> buildDisplayPage()
        }
    }

    private fun buildAccountPage() {
        val apiKey = AppConfig.getApiKey(this)
        val apiKeyLabel = if (apiKey.isNotEmpty()) getString(R.string.status_configured) else getString(R.string.status_not_configured)
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
        // 脱敏显示：只显示前4后4位，中间用***替代
        val maskedKey = if (currentKey.length > 8) {
            "${currentKey.take(4)}***${currentKey.takeLast(4)}"
        } else {
            currentKey
        }
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.hint_api_key_input)
            setText(maskedKey)
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_api_key))
            .setView(edit)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                var key = edit.text.toString().trim()
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
                    } finally {
                        key = ""
                    }
                }.start()
                key = ""
                Toast.makeText(this, getString(R.string.toast_api_key_saved), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showModelSelectDialog() {
        val currentModel = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        val idx = MODEL_VALUES.indexOf(currentModel).coerceAtLeast(0)
        val modelOptions = arrayOf(getString(R.string.model_option_fast), getString(R.string.model_option_pro))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_select_model))
            .setSingleChoiceItems(modelOptions, idx) { dialog, which ->
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
        val tempLabel = String.format("%.1f", temp)
        val topP = AppConfig.getTopP(this)
        val freqPenalty = AppConfig.getFrequencyPenalty(this)
        val presPenalty = AppConfig.getPresencePenalty(this)
        val maxTk = AppConfig.getMaxTokens(this)
        val dialogues = AppConfig.getExampleDialogues(this)

        addSectionTitle(getString(R.string.section_chat_params))
        addClickRow(getString(R.string.label_context_window), "${ctxSize} token", iconRes = R.drawable.ic_context) { showContextSizeDialog() }
        addDivider()
        addClickRow(getString(R.string.label_temperature), tempLabel, iconRes = R.drawable.ic_creative) { showTemperatureDialog() }
        addDivider()
        addClickRow(getString(R.string.label_top_p), String.format("%.2f", topP), iconRes = R.drawable.ic_detail) { showTopPDialog() }
        addDivider()
        addClickRow(getString(R.string.label_frequency_penalty), String.format("%.1f", freqPenalty), iconRes = R.drawable.ic_detail) { showFrequencyPenaltyDialog() }
        addDivider()
        addClickRow(getString(R.string.label_presence_penalty), String.format("%.1f", presPenalty), iconRes = R.drawable.ic_detail) { showPresencePenaltyDialog() }
        addDivider()
        addClickRow(getString(R.string.label_max_tokens), "${maxTk} token", iconRes = R.drawable.ic_detail) { showMaxTokensDialog() }
        addDivider()
        addClickRow(getString(R.string.label_example_dialogues), getString(R.string.label_example_dialogues_value_fmt, dialogues), iconRes = R.drawable.ic_example) { showExampleDialoguesDialog() }
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
        showFloatSeekBarDialog(
            getString(R.string.label_temperature), getString(R.string.desc_temperature_slider),
            AppConfig.getTemperature(this), 0.1f, 2.0f, 0.1f, "%.1f"
        ) { value ->
            AppConfig.setTemperature(this, value)
            applyAllParams(); recreate()
        }
    }

    private fun showTopPDialog() {
        showFloatSeekBarDialog(
            getString(R.string.label_top_p), getString(R.string.desc_top_p),
            AppConfig.getTopP(this), 0.0f, 1.0f, 0.05f, "%.2f"
        ) { value ->
            AppConfig.setTopP(this, value)
            applyAllParams(); recreate()
        }
    }

    private fun showFrequencyPenaltyDialog() {
        showFloatSeekBarDialog(
            getString(R.string.label_frequency_penalty), getString(R.string.desc_frequency_penalty),
            AppConfig.getFrequencyPenalty(this), -2.0f, 2.0f, 0.1f, "%.1f"
        ) { value ->
            AppConfig.setFrequencyPenalty(this, value)
            applyAllParams(); recreate()
        }
    }

    private fun showPresencePenaltyDialog() {
        showFloatSeekBarDialog(
            getString(R.string.label_presence_penalty), getString(R.string.desc_presence_penalty),
            AppConfig.getPresencePenalty(this), -2.0f, 2.0f, 0.1f, "%.1f"
        ) { value ->
            AppConfig.setPresencePenalty(this, value)
            applyAllParams(); recreate()
        }
    }

    private fun showMaxTokensDialog() {
        val current = AppConfig.getMaxTokens(this)
        val min = 256; val max = 4096; val step = 256
        val steps = (max - min) / step
        val currentStep = ((current - min).coerceIn(0, max - min)) / step

        val (layout, _, seekBar) = createSeekBarLayout(
            getString(R.string.desc_max_tokens_slider),
            "${current} token",
            steps, currentStep
        ) { progress -> "${min + progress * step} token" }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_max_tokens))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                AppConfig.setMaxTokens(this, min + seekBar.progress * step)
                applyAllParams(); recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
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
        addClickRow(getString(R.string.memory_capacity_title), getString(R.string.memory_capacity_value_fmt, memoryCapacity), iconRes = R.drawable.ic_settings_memory) {
            showMemoryCapacityDialog()
        }
        addDetailLabel(getString(R.string.memory_capacity_desc))
        addDivider()
        addClickRow(getString(R.string.memory_dedup_title), String.format("%.2f", dedupThreshold), iconRes = R.drawable.ic_settings_memory) {
            showDedupThresholdDialog()
        }
        addDetailLabel(getString(R.string.memory_dedup_desc))
        addDivider()
        addClickRow(getString(R.string.memory_decay_title), getString(R.string.memory_decay_value_fmt, decayHalfLife), iconRes = R.drawable.ic_settings_memory) {
            showDecayHalfLifeDialog()
        }
        addDetailLabel(getString(R.string.memory_decay_desc))
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

        val (layout, _, seekBar) = createSeekBarLayout(
            getString(R.string.memory_capacity_desc_full),
            getString(R.string.memory_capacity_dialog_fmt, current),
            steps, currentStep
        ) { progress -> getString(R.string.memory_capacity_dialog_fmt, min + progress * step) }
        addSeekBarLabels(layout, "100", "2500", "5000")

        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.memory_capacity_title)).setView(layout)
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

        val (layout, _, seekBar) = createSeekBarLayout(
            getString(R.string.memory_dedup_desc_full),
            String.format("%.2f", current),
            steps, currentStep
        ) { progress -> String.format("%.2f", min + progress * step) }
        addSeekBarLabels(layout, "0.30", "0.65", "1.00")

        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.memory_dedup_title)).setView(layout)
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

        val (layout, _, seekBar) = createSeekBarLayout(
            getString(R.string.memory_decay_desc_full),
            getString(R.string.memory_decay_dialog_fmt, current),
            steps, currentStep
        ) { progress -> getString(R.string.memory_decay_dialog_fmt, min + progress) }
        addSeekBarLabels(layout,
            getString(R.string.memory_decay_label_1),
            getString(R.string.memory_decay_label_183),
            getString(R.string.memory_decay_label_365))

        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.memory_decay_title)).setView(layout)
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
                    Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_memory_sync_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── 恢复确认对话框 ──

    private fun showRestoreConfirmDialog(uri: Uri) {
        // 读取文件名用于显示
        var fileName = getString(R.string.label_backup_file)
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
            .setTitle(getString(R.string.dialog_restore_title))
            .setMessage(getString(R.string.msg_restore_confirm, fileName))
            .setPositiveButton(getString(R.string.btn_confirm_restore)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = DataBackupHelper.restore(this@SettingsDetailActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_restore_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@SettingsDetailActivity, getString(R.string.toast_restore_failed), Toast.LENGTH_SHORT).show()
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
            AppConfig.getIntervalOptions(this)[intervalIdx]
        } else {
            val hours = intervalMs / 3600000.0
            if (hours == hours.toLong().toDouble()) getString(R.string.notification_interval_hours_fmt, hours.toLong()) else getString(R.string.notification_interval_hours_decimal_fmt, hours)
        }
        val start = AppConfig.getQuietStart(this)
        val end = AppConfig.getQuietEnd(this)
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else getString(R.string.value_not_set)

        addSectionTitle(getString(R.string.section_proactive_push))
        addToggleCard(getString(R.string.label_proactive_toggle), enabled) { isChecked ->
            AppConfig.setProactiveEnabled(this@SettingsDetailActivity, isChecked)
            if (isChecked) ProactiveService.schedule(this@SettingsDetailActivity) else ProactiveService.cancel(this@SettingsDetailActivity)
        }
        addDivider()

        addClickRow(getString(R.string.label_send_frequency), intervalLabel, iconRes = R.drawable.ic_frequency) {
            val optionsWithCustom = AppConfig.getIntervalOptions(this).toMutableList().apply { add(getString(R.string.option_custom)) }
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

    // ==================== 显示设置 ====================

    private fun buildDisplayPage() {
        val fontSize = AppConfig.getFontSize(this)
        val bubbleRadius = AppConfig.getBubbleRadius(this)
        val showTimestamp = AppConfig.getShowTimestamp(this)

        addSectionTitle(getString(R.string.section_display_settings))
        addClickRow(getString(R.string.theme_dark), if (AppConfig.isDarkMode(this)) getString(R.string.status_enabled) else getString(R.string.status_disabled), iconRes = R.drawable.ic_settings_display) {
            showDarkModeDialog()
        }
        addDivider()
        addClickRow(getString(R.string.display_font_size), fontSizeLabel(fontSize), iconRes = R.drawable.ic_settings_memory) {
            showFontSizeDialog()
        }
        addDivider()
        addClickRow(getString(R.string.display_bubble_radius), "${bubbleRadius}dp", iconRes = R.drawable.ic_detail) {
            showBubbleRadiusDialog()
        }
        addDivider()
        addClickRow(getString(R.string.display_timestamp), if (showTimestamp) getString(R.string.label_show) else getString(R.string.label_hide), iconRes = R.drawable.ic_detail) {
            showTimestampDialog()
        }
    }

    private fun fontSizeLabel(size: String): String = when (size) {
        "small" -> getString(R.string.font_small)
        "large" -> getString(R.string.font_large)
        else -> getString(R.string.font_medium)
    }

    private fun showDarkModeDialog() {
        val options = arrayOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_follow_system))
        val current = when {
            AppConfig.isDarkMode(this) -> 1
            AppConfig.isFollowSystem(this) -> 2
            else -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.theme_dark))
            .setSingleChoiceItems(options, current) { dialog, which ->
                when (which) {
                    0 -> {
                        AppConfig.setThemeMode(this, AppConfig.THEME_LIGHT)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                    1 -> {
                        AppConfig.setThemeMode(this, AppConfig.THEME_DARK)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }
                    2 -> {
                        AppConfig.setThemeMode(this, AppConfig.THEME_SYSTEM)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_theme_changed), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showFontSizeDialog() {
        val options = arrayOf(getString(R.string.font_small), getString(R.string.font_medium), getString(R.string.font_large))
        val current = when (AppConfig.getFontSize(this)) {
            "small" -> 0
            "large" -> 2
            else -> 1
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_font_size))
            .setSingleChoiceItems(options, current) { dialog, which ->
                val size = when (which) { 0 -> "small"; 2 -> "large"; else -> "medium" }
                AppConfig.setFontSize(this, size)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showBubbleRadiusDialog() {
        val current = AppConfig.getBubbleRadius(this)
        val min = 4; val max = 24; val step = 2
        val steps = (max - min) / step
        val currentStep = ((current - min).coerceIn(0, max - min)) / step

        val (layout, _, seekBar) = createSeekBarLayout(
            null, "${current}dp", steps, currentStep
        ) { progress -> "${min + progress * step}dp" }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_bubble_radius))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                AppConfig.setBubbleRadius(this, min + seekBar.progress * step)
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showTimestampDialog() {
        val options = arrayOf(getString(R.string.label_show), getString(R.string.label_hide))
        val current = if (AppConfig.getShowTimestamp(this)) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_timestamp))
            .setSingleChoiceItems(options, current) { dialog, which ->
                AppConfig.setShowTimestamp(this, which == 0)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ==================== 世界书 ====================

    private fun buildWorldBookPage() {
        WorldBookSection(this).build()
    }

    private fun buildVoicePage() {
        buildTtsSection()
        buildAsrSection()
    }

    /** 构建 TTS（文字转语音）设置区域 */
    private fun buildTtsSection() {
        val speechRate = AppConfig.getTtsSpeechRate(this)
        val pitch = AppConfig.getTtsPitch(this)
        val autoRead = AppConfig.getAutoReadAloud(this)
        val voiceTimbre = AppConfig.getTtsVoiceTimbre(this)
        val voiceTimbreLabel = getVoiceTimbreLabel(voiceTimbre)

        addSectionTitle(getString(R.string.section_tts))
        val modelTypeLabel = getTtsModelTypeLabel()
        addClickRow(getString(R.string.tts_model_label), modelTypeLabel, iconRes = R.drawable.ic_speed) {
            showTtsModelTypeDialog()
        }
        addDivider()
        val speakerId = AppConfig.getTtsSpeakerId(this)
        val speakerLabel = getSpeakerLabel(speakerId)
        addClickRow(getString(R.string.tts_speaker_label), speakerLabel, iconRes = R.drawable.ic_speed) {
            showSpeakerDialog()
        }
        addDivider()
        addClickRow(getString(R.string.label_tts_speech_rate), "%.1fx".format(speechRate), iconRes = R.drawable.ic_speed) { showTtsRateDialog() }
        addDivider()
        addClickRow(getString(R.string.label_tts_pitch), "%.1f".format(pitch), iconRes = R.drawable.ic_pitch) { showTtsPitchDialog() }
        addDivider()
        addClickRow(getString(R.string.label_tts_voice_timbre), voiceTimbreLabel, iconRes = R.drawable.ic_speed) { showVoiceTimbreDialog() }
        addDivider()

        addToggleCard(getString(R.string.label_auto_read_aloud), autoRead) { isChecked ->
            AppConfig.setAutoReadAloud(this@SettingsDetailActivity, isChecked)
        }
        addDivider()
    }

    /** 构建 ASR（语音识别）设置区域 */
    private fun buildAsrSection() {
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) { "zh-CN" -> getString(R.string.lang_zh_cn_label); "en-US" -> getString(R.string.lang_en_us); "ja-JP" -> getString(R.string.lang_ja_jp); else -> lang }
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
        val options = arrayOf(getString(R.string.lang_zh_cn_label), getString(R.string.lang_en_us), getString(R.string.lang_ja_jp))
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
        addClickRow(getString(R.string.label_open_source_license), getString(R.string.license_view_label), iconRes = R.drawable.ic_export) {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
        addDivider()
        addClickRow(getString(R.string.label_feedback), getString(R.string.feedback_label), iconRes = R.drawable.ic_export) {
            Toast.makeText(this, getString(R.string.toast_feedback_future), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDataManagementPage() {
        addSectionTitle(getString(R.string.section_data_management))
        addClickRow(getString(R.string.data_backup_label), getString(R.string.data_backup_desc), iconRes = R.drawable.ic_export) {
            backupLauncher.launch(DataBackupHelper.generateFileName())
        }
        addDivider()
        addClickRow(getString(R.string.data_restore_label), getString(R.string.data_restore_desc), iconRes = R.drawable.ic_export) {
            restoreLauncher.launch(arrayOf("application/zip"))
        }
        addDivider()
        addClickRow(getString(R.string.data_export_label), getString(R.string.data_export_desc), iconRes = R.drawable.ic_export) { showExportDialog() }
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
                val content = when (format) { "json" -> ChatExporter.exportToJson(messages, character.name, this@SettingsDetailActivity); else -> ChatExporter.exportToTxt(messages, character.name, this@SettingsDetailActivity) }
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

    internal fun addDetailLabel(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setPadding(dip(6), 0, dip(6), dip(6))
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

    /** 添加玻璃态开关卡片（标签 + SwitchCompat） */
    internal fun addToggleCard(label: String, isChecked: Boolean, onChanged: (Boolean) -> Unit) {
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
            text = label; textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchCompat(this).apply {
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        toggleCard.addView(sw)
        contentLayout.addView(toggleCard)
    }

    /**
     * 创建 SeekBar 通用布局（含描述、数值显示、SeekBar）。
     * @return Triple<布局容器, 数值 TextView, SeekBar>
     */
    private fun createSeekBarLayout(
        desc: String?,
        initialValueText: String,
        max: Int,
        progress: Int,
        onProgress: (Int) -> String
    ): Triple<LinearLayout, TextView, SeekBar> {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0) }
        if (desc != null) {
            layout.addView(TextView(this).apply {
                text = desc; textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setPadding(0, 0, 0, 12)
            })
        }
        val tvValue = TextView(this).apply {
            text = initialValueText; textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = max; this.progress = progress; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { tvValue.text = onProgress(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        return Triple(layout, tvValue, seekBar)
    }

    /** 在 SeekBar 布局底部添加 3 个标签（左-中-右） */
    private fun addSeekBarLabels(layout: LinearLayout, left: String, center: String, right: String) {
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf(left, center, right).withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)
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

    private fun showFloatSeekBarDialog(
        title: String, desc: String,
        current: Float, min: Float, max: Float, step: Float, format: String,
        onConfirm: (Float) -> Unit
    ) {
        val steps = ((max - min) / step).toInt()
        val currentStep = (((current - min) / step).toInt()).coerceIn(0, steps)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = desc; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary)); setPadding(0, 0, 0, 12)
        })
        val tvValue = TextView(this).apply {
            text = String.format(format, current); textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)
        val seekBar = SeekBar(this).apply {
            this.max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    tvValue.text = String.format(format, value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                onConfirm(min + seekBar.progress * step)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun applyAllParams() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val ctx = AppConfig.getContextSize(this@SettingsDetailActivity)
                val temp = AppConfig.getTemperature(this@SettingsDetailActivity).toDouble()
                val topP = AppConfig.getTopP(this@SettingsDetailActivity).toDouble()
                val freqPenalty = AppConfig.getFrequencyPenalty(this@SettingsDetailActivity).toDouble()
                val presPenalty = AppConfig.getPresencePenalty(this@SettingsDetailActivity).toDouble()
                val maxTk = AppConfig.getMaxTokens(this@SettingsDetailActivity)
                val dialogues = AppConfig.getExampleDialogues(this@SettingsDetailActivity)
                val model = AppConfig.getModel(this@SettingsDetailActivity).let { if (it.isBlank()) "deepseek-v4-flash" else it }
                module?.callAttr("apply_params", ctx, temp, topP, freqPenalty, presPenalty, maxTk, dialogues, model)
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
            AppConfig.TTS_MODEL_VITS -> getString(R.string.tts_model_vits)
            AppConfig.TTS_MODEL_MATCHA -> getString(R.string.tts_model_matcha)
            else -> getString(R.string.tts_model_auto)
        }
    }

    /** 显示 TTS 模型类型选择对话框 */
    private fun showTtsModelTypeDialog() {
        val current = AppConfig.getTtsModelType(this)
        val options = arrayOf(getString(R.string.tts_model_auto), getString(R.string.tts_model_vits), getString(R.string.tts_model_matcha))
        val values = arrayOf(AppConfig.TTS_MODEL_AUTO, AppConfig.TTS_MODEL_VITS, AppConfig.TTS_MODEL_MATCHA)
        val idx = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_tts_model))
            .setMessage(getString(R.string.tts_model_desc))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                AppConfig.setTtsModelType(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 获取说话人标签文本 */
    private fun getSpeakerLabel(speakerId: Int): String {
        // VITS aishell3 有 174 个说话人（0-173），全部为中文普通话
        return when (speakerId) {
            0 -> getString(R.string.speaker_default)
            in 1..173 -> getString(R.string.speaker_number_fmt, speakerId)
            else -> getString(R.string.speaker_number_fmt, speakerId)
        }
    }

    /** 显示说话人选择对话框 */
    private fun showSpeakerDialog() {
        val currentId = AppConfig.getTtsSpeakerId(this)
        // 提供预设的说话人选项（0-9 为快速选择，其余可输入数字）
        val speakerOptions = arrayOf(
            getString(R.string.speaker_default_0),
            getString(R.string.speaker_number_fmt, 1),
            getString(R.string.speaker_number_fmt, 2), 
            getString(R.string.speaker_number_fmt, 3),
            getString(R.string.speaker_number_fmt, 5),
            getString(R.string.speaker_number_fmt, 10),
            getString(R.string.speaker_number_fmt, 20),
            getString(R.string.speaker_number_fmt, 50),
            getString(R.string.speaker_number_fmt, 100),
            getString(R.string.speaker_number_fmt, 150),
            getString(R.string.speaker_number_fmt, 173),
        )
        val speakerIds = intArrayOf(0, 1, 2, 3, 5, 10, 20, 50, 100, 150, 173)
        val idx = speakerIds.indexOf(currentId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_speaker_title))
            .setMessage(getString(R.string.dialog_speaker_desc))
            .setSingleChoiceItems(speakerOptions, idx) { dialog, which ->
                AppConfig.setTtsSpeakerId(this, speakerIds[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun getLanguageName(): String {
        val langCode = LocaleHelper.getCurrentLanguage(this)
        return when (langCode) {
            "zh" -> getString(R.string.lang_zh)
            "en" -> getString(R.string.lang_en)
            "ja" -> getString(R.string.lang_ja)
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