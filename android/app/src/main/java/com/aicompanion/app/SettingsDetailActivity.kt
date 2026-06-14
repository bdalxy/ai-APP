package com.aicompanion.app

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置子页面：根据传入的 type 参数展示不同设置内容。
 * type: account | chat | proactive | world_book
 */
class SettingsDetailActivity : AppCompatActivity() {

    companion object {
        private val MODEL_OPTIONS = arrayOf("deepseek-v4-flash（快速）", "deepseek-v4-pro（高质量）")
        private val MODEL_VALUES = arrayOf("deepseek-v4-flash", "deepseek-v4-pro")
        private val INTERVAL_OPTIONS = arrayOf("每1小时", "每2小时", "每3小时", "每6小时", "每12小时", "每天")
        private val INTERVAL_MS = longArrayOf(3600000L, 7200000L, 10800000L, 21600000L, 43200000L, 86400000L)
    }

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private lateinit var contentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ViewUtils.setupEdgeToEdge(this)

        val type = intent.getStringExtra("type") ?: "account"
        val title = when (type) {
            "account" -> "账户设置"
            "chat" -> "对话设置"
            "proactive" -> "主动消息"
            "world_book" -> "世界书"
            else -> "设置"
        }

        val root = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_dark))
        }

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.spacing_page),
                resources.getDimensionPixelSize(R.dimen.spacing_page),
                resources.getDimensionPixelSize(R.dimen.spacing_page),
                resources.getDimensionPixelSize(R.dimen.spacing_page)
            )
        }

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dip(12), 0, resources.getDimensionPixelSize(R.dimen.spacing_3xl))
        }
        topBar.addView(TextView(this).apply {
            text = "← 返回"; textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 0, 0, 0)
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = title; textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = android.view.Gravity.CENTER
            android.widget.TextView.BufferType.NORMAL
            paint.isFakeBoldText = true
        })
        topBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.button_medium),
                resources.getDimensionPixelSize(R.dimen.button_medium)
            )
        })
        contentLayout.addView(topBar)

        when (type) {
            "account" -> buildAccountPage()
            "chat" -> buildChatPage()
            "proactive" -> buildProactivePage()
            "world_book" -> buildWorldBookPage()
        }

        root.addView(contentLayout)
        setContentView(root)
    }

    // ======================== 账户设置 ========================

    private fun buildAccountPage() {
        val apiKey = AppConfig.getApiKey(this)
        val apiKeyLabel = if (apiKey.isNotEmpty()) "已配置 (${apiKey.take(4)}...)" else "未配置"
        val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }

        addSectionTitle("API 配置")
        addClickRow("API Key", apiKeyLabel, iconRes = R.drawable.ic_key) { showApiKeyEditDialog() }
        addDivider()

        addSectionTitle("模型")
        addClickRow("模型选择", model, iconRes = R.drawable.ic_model) { showModelSelectDialog() }
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
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    module?.callAttr("set_api_key", key)
                } catch (e: Exception) {
                    Toast.makeText(this, "Python 同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    // ======================== 对话设置 ========================

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
                recreate()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showSliderDialog(
        title: String, subtitle: String, labels: Array<String>, descs: Array<String>,
        currentIdx: Int, max: Int, onSelected: (Int) -> Unit,
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0)
        }
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
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    tvValue.text = "${labels[progress]} — ${descs[progress]}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 0)
        }
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

    // ======================== 主动消息 ========================

    private fun buildProactivePage() {
        val enabled = prefs.getBoolean("proactive_enabled", false)
        val intervalMs = prefs.getLong("proactive_interval", INTERVAL_MS[2])
        val intervalLabel = INTERVAL_OPTIONS[INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)]
        val start = prefs.getString("quiet_start", "") ?: ""
        val end = prefs.getString("quiet_end", "") ?: ""
        val quietLabel = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else "不设置"

        addSectionTitle("主动消息")
        // 开关行
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
                prefs.edit().putBoolean("proactive_enabled", isChecked).apply()
            }
        }
        toggleRow.addView(sw)
        contentLayout.addView(toggleRow)
        addDivider()

        addClickRow("发送频率", intervalLabel, iconRes = R.drawable.ic_frequency) {
            val idx = INTERVAL_MS.indexOf(intervalMs).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("发送频率")
                .setSingleChoiceItems(INTERVAL_OPTIONS, idx) { dialog, which ->
                    prefs.edit().putLong("proactive_interval", INTERVAL_MS[which]).apply()
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton("取消", null).show()
        }
        addDivider()

        addClickRow("免打扰时段", quietLabel, iconRes = R.drawable.ic_quiet) {
            val options = arrayOf("不设置", "22:00 - 08:00", "23:00 - 07:00", "00:00 - 06:00")
            val idx = options.indexOfFirst {
                it == quietLabel || (quietLabel != "不设置" && it != "不设置" && it.take(5) == start.take(5))
            }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("静默时段")
                .setSingleChoiceItems(options, idx) { dialog, which ->
                    if (options[which] == "不设置") {
                        prefs.edit().remove("quiet_start").remove("quiet_end").apply()
                    } else {
                        val parts = options[which].split(" - ")
                        prefs.edit().putString("quiet_start", parts[0].trim()).putString("quiet_end", parts[1].trim()).apply()
                    }
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton("取消", null).show()
        }
    }

    // ======================== 世界书 ========================

    private fun buildWorldBookPage() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("list_world_books")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                addEmptyHint("世界书加载失败")
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

            addSectionTitle("世界书（知识/常识注入）")

            if (books.length() == 0) {
                addEmptyHint("暂无世界书，点击下方按钮创建")
            } else {
                addHintText("点击条目编辑，右滑开关启用/禁用")
                addDivider()
                for (i in 0 until books.length()) {
                    val book = books.getJSONObject(i)
                    val name = book.optString("name", "")
                    val description = book.optString("description", "")
                    val entries = book.optInt("entry_count", 0)
                    addWorldBookRow(name, description, entries, name in enabledSet)
                    if (i < books.length() - 1) addDivider()
                }
            }

            addDivider()
            // 创建按钮
            val createBtn = Button(this).apply {
                text = "＋ 创建世界书"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showCreateWorldBookDialog() }
            }
            contentLayout.addView(createBtn)
        } catch (e: Exception) {
            Log.e("SettingsDetail", "worldBook 失败: ${e.message}", e)
            addEmptyHint("世界书加载失败: ${e.message}")
        }
    }

    private fun addWorldBookRow(name: String, description: String, entries: Int, isEnabled: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            isClickable = true; isFocusable = true
            setBackgroundResource(getSelectableItemBackground())
            setOnClickListener { showEditWorldBookDialog(name) }
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_book)
            layoutParams = LinearLayout.LayoutParams(20, 20).apply {
                marginEnd = 12
            }
            setColorFilter(ContextCompat.getColor(context, R.color.primary))
        })
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textLayout.addView(TextView(this).apply {
            text = name; textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 0, 0, 2)
        })
        textLayout.addView(TextView(this).apply {
            text = "${entries}条 · ${description.take(30)}"
            textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textLayout)
        val switch = SwitchCompat(this).apply {
            isChecked = isEnabled
            setOnCheckedChangeListener { _, checked ->
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    if (checked) {
                        val r = module?.callAttr("enable_world_book", name)?.toString() ?: "{}"
                        val j = JSONObject(r)
                        if (j.optString("status") == "ok") {
                            saveEnabledWorldBooks()
                            Toast.makeText(this@SettingsDetailActivity, "已启用「${name}」", Toast.LENGTH_SHORT).show()
                        } else {
                            isChecked = false
                            Toast.makeText(this@SettingsDetailActivity, "启用失败: ${j.optString("message")}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        module?.callAttr("disable_world_book", name)
                        saveEnabledWorldBooks()
                        Toast.makeText(this@SettingsDetailActivity, "已禁用「${name}」", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    isChecked = !checked
                    Toast.makeText(this@SettingsDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(switch)
        contentLayout.addView(row)
    }

    private fun showCreateWorldBookDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
        }
        val etName = EditText(this).apply {
            hint = "世界书名称（如：二次元幻想世界）"; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(TextView(this).apply {
            text = "名称"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
        })
        layout.addView(etName)
        layout.addView(createDividerView())
        val etDesc = EditText(this).apply {
            hint = "简短描述（如：一个剑与魔法的异世界）"; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 3
        }
        layout.addView(TextView(this).apply {
            text = "描述"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
        })
        layout.addView(etDesc)

        MaterialAlertDialogBuilder(this)
            .setTitle("创建世界书")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                createWorldBook(name, etDesc.text.toString().trim())
            }
            .setNegativeButton("取消", null).show()
    }

    private fun createWorldBook(name: String, description: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("create_world_book", name, description, "[]")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(this, "世界书「${name}」已创建", Toast.LENGTH_SHORT).show()
                recreate()
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
                Toast.makeText(this, "加载失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val description = book.optString("description", "")
            val entryCount = book.optInt("entry_count", 0)

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            }
            layout.addView(TextView(this).apply {
                text = "描述：${description.take(50)}"; textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.darker_gray)); setPadding(0, 0, 0, 8)
            })
            layout.addView(TextView(this).apply {
                text = "条目数：${entryCount} 条"; textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.darker_gray)); setPadding(0, 0, 0, 12)
            })
            layout.addView(createDividerView())
            val editDescBtn = Button(this).apply {
                text = "编辑描述"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEditDescriptionDialog(name, description) }
            }
            layout.addView(editDescBtn)
            layout.addView(createDividerView())
            val editEntriesBtn = Button(this).apply {
                text = "管理条目（${entryCount}条）"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryListDialog(name) }
            }
            layout.addView(editEntriesBtn)
            layout.addView(createDividerView())
            val auditBtn = Button(this).apply {
                text = "交叉审核"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.secondary))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showAuditReportDialog(name) }
            }
            layout.addView(auditBtn)
            layout.addView(createDividerView())
            val deleteBtn = Button(this).apply {
                text = "删除世界书"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.accent_red))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showDeleteWorldBookConfirmDialog(name) }
            }
            layout.addView(deleteBtn)

            MaterialAlertDialogBuilder(this)
                .setTitle("编辑世界书 — ${name}")
                .setView(layout)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "editWorldBook 失败: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDescriptionDialog(name: String, currentDescription: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
        }
        val etDesc = EditText(this).apply {
            setText(currentDescription); textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
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
            .setNegativeButton("取消", null).show()
    }

    private fun updateWorldBookDescription(name: String, description: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val bookResult = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val bookJson = JSONObject(bookResult)
            val book = bookJson.optJSONObject("book")
            val entries = if (book != null) book.optJSONArray("entries")?.toString() ?: "[]" else "[]"
            val result = module?.callAttr("update_world_book", name, description, entries)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(this, "描述已更新", Toast.LENGTH_SHORT).show()
                recreate()
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
            .setPositiveButton("确认删除") { _, _ -> deleteWorldBook(name) }
            .setNegativeButton("取消", null).show()
    }

    private fun deleteWorldBook(name: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("delete_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                saveEnabledWorldBooks()
                Toast.makeText(this, "世界书「${name}」已删除", Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                Toast.makeText(this, "删除失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
            Log.e("SettingsDetail", "saveEnabledWorldBooks 失败: ${e.message}", e)
        }
    }

    // ======================== 条目管理 ========================

    private fun showEntryListDialog(bookName: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("get_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(this, "加载失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val entries = book.optJSONArray("entries") ?: JSONArray()

            val scrollView = ScrollView(this).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dip(400)
                )
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            if (entries.length() == 0) {
                layout.addView(TextView(this).apply {
                    text = "暂无条目，点击下方按钮添加"; textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, 16, 0, 16); gravity = android.view.Gravity.CENTER
                })
            } else {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val id = entry.optString("id", "")
                    val content = entry.optString("content", "").take(40)
                    val keys = entry.optJSONArray("keys")
                    val keysStr = if (keys != null && keys.length() > 0) {
                        (0 until minOf(keys.length(), 3)).map { keys.optString(it) }.joinToString(", ")
                    } else "无关键词"
                    val constant = if (entry.optBoolean("constant", false)) " [常量]" else ""

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 10, 0, 10)
                        isClickable = true; isFocusable = true
                        setBackgroundResource(getSelectableItemBackground())
                        setOnClickListener { showEntryEditDialog(bookName, entry, false) }
                    }
                    val textLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    textLayout.addView(TextView(this).apply {
                        text = "$id$constant"; textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    })
                    textLayout.addView(TextView(this).apply {
                        text = "$content · $keysStr"; textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(textLayout)
                    // 删除按钮
                    val delBtn = Button(this).apply {
                        text = "✕"; textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.accent_red))
                        setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                        setPadding(16, 4, 0, 4)
                        setOnClickListener {
                            confirmDeleteEntry(bookName, id)
                        }
                    }
                    row.addView(delBtn)
                    layout.addView(row)
                    if (i < entries.length() - 1) layout.addView(createDividerView())
                }
            }

            layout.addView(createDividerView())
            val addBtn = Button(this).apply {
                text = "＋ 添加条目"; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryEditDialog(bookName, null, true) }
            }
            layout.addView(addBtn)

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(this)
                .setTitle("管理条目 — ${bookName}")
                .setView(scrollView)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "entryList 失败: ${e.message}", e)
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEntryEditDialog(bookName: String, existingEntry: JSONObject?, isNew: Boolean) {
        val entryId = if (isNew) "" else existingEntry?.optString("id", "") ?: ""
        val entryContent = if (isNew) "" else existingEntry?.optString("content", "") ?: ""
        val entryKeys = if (isNew) "" else {
            val keys = existingEntry?.optJSONArray("keys")
            if (keys != null) (0 until keys.length()).map { keys.optString(it) }.joinToString(", ") else ""
        }
        val entryComment = if (isNew) "" else existingEntry?.optString("comment", "") ?: ""
        val entryConstant = if (isNew) false else existingEntry?.optBoolean("constant", false) ?: false
        val entryProb = if (isNew) 100 else existingEntry?.optInt("probability", 100) ?: 100
        val entryPriority = if (isNew) 0 else existingEntry?.optInt("priority", 0) ?: 0

        val scrollView = ScrollView(this).apply {
            setPadding(48, 16, 48, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dip(420)
            )
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ID
        val etId = addEditField(layout, "条目ID *", entryId, "如 entry_001")
        if (!isNew) etId.isEnabled = false

        // 内容
        val etContent = addEditField(layout, "触发内容 *", entryContent, "触发时注入的上下文文本（至少20字）")
        etContent.minLines = 3

        // 关键词
        val etKeys = addEditField(layout, "关键词（逗号分隔）", entryKeys, "如: 猫, 宠物, 喵")

        // 备注
        val etComment = addEditField(layout, "备注", entryComment, "开发者的备注说明")

        // 常量开关
        val switchConstant = SwitchCompat(this).apply {
            isChecked = entryConstant
            text = "始终注入（constant）"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(switchConstant)

        // 概率
        val probLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        probLayout.addView(TextView(this).apply {
            text = "触发概率: "; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        val tvProb = TextView(this).apply {
            text = "${entryProb}%"; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(48, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        probLayout.addView(tvProb)
        val seekProb = SeekBar(this).apply {
            max = 100; progress = entryProb
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tvProb.text = "${p}%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        probLayout.addView(seekProb)
        layout.addView(probLayout)

        // 优先级
        val priLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        priLayout.addView(TextView(this).apply {
            text = "优先级: "; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        val tvPriority = TextView(this).apply {
            text = "${entryPriority}"; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
        }
        priLayout.addView(tvPriority)
        val seekPriority = SeekBar(this).apply {
            max = 20; progress = entryPriority
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tvPriority.text = "$p" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        priLayout.addView(seekPriority)
        layout.addView(priLayout)

        scrollView.addView(layout)

        val title = if (isNew) "添加条目" else "编辑条目 — $entryId"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                saveEntry(bookName, entryId, isNew, etId, etContent, etKeys, etComment,
                    switchConstant.isChecked, seekProb.progress, seekPriority.progress)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun addEditField(parent: LinearLayout, label: String, value: String, hint: String): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
            setPadding(0, 4, 0, 4)
        })
        val et = EditText(this).apply {
            setText(value); textSize = 14f; setHint(hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            setPadding(0, 8, 0, 8)
        }
        parent.addView(et)
        parent.addView(createDividerView())
        return et
    }

    private fun saveEntry(bookName: String, entryId: String, isNew: Boolean,
                          etId: EditText, etContent: EditText, etKeys: EditText, etComment: EditText,
                          constant: Boolean, probability: Int, priority: Int) {
        val newId = etId.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (newId.isEmpty()) { Toast.makeText(this, "条目ID不能为空", Toast.LENGTH_SHORT).show(); return }
        if (content.isEmpty()) { Toast.makeText(this, "触发内容不能为空", Toast.LENGTH_SHORT).show(); return }

        val keysStr = etKeys.text.toString().trim()
        val keys = if (keysStr.isNotEmpty()) {
            JSONArray(keysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        } else JSONArray()

        val entryJson = JSONObject().apply {
            put("id", newId)
            put("content", content)
            put("keys", keys)
            put("comment", etComment.text.toString().trim())
            put("constant", constant)
            put("probability", probability)
            put("priority", priority)
        }

        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = if (isNew) {
                module?.callAttr("add_world_book_entry", bookName, entryJson.toString())?.toString() ?: "{}"
            } else {
                module?.callAttr("update_world_book_entry", bookName, entryId, entryJson.toString())?.toString() ?: "{}"
            }
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(this, if (isNew) "条目已添加" else "条目已更新", Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                Toast.makeText(this, "保存失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteEntry(bookName: String, entryId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除条目")
            .setMessage("确认删除条目「${entryId}」吗？此操作不可撤销。")
            .setPositiveButton("确认删除") { _, _ ->
                try {
                    val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                    val result = module?.callAttr("delete_world_book_entry", bookName, entryId)?.toString() ?: "{}"
                    val json = JSONObject(result)
                    if (json.optString("status") == "ok") {
                        Toast.makeText(this, "条目已删除", Toast.LENGTH_SHORT).show()
                        recreate()
                    } else {
                        Toast.makeText(this, "删除失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ======================== 交叉审核 ========================

    private fun showAuditReportDialog(bookName: String) {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val result = module?.callAttr("validate_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(this, "审核失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val report = json.optJSONObject("report") ?: return
            val totalScore = report.optInt("score", 0)
            val passed = report.optBoolean("passed", false)
            val summary = report.optJSONObject("summary") ?: JSONObject()
            val dimensions = report.optJSONArray("dimensions") ?: JSONArray()

            val scrollView = ScrollView(this).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dip(400)
                )
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            // 总分卡片
            val scoreColor = if (passed) R.color.primary else R.color.accent_red
            val scoreEmoji = if (passed) "通过" else "未通过"
            layout.addView(TextView(this).apply {
                text = "综合评分: ${totalScore} 分 [$scoreEmoji]"; textSize = 18f
                setTextColor(ContextCompat.getColor(context, scoreColor))
                setPadding(0, 8, 0, 4); gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            layout.addView(TextView(this).apply {
                text = "条目: ${summary.optInt("total_entries")} · 常量: ${summary.optInt("constant_entries")} · 关键词: ${summary.optInt("total_keywords")} · 平均长度: ${summary.optInt("avg_content_length")}字"
                textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 0, 0, 12); gravity = android.view.Gravity.CENTER
            })
            layout.addView(createDividerView())

            // 各维度
            for (d in 0 until dimensions.length()) {
                val dim = dimensions.getJSONObject(d)
                val dimName = dim.optString("name", "")
                val dimScore = dim.optInt("score", 0)
                val issues = dim.optJSONArray("issues") ?: JSONArray()
                val suggestions = dim.optJSONArray("suggestions") ?: JSONArray()

                layout.addView(TextView(this).apply {
                    text = "$dimName: ${dimScore}分"; textSize = 15f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, 12, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                if (issues.length() == 0 && suggestions.length() == 0) {
                    layout.addView(TextView(this).apply {
                        text = "无问题"; textSize = 13f
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        setPadding(16, 0, 0, 4)
                    })
                }

                for (i in 0 until issues.length()) {
                    val issue = issues.getJSONObject(i)
                    val level = issue.optString("level", "info")
                    val msg = issue.optString("message", "")
                    val color = when (level) {
                        "error" -> R.color.accent_red
                        "warning" -> R.color.secondary
                        else -> R.color.text_secondary
                    }
                    val icon = when (level) {
                        "error" -> "✗ "
                        "warning" -> "⚠ "
                        else -> "ℹ "
                    }
                    layout.addView(TextView(this).apply {
                        text = "$icon$msg"; textSize = 12f
                        setTextColor(ContextCompat.getColor(context, color))
                        setPadding(16, 2, 0, 2)
                    })
                }

                for (i in 0 until suggestions.length()) {
                    val sug = suggestions.optString(i, "")
                    layout.addView(TextView(this).apply {
                        text = "→ $sug"; textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        setPadding(24, 2, 0, 2)
                    })
                }
            }

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(this)
                .setTitle("交叉审核报告 — ${bookName}")
                .setView(scrollView)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "audit 失败: ${e.message}", e)
            Toast.makeText(this, "审核失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dip(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun getSelectableItemBackground(): Int {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun addSectionTitle(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 24, 0, 8)
            paint.isFakeBoldText = true
        })
    }

    private fun addHintText(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
            setPadding(0, 0, 0, 8)
        })
    }

    private fun addEmptyHint(text: String) {
        contentLayout.addView(TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.darker_gray))
            setPadding(0, 8, 0, 8)
            gravity = android.view.Gravity.CENTER
        })
    }

    private fun addClickRow(label: String, value: String, valueColor: Int = R.color.text_secondary, iconRes: Int = 0, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            isClickable = true; isFocusable = true
            setBackgroundResource(getSelectableItemBackground())
            setOnClickListener { onClick() }
        }
        // 标签行：图标 + 文字
        if (iconRes != 0) {
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 6)
            }
            labelRow.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(20, 20).apply {
                    marginEnd = 8
                }
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
                paint.isFakeBoldText = true
                setPadding(0, 0, 0, 6)
            })
        }
        // 预览值：灰色小字
        row.addView(TextView(this).apply {
            text = value; textSize = 14f
            setTextColor(ContextCompat.getColor(context, valueColor))
        })
        contentLayout.addView(row)
    }

    private fun addDivider() {
        contentLayout.addView(createDividerView())
    }

    private fun createDividerView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.divider_thickness)
        )
        setBackgroundColor(ContextCompat.getColor(context, R.color.glass_border))
    }

    private fun applyAllParams() {
        try {
            val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
            val ctx = AppConfig.getContextSize(this)
            val temp = AppConfig.getTemperature(this).toDouble()
            val maxTk = AppConfig.getMaxTokens(this)
            val dialogues = AppConfig.getExampleDialogues(this)
            val model = AppConfig.getModel(this).let { if (it.isBlank()) "deepseek-v4-flash" else it }
            module?.callAttr("apply_params", ctx, temp, maxTk, dialogues, model)
        } catch (e: Exception) {
            Toast.makeText(this, "参数应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}