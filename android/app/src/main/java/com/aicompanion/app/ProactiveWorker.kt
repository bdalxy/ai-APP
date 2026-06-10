package com.aicompanion.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager 定时 Worker（T5.3）。
 *
 * 每 2 小时执行一次 doWork()：
 * 1. 调用 Python generate_proactive_message()
 * 2. 解析 JSON，若 status="ok" 则通过 NotificationHelper 弹出通知
 *
 * WorkManager 保证在满足约束条件（如网络连接）时执行。
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ProactiveWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork 开始")

        return try {
            // 调用 Python 获取主动推送消息
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("chat_bridge")
            val result = module.callAttr("generate_proactive_message").toString()

            Log.d(TAG, "Python 返回: ${result.take(200)}")

            val json = org.json.JSONObject(result)
            val status = json.optString("status", "")

            if (status == "ok") {
                val title = json.optString("title", "AI 伙伴")
                val message = json.optString("message", "有一则新消息")

                // 控制通知内容长度
                val shortMessage = if (message.length > 100) {
                    message.take(97) + "..."
                } else {
                    message
                }

                NotificationHelper.showProactiveMessage(applicationContext, title, shortMessage)
                Log.i(TAG, "主动推送通知已弹出: $title")
            } else {
                val reason = json.optString("message", "暂无消息")
                Log.d(TAG, "无需推送: $reason")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork 执行失败: ${e.message}", e)
            // 使用 retry 让 WorkManager 稍后重试
            Result.retry()
        }
    }
}