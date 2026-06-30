package com.aicompanion.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.aicompanion.app.module.ModuleEventBus
import com.aicompanion.app.module.ModuleRegistry
import com.aicompanion.app.module.character.CharacterModuleImpl
import com.aicompanion.app.module.character.CharacterModule
import com.aicompanion.app.module.plugin.PluginModuleImpl
import com.aicompanion.app.module.plugin.PluginModule
import com.aicompanion.app.module.tts.TtsModuleImpl
import com.aicompanion.app.module.tts.TtsModule
import com.aicompanion.app.module.worldbook.WorldBookModuleImpl
import com.aicompanion.app.module.worldbook.WorldBookModule
import com.aicompanion.app.plugin.BuiltinPlugins
import com.aicompanion.app.plugin.PluginRegistry
import com.aicompanion.app.speech.VoiceRecorder
import com.chaquo.python.android.PyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AI Companion 应用入口。
 * 必须继承 PyApplication 以自动初始化 Chaquopy AndroidPlatform，
 * 否则调用 Python.getInstance() 会报 GenericPlatform 错误。
 *
 * 性能优化：
 * - 非关键初始化（设备适配、通知渠道）移到后台线程
 * - Python 引擎在后台线程预热，减少首次调用延迟
 * - onTrimMemory() 回调在内存紧张时释放资源
 */
class AICompanionApp : PyApplication() {

    companion object {
        private const val TAG = "AICompanionApp"

        /** 后台协程作用域（SupervisorJob 保证子协程异常不影响其他协程） */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Python 是否已预热完成 */
        @Volatile
        var isPythonWarmedUp = false
            private set

        /**
         * 检查并处理崩溃日志。
         * 应在 MainActivity 启动后调用，如果存在崩溃日志则弹出对话框。
         *
         * @param context 用于启动 CrashLogViewerActivity 的 Context
         */
        fun checkAndShowCrashLogDialog(context: Context) {
            if (!CrashHandler.hasCrashLogs(context)) return

            try {
                val crashFiles = CrashHandler.getCrashLogFiles(context)
                val latestFile = crashFiles.firstOrNull()
                val content = latestFile?.readText()?.take(500) ?: ""

                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.crash_log_dialog_title)
                    .setMessage(context.getString(R.string.crash_log_dialog_message, crashFiles.size, content))
                    .setPositiveButton(R.string.crash_log_view_detail) { _, _ ->
                        context.startActivity(Intent(context, CrashLogViewerActivity::class.java))
                    }
                    .setNegativeButton(R.string.crash_log_ignore) { _, _ ->
                        CrashHandler.clearMarker(context)
                    }
                    .setNeutralButton(R.string.crash_log_copy) { _, _ ->
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("崩溃日志", content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
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
        // 记录启动耗时起点
        val appStartTime = System.currentTimeMillis()

        // 设置主题模式（必须在 super.onCreate() 之前）
        val themeMode = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("theme_mode", AppConfig.THEME_LIGHT) ?: AppConfig.THEME_LIGHT
        AppCompatDelegate.setDefaultNightMode(
            if (themeMode == AppConfig.THEME_DARK) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Debug 模式：启用 StrictMode 检测主线程磁盘读写和网络操作
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
            Log.d(TAG, "StrictMode 已启用（Debug 模式）")
        }

        // Chaquopy 初始化必须在主线程（PyApplication.onCreate() 内部初始化 AndroidPlatform）
        super.onCreate()
        Log.d(TAG, "PyApplication.super.onCreate() 完成，耗时: ${System.currentTimeMillis() - appStartTime}ms")

        // 初始化崩溃日志捕获（必须在其他初始化之前，尽早覆盖异常处理）
        try {
            CrashHandler.init(this)
            Log.d(TAG, "CrashHandler 初始化完成")
        } catch (e: Exception) {
            Log.w(TAG, "CrashHandler 初始化失败: ${e.message}")
        }

        // 初始化插件系统（轻量级，无需 Python）
        try {
            PluginRegistry.init(this)
            BuiltinPlugins.registerAll(this)
            Log.d(TAG, "插件系统初始化完成，已注册 ${PluginRegistry.getPluginCount()} 个插件")
        } catch (e: Exception) {
            Log.w(TAG, "插件系统初始化失败: ${e.message}")
        }

        // 非关键初始化移到后台线程，避免阻塞主线程
        // 注意：DeviceAdaptationHelper.init() 内部涉及 Resources 操作，需确保线程安全
        appScope.launch {
            try {
                // 品牌设备 UI 适配（小米 HyperOS / 荣耀 MagicOS 缩放补偿）
                DeviceAdaptationHelper.init(this@AICompanionApp)
                Log.d(TAG, "DeviceAdaptationHelper 初始化完成")
            } catch (e: Exception) {
                Log.w(TAG, "DeviceAdaptationHelper 初始化失败: ${e.message}")
            }
        }

        appScope.launch {
            try {
                // 创建通知渠道（Android 8.0+ 必须，否则通知不显示）
                NotificationHelper.createChannel(this@AICompanionApp)
                Log.d(TAG, "NotificationHelper 渠道创建完成")
            } catch (e: Exception) {
                Log.w(TAG, "NotificationHelper 渠道创建失败: ${e.message}")
            }
        }

        // 启动时清理过期录音文件（后台线程，不影响启动速度）
        appScope.launch {
            try {
                VoiceRecorder.cleanupOldRecordings(this@AICompanionApp)
                Log.d(TAG, "启动时过期录音清理完成")
            } catch (e: Exception) {
                Log.w(TAG, "启动时过期录音清理失败: ${e.message}")
            }
        }

        // 后台预热 Python 引擎（减少首次对话延迟）
        appScope.launch {
            warmUpPython()
        }

        // v2.0 模块化架构：注册所有模块到 ModuleRegistry
        // 注册顺序：先基础模块（无依赖），后依赖模块
        try {
            // 1. 角色卡模块（基础模块，依赖 Context）
            val characterModule = CharacterModuleImpl(this)
            ModuleRegistry.register<CharacterModule>(characterModule)
            Log.d(TAG, "CharacterModule 已注册")

            // 2. 世界书模块（依赖 Python 引擎，延迟调用）
            val worldBookModule = WorldBookModuleImpl()
            ModuleRegistry.register<WorldBookModule>(worldBookModule)
            Log.d(TAG, "WorldBookModule 已注册")

            // 3. 插件模块（依赖 PluginRegistry + Python 引擎）
            val pluginModule = PluginModuleImpl(this)
            ModuleRegistry.register<PluginModule>(pluginModule)
            Log.d(TAG, "PluginModule 已注册")

            // 4. TTS 模块（依赖 SpeechManager，由 MainActivity 注入）
            val ttsModule = TtsModuleImpl()
            ModuleRegistry.register<TtsModule>(ttsModule)
            Log.d(TAG, "TtsModule 已注册")

            Log.d(TAG, "模块化架构初始化完成，共注册 ${ModuleRegistry.modules.size} 个模块")
        } catch (e: Exception) {
            Log.w(TAG, "模块注册失败: ${e.message}")
        }

        val appTotalTime = System.currentTimeMillis() - appStartTime
        Log.d(TAG, "Application.onCreate 完成，总耗时: ${appTotalTime}ms")
    }

    /**
     * 后台预热 Python 引擎。
     * 预先触发 Python 实例化和 chat_bridge 模块加载，
     * 减少用户首次发送消息时的等待时间。
     */
    private fun warmUpPython() {
        try {
            val warmUpStart = System.currentTimeMillis()
            val python = com.chaquo.python.Python.getInstance()
            // 预加载 chat_bridge 模块（触发 .py→.pyc 编译）
            val module = python.getModule("chat_bridge")
            // 显式设置构建类型，确保 Release 构建日志级别降为 WARNING
            val buildType = if (BuildConfig.DEBUG) "debug" else "release"
            module.callAttr("set_build_type", buildType)
            isPythonWarmedUp = true
            val warmUpTime = System.currentTimeMillis() - warmUpStart
            Log.d(TAG, "Python 预热完成，buildType=$buildType，耗时: ${warmUpTime}ms")
            
        } catch (e: Exception) {
            Log.w(TAG, "Python 预热失败: ${e.message}")
        }
    }

    // ── 内存紧张回调 ──

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Log.d(TAG, "onTrimMemory: level=$level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // 内存极度紧张，释放所有可释放资源
                Log.w(TAG, "内存极度紧张，执行紧急清理")
                clearPythonCache()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 内存较低，释放非必要缓存
                Log.w(TAG, "内存较低，清理 Python 缓存")
                clearPythonCache()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // 应用进入后台，可以释放 UI 相关资源
                Log.d(TAG, "应用进入后台，建议释放非必要资源")
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // 后台进程被系统回收前，尽力清理
                Log.w(TAG, "后台进程内存紧张，执行清理")
                clearPythonCache()
            }
        }
    }

    /**
     * 清理 Python 运行时缓存。
     * 注意：不销毁 Python 实例（会丢失已加载的角色卡和对话状态），
     * 仅触发 Python 侧的垃圾回收。
     */
    private fun clearPythonCache() {
        try {
            if (isPythonWarmedUp) {
                val python = com.chaquo.python.Python.getInstance()
                // 调用 Python gc 模块回收内存
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
        Log.w(TAG, "onLowMemory")
        clearPythonCache()
    }
}