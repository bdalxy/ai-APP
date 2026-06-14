package com.aicompanion.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知辅助类。
 * 统一管理 AI Companion 的主动消息通知渠道与弹出逻辑。
 */
object NotificationHelper {

    /** 主动消息通知渠道 ID */
    const val CHANNEL_ID = "proactive_messages"

    /** 主动消息通知渠道名称（用户可见） */
    const val CHANNEL_NAME = "AI 主动消息"

    /** 主动消息通知固定 ID（每次覆盖上一条） */
    const val NOTIFICATION_ID = 1001

    /**
     * 创建主动消息通知渠道（幂等操作，重复调用无副作用）。
     * 应在 Application.onCreate 或 MainActivity.onCreate 中尽早调用。
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "AI 伴侣主动发来的消息"
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 弹出主动推送通知。
     * 点击通知后跳转到 MainActivity，并自动消失。
     *
     * @param context 上下文
     * @param title   通知标题
     * @param message 通知正文
     */
    fun show(context: Context, title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}