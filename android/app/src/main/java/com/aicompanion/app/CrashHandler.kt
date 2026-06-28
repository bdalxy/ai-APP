package com.aicompanion.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局未捕获异常处理器。
 *
 * 功能：
 * - 捕获所有未处理的崩溃异常
 * - 记录堆栈信息、设备信息、时间戳、应用版本信息
 * - 按异常类型进行崩溃分类统计（内存 + 持久化到 JSON 文件）
 * - 写入 filesDir/crash_logs/ 目录
 * - 写入后调用原 DefaultUncaughtExceptionHandler，确保系统正常处理
 */
class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        /** 崩溃日志目录名 */
        private const val CRASH_LOG_DIR = "crash_logs"
        /** 崩溃日志文件名前缀 */
        private const val FILE_PREFIX = "crash_"
        /** 崩溃统计 JSON 文件名 */
        private const val STATS_FILE = "crash_stats.json"
        /** 文件名日期格式（用于文件名） */
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        /** 日志内日期格式（用于报告内容） */
        private val LOG_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        /** 崩溃日志标记文件（用于下次启动检测） */
        private const val MARKER_FILE = "has_crash_log"

        /**
         * 崩溃分类统计——按异常类型分组计数。
         * 使用 ConcurrentHashMap 保证多线程安全（崩溃可能发生在任意线程）。
         * 每次崩溃时更新内存计数，并同步持久化到 crash_stats.json。
         */
        private val crashTypeStats = ConcurrentHashMap<String, Int>()

        /** 单例实例 */
        @Volatile
        private var instance: CrashHandler? = null

        /**
         * 初始化崩溃处理器，设置到当前线程的默认异常处理器上。
         *
         * @param context Application Context
         */
        fun init(context: Context) {
            if (instance != null) {
                Log.w(TAG, "CrashHandler 已初始化，跳过重复初始化")
                return
            }
            instance = CrashHandler(context.applicationContext)
            // 保存原默认处理器（必须在 setDefaultUncaughtExceptionHandler 之前）
            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            // 设置自定义处理器
            Thread.setDefaultUncaughtExceptionHandler(instance)
            instance?.defaultHandler = originalHandler
            // 加载持久化的崩溃统计数据（跨进程生命周期累计）
            instance?.loadPersistedStats()
            Log.d(TAG, "CrashHandler 初始化完成，原处理器: ${originalHandler?.javaClass?.simpleName}")
        }

        /**
         * 获取崩溃日志目录。
         */
        fun getCrashLogDir(context: Context): File {
            return File(context.filesDir, CRASH_LOG_DIR)
        }

        /**
         * 获取标记文件路径。
         */
        fun getMarkerFile(context: Context): File {
            return File(context.filesDir, MARKER_FILE)
        }

        /**
         * 检查是否存在崩溃日志。
         */
        fun hasCrashLogs(context: Context): Boolean {
            return getMarkerFile(context).exists()
        }

        /**
         * 获取所有崩溃日志文件，按时间倒序排列。
         */
        fun getCrashLogFiles(context: Context): List<File> {
            val dir = getCrashLogDir(context)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        /**
         * 清除崩溃标记（用户已查看后调用）。
         */
        fun clearMarker(context: Context) {
            getMarkerFile(context).delete()
        }

        /**
         * 获取崩溃分类统计的快照（线程安全）。
         *
         * @return 异常类型 -> 累计次数的不可变映射
         */
        fun getCrashStats(): Map<String, Int> = crashTypeStats.toMap()

        /**
         * 获取崩溃统计文件路径。
         */
        fun getCrashStatsFile(context: Context): File {
            return File(getCrashLogDir(context), STATS_FILE)
        }
    }

    /** 系统默认的未捕获异常处理器（由 init() 在构造后设置） */
    internal var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 当未捕获异常发生时调用。
     *
     * 流程：
     * 1. 记录崩溃日志到文件
     * 2. 更新崩溃分类统计（内存 + 持久化）
     * 3. 创建标记文件，供下次启动检测
     * 4. 调用原 DefaultUncaughtExceptionHandler
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 只记录异常类型和消息，不记录完整堆栈（避免泄露用户数据到 logcat）
        Log.e(TAG, "捕获到未处理异常: ${throwable.javaClass.simpleName}: ${throwable.message?.take(200) ?: "无消息"}")

        try {
            writeCrashLog(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "写入崩溃日志失败: ${e.javaClass.simpleName}")
        }

        // 传递给原默认处理器（让系统显示崩溃对话框或重启应用）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * 将崩溃信息写入日志文件，同时更新分类统计。
     * 统计更新在文件写入之前完成，确保即使文件写入失败，统计也已记录。
     */
    private fun writeCrashLog(thread: ThrowableThread, throwable: Throwable) {
        // 确保目录存在
        val logDir = getCrashLogDir(context)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // 更新崩溃分类统计（内存中的计数 + 持久化到 JSON 文件）
        val exceptionType = throwable.javaClass.simpleName
        crashTypeStats.merge(exceptionType, 1, Int::plus)
        saveStatsToFile()

        // 生成文件名：crash_yyyyMMdd_HHmmss.log
        val timestamp = FILE_DATE_FORMAT.format(Date())
        val fileName = "${FILE_PREFIX}${timestamp}.log"
        val logFile = File(logDir, fileName)

        Log.d(TAG, "写入崩溃日志: ${logFile.absolutePath}")

        FileWriter(logFile).use { writer ->
            writer.write(buildCrashReport(thread, throwable))
            writer.flush()
        }

        // 创建标记文件
        try {
            getMarkerFile(context).createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "创建标记文件失败: ${e.message}")
        }
    }

    /**
     * 构建崩溃报告内容（增强版）。
     *
     * 报告结构：
     * 1. 设备信息（制造商、品牌、型号、Android版本、屏幕、内存）
     * 2. 应用信息（应用ID、版本名称、版本号、构建类型）
     * 3. 崩溃信息（时间戳、线程、异常类型、异常消息）
     * 4. 崩溃统计（按异常类型分组的累计次数）
     * 5. 堆栈信息（脱敏截断）
     */
    private fun buildCrashReport(thread: ThrowableThread, throwable: Throwable): String {
        val sb = StringBuilder()

        // ── 标题 ──
        sb.appendLine("========== 崩溃报告 ==========")
        sb.appendLine("注意: 本报告仅含设备信息与堆栈，不含用户对话内容。")
        sb.appendLine()

        // ── 设备信息（增强版）──
        sb.appendLine("--- 设备信息 ---")
        appendDeviceInfo(sb)
        sb.appendLine()

        // ── 应用信息（版本号 + 构建信息）──
        sb.appendLine("--- 应用信息 ---")
        appendAppInfo(sb)
        sb.appendLine()

        // ── 崩溃信息 ──
        sb.appendLine("--- 崩溃信息 ---")
        val now = Date()
        // 同时输出可读时间和 epoch 毫秒，方便精确排序和调试
        sb.appendLine("时间: ${LOG_DATE_FORMAT.format(now)} (epoch: ${now.time})")
        sb.appendLine("线程: ${thread.name}")
        sb.appendLine("异常类型: ${throwable.javaClass.simpleName}")

        // 异常消息（脱敏：截断至200字符，防止用户输入内容泄露）
        val rawMessage = throwable.message ?: "无消息"
        val sanitizedMessage = if (rawMessage.length > 200) rawMessage.take(200) + "...（已截断）" else rawMessage
        sb.appendLine("异常消息: $sanitizedMessage")
        sb.appendLine()

        // ── 崩溃分类统计 ──
        sb.appendLine("--- 崩溃统计 ---")
        appendCrashStats(sb, throwable.javaClass.simpleName)
        sb.appendLine()

        // ── 堆栈信息（截断至50行，避免敏感数据泄露）──
        sb.appendLine("--- 堆栈信息（已截断） ---")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val lines = sw.toString().lines()
        lines.take(50).forEach { sb.appendLine(sanitizeStackTraceLine(it)) }
        if (lines.size > 50) {
            sb.appendLine("... (已截断 ${lines.size - 50} 行)")
        }
        sb.appendLine()

        // ── 结束标记 ──
        sb.appendLine("===============================")

        return sb.toString()
    }

    /**
     * 追加详细设备信息到报告。
     * 包含：制造商、品牌、型号、Android版本/SDK、屏幕分辨率、系统内存、应用内存。
     */
    private fun appendDeviceInfo(sb: StringBuilder) {
        // 制造商
        try {
            sb.appendLine("制造商: ${Build.MANUFACTURER ?: "未知"}")
        } catch (e: Exception) {
            sb.appendLine("制造商: 未知")
        }
        // 品牌
        try {
            sb.appendLine("品牌: ${Build.BRAND ?: "未知"}")
        } catch (e: Exception) {
            sb.appendLine("品牌: 未知")
        }
        // 型号
        try {
            sb.appendLine("型号: ${Build.MODEL ?: "未知"}")
        } catch (e: Exception) {
            sb.appendLine("型号: 未知")
        }
        // Android 版本 + SDK 级别
        try {
            sb.appendLine("Android版本: ${Build.VERSION.RELEASE ?: "未知"} (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            sb.appendLine("Android版本: 未知")
        }

        // 屏幕分辨率（通过 WindowManager 获取真实像素尺寸）
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            sb.appendLine("屏幕分辨率: ${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (e: Exception) {
            sb.appendLine("屏幕分辨率: 未知")
        }

        // 系统内存信息（总内存 + 可用内存）
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalRamMB = memInfo.totalMem / (1024 * 1024)
            val availRamMB = memInfo.availMem / (1024 * 1024)
            sb.appendLine("总内存: ${totalRamMB}MB")
            sb.appendLine("可用内存: ${availRamMB}MB")
        } catch (e: Exception) {
            sb.appendLine("系统内存: 未知")
        }

        // 应用进程内存（JVM 堆内存）
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            sb.appendLine("应用内存: 已用${usedMemory}MB / 最大${maxMemory}MB")
        } catch (e: Exception) {
            sb.appendLine("应用内存: 未知")
        }
    }

    /**
     * 追加应用版本和构建信息到报告。
     * 使用 BuildConfig 获取编译时确定的版本和构建类型。
     */
    private fun appendAppInfo(sb: StringBuilder) {
        try {
            sb.appendLine("应用ID: ${BuildConfig.APPLICATION_ID}")
        } catch (e: Exception) {
            sb.appendLine("应用ID: 未知")
        }
        try {
            sb.appendLine("版本名称: ${BuildConfig.VERSION_NAME}")
        } catch (e: Exception) {
            sb.appendLine("版本名称: 未知")
        }
        try {
            sb.appendLine("版本号: ${BuildConfig.VERSION_CODE}")
        } catch (e: Exception) {
            sb.appendLine("版本号: 未知")
        }
        try {
            sb.appendLine("构建类型: ${BuildConfig.BUILD_TYPE}")
        } catch (e: Exception) {
            sb.appendLine("构建类型: 未知")
        }
    }

    /**
     * 追加崩溃分类统计到报告。
     * 按异常类型分组展示累计次数，并标注本次崩溃的类型。
     *
     * @param sb 报告 StringBuilder
     * @param currentType 本次崩溃的异常类型（用于标注"本次崩溃"）
     */
    private fun appendCrashStats(sb: StringBuilder, currentType: String) {
        if (crashTypeStats.isEmpty()) {
            sb.appendLine("暂无统计数据")
            return
        }

        // 按次数降序排列，方便快速定位高频崩溃
        val sortedStats = crashTypeStats.entries.sortedByDescending { it.value }
        sb.appendLine("历史累计崩溃统计（按异常类型分组）：")
        for ((type, count) in sortedStats) {
            val marker = if (type == currentType) "  <-- 本次崩溃" else ""
            sb.appendLine("  $type: ${count}次$marker")
        }
        sb.appendLine("总崩溃次数: ${crashTypeStats.values.sum()}")
    }

    /**
     * 对堆栈行进行脱敏处理。
     * 截断过长的行（>300字符），防止用户输入内容通过堆栈参数泄露。
     */
    private fun sanitizeStackTraceLine(line: String): String {
        if (line.length > 300) {
            return line.take(300) + "...（已截断）"
        }
        return line
    }

    /**
     * 从持久化 JSON 文件加载崩溃统计数据。
     * 如果文件不存在或格式错误，则从空统计开始（不中断应用运行）。
     */
    private fun loadPersistedStats() {
        try {
            val statsFile = getCrashStatsFile(context)
            if (!statsFile.exists()) {
                Log.d(TAG, "崩溃统计文件不存在，从零开始统计")
                return
            }
            val json = JSONObject(statsFile.readText())
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                crashTypeStats[key] = json.getInt(key)
            }
            Log.d(TAG, "已加载崩溃统计: ${crashTypeStats.size} 种异常类型, 总计 ${crashTypeStats.values.sum()} 次")
        } catch (e: Exception) {
            Log.w(TAG, "加载崩溃统计失败: ${e.message}，从零开始统计")
            crashTypeStats.clear()
        }
    }

    /**
     * 将崩溃统计数据持久化到 JSON 文件。
     * 格式：{"异常类型": 次数, ...}，缩进2空格便于人工阅读。
     */
    private fun saveStatsToFile() {
        try {
            val statsFile = getCrashStatsFile(context)
            val logDir = statsFile.parentFile
            if (logDir != null && !logDir.exists()) {
                logDir.mkdirs()
            }
            val json = JSONObject()
            crashTypeStats.forEach { (type, count) ->
                json.put(type, count)
            }
            statsFile.writeText(json.toString(2))
            Log.d(TAG, "崩溃统计已保存: ${crashTypeStats.values.sum()} 次崩溃")
        } catch (e: Exception) {
            Log.w(TAG, "保存崩溃统计失败: ${e.message}")
        }
    }
}

/**
 * 类型别名，增强代码可读性。
 * 表示发生未捕获异常时所在的线程。
 */
private typealias ThrowableThread = Thread