package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
        private val MODEL_OPTIONS = arrayOf("deepseek-v4-flash（快速）", "deepseek-v4-pro（高质量）")
        private val MODEL_VALUES = arrayOf("deepseek-v4-flash", "deepseek-v4-pro")
        private val INTERVAL_OPTIONS = AppConfig.INTERVAL_OPTIONS
        private val INTERVAL_MS = AppConfig.INTERVAL_MS
    }

    internal val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    internal lateinit var contentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ViewUtils.setupEdgeToEdge(this)
        setContentView(R.layout.activity_settings_detail)

        contentLayout = findViewById(R.id.detail_content)

        var type = intent.getStringExtra("type") ?: "account"
        if (type == "proactive") type = "notification"

        val title = when (type) {
            "account" -> "账户设置"
            "chat" -> "对话设置"
            "memory" -> "记忆设置"
            "voice" -> "语音设置"
            "notification" -> "通知设置"
            "about" -> "关于"
            "world_book" -> "世界书"
            else -> "设置"
        }

        findViewById<TextView>(R.id.tvDetailTitle).text = title
        findViewById<View>(R.id.btnDetailBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnDetailSave).setOnClickListener {
            applyAllParams()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
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
        }
    }

    private fun buildAccountPage() {
        val apiKey = AppConfig.getApiKey(this)
        val apiKeyLabel = if (apiKey.isNotEmpty()) "已配置 (${apiKey.take(4)}...)" else "未配置"
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }

        addSectionTitle("API 配置")
        addClickRow("API Key", apiKeyLabel, iconRes = R.drawable.ic_key) { showApiKeyEditDialog() }
        addDivider()
        addSectionTitle("模型")
        addClickRow("模型选择", model, iconRes = R.drawable.ic_model) { showModelSelectDialog() }
        addDivider()
        addSectionTitle("角色管理")
        addClickRow("角色管理", "管理角色卡", iconRes = R.drawable.ic_settings_account) {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }
    }

    private fun showApiKeyEditDialog() {
        val currentKey = AppConfig.getApiKey(this)
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "输入 DeepSeek API Key"
            setText(currentKey)
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("API Key")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val key = edit.text.toString().trim()
                AppConfig.setApiKey(this, key)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("set_api_key", key)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, "Python 同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModelSelectDialog() {
        val currentModel = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        val idx = MODEL_VALUES.indexOf(currentModel).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择模型")
            .setSingleChoiceItems(MODEL_OPTIONS, idx) { dialog, which ->
                val model = MODEL_VALUES[which]
                AppConfig.setModel(this, model)
                applyAllParams()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildChatPage() {
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> "0.5 保守"; temp >= 0.9f -> "0.9 创意"; else -> "0.7 中等" }
        val maxTk = AppConfig.getMaxTokens(this)
        val tokenLabel = when (maxTk) { 500 -> "500（简洁）"; 2000 -> "2000（详细）"; else -> "1000（适中）" }
        val dialogues = AppConfig.getExampleDialogues(this)

        addSectionTitle("对话参数")
        addClickRow("上下文窗口", "${ctxSize} token", iconRes = R.drawable.ic_context) { showContextSizeDialog() }
        addDivider()
        addClickRow("创意度", tempLabel, iconRes = R.drawable.ic_creative) { showTemperatureDialog() }
        addDivider()
        addClickRow("回复详细度", tokenLabel, iconRes = R.drawable.ic_detail) { showMaxTokensDialog() }
        addDivider()
        addClickRow("示例对话数", "${dialogues}条", iconRes = R.drawable.ic_example) { showExampleDialoguesDialog() }
        addDivider()
        addClickRow("开始新对话", "清空当前对话历史", iconRes = R.drawable.ic_new_chat) { showNewChatDialog() }
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
            text = "保留多少对话历史给AI看（单位：token）"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray)); setPadding(0, 0, 0, 12)
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
        for ((i, label) in listOf("500", "4000", "8000").withIndex()) {
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8)
        }
        inputRow.addView(TextView(this).apply {
            text = "自定义："; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
        })
        inputRow.addView(etInput.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        inputRow.addView(TextView(this).apply {
            text = " token"; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
        })
        layout.addView(inputRow)

        MaterialAlertDialogBuilder(this).setTitle("上下文窗口").setView(layout)
            .setPositiveButton("确定") { _, _ ->
                var value = etInput.text.toString().toIntOrNull()
                if (value == null || value < minCtx) value = minCtx
                if (value > maxCtx) value = maxCtx
                value = ((value - minCtx + step / 2) / step) * step + minCtx
                value = value.coerceIn(minCtx, maxCtx)
                AppConfig.setContextSize(this, value)
                applyAllParams()
                recreate()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showTemperatureDialog() {
        val values = floatArrayOf(0.5f, 0.7f, 0.9f)
        val labels = arrayOf("0.5", "0.7", "0.9")
        val descs = arrayOf("保守", "中等", "创意")
        val current = AppConfig.getTemperature(this)
        val idx = when { current <= 0.5f -> 0; current >= 0.9f -> 2; else -> 1 }
        showSliderDialog("创意度", "控制AI回复的随机性和想象力", labels, descs, idx, 2) { which ->
            AppConfig.setTemperature(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showMaxTokensDialog() {
        val values = intArrayOf(500, 1000, 2000)
        val labels = arrayOf("500", "1000", "2000")
        val descs = arrayOf("简洁", "适中", "详细")
        val current = AppConfig.getMaxTokens(this)
        val idx = values.toList().indexOf(current).coerceAtLeast(1)
        showSliderDialog("回复详细度", "每条AI回复的Token上限", labels, descs, idx, 2) { which ->
            AppConfig.setMaxTokens(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showExampleDialoguesDialog() {
        val values = intArrayOf(0, 1, 2, 3)
        val labels = arrayOf("0", "1", "2", "3")
        val descs = arrayOf("不展示", "1条", "2条", "3条")
        val current = AppConfig.getExampleDialogues(this)
        val idx = current.coerceIn(0, 3)
        showSliderDialog("示例对话数", "角色卡中展示的示例对话条数", labels, descs, idx, 3) { which ->
            AppConfig.setExampleDialogues(this, values[which])
            applyAllParams(); recreate()
        }
    }

    private fun showNewChatDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("开始新对话")
            .setMessage("将清空当前对话历史。确定继续吗？")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("reset")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, "对话已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, "清空对话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun buildMemoryPage() {
        addSectionTitle("记忆参数")
        addClickRow("记忆容量", "默认（自动管理）", iconRes = R.drawable.ic_settings_memory) {
            Toast.makeText(this, "记忆容量由系统自动管理，后续版本将支持手动调整", Toast.LENGTH_SHORT).show()
        }
        addDivider()
        addClickRow("去重阈值", "默认（自动去重）", iconRes = R.drawable.ic_settings_memory) {
            Toast.makeText(this, "去重阈值将在后续版本中开放配置", Toast.LENGTH_SHORT).show()
        }
        addDivider()
        addClickRow("衰减参数", "默认（自然衰减）", iconRes = R.drawable.ic_settings_memory) {
            Toast.makeText(this, "衰减参数将在后续版本中开放配置", Toast.LENGTH_SHORT).show()
        }
        addDivider()
        addSectionTitle("记忆管理")
        addClickRow("查看记忆列表", "浏览和管理AI记忆", iconRes = R.drawable.ic_settings_memory) {
            startActivity(Intent(this, MemoryManageActivity::class.java))
        }
        addDivider()
        addClickRow("清空全部记忆", "删除所有长期记忆数据", iconRes = R.drawable.ic_settings_memory) {
            showClearMemoryDialog()
        }
    }

    private fun showClearMemoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空长期记忆")
            .setMessage("将删除所有长期记忆数据，无法恢复。确定吗？")
            .setPositiveButton("确认清空") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("clear_memories")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, "记忆已清空", Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsDetailActivity, "清空记忆失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null).show()
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
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else "不设置"

        addSectionTitle("主动推送")
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        toggleRow.addView(TextView(this).apply {
            text = "开启主动消息"; textSize = 16f
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
        toggleRow.addView(sw)
        contentLayout.addView(toggleRow)
        addDivider()

        addClickRow("发送频率", intervalLabel, iconRes = R.drawable.ic_frequency) {
            val optionsWithCustom = INTERVAL_OPTIONS.toMutableList().apply { add("自定义") }
            val idx = if (INTERVAL_MS.contains(intervalMs)) INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0) else optionsWithCustom.size - 1
            MaterialAlertDialogBuilder(this)
                .setTitle("发送频率")
                .setSingleChoiceItems(optionsWithCustom.toTypedArray(), idx) { dialog, which ->
                    if (which == optionsWithCustom.size - 1) { dialog.dismiss(); showCustomIntervalDialog() }
                    else {
                        AppConfig.setProactiveInterval(this@SettingsDetailActivity, INTERVAL_MS[which])
                        ProactiveService.reschedule(this@SettingsDetailActivity)
                        dialog.dismiss(); recreate()
                    }
                }
                .setNegativeButton("取消", null).show()
        }
        addDivider()

        addClickRow("免打扰时段", quietLabel, iconRes = R.drawable.ic_quiet) {
            val options = arrayOf("不设置", "22:00 - 08:00", "23:00 - 07:00", "00:00 - 06:00", "自定义")
            val idx = if (quietLabel == "不设置") 0
                else if (quietLabel == "22:00 - 08:00") 1
                else if (quietLabel == "23:00 - 07:00") 2
                else if (quietLabel == "00:00 - 06:00") 3
                else options.size - 1
            MaterialAlertDialogBuilder(this)
                .setTitle("静默时段")
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
                .setNegativeButton("取消", null).show()
        }
    }

    private fun showCustomIntervalDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "输入小时数（最低 0.5 小时）"
            setText("1")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义发送间隔")
            .setMessage("最低间隔 30 分钟（0.5 小时）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val hours = input.text.toString().toDoubleOrNull()
                if (hours == null || hours <= 0) { Toast.makeText(this, "请输入有效的正数", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val intervalMs = (hours * 3600000L).toLong()
                if (intervalMs < AppConfig.MIN_INTERVAL_MS) { Toast.makeText(this, "最低间隔为 30 分钟（0.5 小时）", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                AppConfig.setProactiveInterval(this@SettingsDetailActivity, intervalMs)
                ProactiveService.reschedule(this@SettingsDetailActivity)
                recreate()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showCustomQuietDialog() {
        val currentStart = AppConfig.getQuietStart(this)
        val currentEnd = AppConfig.getQuietEnd(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 16)
        }
        val startInput = EditText(this).apply {
            hint = "开始时间（如 22:00）"
            setText(currentStart)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val endInput = EditText(this).apply {
            hint = "结束时间（如 08:00）"
            setText(currentEnd)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        layout.addView(TextView(this).apply { text = "开始时间"; textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.text_primary)) })
        layout.addView(startInput)
        layout.addView(TextView(this).apply { text = "结束时间"; textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(0, 16, 0, 0) })
        layout.addView(endInput)
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义静默时段")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val s = startInput.text.toString().trim()
                val e = endInput.text.toString().trim()
                if (s.isNotEmpty() && e.isNotEmpty() && s.matches(Regex("\\d{1,2}:\\d{2}")) && e.matches(Regex("\\d{1,2}:\\d{2}"))) {
                    AppConfig.setQuietHours(this@SettingsDetailActivity, s, e)
                    recreate()
                } else {
                    Toast.makeText(this, "请输入有效的时间格式（HH:mm）", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun buildWorldBookPage() {
        WorldBookSection(this).build()
    }

    private fun buildVoicePage() {
        val speechRate = AppConfig.getTtsSpeechRate(this)
        val pitch = AppConfig.getTtsPitch(this)
        val autoRead = AppConfig.getAutoReadAloud(this)
        val lang = AppConfig.getVoiceRecognitionLang(this)
        val langLabel = when (lang) { "zh-CN" -> "中文（普通话）"; "en-US" -> "English (US)"; "ja-JP" -> "日本語"; else -> lang }

        addSectionTitle("语音合成（TTS）")
        addClickRow("语速", "%.1fx".format(speechRate), iconRes = R.drawable.ic_speed) { showTtsRateDialog() }
        addDivider()
        addClickRow("音调", "%.1f".format(pitch), iconRes = R.drawable.ic_pitch) { showTtsPitchDialog() }
        addDivider()

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 12, 0, 12)
        }
        toggleRow.addView(TextView(this).apply {
            text = "自动朗读AI回复"; textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = autoRead
            setOnCheckedChangeListener { _, isChecked -> AppConfig.setAutoReadAloud(this@SettingsDetailActivity, isChecked) }
        }
        toggleRow.addView(sw)
        contentLayout.addView(toggleRow)
        addDivider()

        addSectionTitle("语音识别（ASR）")
        addClickRow("识别语言", langLabel, iconRes = R.drawable.ic_language) { showVoiceLangDialog() }
    }

    private fun showTtsRateDialog() {
        val current = AppConfig.getTtsSpeechRate(this)
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val idx = values.indexOfFirst { it >= current - 0.01f }.coerceAtLeast(0)
        showSliderDialog("TTS 语速", "调整AI语音朗读的快慢", labels, labels, idx, labels.size - 1) { which ->
            AppConfig.setTtsSpeechRate(this, values[which]); recreate()
        }
    }

    private fun showTtsPitchDialog() {
        val current = AppConfig.getTtsPitch(this)
        val labels = arrayOf("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val idx = values.indexOfFirst { it >= current - 0.01f }.coerceAtLeast(0)
        showSliderDialog("TTS 音调", "调整AI语音的音调高低", labels, labels, idx, labels.size - 1) { which ->
            AppConfig.setTtsPitch(this, values[which]); recreate()
        }
    }

    private fun showVoiceLangDialog() {
        val current = AppConfig.getVoiceRecognitionLang(this)
        val options = arrayOf("中文（普通话）", "English (US)", "日本語")
        val values = arrayOf("zh-CN", "en-US", "ja-JP")
        val idx = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("语音识别语言")
            .setSingleChoiceItems(options, idx) { dialog, which ->
                AppConfig.setVoiceRecognitionLang(this, values[which]); dialog.dismiss(); recreate()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun buildAboutPage() {
        addSectionTitle("应用信息")
        addClickRow("版本号", "v${BuildConfig.VERSION_NAME}", iconRes = R.drawable.ic_export) {}
        addDivider()
        addClickRow("崩溃日志", "查看应用崩溃记录", iconRes = R.drawable.ic_error) {
            startActivity(Intent(this, CrashLogViewerActivity::class.java))
        }
        addDivider()
        addClickRow("开源许可", "查看开源组件许可", iconRes = R.drawable.ic_export) {
            Toast.makeText(this, "本项目使用以下开源组件：\n- Material Design 3\n- Chaquopy\n- OkHttp\n详情请查看 GitHub 仓库", Toast.LENGTH_LONG).show()
        }
        addDivider()
        addClickRow("意见反馈", "向我们反馈问题或建议", iconRes = R.drawable.ic_export) {
            Toast.makeText(this, "反馈功能将在后续版本中开放，敬请期待", Toast.LENGTH_SHORT).show()
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
                    } catch (_: Exception) { }
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
            setPadding(0, 24, 0, 8)
            paint.isFakeBoldText = true
        })
    }

    internal fun addHintText(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
            setPadding(0, 0, 0, 8)
        })
    }

    internal fun addEmptyHint(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
            setPadding(0, 8, 0, 8)
            gravity = android.view.Gravity.CENTER
        })
    }

    internal fun addClickRow(label: String, value: String, valueColor: Int = R.color.text_secondary, iconRes: Int = 0, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 16)
            isClickable = true; isFocusable = true
            setBackgroundResource(getSelectableItemBackground())
            setOnClickListener { onClick() }
        }
        if (iconRes != 0) {
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 6)
            }
            labelRow.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(20, 20).apply { marginEnd = 8 }
                setColorFilter(ContextCompat.getColor(context, R.color.primary))
            })
            labelRow.addView(TextView(this).apply {
                text = label; textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                paint.isFakeBoldText = true
            })
            row.addView(labelRow)
        } else {
            row.addView(TextView(this).apply {
                text = label; textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                paint.isFakeBoldText = true; setPadding(0, 0, 0, 6)
            })
        }
        row.addView(TextView(this).apply {
            text = value; textSize = 14f
            setTextColor(ContextCompat.getColor(context, valueColor))
        })
        contentLayout.addView(row)
    }

    internal fun addDivider() { contentLayout.addView(createDividerView()) }

    internal fun createDividerView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.divider_thickness))
        setBackgroundColor(ContextCompat.getColor(context, R.color.glass_border))
    }

    private fun showSliderDialog(title: String, subtitle: String, labels: Array<String>, descs: Array<String>, currentIdx: Int, max: Int, onSelected: (Int) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0) }
        layout.addView(TextView(this).apply {
            text = subtitle; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray)); setPadding(0, 0, 0, 16)
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
                setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; max -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelRow)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(layout)
            .setPositiveButton("确定") { _, _ -> onSelected(seekBar.progress) }
            .setNegativeButton("取消", null).show()
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
                    Toast.makeText(this@SettingsDetailActivity, "参数应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}