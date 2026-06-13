package com.aicompanion.app

import com.chaquo.python.android.PyApplication

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
        NotificationHelper.createChannel(this)
    }
}