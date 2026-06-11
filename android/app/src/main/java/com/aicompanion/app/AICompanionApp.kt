package com.aicompanion.app

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.chaquo.python.android.PyApplication
import java.util.concurrent.TimeUnit

/**
 * AI Companion 应用入口。
 * 必须继承 PyApplication 以自动初始化 Chaquopy AndroidPlatform，
 * 否则调用 Python.getInstance() 会报 GenericPlatform 错误。
 */
class AICompanionApp : PyApplication() {

    override fun onCreate() {
        super.onCreate()

        // 品牌设备 UI 适配（小米 HyperOS / 荣耀 MagicOS 缩放补偿）
        DeviceAdaptationHelper.init(this)

        // 创建通知渠道（Android 8.0+ 必须，否则通知不显示）
        NotificationHelper.createChannels(this)

        // T5.3: 注册 WorkManager 定时任务（每 2 小时检查一次主动推送）
        registerProactiveWorker()
    }

    /**
     * 注册主动推送 WorkManager 定时任务。
     * 每 2 小时触发一次，要求网络连接。
     * 使用 KEEP 策略避免重复注册。
     */
    private fun registerProactiveWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val proactiveRequest = PeriodicWorkRequestBuilder<ProactiveWorker>(
            2, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "proactive_push",
            ExistingPeriodicWorkPolicy.KEEP,
            proactiveRequest
        )
    }
}