package com.aicompanion.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 主动推送定时调度工具。
 *
 * 负责向 WorkManager 注册/更新/取消 PeriodicWorkRequest，
 * 由 ProactiveWorker 执行实际的推送检查逻辑。
 */
object ProactiveService {

    private const val TAG = "ProactiveService"
    private const val WORK_NAME = "proactive_message_work"

    /**
     * 注册或更新定时任务。
     * 从 SharedPreferences 读取间隔配置，使用 UPDATE 策略覆盖已有任务。
     *
     * @param context 上下文
     */
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // 默认 3 小时，WorkManager 最小间隔是 15 分钟
        val intervalMs = prefs.getLong("proactive_interval", 10800000L)
        val intervalMinutes = (intervalMs / 60000).coerceAtLeast(15)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        Log.i(TAG, "主动消息已调度: 每${intervalMinutes}分钟")
    }

    /**
     * 取消定时任务。
     *
     * @param context 上下文
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "主动消息已取消")
    }

    /**
     * 重新调度（先取消再注册）。
     * 供设置页在用户修改参数后调用。
     *
     * @param context 上下文
     */
    fun reschedule(context: Context) {
        cancel(context)
        schedule(context)
    }
}