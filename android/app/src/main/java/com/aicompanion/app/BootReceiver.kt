package com.aicompanion.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启动广播接收器。
 *
 * 设备重启后自动恢复主动推送定时器（ProactiveWorker）。
 * 仅在用户已启用主动推送功能时生效。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "收到开机广播，恢复主动推送定时器")
            try {
                ProactiveService.schedule(context)
                Log.d(TAG, "主动推送定时器已恢复")
            } catch (e: Exception) {
                Log.w(TAG, "恢复主动推送定时器失败: ${e.message}")
            }
        }
    }
}