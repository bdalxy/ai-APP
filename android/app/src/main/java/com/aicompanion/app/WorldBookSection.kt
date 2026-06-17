package com.aicompanion.app

import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书（World Book）管理模块
 * 从 SettingsDetailActivity 提取，负责世界书的创建、编辑、条目管理、交叉审核等功能。
 * 通过持有 SettingsDetailActivity 引用，复用其 UI 工具方法和属性。
 */
class WorldBookSection(private val activity: SettingsDetailActivity) {

    /** 缓存 Python 模块引用，避免重复获取 */
    private fun getModule() = com.chaquo.python.Python.getInstance().getModule("chat_bridge")

    /**
     * 构建世界书管理页面（异步加载，避免主线程调用 Python）
     */
    fun build() {
        activity.addSectionTitle("世界书（知识/常识注入）")

        // 添加加载指示器
        val loadingLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        loadingLayout.addView(ProgressBar(activity).apply {
            indeterminateTintList = ContextCompat.getColorStateList(activity, R.color.primary)
        })
        loadingLayout.addView(TextView(activity).apply {
            text = "加载中..."; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            setPadding(0, 12, 0, 0)
            gravity = Gravity.CENTER
        })
        activity.contentLayout.addView(loadingLayout)

        // 在后台线程调用 Python，避免阻塞 UI
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val module = getModule()
                val result = module?.callAttr("list_world_books")?.toString() ?: "{}"
                val json = JSONObject(result)
                if (json.optString("status") != "ok") {
                    withContext(Dispatchers.Main) {
                        activity.contentLayout.removeView(loadingLayout)
                        activity.addEmptyHint("世界书加载失败")
                    }
                    return@launch
                }
                val books = json.optJSONArray("books") ?: JSONArray()

                val enabledResult = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
                val enabledJson = JSONObject(enabledResult)
                val enabledArray = enabledJson.optJSONArray("enabled")
                val enabledSet = mutableSetOf<String>()
                if (enabledArray != null) {
                    for (i in 0 until enabledArray.length()) enabledSet.add(enabledArray.optString(i, ""))
                }

                // 切回主线程更新 UI
                withContext(Dispatchers.Main) {
                    activity.contentLayout.removeView(loadingLayout)

                    if (books.length() == 0) {
                        activity.addEmptyHint("暂无世界书，点击下方按钮创建")
                    } else {
                        activity.addHintText("点击条目编辑，右滑开关启用/禁用")
                        activity.addDivider()
                        for (i in 0 until books.length()) {
                            val book = books.getJSONObject(i)
                            val name = book.optString("name", "")
                            val description = book.optString("description", "")
                            val entries = book.optInt("entry_count", 0)
                            addWorldBookRow(name, description, entries, name in enabledSet)
                            if (i < books.length() - 1) activity.addDivider()
                        }
                    }

                    activity.addDivider()
                    // 创建按钮
                    val createBtn = Button(activity).apply {
                        text = "＋ 创建世界书"; textSize = 14f
                        setTextColor(ContextCompat.getColor(activity, R.color.primary))
                        setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                        setPadding(0, 12, 0, 12)
                        setOnClickListener { showCreateWorldBookDialog() }
                    }
                    activity.contentLayout.addView(createBtn)
                }
            } catch (e: Exception) {
                Log.e("SettingsDetail", "worldBook 失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    activity.contentLayout.removeView(loadingLayout)
                    activity.addEmptyHint("世界书加载失败: ${e.message}")
                }
            }
        }
    }

    // ======================== 世界书行条目 ========================

    private fun addWorldBookRow(name: String, description: String, entries: Int, isEnabled: Boolean) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            isClickable = true; isFocusable = true
            setBackgroundResource(activity.getSelectableItemBackground())
            setOnClickListener { showEditWorldBookDialog(name) }
        }
        row.addView(android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.ic_book)
            layoutParams = LinearLayout.LayoutParams(20, 20).apply {
                marginEnd = 12
            }
            setColorFilter(ContextCompat.getColor(activity, R.color.primary))
        })
        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textLayout.addView(TextView(activity).apply {
            text = name; textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 0, 0, 2)
        })
        textLayout.addView(TextView(activity).apply {
            text = "${entries}条 · ${description.take(30)}"
            textSize = 12f; setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textLayout)
        val switch = SwitchCompat(activity).apply {
            isChecked = isEnabled
            setOnCheckedChangeListener { _, checked ->
                // 在后台线程调用 Python，避免阻塞 UI
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = getModule()
                        if (checked) {
                            val r = module?.callAttr("enable_world_book", name)?.toString() ?: "{}"
                            val j = JSONObject(r)
                            withContext(Dispatchers.Main) {
                                if (j.optString("status") == "ok") {
                                    saveEnabledWorldBooks()
                                    Toast.makeText(activity, "已启用「${name}」", Toast.LENGTH_SHORT).show()
                                } else {
                                    switch.isChecked = false
                                    Toast.makeText(activity, "启用失败: ${j.optString("message")}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            module?.callAttr("disable_world_book", name)
                            withContext(Dispatchers.Main) {
                                saveEnabledWorldBooks()
                                Toast.makeText(activity, "已禁用「${name}」", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            switch.isChecked = !checked
                            Toast.makeText(activity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        row.addView(switch)
        activity.contentLayout.addView(row)
    }

    // ======================== 创建世界书 ========================

    private fun showCreateWorldBookDialog() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
        }
        val etName = EditText(activity).apply {
            hint = "世界书名称（如：二次元幻想世界）"; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(TextView(activity).apply {
            text = "名称"; textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.darker_gray))
        })
        layout.addView(etName)
        layout.addView(activity.createDividerView())
        val etDesc = EditText(activity).apply {
            hint = "简短描述（如：一个剑与魔法的异世界）"; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 3
        }
        layout.addView(TextView(activity).apply {
            text = "描述"; textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.darker_gray))
        })
        layout.addView(etDesc)

        MaterialAlertDialogBuilder(activity)
            .setTitle("创建世界书")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                createWorldBook(name, etDesc.text.toString().trim())
            }
            .setNegativeButton("取消", null).show()
    }

    private fun createWorldBook(name: String, description: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("create_world_book", name, description, "[]")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(activity, "世界书「${name}」已创建", Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, "创建失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 编辑世界书 ========================

    private fun showEditWorldBookDialog(name: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, "加载失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val description = book.optString("description", "")
            val entryCount = book.optInt("entry_count", 0)

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            }
            layout.addView(TextView(activity).apply {
                text = "描述：${description.take(50)}"; textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.darker_gray)); setPadding(0, 0, 0, 8)
            })
            layout.addView(TextView(activity).apply {
                text = "条目数：${entryCount} 条"; textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.darker_gray)); setPadding(0, 0, 0, 12)
            })
            layout.addView(activity.createDividerView())
            val editDescBtn = Button(activity).apply {
                text = "编辑描述"; textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEditDescriptionDialog(name, description) }
            }
            layout.addView(editDescBtn)
            layout.addView(activity.createDividerView())
            val editEntriesBtn = Button(activity).apply {
                text = "管理条目（${entryCount}条）"; textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryListDialog(name) }
            }
            layout.addView(editEntriesBtn)
            layout.addView(activity.createDividerView())
            val auditBtn = Button(activity).apply {
                text = "交叉审核"; textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.secondary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showAuditReportDialog(name) }
            }
            layout.addView(auditBtn)
            layout.addView(activity.createDividerView())
            val deleteBtn = Button(activity).apply {
                text = "删除世界书"; textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.accent_red))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showDeleteWorldBookConfirmDialog(name) }
            }
            layout.addView(deleteBtn)

            MaterialAlertDialogBuilder(activity)
                .setTitle("编辑世界书 — ${name}")
                .setView(layout)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "editWorldBook 失败: ${e.message}", e)
            Toast.makeText(activity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 编辑描述 ========================

    private fun showEditDescriptionDialog(name: String, currentDescription: String) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
        }
        val etDesc = EditText(activity).apply {
            setText(currentDescription); textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 5
        }
        layout.addView(etDesc)
        MaterialAlertDialogBuilder(activity)
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
            val module = getModule()
            val bookResult = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val bookJson = JSONObject(bookResult)
            val book = bookJson.optJSONObject("book")
            val entries = if (book != null) book.optJSONArray("entries")?.toString() ?: "[]" else "[]"
            val result = module?.callAttr("update_world_book", name, description, entries)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(activity, "描述已更新", Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, "更新失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 删除世界书 ========================

    private fun showDeleteWorldBookConfirmDialog(name: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("删除世界书")
            .setMessage("确定要删除世界书「${name}」吗？\n\n此操作不可撤销！")
            .setPositiveButton("确认删除") { _, _ -> deleteWorldBook(name) }
            .setNegativeButton("取消", null).show()
    }

    private fun deleteWorldBook(name: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("delete_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                saveEnabledWorldBooks()
                Toast.makeText(activity, "世界书「${name}」已删除", Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, "删除失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 保存已启用状态 ========================

    private fun saveEnabledWorldBooks() {
        try {
            val module = getModule()
            val result = module?.callAttr("get_enabled_world_books")?.toString() ?: "{}"
            val json = JSONObject(result)
            val enabled = json.optJSONArray("enabled") ?: return
            val names = (0 until enabled.length()).map { enabled.optString(it, "") }.filter { it.isNotEmpty() }
            activity.prefs.edit().putString("enabled_world_books", names.joinToString(",")).apply()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "saveEnabledWorldBooks 失败: ${e.message}", e)
            activity.runOnUiThread {
                Toast.makeText(activity, "保存世界书状态失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ======================== 条目管理 ========================

    private fun showEntryListDialog(bookName: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("get_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, "加载失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val entries = book.optJSONArray("entries") ?: JSONArray()

            val scrollView = ScrollView(activity).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dialogHeight()  // 动态计算：屏幕高度 55%
                )
            }
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            if (entries.length() == 0) {
                layout.addView(TextView(activity).apply {
                    text = "暂无条目，点击下方按钮添加"; textSize = 14f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
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

                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 10, 0, 10)
                        isClickable = true; isFocusable = true
                        setBackgroundResource(activity.getSelectableItemBackground())
                        setOnClickListener { showEntryEditDialog(bookName, entry, false) }
                    }
                    val textLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    textLayout.addView(TextView(activity).apply {
                        text = "$id$constant"; textSize = 14f
                        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    })
                    textLayout.addView(TextView(activity).apply {
                        text = "$content · $keysStr"; textSize = 12f
                        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(textLayout)
                    // 删除按钮
                    val delBtn = Button(activity).apply {
                        text = "✕"; textSize = 14f
                        setTextColor(ContextCompat.getColor(activity, R.color.accent_red))
                        setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                        setPadding(16, 4, 0, 4)
                        setOnClickListener {
                            confirmDeleteEntry(bookName, id)
                        }
                    }
                    row.addView(delBtn)
                    layout.addView(row)
                    if (i < entries.length() - 1) layout.addView(activity.createDividerView())
                }
            }

            layout.addView(activity.createDividerView())
            val addBtn = Button(activity).apply {
                text = "＋ 添加条目"; textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryEditDialog(bookName, null, true) }
            }
            layout.addView(addBtn)

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(activity)
                .setTitle("管理条目 — ${bookName}")
                .setView(scrollView)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "entryList 失败: ${e.message}", e)
            Toast.makeText(activity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 条目编辑对话框 ========================

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

        val scrollView = ScrollView(activity).apply {
            setPadding(48, 16, 48, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dialogHeight()  // 动态计算：屏幕高度 55%
            )
        }
        val layout = LinearLayout(activity).apply {
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
        val switchConstant = SwitchCompat(activity).apply {
            isChecked = entryConstant
            text = "始终注入（constant）"
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(switchConstant)

        // 概率
        val probLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        probLayout.addView(TextView(activity).apply {
            text = "触发概率: "; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        val tvProb = TextView(activity).apply {
            text = "${entryProb}%"; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(48, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        probLayout.addView(tvProb)
        val seekProb = SeekBar(activity).apply {
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
        val priLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        priLayout.addView(TextView(activity).apply {
            text = "优先级: "; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        val tvPriority = TextView(activity).apply {
            text = "${entryPriority}"; textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.primary))
        }
        priLayout.addView(tvPriority)
        val seekPriority = SeekBar(activity).apply {
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
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                saveEntry(bookName, entryId, isNew, etId, etContent, etKeys, etComment,
                    switchConstant.isChecked, seekProb.progress, seekPriority.progress)
            }
            .setNegativeButton("取消", null).show()
    }

    // ======================== 编辑字段辅助 ========================

    private fun addEditField(parent: LinearLayout, label: String, value: String, hint: String): EditText {
        parent.addView(TextView(activity).apply {
            text = label; textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.darker_gray))
            setPadding(0, 4, 0, 4)
        })
        val et = EditText(activity).apply {
            setText(value); textSize = 14f; setHint(hint)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_tertiary))
            setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
            setPadding(0, 8, 0, 8)
        }
        parent.addView(et)
        parent.addView(activity.createDividerView())
        return et
    }

    // ======================== 保存条目 ========================

    private fun saveEntry(bookName: String, entryId: String, isNew: Boolean,
                          etId: EditText, etContent: EditText, etKeys: EditText, etComment: EditText,
                          constant: Boolean, probability: Int, priority: Int) {
        val newId = etId.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (newId.isEmpty()) { Toast.makeText(activity, "条目ID不能为空", Toast.LENGTH_SHORT).show(); return }
        if (content.isEmpty()) { Toast.makeText(activity, "触发内容不能为空", Toast.LENGTH_SHORT).show(); return }

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
            val module = getModule()
            val result = if (isNew) {
                module?.callAttr("add_world_book_entry", bookName, entryJson.toString())?.toString() ?: "{}"
            } else {
                module?.callAttr("update_world_book_entry", bookName, entryId, entryJson.toString())?.toString() ?: "{}"
            }
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(activity, if (isNew) "条目已添加" else "条目已更新", Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, "保存失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 删除条目 ========================

    private fun confirmDeleteEntry(bookName: String, entryId: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("删除条目")
            .setMessage("确认删除条目「${entryId}」吗？此操作不可撤销。")
            .setPositiveButton("确认删除") { _, _ ->
                try {
                    val module = getModule()
                    val result = module?.callAttr("delete_world_book_entry", bookName, entryId)?.toString() ?: "{}"
                    val json = JSONObject(result)
                    if (json.optString("status") == "ok") {
                        Toast.makeText(activity, "条目已删除", Toast.LENGTH_SHORT).show()
                        activity.recreate()
                    } else {
                        Toast.makeText(activity, "删除失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ======================== 交叉审核 ========================

    private fun showAuditReportDialog(bookName: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("validate_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, "审核失败: ${json.optString("message")}", Toast.LENGTH_SHORT).show(); return
            }
            val report = json.optJSONObject("report") ?: return
            val totalScore = report.optInt("score", 0)
            val passed = report.optBoolean("passed", false)
            val summary = report.optJSONObject("summary") ?: JSONObject()
            val dimensions = report.optJSONArray("dimensions") ?: JSONArray()

            val scrollView = ScrollView(activity).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dialogHeight()  // 动态计算：屏幕高度 55%
                )
            }
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            // 总分卡片
            val scoreColor = if (passed) R.color.primary else R.color.accent_red
            val scoreEmoji = if (passed) "通过" else "未通过"
            layout.addView(TextView(activity).apply {
                text = "综合评分: ${totalScore} 分 [$scoreEmoji]"; textSize = 18f
                setTextColor(ContextCompat.getColor(activity, scoreColor))
                setPadding(0, 8, 0, 4); gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            layout.addView(TextView(activity).apply {
                text = "条目: ${summary.optInt("total_entries")} · 常量: ${summary.optInt("constant_entries")} · 关键词: ${summary.optInt("total_keywords")} · 平均长度: ${summary.optInt("avg_content_length")}字"
                textSize = 12f; setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                setPadding(0, 0, 0, 12); gravity = android.view.Gravity.CENTER
            })
            layout.addView(activity.createDividerView())

            // 各维度
            for (d in 0 until dimensions.length()) {
                val dim = dimensions.getJSONObject(d)
                val dimName = dim.optString("name", "")
                val dimScore = dim.optInt("score", 0)
                val issues = dim.optJSONArray("issues") ?: JSONArray()
                val suggestions = dim.optJSONArray("suggestions") ?: JSONArray()

                layout.addView(TextView(activity).apply {
                    text = "$dimName: ${dimScore}分"; textSize = 15f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    setPadding(0, 12, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                if (issues.length() == 0 && suggestions.length() == 0) {
                    layout.addView(TextView(activity).apply {
                        text = "无问题"; textSize = 13f
                        setTextColor(ContextCompat.getColor(activity, R.color.primary))
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
                    layout.addView(TextView(activity).apply {
                        text = "$icon$msg"; textSize = 12f
                        setTextColor(ContextCompat.getColor(activity, color))
                        setPadding(16, 2, 0, 2)
                    })
                }

                for (i in 0 until suggestions.length()) {
                    val sug = suggestions.optString(i, "")
                    layout.addView(TextView(activity).apply {
                        text = "→ $sug"; textSize = 12f
                        setTextColor(ContextCompat.getColor(activity, R.color.primary))
                        setPadding(24, 2, 0, 2)
                    })
                }
            }

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(activity)
                .setTitle("交叉审核报告 — ${bookName}")
                .setView(scrollView)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "audit 失败: ${e.message}", e)
            Toast.makeText(activity, "审核失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
