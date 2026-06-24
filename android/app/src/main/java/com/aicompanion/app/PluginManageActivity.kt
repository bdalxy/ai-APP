package com.aicompanion.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            "all" to getString(R.string.tab_label_all),
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
                currentCategory = categories.getOrElse(idx) { "all" to getString(R.string.tab_label_all) }.first
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
            Toast.makeText(this, getString(R.string.toast_plugin_load_failed, e.message), Toast.LENGTH_SHORT).show()
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
        for (plugin in pythonPlugins) { merged[plugin.id] = plugin }
        for (plugin in nativePlugins) { merged[plugin.id] = plugin }
        return merged.values.toList()
    }

    private fun parsePythonPluginItem(json: org.json.JSONObject): PluginItem {
        val stats = json.optJSONObject("stats") ?: org.json.JSONObject()
        val pluginName = json.optString("name", "")
        return PluginItem(
            id = pluginName,
            name = pluginName,
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
        tvSummary.text = getString(R.string.summary_plugins_format, allPlugins.size, enabled)
        adapter.setAllDisabled(allPlugins.none { it.enabled })
        refreshFilteredList()
    }

    private fun refreshFilteredList() {
        val filtered = if (currentCategory == "all") allPluginsCache else allPluginsCache.filter { it.category == currentCategory }
        tvSummary.text = getString(R.string.summary_plugins_format, allPluginsCache.size, allPluginsCache.count { it.enabled })
        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleToggle(plugin: PluginItem, newEnabled: Boolean) {
        val pluginId = plugin.id
        val registeredPlugin = PluginRegistry.getPlugin(pluginId)
        if (registeredPlugin != null) {
            val success = if (newEnabled) PluginRegistry.enablePlugin(this, pluginId) else PluginRegistry.disablePlugin(this, pluginId)
            if (success) loadPlugins() else Toast.makeText(this, getString(R.string.toast_plugin_operation_failed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (AICompanionApp.isPythonWarmedUp) {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val result = module?.callAttr("toggle_plugin", plugin.name, newEnabled)?.toString() ?: "{}"
                val json = org.json.JSONObject(result)
                if (json.optString("status") == "ok") loadPlugins()
                else Toast.makeText(this, json.optString("message", getString(R.string.toast_plugin_toggle_failed)), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, getString(R.string.toast_python_not_ready), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("PluginManage", "切换插件状态失败", e)
            Toast.makeText(this, getString(R.string.toast_plugin_operation_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetailDialog(plugin: PluginItem) {
        val message = buildString {
            append(getString(R.string.label_plugin_version)); append(plugin.version); append("\n")
            append(getString(R.string.label_plugin_author)); append(plugin.author); append("\n")
            append(getString(R.string.label_plugin_category)); append(plugin.categoryLabel); append("\n")
            if (plugin.isBuiltIn) append(getString(R.string.label_plugin_builtin))
            append(getString(R.string.label_plugin_status)); append(plugin.statusLabel); append("\n")
            append(getString(R.string.label_plugin_stats))
            append(getString(R.string.label_plugin_call_count)); append(plugin.callCount); append("\n")
            append(getString(R.string.label_plugin_error_count)); append(plugin.errorCount); append("\n")
            if (plugin.lastError.isNotEmpty()) { append(getString(R.string.label_plugin_last_error)); append(plugin.lastError); append("\n") }
            append(getString(R.string.label_plugin_hooks))
            val allHooks = listOf("pre_process", "post_process", "on_turn_end", "on_memory_extracted")
            for (hook in allHooks) {
                val implemented = hook in plugin.hooks
                append(if (implemented) "✓ " else "✗ ")
                append(hook)
                append("\n")
            }
            if (plugin.dependencies.isNotEmpty()) append("\n依赖：${plugin.dependencies.joinToString(", ")}")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_plugin_detail, plugin.name))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_close), null)

        dialog.setNeutralButton(getString(R.string.btn_toggle_plugin)) { _, _ ->
            handleToggle(plugin, !plugin.enabled)
        }
        dialog.show()
    }
}

private fun PluginInfo.toPluginItem(): PluginItem {
    return PluginItem(
        id = this.id,
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