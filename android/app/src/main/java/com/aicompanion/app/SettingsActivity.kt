package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aicompanion.app.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页面 Activity。
 * 简洁卡片式设计：每个卡片点击后弹出对话框设置子级选项。
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
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.settingsRoot)

        binding.btnBack.setOnClickListener { finish() }

        binding.cardAccount.setOnClickListener { showAccountDialog() }
        binding.cardChat.setOnClickListener { showChatDialog() }
        binding.cardProactive.setOnClickListener { showProactiveDialog() }
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, MemoryManageActivity::class.java))
        }
        binding.cardWorldBook.setOnClickListener { showWorldBookDialog() }

        refreshUI()
    }

    // ======================== 账户设置弹窗 ========================

    private fun showAccountDialog() {
        try {
            // 预计算所有值，避免在lambda中访问可能失败的方法
            val apiKey = AppConfig.getApiKey(this)
            val apiKeyLabel = if (apiKey.isNotEmpty()) "已配置 (${apiKey.take(4)}...)" else "未配置"
            val char = CharacterStorage.getCurrent(this)
            val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
            val ctx = this

            val layout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }

            layout.addView(createSettingsRow("API Key", apiKeyLabel, apiKey.isNotEmpty()) {
                showApiKeyEditDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("角色预设", char.name, true) {
                showRoleSelectDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("模型选择", model, true) {
                showModelSelectDialog()
            })

            MaterialAlertDialogBuilder(ctx)
                .setTitle("账户设置")
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "showAccountDialog 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showApiKeyEditDialog() {
        val currentKey = AppConfig.getApiKey(this)
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
                AppConfig.setApiKey(this, key)
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("set_api_key", key)
                } catch (e: Exception) {
                    Toast.makeText(this, "Python 同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                refreshUI()
                Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRoleSelectDialog() {
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

    private fun showModelSelectDialog() {
        val currentModel = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        val idx = MODEL_VALUES.indexOf(currentModel).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("选择模型")
            .setSingleChoiceItems(MODEL_OPTIONS, idx) { dialog, which ->
                val model = MODEL_VALUES[which]
                AppConfig.setModel(this, model)
                applyAllParams()
                refreshUI()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ======================== 对话设置弹窗 ========================

    private fun showChatDialog() {
        try {
            val ctx = this
            val ctxSize = AppConfig.getContextSize(ctx)
            val temp = AppConfig.getTemperature(ctx)
            val tempLabel = when { temp <= 0.5f -> "0.5 保守"; temp >= 0.9f -> "0.9 创意"; else -> "0.7 中等" }
            val maxTk = AppConfig.getMaxTokens(ctx)
            val tokenLabel = when (maxTk) { 500 -> "500（简洁）"; 2000 -> "2000（详细）"; else -> "1000（适中）" }
            val dialogues = AppConfig.getExampleDialogues(ctx)

            val layout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }

            layout.addView(createSettingsRow("上下文窗口", "${ctxSize} token", true) {
                showContextSizeDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("创意度", tempLabel, true) {
                showTemperatureDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("回复详细度", tokenLabel, true) {
                showMaxTokensDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("示例对话数", "${dialogues}条", true) {
                showExampleDialoguesDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("开始新对话", "清空当前对话历史", true) {
                showNewChatDialog()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("清空长期记忆", "删除所有记忆数据", true, R.color.accent_orange) {
                showClearMemoryDialog()
            })

            MaterialAlertDialogBuilder(ctx)
                .setTitle("对话设置")
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "showChatDialog 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showContextSizeDialog() {
        val current = AppConfig.getContextSize(this)
        val minCtx = 500; val maxCtx = 8000; val step = 500
        val steps = (maxCtx - minCtx) / step
        val currentStep = ((current - minCtx).coerceIn(0, maxCtx - minCtx)) / step

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 20, 48, 0)
        }

        val tvSubtitle = TextView(this).apply {
            text = "保留多少对话历史给AI看（单位：token）"
            textSize = 13f; setTextColor(getColor(R.color.darker_gray)); setPadding(0, 0, 0, 12)
        }
        layout.addView(tvSubtitle)

        val tvValue = TextView(this).apply {
            text = "${current} token"; textSize = 20f
            setTextColor(getColor(R.color.primary)); textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 8)
        }
        layout.addView(tvValue)

        val etInput = EditText(this).apply {
            setText(current.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f; setTextColor(getColor(R.color.text_primary))
            setBackgroundColor(getColor(android.R.color.transparent)); setPadding(16, 8, 16, 8)
        }

        val seekBar = android.widget.SeekBar(this).apply {
            max = steps; progress = currentStep; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    val value = minCtx + progress * step
                    tvValue.text = "${value} token"; etInput.setText(value.toString())
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        layout.addView(seekBar)

        val labelLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 12)
        }
        for ((i, label) in listOf("500", "4000", "8000").withIndex()) {
            labelLayout.addView(TextView(this).apply {
                text = label; textSize = 11f; setTextColor(getColor(R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; 2 -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelLayout)

        val inputRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8)
        }
        inputRow.addView(TextView(this).apply { text = "自定义："; textSize = 14f; setTextColor(getColor(R.color.darker_gray)) })
        inputRow.addView(etInput.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        inputRow.addView(TextView(this).apply { text = " token"; textSize = 14f; setTextColor(getColor(R.color.darker_gray)) })
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
                refreshUI()
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
            applyAllParams(); refreshUI()
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
            applyAllParams(); refreshUI()
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
            applyAllParams(); refreshUI()
        }
    }

    private fun showNewChatDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("开始新对话")
            .setMessage("将清空当前对话历史。确定继续吗？")
            .setPositiveButton("确定") { _, _ ->
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("reset")
                } catch (e: Exception) {
                    Toast.makeText(this, "清空对话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "对话已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showClearMemoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空长期记忆")
            .setMessage("将删除所有长期记忆数据，无法恢复。确定吗？")
            .setPositiveButton("确认清空") { _, _ ->
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("clear_memories")
                } catch (e: Exception) {
                    Toast.makeText(this, "清空记忆失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "记忆已清空", Toast.LENGTH_SHORT).show()
                refreshUI()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showSliderDialog(
        title: String, subtitle: String, labels: Array<String>, descs: Array<String>,
        currentIdx: Int, max: Int, onSelected: (Int) -> Unit,
    ) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(getColor(R.color.darker_gray)); setPadding(0, 0, 0, 16)
        })
        val tvValue = TextView(this).apply {
            text = "${labels[currentIdx]} — ${descs[currentIdx]}"; textSize = 18f
            setTextColor(getColor(R.color.primary)); textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 12)
        }
        layout.addView(tvValue)
        val seekBar = android.widget.SeekBar(this).apply {
            this.max = max; progress = currentIdx; setPadding(8, 0, 8, 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    tvValue.text = "${labels[progress]} — ${descs[progress]}"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 0)
        }
        for (i in 0..max) {
            labelLayout.addView(TextView(this).apply {
                text = descs[i]; textSize = 11f; setTextColor(getColor(R.color.darker_gray))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = when (i) { 0 -> View.TEXT_ALIGNMENT_TEXT_START; max -> View.TEXT_ALIGNMENT_TEXT_END; else -> View.TEXT_ALIGNMENT_CENTER }
            })
        }
        layout.addView(labelLayout)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(layout)
            .setPositiveButton("确定") { _, _ -> onSelected(seekBar.progress) }
            .setNegativeButton("取消", null).show()
    }

    // ======================== 主动消息弹窗 ========================

    private fun showProactiveDialog() {
        try {
            val ctx = this
            val enabled = prefs.getBoolean("proactive_enabled", false)
            val intervalMs = prefs.getLong("proactive_interval", INTERVAL_MS[2])
            val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
            val start = prefs.getString("quiet_start", "") ?: ""
            val end = prefs.getString("quiet_end", "") ?: ""
            val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else "不设置"

            val layout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }

            // 开关行
            val toggleRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            toggleRow.addView(TextView(ctx).apply {
                text = "主动消息"; textSize = 16f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = enabled
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("proactive_enabled", isChecked).apply()
                    refreshUI()
                }
            }
            toggleRow.addView(sw)
            layout.addView(toggleRow)
            layout.addView(createDivider())

            layout.addView(createSettingsRow("发送频率", intervalLabel, true) {
                val idx = INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("发送频率")
                    .setSingleChoiceItems(INTERVAL_OPTIONS, idx) { dialog, which ->
                        prefs.edit().putLong("proactive_interval", INTERVAL_MS[which]).apply()
                        refreshUI(); dialog.dismiss()
                    }
                    .setNegativeButton("取消", null).show()
            })
            layout.addView(createDivider())

            layout.addView(createSettingsRow("免打扰时段", quietLabel, true) {
                val options = arrayOf("不设置", "22:00 - 08:00", "23:00 - 07:00", "00:00 - 06:00")
                val idx = options.indexOfFirst {
                    it == quietLabel || (quietLabel != "不设置" && it != "不设置" && it.take(5) == start.take(5))
                }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("静默时段")
                    .setSingleChoiceItems(options, idx) { dialog, which ->
                        if (options[which] == "不设置") {
                            prefs.edit().remove("quiet_start").remove("quiet_end").apply()
                        } else {
                            val parts = options[which].split(" - ")
                            prefs.edit().putString("quiet_start", parts[0].trim()).putString("quiet_end", parts[1].trim()).apply()
                        }
                        refreshUI(); dialog.dismiss()
                    }
                    .setNegativeButton("取消", null).show()
            })

            MaterialAlertDialogBuilder(ctx)
                .setTitle("主动消息")
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "showProactiveDialog 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 世界书弹窗 ========================

    private fun showWorldBookDialog() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("list_world_books")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(this, "世界书加载失败", Toast.LENGTH_SHORT).show()
                return
            }
            val books = json.optJSONArray("books") ?: JSONArray()

            val enabledResult = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
            val enabledJson = JSONObject(enabledResult)
            val enabledArray = enabledJson.optJSONArray("enabled")
            val enabledSet = mutableSetOf<String>()
            if (enabledArray != null) {
                for (i in 0 until enabledArray.length()) enabledSet.add(enabledArray.optString(i, ""))
            }

            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }

            if (books.length() == 0) {
                layout.addView(TextView(this).apply {
                    text = "暂无世界书，点击下方按钮创建"
                    textSize = 14f; setTextColor(getColor(R.color.darker_gray))
                    setPadding(0, 8, 0, 8); gravity = android.view.Gravity.CENTER
                })
            } else {
                // 提示文字
                layout.addView(TextView(this).apply {
                    text = "点击条目编辑，右滑开关启用/禁用"
                    textSize = 12f; setTextColor(getColor(R.color.darker_gray))
                    setPadding(0, 0, 0, 8)
                })
                layout.addView(createDivider())

                for (i in 0 until books.length()) {
                    val book = books.getJSONObject(i)
                    val name = book.optString("name", "")
                    val description = book.optString("description", "")
                    val entries = book.optInt("entry_count", 0)

                    val row = createWorldBookRow(name, description, entries, name in enabledSet)
                    layout.addView(row)
                    if (i < books.length() - 1) layout.addView(createDivider())
                }
            }

            layout.addView(createDivider())
            // 创建按钮
            val createBtn = android.widget.Button(this).apply {
                text = "＋ 创建世界书"
                textSize = 14f; setTextColor(getColor(R.color.primary))
                setBackgroundColor(getColor(android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showCreateWorldBookDialog() }
            }
            layout.addView(createBtn)

            MaterialAlertDialogBuilder(this)
                .setTitle("世界书（知识/常识注入）")
                .setView(layout)
                .setPositiveButton("关闭") { _, _ -> refreshUI() }
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "showWorldBookDialog 失败: ${e.message}", e)
            Toast.makeText(this, "世界书加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateWorldBookDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etName = EditText(this).apply {
            hint = "世界书名称（如：二次元幻想世界）"
            textSize = 14f; setTextColor(getColor(R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(TextView(this).apply {
            text = "名称"; textSize = 13f; setTextColor(getColor(R.color.darker_gray))
        })
        layout.addView(etName)
        layout.addView(createDivider())

        val etDesc = EditText(this).apply {
            hint = "简短描述（如：一个剑与魔法的异世界）"
            textSize = 14f; setTextColor(getColor(R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 3
        }
        layout.addView(TextView(this).apply {
            text = "描述"; textSize = 13f; setTextColor(getColor(R.color.darker_gray))
        })
        layout.addView(etDesc)

        MaterialAlertDialogBuilder(this)
            .setTitle("创建世界书")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val desc = etDesc.text.toString().trim()
                createWorldBook(name, desc)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createWorldBook(name: String, description: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("create_world_book", name, description, "[]")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(this, "世界书「${name}」已创建", Toast.LENGTH_SHORT).show()
                refreshUI()
                showWorldBookDialog()
            } else {
                Toast.makeText(this, "创建失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditWorldBookDialog(name: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(this, "加载失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
                return
            }
            val book = json.optJSONObject("book") ?: return
            val description = book.optString("description", "")
            val entryCount = book.optInt("entry_count", 0)

            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }

            // 描述信息行
            layout.addView(TextView(this).apply {
                text = "描述：${description.take(50)}"
                textSize = 13f; setTextColor(getColor(R.color.darker_gray))
                setPadding(0, 0, 0, 8)
            })
            layout.addView(TextView(this).apply {
                text = "条目数：${entryCount} 条"
                textSize = 13f; setTextColor(getColor(R.color.darker_gray))
                setPadding(0, 0, 0, 12)
            })
            layout.addView(createDivider())

            // 编辑描述按钮
            val editDescBtn = android.widget.Button(this).apply {
                text = "编辑描述"
                textSize = 14f; setTextColor(getColor(R.color.primary))
                setBackgroundColor(getColor(android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    showEditDescriptionDialog(name, description)
                }
            }
            layout.addView(editDescBtn)
            layout.addView(createDivider())

            // 管理条目按钮
            val editEntriesBtn = android.widget.Button(this).apply {
                text = "管理条目（${entryCount}条）"
                textSize = 14f; setTextColor(getColor(R.color.primary))
                setBackgroundColor(getColor(android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    Toast.makeText(this@SettingsActivity, "条目管理功能将在后续版本中完善", Toast.LENGTH_SHORT).show()
                }
            }
            layout.addView(editEntriesBtn)
            layout.addView(createDivider())

            // 删除按钮
            val deleteBtn = android.widget.Button(this).apply {
                text = "删除世界书"
                textSize = 14f; setTextColor(getColor(R.color.accent_red))
                setBackgroundColor(getColor(android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    showDeleteWorldBookConfirmDialog(name)
                }
            }
            layout.addView(deleteBtn)

            MaterialAlertDialogBuilder(this)
                .setTitle("编辑世界书 — ${name}")
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "showEditWorldBookDialog 失败: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDescriptionDialog(name: String, currentDescription: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etDesc = EditText(this).apply {
            setText(currentDescription)
            textSize = 14f; setTextColor(getColor(R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 5
        }
        layout.addView(etDesc)

        MaterialAlertDialogBuilder(this)
            .setTitle("编辑描述")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newDesc = etDesc.text.toString().trim()
                updateWorldBookDescription(name, newDesc)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateWorldBookDescription(name: String, description: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            // 获取现有条目，保留不变
            val bookResult = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val bookJson = JSONObject(bookResult)
            val book = bookJson.optJSONObject("book")
            val entries = if (book != null) book.optJSONArray("entries")?.toString() ?: "[]" else "[]"

            val result = module?.callAttr("update_world_book", name, description, entries)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(this, "描述已更新", Toast.LENGTH_SHORT).show()
                refreshUI()
            } else {
                Toast.makeText(this, "更新失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteWorldBookConfirmDialog(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除世界书")
            .setMessage("确定要删除世界书「${name}」吗？\n\n此操作不可撤销！")
            .setPositiveButton("确认删除") { _, _ ->
                deleteWorldBook(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteWorldBook(name: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("delete_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                saveEnabledWorldBooks()
                Toast.makeText(this, "世界书「${name}」已删除", Toast.LENGTH_SHORT).show()
                refreshUI()
                showWorldBookDialog()
            } else {
                Toast.makeText(this, "删除失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createWorldBookRow(
        name: String, description: String, entries: Int, isEnabled: Boolean,
    ): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            // 点击整行进入编辑
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.attr.selectableItemBackground)
            setOnClickListener { showEditWorldBookDialog(name) }
        }
        val textLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textLayout.addView(TextView(this).apply {
            text = name; textSize = 15f; setTextColor(getColor(R.color.text_primary)); setPadding(0, 0, 0, 2)
        })
        textLayout.addView(TextView(this).apply {
            text = "${entries}条 · ${description.take(30)}"
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textLayout)
        val switch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = isEnabled
            setOnCheckedChangeListener { _, checked ->
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    if (checked) {
                        val r = module?.callAttr("enable_world_book", name)?.toString() ?: "{}"
                        val j = JSONObject(r)
                        if (j.optString("status") == "ok") {
                            saveEnabledWorldBooks()
                            Toast.makeText(this@SettingsActivity, "已启用「${name}」", Toast.LENGTH_SHORT).show()
                        } else {
                            isChecked = false
                            Toast.makeText(this@SettingsActivity, "启用失败: ${j.optString("message")}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        module?.callAttr("disable_world_book", name)
                        saveEnabledWorldBooks()
                        Toast.makeText(this@SettingsActivity, "已禁用「${name}」", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    isChecked = !checked; Toast.makeText(this@SettingsActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(switch)
        return row
    }

    private fun saveEnabledWorldBooks() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
            val json = JSONObject(result)
            val enabled = json.optJSONArray("enabled") ?: return
            val names = (0 until enabled.length()).map { enabled.optString(it, "") }.filter { it.isNotEmpty() }
            prefs.edit().putString("enabled_world_books", names.joinToString(",")).apply()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "saveEnabledWorldBooks 失败: ${e.message}", e)
        }
    }

    // ======================== 辅助方法 ========================

    private fun createSettingsRow(
        label: String, value: String, clickable: Boolean, valueColor: Int = R.color.text_secondary,
        onClick: (() -> Unit)? = null,
    ): android.widget.LinearLayout {
        val ctx = this
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
            if (clickable) {
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.attr.selectableItemBackground)
                setOnClickListener { onClick?.invoke() }
            }
        }
        row.addView(TextView(ctx).apply {
            text = label; textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = value; textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, valueColor))
        })
        return row
    }

    private fun createDivider(): View {
        val ctx = this
        return View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.divider_thickness)
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.glass_border))
        }
    }

    private fun applyAllParams() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val ctx = AppConfig.getContextSize(this)
            val temp = AppConfig.getTemperature(this).toDouble()
            val maxTk = AppConfig.getMaxTokens(this)
            val dialogues = AppConfig.getExampleDialogues(this)
            val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
            val result = module?.callAttr("apply_params", ctx, temp, maxTk, dialogues, model)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(this, "参数应用失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "参数应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== UI刷新 ========================

    private fun refreshUI() {
        // 账户摘要
        val apiKey = AppConfig.getApiKey(this)
        val apiStatus = if (apiKey.isNotEmpty()) "已配置" else "未配置"
        val char = CharacterStorage.getCurrent(this)
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
        binding.tvAccountSummary.text = "$apiStatus · ${char.name} · $model"

        // 对话摘要
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this)
        val tempLabel = when { temp <= 0.5f -> "保守"; temp >= 0.9f -> "创意"; else -> "中等" }
        val maxTk = AppConfig.getMaxTokens(this)
        binding.tvChatSummary.text = "${ctxSize} token · 创意度${tempLabel} · 回复${maxTk}字"

        // 主动消息摘要
        val enabled = prefs.getBoolean("proactive_enabled", false)
        val intervalMs = prefs.getLong("proactive_interval", INTERVAL_MS[2])
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = prefs.getString("quiet_start", "") ?: ""
        val end = prefs.getString("quiet_end", "") ?: ""
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "免打扰 $start-$end" else "无免打扰"
        binding.tvProactiveSummary.text = if (enabled) "已开启 · $intervalLabel · $quietLabel" else "已关闭"

        // 记忆摘要
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("get_memory_count")?.toString() ?: "{}"
            val json = JSONObject(result)
            val count = json.optInt("count", 0)
            binding.tvMemorySummary.text = "${count}条长期记忆"
        } catch (e: Exception) {
            binding.tvMemorySummary.text = "加载中..."
        }

        // 世界书摘要
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
            val json = JSONObject(result)
            val enabled = json.optJSONArray("enabled")
            val count = enabled?.length() ?: 0
            binding.tvWorldBookSummary.text = if (count > 0) "已启用${count}本" else "未启用"
        } catch (e: Exception) {
            binding.tvWorldBookSummary.text = "未启用"
        }

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }
}