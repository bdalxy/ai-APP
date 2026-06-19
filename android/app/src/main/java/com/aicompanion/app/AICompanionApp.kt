package com.aicompanion.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import com.aicompanion.app.plugin.BuiltinPlugins
import com.aicompanion.app.plugin.PluginRegistry
import com.chaquo.python.android.PyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AICompanionApp : PyApplication() {

    companion object {
        private const val TAG = "AICompanionApp"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var isPythonWarmedUp = false
            private set

        fun checkAndShowCrashLogDialog(context: Context) {
            if (!CrashHandler.hasCrashLogs(context)) return
            try {
                val crashFiles = CrashHandler.getCrashLogFiles(context)
                val latestFile = crashFiles.firstOrNull()
                val content = latestFile?.readText()?.take(500) ?: ""
                val dialog = android.app.AlertDialog.Builder(context)
                    .setTitle("检测到崩溃日志")
                    .setMessage("上次运行应用时发生了崩溃。\n\n时间: ${crashFiles.size} 条崩溃记录\n\n${content}")
                    .setPositiveButton("查看详情") { _, _ ->
                        context.startActivity(Intent(context, CrashLogViewerActivity::class.java))
                    }
                    .setNegativeButton("忽略") { _, _ ->
                        CrashHandler.clearMarker(context)
                    }
                    .setNeutralButton("复制到剪贴板") { _, _ ->
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("崩溃日志", content)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.w(TAG, "复制到剪贴板失败: ${e.message}")
                        }
                    }
                    .create()
                dialog.show()
            } catch (e: Exception) {
                Log.w(TAG, "显示崩溃日志对话框失败: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        PerformanceMonitor.markAppCreateStart()
        super.onCreate()
        Log.d(TAG, "PyApplication.super.onCreate() 完成")
        try {
            CrashHandler.init(this)
            Log.d(TAG, "CrashHandler 初始化完成")
        } catch (e: Exception) {
            Log.w(TAG, "CrashHandler 初始化失败: ${e.message}")
        }
        try {
            PluginRegistry.init(this)
            BuiltinPlugins.registerAll(this)
            Log.d(TAG, "插件系统初始化完成，已注册 ${PluginRegistry.getPluginCount()} 个插件")
        } catch (e: Exception) {
            Log.w(TAG, "插件系统初始化失败: ${e.message}")
        }
        appScope.launch {
            try {
                DeviceAdaptationHelper.init(this@AICompanionApp)
                Log.d(TAG, "DeviceAdaptationHelper 初始化完成")
            } catch (e: Exception) {
                Log.w(TAG, "DeviceAdaptationHelper 初始化失败: ${e.message}")
            }
        }
        appScope.launch {
            try {
                NotificationHelper.createChannel(this@AICompanionApp)
                Log.d(TAG, "NotificationHelper 渠道创建完成")
            } catch (e: Exception) {
                Log.w(TAG, "NotificationHelper 渠道创建失败: ${e.message}")
            }
        }
        appScope.launch {
            warmUpPython()
        }
        PerformanceMonitor.markAppCreateEnd()
        Log.d(TAG, "Application.onCreate 完成")
    }

    private fun warmUpPython() {
        try {
            val warmUpStart = System.currentTimeMillis()
            val python = com.chaquo.python.Python.getInstance()
            python.getModule("chat_bridge")
            isPythonWarmedUp = true
            val warmUpTime = System.currentTimeMillis() - warmUpStart
            Log.d(TAG, "Python 预热完成，耗时: ${warmUpTime}ms")
            PerformanceMonitor.recordMemory()
        } catch (e: Exception) {
            Log.w(TAG, "Python 预热失败: ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory: level=$level, ${PerformanceMonitor.getMemorySummary()}")
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "内存极度紧张，执行紧急清理")
                clearPythonCache()
                System.gc()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "内存较低，清理 Python 缓存")
                clearPythonCache()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "应用进入后台，建议释放非必要资源")
                System.gc()
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "后台进程内存紧张，执行清理")
                clearPythonCache()
                System.gc()
            }
        }
    }

    private fun clearPythonCache() {
        try {
            if (isPythonWarmedUp) {
                val python = com.chaquo.python.Python.getInstance()
                python.getModule("gc").callAttr("collect")
                Log.d(TAG, "Python gc.collect() 已执行")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Python 缓存清理失败: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: ${newConfig.orientation}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory: ${PerformanceMonitor.getMemorySummary()}")
        clearPythonCache()
        System.gc()
    }
}