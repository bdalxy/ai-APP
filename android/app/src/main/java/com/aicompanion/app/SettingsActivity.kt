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
        private val MODEL_OPTIONS = arrayOf(
            "deepseek-v4-flash（快速）",
            "deepseek-v4-pro（高质量）"
        )
        private val MODEL_VALUES = arrayOf(
            "deepseek-v4-flash", "deepseek-v4-pro"
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
        setupContextSize()
        setupTemperature()
        setupMaxTokens()
        setupExampleDialogues()
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
            val currentModel = AppConfig.getModel(this@SettingsActivity).let {
                if (it.isBlank()) "deepseek-v4-flash" else it
            }
            val idx = MODEL_VALUES.indexOf(currentModel).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("选择模型")
                .setSingleChoiceItems(MODEL_OPTIONS, idx) { dialog, which ->
                    val model = MODEL_VALUES[which]
                    AppConfig.setModel(this@SettingsActivity, model)
                    applyAllParams()
                    refreshUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun applyAllParams() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val ctx = AppConfig.getContextSize(this@SettingsActivity)
            val temp = AppConfig.getTemperature(this@SettingsActivity).toDouble()
            val maxTk = AppConfig.getMaxTokens(this@SettingsActivity)
            val dialogues = AppConfig.getExampleDialogues(this@SettingsActivity)
            val model = AppConfig.getModel(this@SettingsActivity).let {
                if (it.isBlank()) "deepseek-v4-flash" else it
            }
            module?.callAttr("apply_params", ctx, temp, maxTk, dialogues, model)
        } catch (e: Exception) {
            Toast.makeText(this@SettingsActivity, "参数应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 对话设置 ========================

    private fun setupContextSize() {
        findViewById<View>(R.id.itemContextSize).setOnClickListener {
            val current = AppConfig.getContextSize(this@SettingsActivity)
            // 范围 500~8000，步长 500，共 16 档
            val minCtx = 500
            val maxCtx = 8000
            val step = 500
            val steps = (maxCtx - minCtx) / step  // 15
            val currentStep = ((current - minCtx).coerceIn(0, maxCtx - minCtx)) / step

            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 20, 48, 0)
            }

            val tvSubtitle = TextView(this@SettingsActivity).apply {
                text = "保留多少对话历史给AI看（单位：token，约1token≈0.5~1个中文字）"
                textSize = 13f
                setTextColor(getColor(android.R.color.darker_gray))
                setPadding(0, 0, 0, 12)
            }
            layout.addView(tvSubtitle)

            val tvValue = TextView(this@SettingsActivity).apply {
                text = "${current} token"
                textSize = 20f
                setTextColor(getColor(R.color.primary))
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 8)
            }
            layout.addView(tvValue)

            // EditText 必须声明在 SeekBar 之前，因为 SeekBar 回调会引用它
            val etInput = EditText(this@SettingsActivity).apply {
                setText(current.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundColor(getColor(android.R.color.transparent))
                setPadding(16, 8, 16, 8)
            }

            val seekBar = android.widget.SeekBar(this).apply {
                max = steps
                progress = currentStep
                setPadding(8, 0, 8, 0)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                        val value = minCtx + progress * step
                        tvValue.text = "${value} token"
                        etInput.setText(value.toString())
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            }
            layout.addView(seekBar)

            // 标签行
            val labelLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(8, 2, 8, 12)
            }
            val labelMin = TextView(this@SettingsActivity).apply {
                text = "500"
                textSize = 11f
                setTextColor(getColor(android.R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val labelMid = TextView(this@SettingsActivity).apply {
                text = "4000"
                textSize = 11f
                setTextColor(getColor(android.R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            }
            val labelMax = TextView(this@SettingsActivity).apply {
                text = "8000"
                textSize = 11f
                setTextColor(getColor(android.R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
            }
            labelLayout.addView(labelMin)
            labelLayout.addView(labelMid)
            labelLayout.addView(labelMax)
            layout.addView(labelLayout)

            // 自定义输入行
            val inputRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8)
            }
            val tvCustom = TextView(this@SettingsActivity).apply {
                text = "自定义："
                textSize = 14f
                setTextColor(getColor(android.R.color.darker_gray))
            }
            inputRow.addView(tvCustom)
            inputRow.addView(etInput.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            val tvUnit = TextView(this@SettingsActivity).apply {
                text = " token"
                textSize = 14f
                setTextColor(getColor(android.R.color.darker_gray))
            }
            inputRow.addView(tvUnit)
            layout.addView(inputRow)

            MaterialAlertDialogBuilder(this)
                .setTitle("上下文窗口")
                .setView(layout)
                .setPositiveButton("确定") { _, _ ->
                    var value = etInput.text.toString().toIntOrNull()
                    if (value == null || value < minCtx) value = minCtx
                    if (value > maxCtx) value = maxCtx
                    // 对齐到步长
                    value = ((value - minCtx + step / 2) / step) * step + minCtx
                    value = value.coerceIn(minCtx, maxCtx)
                    AppConfig.setContextSize(this@SettingsActivity, value)
                    applyAllParams()
                    refreshUI()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupTemperature() {
        findViewById<View>(R.id.itemTemperature).setOnClickListener {
            val values = floatArrayOf(0.5f, 0.7f, 0.9f)
            val labels = arrayOf("0.5", "0.7", "0.9")
            val descs = arrayOf("保守", "中等", "创意")
            val current = AppConfig.getTemperature(this@SettingsActivity)
            val idx = when { current <= 0.5f -> 0; current >= 0.9f -> 2; else -> 1 }

            showSliderDialog("创意度", "控制AI回复的随机性和想象力", labels, descs, idx, 2) { which ->
                AppConfig.setTemperature(this@SettingsActivity, values[which])
                applyAllParams()
                refreshUI()
            }
        }
    }

    private fun setupMaxTokens() {
        findViewById<View>(R.id.itemMaxTokens).setOnClickListener {
            val values = intArrayOf(500, 1000, 2000)
            val labels = arrayOf("500", "1000", "2000")
            val descs = arrayOf("简洁", "适中", "详细")
            val current = AppConfig.getMaxTokens(this@SettingsActivity)
            val idx = values.toList().indexOf(current).coerceAtLeast(1)

            showSliderDialog("回复详细度", "每条AI回复的Token上限（单次，非累计）。连续对话中每条消息独立计算", labels, descs, idx, 2) { which ->
                AppConfig.setMaxTokens(this@SettingsActivity, values[which])
                applyAllParams()
                refreshUI()
            }
        }
    }

    private fun setupExampleDialogues() {
        findViewById<View>(R.id.itemExampleDialogues).setOnClickListener {
            val values = intArrayOf(0, 1, 2, 3)
            val labels = arrayOf("0", "1", "2", "3")
            val descs = arrayOf("不展示", "1条", "2条", "3条")
            val current = AppConfig.getExampleDialogues(this@SettingsActivity)
            val idx = current.coerceIn(0, 3)

            showSliderDialog("示例对话数", "角色卡中展示的示例对话条数，越多越能体现角色风格", labels, descs, idx, 3) { which ->
                AppConfig.setExampleDialogues(this@SettingsActivity, values[which])
                applyAllParams()
                refreshUI()
            }
        }
    }

    /**
     * 通用滑动条对话框。
     * @param title 对话框标题
     * @param subtitle 说明文字
     * @param labels 各档位标签（如 "1000", "2000", "4000"）
     * @param descs 各档位描述（如 "小窗口", "中等", "大窗口"）
     * @param currentIdx 当前选中档位
     * @param max 最大档位（SeekBar max）
     * @param onSelected 选中回调，参数为档位索引
     */
    private fun showSliderDialog(
        title: String,
        subtitle: String,
        labels: Array<String>,
        descs: Array<String>,
        currentIdx: Int,
        max: Int,
        onSelected: (Int) -> Unit,
    ) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 20, 48, 0)
        }

        val tvSubtitle = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSubtitle)

        val tvValue = TextView(this).apply {
            text = "${labels[currentIdx]} — ${descs[currentIdx]}"
            textSize = 18f
            setTextColor(getColor(R.color.primary))
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvValue)

        val seekBar = android.widget.SeekBar(this).apply {
            this.max = max
            progress = currentIdx
            setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    tvValue.text = "${labels[progress]} — ${descs[progress]}"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
            })
        }
        layout.addView(seekBar)

        // 标签行
        val labelLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 0)
        }
        for (i in 0..max) {
            val label = TextView(this@SettingsActivity).apply {
                text = descs[i]
                textSize = 11f
                setTextColor(getColor(android.R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = if (i == 0) android.view.View.TEXT_ALIGNMENT_TEXT_START
                    else if (i == max) android.view.View.TEXT_ALIGNMENT_TEXT_END
                    else android.view.View.TEXT_ALIGNMENT_CENTER
            }
            labelLayout.addView(label)
        }
        layout.addView(labelLayout)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                onSelected(seekBar.progress)
            }
            .setNegativeButton("取消", null)
            .show()
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
            if (it.isBlank()) "deepseek-v4-flash（默认）" else it
        }
        findViewById<TextView>(R.id.tvModel)?.text = model

        val ctxSize = AppConfig.getContextSize(this@SettingsActivity)
        findViewById<TextView>(R.id.tvContextSize)?.text = "${ctxSize} token"

        val temp = AppConfig.getTemperature(this@SettingsActivity)
        val tempLabel = when {
            temp <= 0.5f -> "0.5 保守"
            temp >= 0.9f -> "0.9 创意"
            else -> "0.7 中等"
        }
        findViewById<TextView>(R.id.tvTemperature)?.text = tempLabel

        val maxTk = AppConfig.getMaxTokens(this@SettingsActivity)
        val tokenLabel = when (maxTk) {
            500 -> "500（简洁）"
            2000 -> "2000（详细）"
            else -> "1000（适中）"
        }
        findViewById<TextView>(R.id.tvMaxTokens)?.text = tokenLabel

        val dialogues = AppConfig.getExampleDialogues(this@SettingsActivity)
        findViewById<TextView>(R.id.tvExampleDialogues)?.text = "${dialogues}条"

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
