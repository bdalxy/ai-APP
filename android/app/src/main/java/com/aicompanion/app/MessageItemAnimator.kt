package com.aicompanion.app

import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * 消息气泡入场动画（P2 UI 优化）
 *
 * 新消息从下方淡入滑入，提供流畅的视觉过渡。
 */
class MessageItemAnimator : DefaultItemAnimator() {

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        // 初始状态：透明 + 向下偏移30px
        view.alpha = 0f
        view.translationY = 30f
        // 执行动画：淡入 + 上滑归位
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setStartDelay(50)
            .start()
        // 立即通知添加完成，避免 RecyclerView 再做默认动画
        dispatchAddFinished(holder)
        // 返回 false 表示自己处理了动画
        return false
    }
}