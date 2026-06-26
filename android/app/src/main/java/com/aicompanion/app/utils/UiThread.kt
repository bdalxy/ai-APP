package com.aicompanion.app.utils

import android.os.Handler
import android.os.Looper

/**
 * 主线程调度工具类。
 * 用于在非主线程中将操作切换到 UI 线程执行。
 */
object UiThread {
    private val handler = Handler(Looper.getMainLooper())

    fun run(action: () -> Unit) {
        handler.post(action)
    }
}