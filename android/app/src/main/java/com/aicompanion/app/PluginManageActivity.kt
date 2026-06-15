package com.aicompanion.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

/**
 * 插件管理独立页面 —「星轨控制台」。
 * 展示已安装插件，支持分类筛选、启用/禁用、查看详情。
 */
class PluginManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var adapter: PluginAdapter
    private val viewModel by lazy { PluginViewModel(application) }

    private var currentCategory: String = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_manage)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(findViewById(R.id.plugin_manage_root))

        // 顶栏返回按钮
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // 视图初始化
        recyclerView = findViewById(R.id.rvPlugins)
        tabLayout = findViewById(R.id.tabCategories)
        tvEmpty = findViewById(R.id.tvPluginEmpty)
        tvSummary = findViewById(R.id.tvPluginSummary)

        // RecyclerView 配置
        adapter = PluginAdapter(
            onToggle = { plugin, newEnabled ->
                viewModel.togglePlugin(plugin.name, newEnabled)
            },
            onDetailClick = { plugin ->
                showDetailDialog(plugin)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Tab 分类配置
        setupTabs()

        // 状态监听
        viewModel.onStateChanged = { state ->
            runOnUiThread {
                if (state.isLoading) {
                    // 加载中保持当前列表
                    tvSummary.text = "加载中..."
                } else if (state.errorMessage != null) {
                    Toast.makeText(this, state.errorMessage, Toast.LENGTH_SHORT).show()
                } else {
                    updateUI(state.plugins)
                }
            }
        }

        viewModel.loadPlugins()
    }

    private fun setupTabs() {
        val categories = arrayOf("all", "chat", "appearance", "script")
        val labels = arrayOf("全部", "对话增强", "外观美化", "脚本工具")

        for (i in categories.indices) {
            val tab = tabLayout.newTab().setText(labels[i])
            tabLayout.addTab(tab)
        }

        tabLayout.tabTextColors = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
        )
        tabLayout.setSelectedTabIndicatorColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.elysian_purple)
        )
        tabLayout.setTabTextColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary),
            androidx.core.content.ContextCompat.getColor(this, R.color.elysian_pink)
        )

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = categories.getOrElse(tab?.position ?: 0) { "all" }
                refreshFilteredList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateUI(allPlugins: List<PluginItem>) {
        allPluginsCache = allPlugins
        val enabled = allPlugins.count { it.enabled }
        tvSummary.text = "已安装 ${allPlugins.size} 个 · 已启用 $enabled 个"
        adapter.setAllDisabled(allPlugins.none { it.enabled })
        refreshFilteredList()
    }

    private var allPluginsCache: List<PluginItem> = emptyList()

    private fun refreshFilteredList() {
        val filtered = if (currentCategory == "all") {
            allPluginsCache
        } else {
            allPluginsCache.filter { it.category == currentCategory }
        }
        tvSummary.text = "已安装 ${allPluginsCache.size} 个 · 已启用 ${allPluginsCache.count { it.enabled }} 个"
        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetailDialog(plugin: PluginItem) {
        val message = buildString {
            append("版本：${plugin.version}\n")
            append("作者：${plugin.author}\n")
            append("分类：${plugin.categoryLabel}\n")
            append("状态：${plugin.statusLabel}\n")
            append("\n--- 统计 ---\n")
            append("调用次数：${plugin.callCount}\n")
            append("异常次数：${plugin.errorCount}\n")
            if (plugin.lastError.isNotEmpty()) {
                append("最后错误：${plugin.lastError}\n")
            }
            append("\n--- 实现钩子 ---\n")
            val allHooks = listOf("pre_process", "post_process", "on_turn_end", "on_memory_extracted")
            for (hook in allHooks) {
                val implemented = hook in plugin.hooks
                append(if (implemented) "✓ " else "✗ ")
                append(hook)
                append("\n")
            }
            if (plugin.dependencies.isNotEmpty()) {
                append("\n依赖：${plugin.dependencies.joinToString(", ")}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${plugin.name} 详情")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .setNeutralButton("启用/禁用") { _, _ ->
                viewModel.togglePlugin(plugin.name, !plugin.enabled)
            }
            .show()
    }
}