package com.aicompanion.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * WorkManager 定时 Worker。
 *
 * 每次执行 doWork() 时：
 * 1. 检查 proactive_enabled 开关
 * 2. 检查免打扰时段（支持跨天，如 23:00-07:00）
 * 3. 调用 Python generate_proactive_message()
 * 4. 解析 JSON，若 status="ok" 则通过 NotificationHelper 弹出通知
 *
 * 失败时返回 retry，WorkManager 会自动重试。
 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ProactiveWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            // 1. 检查主动消息是否开启
            if (!prefs.getBoolean("proactive_enabled", false)) {
                Log.d(TAG, "主动消息未开启，跳过")
                return@withContext Result.success()
            }

            // 2. 免打扰时段检查
            val quietStart = prefs.getString("quiet_start", "") ?: ""
            val quietEnd = prefs.getString("quiet_end", "") ?: ""
            if (quietStart.isNotEmpty() && quietEnd.isNotEmpty()) {
                try {
                    val now = LocalTime.now()
                    val start = LocalTime.parse(quietStart)
                    val end = LocalTime.parse(quietEnd)
                    if (start == end) {
                        Log.w(TAG, "免打扰时段开始=结束 ($quietStart)，全天静默，跳过")
                        return@withContext Result.success()
                    }
                    if (start.isBefore(end)) {
                        // 同一天内（如 08:00-22:00）
                        if (now in start..end) {
                            Log.d(TAG, "处于免打扰时段 ($quietStart-$quietEnd)，跳过")
                            return@withContext Result.success()
                        }
                    } else {
                        // 跨天免打扰（如 23:00-07:00）
                        if (now >= start || now <= end) {
                            Log.d(TAG, "处于免打扰时段 ($quietStart-$quietEnd)，跳过")
                            return@withContext Result.success()
                        }
                    }
                } catch (e: DateTimeParseException) {
                    Log.w(TAG, "免打扰时间格式错误: $quietStart-$quietEnd, 跳过免打扰检查")
                }
            }

            // 3. 调用 Python 生成主动消息
            val resultJson: String
            try {
                val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                val pyResult = module?.callAttr("generate_proactive_message")
                resultJson = pyResult?.toString() ?: "{\"status\":\"error\",\"message\":\"Python 返回为空\"}"
            } catch (e: Exception) {
                Log.e(TAG, "Python 调用失败: ${e.message}", e)
                return@withContext Result.retry()
            }

            // 4. 解析并处理结果
            val json = JSONObject(resultJson)
            val status = json.optString("status", "error")

            when (status) {
                "ok" -> {
                    val title = json.optString("title", "AI伴侣")
                    val message = json.optString("message", "")
                    NotificationHelper.show(applicationContext, title, message)
                    Log.i(TAG, "主动消息推送成功: ${message.take(50)}...")
                    Result.success()
                }
                "skip" -> {
                    Log.d(TAG, "主动消息跳过: ${json.optString("message")}")
                    Result.success()
                }
                else -> {
                    Log.w(TAG, "主动消息生成失败: ${json.optString("message")}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker 异常: ${e.message}", e)
            Result.retry()
        }
    }
}