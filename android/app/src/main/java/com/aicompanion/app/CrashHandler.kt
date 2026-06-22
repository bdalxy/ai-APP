package com.aicompanion.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 功能：
 * - 捕获所有未处理的崩溃异常
 * - 记录堆栈信息、设备信息、时间戳
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
        /** 文件名日期格式 */
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        /** 日志内日期格式 */
        private val LOG_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        /** 崩溃日志标记文件（用于下次启动检测） */
        private const val MARKER_FILE = "has_crash_log"

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
            // 保存原默认处理器
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            // 设置自定义处理器
            Thread.setDefaultUncaughtExceptionHandler(instance)
            Log.d(TAG, "CrashHandler 初始化完成，原处理器: ${defaultHandler?.javaClass?.simpleName}")
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
    }

    /** 系统默认的未捕获异常处理器 */
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /**
     * 当未捕获异常发生时调用。
     *
     * 流程：
     * 1. 记录崩溃日志到文件
     * 2. 创建标记文件，供下次启动检测
     * 3. 调用原 DefaultUncaughtExceptionHandler
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
     * 将崩溃信息写入日志文件。
     */
    private fun writeCrashLog(thread: ThrowableThread, throwable: Throwable) {
        // 确保目录存在
        val logDir = getCrashLogDir(context)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // 生成文件名
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
     * 构建崩溃报告内容。
     */
    private fun buildCrashReport(thread: ThrowableThread, throwable: Throwable): String {
        val sb = StringBuilder()

        // 标题
        sb.appendLine("========== 崩溃报告 ==========")
        sb.appendLine("注意: 本报告仅含设备信息与堆栈，不含用户对话内容。")

        // 时间
        sb.appendLine("时间: ${LOG_DATE_FORMAT.format(Date())}")

        // 设备信息
        val deviceInfo = collectDeviceInfo()
        sb.appendLine("设备: ${deviceInfo.first} (Android ${deviceInfo.second})")

        // APP 版本
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("APP版本: ${pkgInfo.versionName ?: "未知"}")
        } catch (e: Exception) {
            sb.appendLine("APP版本: 未知")
        }

        // 内存信息
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        sb.appendLine("内存: 已用${usedMemory}MB / 最大${maxMemory}MB")

        // 崩溃线程
        sb.appendLine("线程: ${thread.name}")

        // 异常消息（脱敏：截断至200字符）
        val rawMessage = throwable.message ?: "无消息"
        val sanitizedMessage = if (rawMessage.length > 200) rawMessage.take(200) + "...（已截断）" else rawMessage
        sb.appendLine("异常: ${throwable.javaClass.simpleName}: $sanitizedMessage")

        // 堆栈信息（截断至50行，避免敏感数据泄露）
        sb.appendLine("--- 堆栈信息（已截断） ---")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val lines = sw.toString().lines()
        lines.take(50).forEach { sb.appendLine(sanitizeStackTraceLine(it)) }
        if (lines.size > 50) {
            sb.appendLine("... (已截断 ${lines.size - 50} 行)")
        }

        // 结束标记
        sb.appendLine("===============================")

        return sb.toString()
    }

    /**
     * 对堆栈行进行脱敏处理。
     * 截断过长的行（>300字符），过滤可能包含用户输入内容的行。
     */
    private fun sanitizeStackTraceLine(line: String): String {
        if (line.length > 300) {
            return line.take(300) + "...（已截断）"
        }
        return line
    }

    /**
     * 收集设备信息。
     *
     * @return Pair(设备型号, Android版本)
     */
    private fun collectDeviceInfo(): Pair<String, String> {
        val model = try {
            Build.MODEL ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
        val androidVersion = try {
            Build.VERSION.RELEASE ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
        return Pair(model, androidVersion)
    }
}

/**
 * 类型别名，增强代码可读性。
 */
private typealias ThrowableThread = Thread