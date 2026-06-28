package com.aicompanion.app

import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * 消息气泡入场动画（S2.4.5 动画优化）
 *
 * 新消息从底部淡入+微移，提供流畅的视觉过渡。
 * - translateY 10dp -> 0
 * - alpha 0 -> 1
 * - 持续 300ms
 * - 使用 hardware layer 加速，避免低端设备卡顿
 */
class MessageItemAnimator : DefaultItemAnimator() {

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        // 10dp 转 px（密度无关像素）
        val translatePx = (10f * view.context.resources.displayMetrics.density).toInt()
        // 初始状态：透明 + 向下偏移 10dp
        view.alpha = 0f
        view.translationY = translatePx.toFloat()
        // 启用硬件层加速动画
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // 执行动画：淡入 + 上滑归位
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay(30)
            .withEndAction {
                // 动画结束后关闭硬件层（释放资源）
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()
        // 立即通知添加完成，避免 RecyclerView 再做默认动画
        dispatchAddFinished(holder)
        // 返回 false 表示自己处理了动画
        return false
    }
}