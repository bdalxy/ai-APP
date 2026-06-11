package com.aicompanion.app

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * - 搜索记忆内容
 * - 记忆列表（分页加载）
 * - 长按删除单条记忆
 * - 清空全部记忆（需二次确认）
 * - 下拉刷新
 *
 * 所有对 Python chat_bridge 的调用都在 Dispatchers.IO 协程中执行。
 */
class MemoryManageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MemoryManage"
        private const val PAGE_SIZE = 50
        /** 搜索防抖延迟（毫秒） */
        private const val SEARCH_DEBOUNCE_MS = 400L
    }

    // ── UI 组件 ──
    private lateinit var rvMemories: RecyclerView
    private lateinit var adapter: MemoryAdapter
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClearAll: Button
    private lateinit var btnBack: Button
    private lateinit var tvStats: TextView
    private lateinit var tvStatsLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLoadMore: TextView
    private lateinit var layoutStats: View

    // ── 数据状态 ──
    private val memoryItems = mutableListOf<MemoryItem>()
    private var currentPage = 1
    private var totalCount = 0
    private var isLoading = false
    private var hasMore = true
    private var currentKeyword = ""

    // ── 搜索防抖 ──
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_manage)

        // 绑定视图
        bindViews()

        // 设置 RecyclerView
        setupRecyclerView()

        // 设置监听器
        setupListeners()

        // 初始加载
        loadStats()
        loadMemories(page = 1, keyword = "")
    }

    // ── 视图绑定 ──

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        etSearch = findViewById(R.id.etSearch)
        tvStats = findViewById(R.id.tvStats)
        tvStatsLoading = findViewById(R.id.tvStatsLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvLoadMore = findViewById(R.id.tvLoadMore)
        layoutStats = findViewById(R.id.layoutStats)
        rvMemories = findViewById(R.id.rvMemories)
    }

    // ── RecyclerView 设置 ──

    private fun setupRecyclerView() {
        adapter = MemoryAdapter(memoryItems) { item -> showDeleteConfirmDialog(item) }
        rvMemories.adapter = adapter
        rvMemories.layoutManager = LinearLayoutManager(this)

        // 滚动监听 — 触底加载更多
        rvMemories.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return // 只处理向下滚动

                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisiblePos = layoutManager.findLastVisibleItemPosition()
                val totalItems = adapter.itemCount

                // 当滚动到倒数第 5 条时触发加载
                if (!isLoading && hasMore && totalItems > 0 && lastVisiblePos >= totalItems - 5) {
                    loadNextPage()
                }
            }
        })
    }

    // ── 监听器设置 ──

    private fun setupListeners() {
        // 返回
        btnBack.setOnClickListener { finish() }

        // 刷新
        btnRefresh.setOnClickListener { refreshAll() }

        // 清空全部
        btnClearAll.setOnClickListener { showClearAllConfirmDialog() }

        // 搜索输入 — 防抖
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString()?.trim() ?: ""
                // 控制清除按钮可见性
                btnClearSearch.visibility = if (keyword.isNotEmpty()) View.VISIBLE else View.GONE

                // 防抖搜索
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    currentKeyword = keyword
                    currentPage = 1
                    hasMore = true
                    memoryItems.clear()
                    adapter.notifyDataSetChanged()
                    showEmpty(false)
                    loadMemories(page = 1, keyword = keyword)
                }
                searchHandler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
            }
        })

        // 清除搜索
        btnClearSearch.setOnClickListener {
            etSearch.setText("")
            // afterTextChanged 会自动触发重新加载
        }

        // 搜索键盘动作
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                // 立即触发搜索（取消防抖）
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val keyword = etSearch.text?.toString()?.trim() ?: ""
                currentKeyword = keyword
                currentPage = 1
                hasMore = true
                memoryItems.clear()
                adapter.notifyDataSetChanged()
                showEmpty(false)
                loadMemories(page = 1, keyword = keyword)
                true
            } else {
                false
            }
        }
    }

    // ── 数据加载 ──

    /** 加载记忆统计 */
    private fun loadStats() {
        lifecycleScope.launch {
            tvStatsLoading.visibility = View.VISIBLE
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

            tvStatsLoading.visibility = View.GONE

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    val total = json.optInt("total", 0)
                    totalCount = total

                    val byType = json.optJSONObject("by_type")
                    val typeStr = if (byType != null) {
                        val parts = mutableListOf<String>()
                        byType.keys().forEach { key ->
                            parts.add("${typeLabel(key)} ${byType.getInt(key)}")
                        }
                        parts.joinToString("  ")
                    } else ""

                    tvStats.text = if (typeStr.isNotEmpty()) {
                        "总记忆：${total} 条    $typeStr"
                    } else {
                        "总记忆：${total} 条"
                    }
                    layoutStats.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.w(TAG, "解析统计结果失败: ${e.message}")
                    layoutStats.visibility = View.GONE
                }
            } else {
                layoutStats.visibility = View.GONE
            }
        }
    }

    /** 加载记忆列表 */
    private fun loadMemories(page: Int, keyword: String) {
        if (isLoading) return
        isLoading = true

        if (page == 1) {
            // 首页：显示加载状态，隐藏"加载更多"
            tvLoadMore.visibility = View.GONE
        } else {
            tvLoadMore.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")

                    if (keyword.isNotBlank()) {
                        // 有关键词：使用 search_memories（不分页，返回全部匹配结果）
                        module.callAttr("search_memories", keyword).toString()
                    } else {
                        // 无关键词：使用 list_memories（分页）
                        module.callAttr("list_memories", "", page, PAGE_SIZE).toString()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载记忆列表失败: ${e.message}")
                null
            }

            isLoading = false
            tvLoadMore.visibility = View.GONE

            if (result != null) {
                try {
                    parseAndAddItems(result, keyword.isNotBlank())
                } catch (e: Exception) {
                    Log.w(TAG, "解析记忆列表失败: ${e.message}")
                }
            }

            // 更新空状态
            showEmpty(memoryItems.isEmpty())
        }
    }

    /** 解析 JSON 结果并添加到列表 */
    private fun parseAndAddItems(jsonStr: String, isSearch: Boolean) {
        val json = JSONObject(jsonStr)
        val itemsArray: JSONArray? = json.optJSONArray("items")

        if (itemsArray == null || itemsArray.length() == 0) {
            hasMore = false
            if (currentPage == 1) showEmpty(true)
            return
        }

        val parsedItems = mutableListOf<MemoryItem>()
        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            parsedItems.add(
                MemoryItem(
                    rowid = obj.optInt("rowid", 0),
                    id = obj.optString("id", ""),
                    type = obj.optString("type", "unknown"),
                    content = obj.optString("content", ""),
                    createdAt = obj.optString("created_at", ""),
                    importance = obj.optDouble("importance", 0.0)
                )
            )
        }

        if (isSearch) {
            // 搜索模式：不翻页，直接替换
            hasMore = false
            memoryItems.clear()
        }

        adapter.addItems(parsedItems)

        // 判断是否还有更多
        if (!isSearch) {
            val total = json.optInt("total", 0)
            totalCount = total
            hasMore = memoryItems.size < total
        }

        showEmpty(memoryItems.isEmpty())
    }

    /** 加载下一页 */
    private fun loadNextPage() {
        if (!hasMore || isLoading) return
        currentPage++
        loadMemories(page = currentPage, keyword = currentKeyword)
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
                        // 从列表中移除
                        val index = memoryItems.indexOfFirst { it.rowid == item.rowid }
                        if (index >= 0) {
                            adapter.removeAt(index)
                        }
                        totalCount = (totalCount - 1).coerceAtLeast(0)
                        updateStatsDisplay()
                        showEmpty(memoryItems.isEmpty())
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
                        memoryItems.clear()
                        adapter.notifyDataSetChanged()
                        totalCount = 0
                        currentPage = 1
                        hasMore = false
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

    /** 刷新全部（统计 + 列表） */
    private fun refreshAll() {
        currentPage = 1
        hasMore = true
        currentKeyword = etSearch.text?.toString()?.trim() ?: ""
        memoryItems.clear()
        adapter.notifyDataSetChanged()
        showEmpty(false)

        loadStats()
        loadMemories(page = 1, keyword = currentKeyword)
    }

    // ── 对话框 ──

    /** 单条删除确认 */
    private fun showDeleteConfirmDialog(item: MemoryItem) {
        val typeLabel = typeLabel(item.type)
        val preview = if (item.content.length > 40) {
            item.content.substring(0, 40) + "..."
        } else {
            item.content
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("删除记忆")
            .setMessage("确定要删除这条${typeLabel}吗？\n\n\"$preview\"")
            .setPositiveButton("删除") { _, _ -> deleteMemory(item) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空全部确认 */
    private fun showClearAllConfirmDialog() {
        if (totalCount <= 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle("提示")
                .setMessage("当前没有记忆可以清空。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部记忆")
            .setMessage("确定要删除全部 ${totalCount} 条记忆吗？\n\n此操作不可撤销！")
            .setPositiveButton("确认清空") { _, _ -> clearAllMemories() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 辅助方法 ──

    /** 控制空状态提示的显示 */
    private fun showEmpty(show: Boolean) {
        tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        rvMemories.visibility = if (show) View.GONE else View.VISIBLE
    }

    /** 更新统计栏显示 */
    private fun updateStatsDisplay() {
        tvStats.text = "总记忆：${totalCount} 条"
        if (totalCount <= 0) {
            layoutStats.visibility = View.GONE
        } else {
            layoutStats.visibility = View.VISIBLE
        }
    }

    /** 类型中文标签 */
    private fun typeLabel(type: String): String = when (type) {
        "episodic" -> "情景记忆"
        "semantic" -> "语义记忆"
        "user_fact" -> "用户事实"
        else -> type
    }
}