package com.aicompanion.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主动推送前台服务。
 *
 * 被 WorkManager 或外部 Intent 启动后：
 * 1. 立即进入前台模式（startForeground）
 * 2. 在后台线程调用 Python generate_proactive_message()
 * 3. 解析返回 JSON，若 status="ok" 则弹出通知
 * 4. 完成后 stopSelf()
 */
class ProactiveService : Service() {

    companion object {
        private const val TAG = "ProactiveService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // 进入前台模式
        val notification = NotificationHelper.buildServiceNotification(this)
        startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)

        // 后台执行主动推送检查
        serviceScope.launch {
            try {
                checkAndPush()
            } catch (e: Exception) {
                Log.e(TAG, "主动推送执行失败: ${e.message}", e)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        serviceScope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    /**
     * 调用 Python generate_proactive_message() 并处理结果。
     */
    private fun checkAndPush() {
        try {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("chat_bridge")
            val result = module.callAttr("generate_proactive_message").toString()

            Log.d(TAG, "Python 返回: ${result.take(200)}")

            val json = org.json.JSONObject(result)
            val status = json.optString("status", "")

            if (status == "ok") {
                val title = json.optString("title", "AI 伙伴")
                val message = json.optString("message", "有一则新消息")

                // 通知内容控制在1-2行
                val shortMessage = if (message.length > 100) {
                    message.take(97) + "..."
                } else {
                    message
                }

                NotificationHelper.showProactiveMessage(this, title, shortMessage)
                Log.i(TAG, "主动推送通知已弹出: $title")
            } else {
                val reason = json.optString("message", "暂无消息")
                Log.d(TAG, "无需推送: $reason")
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAndPush 异常: ${e.message}", e)
        }
    }
}