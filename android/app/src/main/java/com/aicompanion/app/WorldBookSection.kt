package com.aicompanion.app

import android.os.Handler
import android.os.Looper
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
 * 世界书设置区域 UI 组件。
 *
 * ISS-093: 本类直接依赖 SettingsDetailActivity 而非抽象接口。
 * 这是有意为之的设计决策：WorldBookSection 是 SettingsDetailActivity 专用的 UI 组件，
 * 需要访问 Activity 的 lifecycleScope、addSectionTitle()、addEmptyHint() 等内部方法。
 * 如果需要复用，可以将这些方法提取为 WorldBookViewHost 接口。
 */
class WorldBookSection(private val activity: SettingsDetailActivity) {

    private fun getModule() = com.chaquo.python.Python.getInstance().getModule("chat_bridge")

    fun build() {
        activity.addSectionTitle(activity.getString(R.string.section_world_book))

        val loadingLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        loadingLayout.addView(ProgressBar(activity).apply {
            indeterminateTintList = ContextCompat.getColorStateList(activity, R.color.primary)
        })
        loadingLayout.addView(TextView(activity).apply {
            text = activity.getString(R.string.status_loading); textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            setPadding(0, 12, 0, 0)
            gravity = Gravity.CENTER
        })
        activity.contentLayout.addView(loadingLayout)

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val module = getModule()
                val result = module?.callAttr("list_world_books")?.toString() ?: "{}"
                val json = JSONObject(result)
                if (json.optString("status") != "ok") {
                    withContext(Dispatchers.Main) {
                        activity.contentLayout.removeView(loadingLayout)
                        activity.addEmptyHint(activity.getString(R.string.world_book_load_failed))
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

                withContext(Dispatchers.Main) {
                    activity.contentLayout.removeView(loadingLayout)

                    if (books.length() == 0) {
                        activity.addEmptyHint(activity.getString(R.string.world_book_empty_hint))
                    } else {
                        activity.addHintText(activity.getString(R.string.world_book_usage_hint))
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
                    val createBtn = Button(activity).apply {
                        text = activity.getString(R.string.world_book_create_btn); textSize = 14f
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
                    activity.addEmptyHint(activity.getString(R.string.world_book_load_failed_with_msg, e.message))
                }
            }
        }
    }

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
            layoutParams = LinearLayout.LayoutParams(20, 20).apply { marginEnd = 12 }
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
            text = activity.getString(R.string.world_book_entry_summary, entries, description.take(30))
            textSize = 12f; setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textLayout)
        val switchView = SwitchCompat(activity)
        switchView.isChecked = isEnabled
        switchView.setOnCheckedChangeListener { _, checked ->
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val module = getModule()
                    if (checked) {
                        val r = module?.callAttr("enable_world_book", name)?.toString() ?: "{}"
                        val j = JSONObject(r)
                        withContext(Dispatchers.Main) {
                            if (j.optString("status") == "ok") {
                                saveEnabledWorldBooks()
                                Toast.makeText(activity, activity.getString(R.string.world_book_toast_enabled, name), Toast.LENGTH_SHORT).show()
                            } else {
                                switchView.isChecked = false
                                Toast.makeText(activity, activity.getString(R.string.world_book_toast_enable_failed, j.optString("message")), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        module?.callAttr("disable_world_book", name)
                        withContext(Dispatchers.Main) {
                            saveEnabledWorldBooks()
                            Toast.makeText(activity, activity.getString(R.string.world_book_toast_disabled, name), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        switchView.isChecked = !checked
                        Toast.makeText(activity, activity.getString(R.string.world_book_toast_operation_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        row.addView(switchView)
        activity.contentLayout.addView(row)
    }

    private fun showCreateWorldBookDialog() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
        }
        val etName = EditText(activity).apply {
            hint = activity.getString(R.string.world_book_hint_name); textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(TextView(activity).apply {
            text = activity.getString(R.string.world_book_label_name); textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.darker_gray))
        })
        layout.addView(etName)
        layout.addView(activity.createDividerView())
        val etDesc = EditText(activity).apply {
            hint = activity.getString(R.string.world_book_hint_description); textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8); maxLines = 3
        }
        layout.addView(TextView(activity).apply {
            text = activity.getString(R.string.world_book_label_description); textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.darker_gray))
        })
        layout.addView(etDesc)

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.world_book_dialog_create_title))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.world_book_btn_create)) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(activity, activity.getString(R.string.world_book_error_name_empty), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                createWorldBook(name, etDesc.text.toString().trim())
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null).show()
    }

    private fun createWorldBook(name: String, description: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("create_world_book", name, description, "[]")?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_created, name), Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_create_failed, json.optString("message")), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_create_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditWorldBookDialog(name: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("get_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_load_failed, json.optString("message")), Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val description = book.optString("description", "")
            val entryCount = book.optInt("entry_count", 0)

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            }
            layout.addView(TextView(activity).apply {
                text = activity.getString(R.string.world_book_label_desc_with_value, description.take(50)); textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.darker_gray)); setPadding(0, 0, 0, 8)
            })
            layout.addView(TextView(activity).apply {
                text = activity.getString(R.string.world_book_label_entry_count, entryCount); textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.darker_gray)); setPadding(0, 0, 0, 12)
            })
            layout.addView(activity.createDividerView())
            val editDescBtn = Button(activity).apply {
                text = activity.getString(R.string.world_book_action_edit_description); textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEditDescriptionDialog(name, description) }
            }
            layout.addView(editDescBtn)
            layout.addView(activity.createDividerView())
            val editEntriesBtn = Button(activity).apply {
                text = activity.getString(R.string.world_book_action_manage_entries, entryCount); textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryListDialog(name) }
            }
            layout.addView(editEntriesBtn)
            layout.addView(activity.createDividerView())
            val auditBtn = Button(activity).apply {
                text = activity.getString(R.string.world_book_action_audit); textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.secondary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showAuditReportDialog(name) }
            }
            layout.addView(auditBtn)
            layout.addView(activity.createDividerView())
            val deleteBtn = Button(activity).apply {
                text = activity.getString(R.string.world_book_action_delete); textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.accent_red))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showDeleteWorldBookConfirmDialog(name) }
            }
            layout.addView(deleteBtn)

            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.world_book_dialog_edit_title, name))
                .setView(layout)
                .setPositiveButton(activity.getString(R.string.btn_close), null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "editWorldBook 失败: ${e.message}", e)
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_load_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

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
            .setTitle(activity.getString(R.string.world_book_action_edit_description))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.btn_save)) { _, _ ->
                val newDesc = etDesc.text.toString().trim()
                updateWorldBookDescription(name, newDesc)
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null).show()
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
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_desc_updated), Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_update_failed, json.optString("message")), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_update_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteWorldBookConfirmDialog(name: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.world_book_action_delete))
            .setMessage(activity.getString(R.string.world_book_msg_delete_confirm, name))
            .setPositiveButton(activity.getString(R.string.world_book_btn_confirm_delete)) { _, _ -> deleteWorldBook(name) }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null).show()
    }

    private fun deleteWorldBook(name: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("delete_world_book", name)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                saveEnabledWorldBooks()
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_deleted, name), Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_delete_failed, json.optString("message")), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_delete_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

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
            activity.lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_save_state_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEntryListDialog(bookName: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("get_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_load_failed, json.optString("message")), Toast.LENGTH_SHORT).show(); return
            }
            val book = json.optJSONObject("book") ?: return
            val entries = book.optJSONArray("entries") ?: JSONArray()

            val scrollView = ScrollView(activity).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dialogHeight())
            }
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

            if (entries.length() == 0) {
                layout.addView(TextView(activity).apply {
                    text = activity.getString(R.string.world_book_empty_entries); textSize = 14f
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
                    } else activity.getString(R.string.world_book_label_no_keywords)
                    val constant = if (entry.optBoolean("constant", false)) activity.getString(R.string.world_book_suffix_constant) else ""

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
                    val delBtn = Button(activity).apply {
                        text = "✕"; textSize = 14f
                        setTextColor(ContextCompat.getColor(activity, R.color.accent_red))
                        setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                        setPadding(16, 4, 0, 4)
                        setOnClickListener { confirmDeleteEntry(bookName, id) }
                    }
                    row.addView(delBtn)
                    layout.addView(row)
                    if (i < entries.length() - 1) layout.addView(activity.createDividerView())
                }
            }

            layout.addView(activity.createDividerView())
            val addBtn = Button(activity).apply {
                text = activity.getString(R.string.world_book_btn_add_entry); textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(activity, android.R.color.transparent))
                setPadding(0, 12, 0, 12)
                setOnClickListener { showEntryEditDialog(bookName, null, true) }
            }
            layout.addView(addBtn)

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.world_book_dialog_manage_entries, bookName))
                .setView(scrollView)
                .setPositiveButton(activity.getString(R.string.btn_close), null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "entryList 失败: ${e.message}", e)
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_load_failed, e.message), Toast.LENGTH_SHORT).show()
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

        val scrollView = ScrollView(activity).apply {
            setPadding(48, 16, 48, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dialogHeight())
        }
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val etId = addEditField(layout, activity.getString(R.string.world_book_label_entry_id), entryId, activity.getString(R.string.world_book_hint_entry_id))
        if (!isNew) etId.isEnabled = false

        val etContent = addEditField(layout, activity.getString(R.string.world_book_label_entry_content), entryContent, activity.getString(R.string.world_book_hint_entry_content))
        etContent.minLines = 3

        val etKeys = addEditField(layout, activity.getString(R.string.world_book_label_entry_keys), entryKeys, activity.getString(R.string.world_book_hint_entry_keys))

        val etComment = addEditField(layout, activity.getString(R.string.world_book_label_entry_comment), entryComment, activity.getString(R.string.world_book_hint_entry_comment))

        val switchConstant = SwitchCompat(activity).apply {
            isChecked = entryConstant
            text = activity.getString(R.string.world_book_label_always_inject)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(switchConstant)

        val probLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        probLayout.addView(TextView(activity).apply {
            text = activity.getString(R.string.world_book_label_probability); textSize = 14f
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

        val priLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        priLayout.addView(TextView(activity).apply {
            text = activity.getString(R.string.world_book_label_priority); textSize = 14f
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

        val title = if (isNew) activity.getString(R.string.world_book_dialog_add_entry) else activity.getString(R.string.world_book_dialog_edit_entry, entryId)
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(activity.getString(R.string.btn_save)) { _, _ ->
                saveEntry(bookName, entryId, isNew, etId, etContent, etKeys, etComment, switchConstant.isChecked, seekProb.progress, seekPriority.progress)
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null).show()
    }

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

    private fun saveEntry(bookName: String, entryId: String, isNew: Boolean, etId: EditText, etContent: EditText, etKeys: EditText, etComment: EditText, constant: Boolean, probability: Int, priority: Int) {
        val newId = etId.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (newId.isEmpty()) { Toast.makeText(activity, activity.getString(R.string.world_book_error_entry_id_empty), Toast.LENGTH_SHORT).show(); return }
        if (content.isEmpty()) { Toast.makeText(activity, activity.getString(R.string.world_book_error_entry_content_empty), Toast.LENGTH_SHORT).show(); return }

        val keysStr = etKeys.text.toString().trim()
        val keys = if (keysStr.isNotEmpty()) JSONArray(keysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }) else JSONArray()

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
                Toast.makeText(activity, if (isNew) activity.getString(R.string.world_book_toast_entry_added) else activity.getString(R.string.world_book_toast_entry_updated), Toast.LENGTH_SHORT).show()
                activity.recreate()
            } else {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_entry_save_failed, json.optString("message")), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_entry_save_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteEntry(bookName: String, entryId: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.world_book_dialog_delete_entry))
            .setMessage(activity.getString(R.string.world_book_msg_delete_entry_confirm, entryId))
            .setPositiveButton(activity.getString(R.string.world_book_btn_confirm_delete)) { _, _ ->
                try {
                    val module = getModule()
                    val result = module?.callAttr("delete_world_book_entry", bookName, entryId)?.toString() ?: "{}"
                    val json = JSONObject(result)
                    if (json.optString("status") == "ok") {
                        Toast.makeText(activity, activity.getString(R.string.world_book_toast_entry_deleted), Toast.LENGTH_SHORT).show()
                        activity.recreate()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.world_book_toast_delete_failed, json.optString("message")), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, activity.getString(R.string.world_book_toast_delete_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null).show()
    }

    private fun showAuditReportDialog(bookName: String) {
        try {
            val module = getModule()
            val result = module?.callAttr("validate_world_book", bookName)?.toString() ?: "{}"
            val json = JSONObject(result)
            if (json.optString("status") != "ok") {
                Toast.makeText(activity, activity.getString(R.string.world_book_toast_audit_failed, json.optString("message")), Toast.LENGTH_SHORT).show(); return
            }
            val report = json.optJSONObject("report") ?: return
            val totalScore = report.optInt("score", 0)
            val passed = report.optBoolean("passed", false)
            val summary = report.optJSONObject("summary") ?: JSONObject()
            val dimensions = report.optJSONArray("dimensions") ?: JSONArray()

            val scrollView = ScrollView(activity).apply {
                setPadding(48, 16, 48, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dialogHeight())
            }
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

            val scoreColor = if (passed) R.color.primary else R.color.accent_red
            val scoreEmoji = if (passed) activity.getString(R.string.world_book_audit_passed) else activity.getString(R.string.world_book_audit_failed_label)
            layout.addView(TextView(activity).apply {
                text = activity.getString(R.string.world_book_label_audit_score, totalScore, scoreEmoji); textSize = 18f
                setTextColor(ContextCompat.getColor(activity, scoreColor))
                setPadding(0, 8, 0, 4); gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            layout.addView(TextView(activity).apply {
                text = activity.getString(R.string.world_book_label_audit_summary, summary.optInt("total_entries"), summary.optInt("constant_entries"), summary.optInt("total_keywords"), summary.optInt("avg_content_length"))
                textSize = 12f; setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                setPadding(0, 0, 0, 12); gravity = android.view.Gravity.CENTER
            })
            layout.addView(activity.createDividerView())

            for (d in 0 until dimensions.length()) {
                val dim = dimensions.getJSONObject(d)
                val dimName = dim.optString("name", "")
                val dimScore = dim.optInt("score", 0)
                val issues = dim.optJSONArray("issues") ?: JSONArray()
                val suggestions = dim.optJSONArray("suggestions") ?: JSONArray()

                layout.addView(TextView(activity).apply {
                    text = activity.getString(R.string.world_book_audit_dim_score, dimName, dimScore); textSize = 15f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    setPadding(0, 12, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                if (issues.length() == 0 && suggestions.length() == 0) {
                    layout.addView(TextView(activity).apply {
                        text = activity.getString(R.string.world_book_label_no_issues); textSize = 13f
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
                        "error" -> activity.getString(R.string.world_book_prefix_error)
                        "warning" -> activity.getString(R.string.world_book_prefix_warning)
                        else -> activity.getString(R.string.world_book_prefix_info)
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
                        text = activity.getString(R.string.world_book_prefix_suggestion, sug); textSize = 12f
                        setTextColor(ContextCompat.getColor(activity, R.color.primary))
                        setPadding(24, 2, 0, 2)
                    })
                }
            }

            scrollView.addView(layout)
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.world_book_dialog_audit_title, bookName))
                .setView(scrollView)
                .setPositiveButton(activity.getString(R.string.btn_close), null).show()
        } catch (e: Exception) {
            Log.e("SettingsDetail", "audit 失败: ${e.message}", e)
            Toast.makeText(activity, activity.getString(R.string.world_book_toast_audit_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
}