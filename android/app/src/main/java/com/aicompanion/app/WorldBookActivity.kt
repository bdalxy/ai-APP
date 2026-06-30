package com.aicompanion.app

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class WorldBookActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WorldBook"
        private const val DEFAULT_BOOK_NAME = "_common_sense"
    }

    private lateinit var rootView: View
    private lateinit var rvEntries: androidx.recyclerview.widget.RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var layoutLoading: View
    private lateinit var tvEntryCount: TextView
    private lateinit var fabAddEntry: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var ivEmptyIcon: ImageView

    private val entries = mutableListOf<WorldBookEntry>()
    private lateinit var adapter: WorldBookAdapter
    private var modalContainer: FrameLayout? = null
    private var isModalShowing = false
    private var featherAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_world_book)
        ViewUtils.setupEdgeToEdge(this)
        bindViews()
        setupListeners()
        loadEntries()
    }

    override fun onDestroy() {
        super.onDestroy()
        featherAnimator?.cancel()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.worldBookRoot)
        rvEntries = findViewById(R.id.rvEntries)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvEntryCount = findViewById(R.id.tvEntryCount)
        fabAddEntry = findViewById(R.id.fabAddEntry)
        ivEmptyIcon = layoutEmpty.findViewById(R.id.ivEmptyIcon)
        ViewUtils.applyInsets(rootView)
    }

    private fun setupListeners() {
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        fabAddEntry.setOnClickListener { showAddModal() }
        adapter = WorldBookAdapter(this, entries,
            onEdit = { entry -> showEditModal(entry) },
            onDelete = { entry, pos -> performForget(entry, pos) }
        )
        rvEntries.adapter = adapter
        rvEntries.layoutManager = LinearLayoutManager(this)
    }

    private fun loadEntries() {
        showLoading(true); showEmpty(false)
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    val bookResult = module.callAttr("get_world_book", DEFAULT_BOOK_NAME).toString()
                    val json = JSONObject(bookResult)
                    if (json.optString("status") == "ok") {
                        json.optJSONObject("book")?.optJSONArray("entries")?.toString() ?: "[]"
                    } else {
                        module.callAttr("create_world_book", DEFAULT_BOOK_NAME, getString(R.string.default_world_book_description), "[]")
                        "[]"
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "加载失败: ${e.message}", e); null }
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (result != null) try { parseEntries(result) } catch (e: Exception) { showEmpty(true) }
                else showEmpty(true)
            }
        }
    }

    private fun parseEntries(jsonStr: String) {
        entries.clear()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val keysArray = obj.optJSONArray("keys")
            val tags = mutableListOf<String>()
            if (keysArray != null) {
                for (j in 0 until keysArray.length()) {
                    tags.add(keysArray.getString(j))
                }
            }
            entries.add(WorldBookEntry(
                id = obj.optString("id", ""),
                category = obj.optString("comment", ""),
                content = obj.optString("content", ""),
                tags = tags,
                priority = obj.optInt("priority", 0),
                createdAt = obj.optString("created_at", currentTimeIso()),
                updatedAt = obj.optString("updated_at", currentTimeIso())
            ))
        }
        adapter.notifyDataSetChanged()
        updateEntryCount()
        showEmpty(entries.isEmpty())
    }

    private fun showAddModal() {
        if (isModalShowing) return; isModalShowing = true
        val rootLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.worldBookRoot) ?: return
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_modal_overlay))
            setOnClickListener { dismissModal() }
        }
        val card = createModalCard(getString(R.string.title_add_entry), "", "") { category, content ->
            performAddEntry(category, content); dismissModal()
        }
        card.setOnClickListener {}
        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins((32 * resources.displayMetrics.density).toInt(), 0, (32 * resources.displayMetrics.density).toInt(), 0)
        }
        container.addView(card, cardParams)
        rootLayout.addView(container)
        modalContainer = container
        card.alpha = 0f; card.scaleX = 0.85f; card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start()
    }

    private fun showEditModal(entry: WorldBookEntry) {
        if (isModalShowing) return; isModalShowing = true
        val rootLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.worldBookRoot) ?: return
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_modal_overlay))
            setOnClickListener { dismissModal() }
        }
        val card = createModalCard(getString(R.string.title_edit_entry), entry.category, entry.content) { category, content ->
            performUpdateEntry(entry, category, content); dismissModal()
        }
        card.setOnClickListener {}
        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins((32 * resources.displayMetrics.density).toInt(), 0, (32 * resources.displayMetrics.density).toInt(), 0)
        }
        container.addView(card, cardParams)
        rootLayout.addView(container)
        modalContainer = container
        card.alpha = 0f; card.scaleX = 0.85f; card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start()
    }

    private fun createModalCard(title: String, category: String, content: String, onSave: (String, String) -> Unit): View {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = ContextCompat.getDrawable(this@WorldBookActivity, R.drawable.bg_world_modal)
            clipToOutline = true; elevation = 8 * density
        }
        card.addView(TextView(this).apply {
            this.text = title; textSize = 18f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_warm))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.label_category); textSize = 13f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_secondary))
            setPadding(0, 0, 0, (6 * density).toInt())
        })
        val etCategory = EditText(this).apply {
            this.setText(category); this.hint = getString(R.string.hint_entry_category); textSize = 14f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_warm))
            setHintTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_hint))
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, android.R.color.transparent))
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt()); maxLines = 1
        }
        card.addView(etCategory)
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_card_border))
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.label_content); textSize = 13f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_secondary))
            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
        })
        val etContent = EditText(this).apply {
            this.setText(content); this.hint = getString(R.string.hint_entry_content); textSize = 14f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_warm))
            setHintTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_hint))
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, android.R.color.transparent))
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt()); minLines = 3; gravity = Gravity.TOP
        }
        card.addView(etContent)
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = (8 * density).toInt() }
            setBackgroundColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_card_border))
        })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        btnRow.addView(TextView(this).apply {
            text = getString(R.string.btn_cancel); textSize = 14f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_text_secondary))
            setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
            setOnClickListener { dismissModal() }
        })
        btnRow.addView(TextView(this).apply {
            text = getString(R.string.btn_save_entry); textSize = 14f
            setTextColor(ContextCompat.getColor(this@WorldBookActivity, R.color.wb_tag_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                val cat = etCategory.text.toString().trim()
                val con = etContent.text.toString().trim()
                if (con.isEmpty()) { Toast.makeText(this@WorldBookActivity, getString(R.string.toast_entry_content_empty), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                onSave(cat, con)
            }
        })
        card.addView(btnRow)
        return card
    }

    private fun dismissModal() {
        modalContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        modalContainer = null; isModalShowing = false
    }

    private fun performAddEntry(category: String, content: String) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    val entryJson = JSONObject().apply {
                        put("id", UUID.randomUUID().toString().take(8))
                        put("content", content); put("keys", JSONArray()); put("comment", category)
                        put("constant", false); put("probability", 100); put("priority", 0)
                    }
                    module.callAttr("add_world_book_entry", DEFAULT_BOOK_NAME, entryJson.toString()).toString()
                }
            } catch (e: Exception) { Log.e(TAG, "新增失败: ${e.message}", e); null }
            withContext(Dispatchers.Main) {
                if (result != null) {
                    try {
                        val json = JSONObject(result)
                        if (json.optString("status") == "ok") {
                            val now = currentTimeIso()
                            adapter.addItem(WorldBookEntry(UUID.randomUUID().toString().take(8), category, content, emptyList(), 0, now, now))
                            updateEntryCount(); showEmpty(false)
                            Toast.makeText(this@WorldBookActivity, R.string.toast_entry_saved, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) { Log.e(TAG, "解析失败: ${e.message}", e) }
                }
            }
        }
    }

    private fun performUpdateEntry(entry: WorldBookEntry, category: String, content: String) {
        val pos = entries.indexOfFirst { it.id == entry.id }; if (pos < 0) return
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    val entryJson = JSONObject().apply {
                        put("id", entry.id); put("content", content); put("keys", JSONArray()); put("comment", category)
                        put("constant", false); put("probability", 100); put("priority", 0)
                    }
                    module.callAttr("update_world_book_entry", DEFAULT_BOOK_NAME, entry.id, entryJson.toString()).toString()
                }
            } catch (e: Exception) { Log.e(TAG, "更新失败: ${e.message}", e); null }
            withContext(Dispatchers.Main) {
                if (result != null) {
                    try {
                        if (JSONObject(result).optString("status") == "ok") {
                            adapter.updateItem(pos, entry.copy(category = category, content = content, updatedAt = currentTimeIso()))
                            Toast.makeText(this@WorldBookActivity, R.string.toast_entry_updated, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) { Log.e(TAG, "解析失败: ${e.message}", e) }
                }
            }
        }
    }

    private fun performForget(entry: WorldBookEntry, position: Int) {
        if (position < 0 || position >= entries.size) return
        val viewHolder = rvEntries.findViewHolderForAdapterPosition(position) as? WorldBookAdapter.ViewHolder
        if (viewHolder != null) {
            adapter.animateForget(viewHolder) { executeDelete(entry, position) }
        } else executeDelete(entry, position)
    }

    private fun executeDelete(entry: WorldBookEntry, position: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val py = com.chaquo.python.Python.getInstance()
                    py.getModule("chat_bridge").callAttr("delete_world_book_entry", DEFAULT_BOOK_NAME, entry.id)
                } catch (e: Exception) { Log.e(TAG, "删除失败: ${e.message}", e) }
            }
            withContext(Dispatchers.Main) {
                adapter.removeItem(position); updateEntryCount()
                if (entries.isEmpty()) showEmpty(true)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        rvEntries.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showEmpty(show: Boolean) {
        layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        rvEntries.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            ivEmptyIcon.setImageResource(R.drawable.ic_feather)
            val emptyTitle = layoutEmpty.findViewById<TextView>(R.id.tvEmptyTitle)
            emptyTitle.setText(R.string.empty_world_book)
            val emptyDesc = layoutEmpty.findViewById<TextView>(R.id.tvEmptyDesc)
            emptyDesc.visibility = View.GONE
            startFeatherAnimation()
        } else {
            stopFeatherAnimation()
        }
    }

    private fun updateEntryCount() {
        tvEntryCount.text = if (entries.isNotEmpty()) getString(R.string.label_entry_count_format, entries.size) else ""
    }

    private fun startFeatherAnimation() {
        featherAnimator?.cancel()
        featherAnimator = ObjectAnimator.ofFloat(ivEmptyIcon, "rotation", 0f, 360f).apply {
            duration = 4000; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }

    private fun stopFeatherAnimation() {
        featherAnimator?.cancel(); featherAnimator = null; ivEmptyIcon.rotation = 0f
    }

    private fun currentTimeIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
    }
}