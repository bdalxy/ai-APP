package com.aicompanion.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.plugin.PluginInfo
import com.aicompanion.app.plugin.PluginRegistry
import com.aicompanion.app.plugin.PluginType
import com.google.android.material.tabs.TabLayout

class PluginManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var adapter: PluginAdapter

    private var currentCategory: String = "all"
    private var allPluginsCache: List<PluginItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_manage)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(findViewById(R.id.plugin_manage_root))

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.rvPlugins)
        tabLayout = findViewById(R.id.tabCategories)
        tvEmpty = findViewById(R.id.tvPluginEmpty)
        tvSummary = findViewById(R.id.tvPluginSummary)

        adapter = PluginAdapter(
            onToggle = { plugin, newEnabled -> handleToggle(plugin, newEnabled) },
            onDetailClick = { plugin -> showDetailDialog(plugin) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        setupTabs()
        loadPlugins()
    }

    private fun setupTabs() {
        val categories = listOf(
            "all" to "全部",
            PluginType.TOOL.category to PluginType.TOOL.label,
            PluginType.GAME.category to PluginType.GAME.label,
            PluginType.MEMORY.category to PluginType.MEMORY.label,
            PluginType.CUSTOM.category to PluginType.CUSTOM.label
        )

        for ((key, label) in categories) {
            tabLayout.addTab(tabLayout.newTab().setText(label))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val idx = tab?.position ?: 0
                currentCategory = categories.getOrElse(idx) { "all" to "全部" }.first
                refreshFilteredList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadPlugins() {
        try {
            val nativePlugins = PluginRegistry.getAllPlugins().map { it.toPluginItem() }
            val pythonPlugins = loadPythonPlugins()
            val mergedPlugins = mergePlugins(nativePlugins, pythonPlugins)
            allPluginsCache = mergedPlugins
            updateUI(allPluginsCache)
        } catch (e: Exception) {
            android.util.Log.e("PluginManage", "加载插件失败", e)
            Toast.makeText(this, "加载插件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            allPluginsCache = emptyList()
            updateUI(allPluginsCache)
        }
    }

    private fun loadPythonPlugins(): List<PluginItem> {
        return try {
            if (AICompanionApp.isPythonWarmedUp) {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val result = module?.callAttr("list_plugins")?.toString() ?: "{}"
                val json = org.json.JSONObject(result)
                if (json.optString("status") == "ok") {
                    val arr = json.getJSONArray("plugins")
                    val plugins = mutableListOf<PluginItem>()
                    for (i in 0 until arr.length()) {
                        plugins.add(parsePythonPluginItem(arr.getJSONObject(i)))
                    }
                    plugins
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            android.util.Log.d("PluginManage", "Python 插件加载跳过: ${e.message}")
            emptyList()
        }
    }

    private fun mergePlugins(nativePlugins: List<PluginItem>, pythonPlugins: List<PluginItem>): List<PluginItem> {
        val merged = mutableMapOf<String, PluginItem>()
        for (plugin in pythonPlugins) { merged[plugin.name] = plugin }
        for (plugin in nativePlugins) { merged[plugin.name] = plugin }
        return merged.values.toList()
    }

    private fun parsePythonPluginItem(json: org.json.JSONObject): PluginItem {
        val stats = json.optJSONObject("stats") ?: org.json.JSONObject()
        return PluginItem(
            name = json.optString("name", ""),
            version = json.optString("version", ""),
            description = json.optString("description", ""),
            category = json.optString("category", "script"),
            enabled = json.optBoolean("enabled", false),
            author = json.optString("author", ""),
            icon = json.optString("icon", "sparkle"),
            isBuiltIn = false,
            dependencies = jsonArrayToList(json.optJSONArray("dependencies")),
            conflicts = jsonArrayToList(json.optJSONArray("conflicts")),
            hooks = jsonArrayToList(json.optJSONArray("hooks")),
            callCount = stats.optInt("call_count", 0),
            errorCount = stats.optInt("error_count", 0),
            installTime = stats.optLong("install_time", 0),
            lastCallTime = stats.optLong("last_call_time", 0),
            lastError = stats.optString("last_error", "")
        )
    }

    private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun updateUI(allPlugins: List<PluginItem>) {
        val enabled = allPlugins.count { it.enabled }
        tvSummary.text = "已安装 ${allPlugins.size} 个 · 已启用 $enabled 个"
        adapter.setAllDisabled(allPlugins.none { it.enabled })
        refreshFilteredList()
    }

    private fun refreshFilteredList() {
        val filtered = if (currentCategory == "all") allPluginsCache else allPluginsCache.filter { it.category == currentCategory }
        tvSummary.text = "已安装 ${allPluginsCache.size} 个 · 已启用 ${allPluginsCache.count { it.enabled }} 个"
        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleToggle(plugin: PluginItem, newEnabled: Boolean) {
        val pluginId = plugin.name
        val registeredPlugin = PluginRegistry.getPlugin(pluginId)
        if (registeredPlugin != null) {
            val success = if (newEnabled) PluginRegistry.enablePlugin(this, pluginId) else PluginRegistry.disablePlugin(this, pluginId)
            if (success) loadPlugins() else Toast.makeText(this, "操作失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (AICompanionApp.isPythonWarmedUp) {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val result = module?.callAttr("toggle_plugin", plugin.name, newEnabled)?.toString() ?: "{}"
                val json = org.json.JSONObject(result)
                if (json.optString("status") == "ok") loadPlugins()
                else Toast.makeText(this, json.optString("message", "操作失败"), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "Python 引擎未就绪，无法操作插件", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("PluginManage", "切换插件状态失败", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetailDialog(plugin: PluginItem) {
        val message = buildString {
            append("版本：${plugin.version}\n")
            append("作者：${plugin.author}\n")
            append("分类：${plugin.categoryLabel}\n")
            if (plugin.isBuiltIn) append("类型：内置插件（不可卸载）\n")
            append("状态：${plugin.statusLabel}\n")
            append("\n--- 统计 ---\n")
            append("调用次数：${plugin.callCount}\n")
            append("异常次数：${plugin.errorCount}\n")
            if (plugin.lastError.isNotEmpty()) append("最后错误：${plugin.lastError}\n")
            append("\n--- 实现钩子 ---\n")
            val allHooks = listOf("pre_process", "post_process", "on_turn_end", "on_memory_extracted")
            for (hook in allHooks) {
                val implemented = hook in plugin.hooks
                append(if (implemented) "✓ " else "✗ ")
                append(hook)
                append("\n")
            }
            if (plugin.dependencies.isNotEmpty()) append("\n依赖：${plugin.dependencies.joinToString(", ")}")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("${plugin.name} 详情")
            .setMessage(message)
            .setPositiveButton("关闭", null)

        dialog.setNeutralButton("启用/禁用") { _, _ ->
            handleToggle(plugin, !plugin.enabled)
        }
        dialog.show()
    }
}

private fun PluginInfo.toPluginItem(): PluginItem {
    return PluginItem(
        name = this.name,
        version = this.version,
        description = this.description,
        category = this.type.category,
        enabled = this.isEnabled,
        author = this.author,
        icon = "plugin",
        isBuiltIn = this.isBuiltIn,
        dependencies = this.permissionRequired,
        conflicts = emptyList(),
        hooks = emptyList(),
        callCount = 0,
        errorCount = 0,
        installTime = System.currentTimeMillis(),
        lastCallTime = 0,
        lastError = ""
    )
}