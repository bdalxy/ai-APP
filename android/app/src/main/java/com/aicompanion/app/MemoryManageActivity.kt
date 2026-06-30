package com.aicompanion.app

import android.graphics.Typeface
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.databinding.ActivityMemoryManageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆管理页面。
 *
 * 功能：
 * - 统计头部（总记忆数 + 类型分布）
 * - 按分类分组展示（5大父类 + 13细粒度标签）
 * - 分类过滤芯片栏
 * - 搜索记忆内容
 * - 记忆详情查看（点击弹出对话框）
 * - 长按删除单条记忆（确认对话框）
 * - 清空全部记忆（二次确认）
 * - 下拉刷新
 */
class MemoryManageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MemoryManage"
        private const val PAGE_SIZE = 500
        private const val SEARCH_DEBOUNCE_MS = 400L

        /** 细粒度类型 → 父类型映射 */
        private val PARENT_TYPE_MAP = mapOf(
            "episodic_event" to "episodic",
            "episodic_experience" to "episodic",
            "episodic_activity" to "episodic",
            "semantic_knowledge" to "semantic",
            "semantic_opinion" to "semantic",
            "semantic_concept" to "semantic",
            "user_identity" to "user_fact",
            "user_preference" to "user_fact",
            "user_attribute" to "user_fact",
            "user_relationship" to "user_fact",
            "user_status" to "user_fact",
            "emotional_mood" to "emotional",
            "emotional_sentiment" to "emotional",
            "summary" to "summary",
            // 兼容旧类型
            "episodic" to "episodic",
            "semantic" to "semantic",
            "user_fact" to "user_fact",
            "emotional" to "emotional",
        )

        /** 父类型显示顺序 */
        private val PARENT_TYPE_ORDER = listOf("episodic", "semantic", "user_fact", "emotional", "summary")
    }

    // ── UI 组件 ──
    private lateinit var binding: ActivityMemoryManageBinding
    private lateinit var adapter: MemoryAdapter

    // ── 数据状态 ──
    private val allMemories = mutableListOf<MemoryItem>()
    private val entryList = mutableListOf<MemoryListEntry>()
    private var totalCount = 0
    @Volatile private var isLoading = false
    private var currentKeyword = ""
    private var currentFilterParentType: String = "" // "" = 全部

    // ── 搜索防抖 ──
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onDestroy() {
        super.onDestroy()
        searchHandler.removeCallbacksAndMessages(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.memoryManageRoot)

        setupRecyclerView()
        setupFilterChips()
        setupListeners()

        loadStats()
        loadAllMemories()
    }

    // ── RecyclerView 设置 ──

    private fun setupRecyclerView() {
        adapter = MemoryAdapter(
            this, entryList,
            onDetailClick = { item -> showDetailDialog(item) },
            onDeleteClick = { item -> showDeleteConfirmDialog(item) }
        )
        binding.rvMemories.adapter = adapter
        binding.rvMemories.layoutManager = LinearLayoutManager(this)
    }

    // ── 分类过滤芯片 ──

    private fun setupFilterChips() {
        val chipLabels = listOf(
            "" to getString(R.string.label_filter_all),
            "episodic" to getString(R.string.label_memory_type_episodic),
            "semantic" to getString(R.string.label_memory_type_semantic),
            "user_fact" to getString(R.string.label_memory_type_user_fact),
            "emotional" to getString(R.string.label_memory_type_emotional),
            "summary" to getString(R.string.label_memory_type_summary),
        )

        val layout = binding.layoutFilterChips
        val selectedBg = ContextCompat.getColor(this, R.color.primary)
        val unselectedBg = ContextCompat.getColor(this, R.color.surfaceVariant)
        val selectedTextColor = ContextCompat.getColor(this, R.color.sakura_white)
        val unselectedTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        for ((type, label) in chipLabels) {
            val isSelected = type == ""
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                setTextColor(if (isSelected) selectedTextColor else unselectedTextColor)
                setBackgroundResource(R.drawable.bg_chip_rounded)
                background.setTint(if (isSelected) selectedBg else unselectedBg)
                setOnClickListener { selectChip(type) }
                tag = type
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 10
            }
            layout.addView(chip, params)
        }
    }

    private fun selectChip(parentType: String) {
        if (currentFilterParentType == parentType) return

        currentFilterParentType = parentType

        // 更新芯片样式
        val selectedBg = ContextCompat.getColor(this, R.color.primary)
        val unselectedBg = ContextCompat.getColor(this, R.color.surfaceVariant)
        val selectedTextColor = ContextCompat.getColor(this, R.color.sakura_white)
        val unselectedTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        val layout = binding.layoutFilterChips
        for (i in 0 until layout.childCount) {
            val chip = layout.getChildAt(i) as? TextView ?: continue
            val isSelected = chip.tag == parentType
            chip.setTextColor(if (isSelected) selectedTextColor else unselectedTextColor)
            chip.background.setTint(if (isSelected) selectedBg else unselectedBg)
            chip.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }

        // 重新构建列表
        buildEntryList()
    }

    // ── 监听器设置 ──

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshAll() }
        binding.btnClearAll.setOnClickListener { showClearAllConfirmDialog() }

        // 搜索输入
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (keyword.isNotEmpty()) View.VISIBLE else View.GONE

                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    currentKeyword = keyword
                    buildEntryList()
                }
                searchRunnable = runnable
                searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                currentKeyword = binding.etSearch.text?.toString()?.trim() ?: ""
                buildEntryList()
                true
            } else {
                false
            }
        }
    }

    // ── 数据加载 ──

    private fun loadStats() {
        lifecycleScope.launch {
            binding.tvStatsLoading.visibility = View.VISIBLE
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("get_memory_stats").toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取记忆统计失败: ${e.message}")
                null
            }

            binding.tvStatsLoading.visibility = View.GONE

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    totalCount = json.optInt("total", 0)

                    val byType = json.optJSONObject("by_type")
                    val typeStr = if (byType != null) {
                        val parts = mutableListOf<String>()
                        byType.keys().forEach { key ->
                            parts.add("${parentTypeLabel(key)} ${byType.getInt(key)}")
                        }
                        parts.joinToString("  ")
                    } else ""

                    binding.tvStats.text = if (typeStr.isNotEmpty()) {
                        getString(R.string.label_total_memories_format, totalCount) + "    $typeStr"
                    } else {
                        getString(R.string.label_total_memories_format, totalCount)
                    }
                    binding.layoutStats.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.w(TAG, "解析统计结果失败: ${e.message}")
                    binding.layoutStats.visibility = View.GONE
                }
            } else {
                binding.layoutStats.visibility = View.GONE
            }
        }
    }

    /** 加载全部记忆（用于分组展示） */
    private fun loadAllMemories() {
        if (isLoading) return
        isLoading = true
        binding.tvLoadMore.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("list_memories", "", 1, PAGE_SIZE).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载记忆列表失败: ${e.message}")
                null
            }

            isLoading = false
            binding.tvLoadMore.visibility = View.GONE

            if (result != null) {
                try {
                    parseMemories(result)
                } catch (e: Exception) {
                    Log.w(TAG, "解析记忆列表失败: ${e.message}")
                }
            }

            buildEntryList()
            showEmpty(entryList.isEmpty())
        }
    }

    /** 解析 JSON 结果到 allMemories */
    private fun parseMemories(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val itemsArray: JSONArray? = json.optJSONArray("items")

        allMemories.clear()

        if (itemsArray == null || itemsArray.length() == 0) {
            totalCount = 0
            return
        }

        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            val type = obj.optString("memory_type", "unknown")
            allMemories.add(
                MemoryItem(
                    rowid = obj.optInt("rowid", 0),
                    id = obj.optString("id", ""),
                    type = type,
                    parentType = PARENT_TYPE_MAP[type] ?: "semantic",
                    content = obj.optString("content", ""),
                    createdAt = obj.optString("created_at", ""),
                    importance = obj.optDouble("importance", 0.0)
                )
            )
        }

        totalCount = json.optInt("total", allMemories.size)
    }

    /** 根据当前过滤条件构建分组列表 */
    private fun buildEntryList() {
        // 1. 按关键词过滤
        val filtered = if (currentKeyword.isNotBlank()) {
            allMemories.filter {
                it.content.contains(currentKeyword, ignoreCase = true)
            }
        } else {
            allMemories.toList()
        }

        // 2. 按父类型过滤
        val typeFiltered = if (currentFilterParentType.isNotBlank()) {
            filtered.filter { it.parentType == currentFilterParentType }
        } else {
            filtered
        }

        // 3. 按父类型分组
        val grouped = LinkedHashMap<String, MutableList<MemoryItem>>()
        for (pt in PARENT_TYPE_ORDER) {
            grouped[pt] = mutableListOf()
        }
        for (item in typeFiltered) {
            val pt = item.parentType
            grouped[pt]?.add(item)
        }

        // 4. 构建 MemoryListEntry 列表
        val newEntries = mutableListOf<MemoryListEntry>()
        for ((pt, items) in grouped) {
            if (items.isEmpty()) continue
            newEntries.add(
                MemoryListEntry.Header(
                    type = pt,
                    label = parentTypeLabel(pt),
                    count = items.size
                )
            )
            for (item in items) {
                newEntries.add(MemoryListEntry.Item(item))
            }
        }

        adapter.replaceEntries(newEntries)
        showEmpty(newEntries.isEmpty())
    }

    // ── 操作 ──

    /** 删除单条记忆 */
    private fun deleteMemory(item: MemoryItem) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("delete_memory", item.rowid).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "删除记忆失败: ${e.message}")
                null
            }

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    if (json.optString("status") == "ok") {
                        // 从 allMemories 移除
                        allMemories.removeAll { it.rowid == item.rowid }
                        totalCount = (totalCount - 1).coerceAtLeast(0)
                        updateStatsDisplay()
                        // 重新构建分组列表
                        buildEntryList()
                        Log.d(TAG, "已删除记忆 rowid=${item.rowid}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析删除结果失败: ${e.message}")
                }
            }
        }
    }

    /** 清空全部记忆 */
    private fun clearAllMemories() {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("clear_memories").toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "清空记忆失败: ${e.message}")
                null
            }

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    val deleted = json.optInt("deleted", -1)
                    if (deleted >= 0) {
                        allMemories.clear()
                        entryList.clear()
                        adapter.clear()
                        totalCount = 0
                        updateStatsDisplay()
                        showEmpty(true)
                        Log.i(TAG, "已清空 $deleted 条记忆")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析清空结果失败: ${e.message}")
                }
            }
        }
    }

    /** 刷新全部 */
    private fun refreshAll() {
        currentKeyword = binding.etSearch.text?.toString()?.trim() ?: ""
        currentFilterParentType = ""
        selectChip("")

        allMemories.clear()
        entryList.clear()
        adapter.clear()
        showEmpty(false)

        loadStats()
        loadAllMemories()
    }

    // ── 对话框 ──

    /** 记忆详情对话框 */
    private fun showDetailDialog(item: MemoryItem) {
        val fineLabel = fineTypeLabel(item.type)
        val parentLabel = parentTypeLabel(item.parentType)
        val categoryText = "$parentLabel · $fineLabel"
        val timeText = if (item.createdAt.length >= 19) {
            item.createdAt.substring(0, 19)
        } else {
            item.createdAt
        }
        val importanceText = "%.0f%%".format(item.importance * 100)

        // 构建自定义视图
        val detailView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }

        fun addLabelValue(label: String, value: String) {
            val labelTv = TextView(this@MemoryManageActivity).apply {
                text = label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MemoryManageActivity, R.color.text_secondary))
                setPadding(0, 8, 0, 2)
            }
            val valueTv = TextView(this@MemoryManageActivity).apply {
                text = value
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MemoryManageActivity, R.color.text_primary))
                setPadding(0, 0, 0, 4)
            }
            detailView.addView(labelTv)
            detailView.addView(valueTv)
        }

        addLabelValue(getString(R.string.label_memory_content), item.content)
        addLabelValue(getString(R.string.label_memory_category), categoryText)
        addLabelValue(getString(R.string.label_memory_created_at), timeText)
        addLabelValue(getString(R.string.label_memory_importance), importanceText)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_memory_detail))
            .setView(detailView)
            .setPositiveButton(getString(R.string.btn_got_it), null)
            .setNegativeButton(getString(R.string.btn_delete)) { _, _ -> deleteMemory(item) }
            .show()
    }

    /** 单条删除确认 */
    private fun showDeleteConfirmDialog(item: MemoryItem) {
        val typeLabel = fineTypeLabel(item.type)
        val preview = if (item.content.length > 40) {
            item.content.substring(0, 40) + "..."
        } else {
            item.content
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_delete_memory))
            .setMessage(getString(R.string.msg_delete_memory_confirm, typeLabel, preview))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> deleteMemory(item) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 清空全部确认 */
    private fun showClearAllConfirmDialog() {
        if (totalCount <= 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_hint))
                .setMessage(getString(R.string.msg_no_memory_to_clear))
                .setPositiveButton(getString(R.string.btn_got_it), null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_clear_all_memories))
            .setMessage(getString(R.string.msg_clear_all_confirm, totalCount))
            .setPositiveButton(getString(R.string.btn_confirm_clear)) { _, _ -> clearAllMemories() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── 辅助方法 ──

    private fun showEmpty(show: Boolean) {
        binding.layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvMemories.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            val emptyIcon = binding.layoutEmpty.findViewById<ImageView>(R.id.ivEmptyIcon)
            val emptyTitle = binding.layoutEmpty.findViewById<TextView>(R.id.tvEmptyTitle)
            val emptyDesc = binding.layoutEmpty.findViewById<TextView>(R.id.tvEmptyDesc)
            emptyIcon.setImageResource(R.drawable.ic_settings_memory)
            emptyTitle.setText(R.string.empty_memories)
            emptyDesc.visibility = View.GONE
        }
    }

    private fun updateStatsDisplay() {
        binding.tvStats.text = getString(R.string.label_total_memories_format, totalCount)
        if (totalCount <= 0) {
            binding.layoutStats.visibility = View.GONE
        } else {
            binding.layoutStats.visibility = View.VISIBLE
        }
    }

    /** 父类型中文标签 */
    private fun parentTypeLabel(type: String): String = when (type) {
        "episodic" -> getString(R.string.label_memory_type_episodic)
        "semantic" -> getString(R.string.label_memory_type_semantic)
        "user_fact" -> getString(R.string.label_memory_type_user_fact)
        "emotional" -> getString(R.string.label_memory_type_emotional)
        "summary" -> getString(R.string.label_memory_type_summary)
        else -> type
    }

    /** 细粒度类型中文标签 */
    private fun fineTypeLabel(type: String): String = when (type) {
        "episodic_event" -> getString(R.string.label_memory_type_episodic_event)
        "episodic_experience" -> getString(R.string.label_memory_type_episodic_experience)
        "episodic_activity" -> getString(R.string.label_memory_type_episodic_activity)
        "semantic_knowledge" -> getString(R.string.label_memory_type_semantic_knowledge)
        "semantic_opinion" -> getString(R.string.label_memory_type_semantic_opinion)
        "semantic_concept" -> getString(R.string.label_memory_type_semantic_concept)
        "user_identity" -> getString(R.string.label_memory_type_user_identity)
        "user_preference" -> getString(R.string.label_memory_type_user_preference)
        "user_attribute" -> getString(R.string.label_memory_type_user_attribute)
        "user_relationship" -> getString(R.string.label_memory_type_user_relationship)
        "user_status" -> getString(R.string.label_memory_type_user_status)
        "emotional_mood" -> getString(R.string.label_memory_type_emotional_mood)
        "emotional_sentiment" -> getString(R.string.label_memory_type_emotional_sentiment)
        "summary" -> getString(R.string.label_memory_type_summary)
        else -> parentTypeLabel(type)
    }
}