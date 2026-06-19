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

class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val FILE_PREFIX = "crash_"
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        private val LOG_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private const val MARKER_FILE = "has_crash_log"
        @Volatile
        private var instance: CrashHandler? = null

        fun init(context: Context) {
            if (instance != null) {
                Log.w(TAG, "CrashHandler 已初始化，跳过重复初始化")
                return
            }
            instance = CrashHandler(context.applicationContext)
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(instance)
            Log.d(TAG, "CrashHandler 初始化完成")
        }

        fun getCrashLogDir(context: Context): File {
            return File(context.filesDir, CRASH_LOG_DIR)
        }

        fun getMarkerFile(context: Context): File {
            return File(context.filesDir, MARKER_FILE)
        }

        fun hasCrashLogs(context: Context): Boolean {
            return getMarkerFile(context).exists()
        }

        fun getCrashLogFiles(context: Context): List<File> {
            val dir = getCrashLogDir(context)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        fun clearMarker(context: Context) {
            getMarkerFile(context).delete()
        }
    }

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "捕获到未处理异常: ${throwable.message}", throwable)
        try {
            writeCrashLog(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "写入崩溃日志失败: ${e.message}", e)
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val logDir = getCrashLogDir(context)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val timestamp = FILE_DATE_FORMAT.format(Date())
        val fileName = "${FILE_PREFIX}${timestamp}.log"
        val logFile = File(logDir, fileName)
        Log.d(TAG, "写入崩溃日志: ${logFile.absolutePath}")
        FileWriter(logFile).use { writer ->
            writer.write(buildCrashReport(thread, throwable))
            writer.flush()
        }
        try {
            getMarkerFile(context).createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "创建标记文件失败: ${e.message}")
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("========== 崩溃报告 ==========")
        sb.appendLine("时间: ${LOG_DATE_FORMAT.format(Date())}")
        val deviceInfo = collectDeviceInfo()
        sb.appendLine("设备: ${deviceInfo.first} (Android ${deviceInfo.second})")
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("APP版本: ${pkgInfo.versionName ?: "未知"}")
        } catch (e: Exception) {
            sb.appendLine("APP版本: 未知")
        }
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        sb.appendLine("内存: 已用${usedMemory}MB / 最大${maxMemory}MB")
        sb.appendLine("线程: ${thread.name}")
        sb.appendLine("--- 堆栈信息 ---")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())
        sb.appendLine("===============================")
        return sb.toString()
    }

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