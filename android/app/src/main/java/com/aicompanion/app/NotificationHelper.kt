package com.aicompanion.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知辅助类，统一管理 AI Companion 的通知渠道和弹出逻辑。
 */
object NotificationHelper {

    /** 主动推送通知渠道 ID */
    const val CHANNEL_PROACTIVE = "ai_companion_proactive"

    /** 前台服务通知渠道 ID */
    const val CHANNEL_SERVICE = "ai_companion_service"

    /** 前台服务通知 ID */
    const val SERVICE_NOTIFICATION_ID = 1

    /** 主动推送通知起始 ID */
    private const val PROACTIVE_NOTIFICATION_ID_BASE = 100

    /**
     * 创建所需的通知渠道（幂等操作，重复调用无副作用）。
     * 应在 Application.onCreate 中调用。
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 主动推送渠道：重要性 HIGH（带声音和通知点）
        val proactiveChannel = NotificationChannel(
            CHANNEL_PROACTIVE,
            "AI 主动推送",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI 伙伴的主动消息推送"
        }

        // 前台服务渠道：重要性 LOW（静默，仅显示在状态栏）
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "AI 伙伴服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AI 伙伴后台服务运行状态"
        }

        manager.createNotificationChannel(proactiveChannel)
        manager.createNotificationChannel(serviceChannel)
    }

    /**
     * 弹出主动推送通知。
     * @param context 上下文
     * @param title 通知标题
     * @param message 通知内容（1-2行）
     */
    fun showProactiveMessage(context: Context, title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PROACTIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 使用 System.currentTimeMillis() 的 hashCode 作为唯一 ID，避免覆盖
        manager.notify(
            (PROACTIVE_NOTIFICATION_ID_BASE + System.currentTimeMillis() % 1000).toInt(),
            notification
        )
    }

    /**
     * 构建前台服务通知。
     * ProactiveService 创建后即可调用 startForeground 绑定此通知。
     */
    fun buildServiceNotification(context: Context): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI 伙伴运行中")
            .setContentText("正在检查是否有新的消息...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}