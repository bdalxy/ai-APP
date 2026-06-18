package com.aicompanion.app

import android.animation.ArgbEvaluator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/**
 * 记忆档案馆瀑布流卡片适配器。
 *
 * 核心功能：
 * - 2 列瀑布流布局（StaggeredGridLayoutManager）
 * - 每张卡片显示：日期、诗意摘要、情感标签
 * - 色温渐变：根据卡片在列表中的位置，越靠后的卡片颜色越冷
 * - 长按回调：通知 Activity 触发破碎删除动画
 *
 * 色温渐变逻辑：
 * - 前 20% 的记忆保持暖色（淡樱粉）
 * - 20%~80% 逐渐过渡到淡天蓝
 * - 80%~100% 过渡到浅灰
 */
class MemoryCardAdapter(
    private val onCardLongClick: (MemoryCardData, View, Int) -> Unit
) : RecyclerView.Adapter<MemoryCardAdapter.ViewHolder>() {

    private val cards = mutableListOf<MemoryCardData>()

    // ── 色温渐变相关 ──
    companion object {
        /** 暖色（淡樱粉） */
        private const val WARM_COLOR = 0xFFFDF0F0.toInt()
        /** 冷色（淡天蓝） */
        private const val COOL_COLOR = 0xFFD4E8F0.toInt()
        /** 灰色（浅灰） */
        private const val GRAY_COLOR = 0xFFE8E8E8.toInt()
        /** 每张卡片可变的随机高度偏移（用于瀑布流效果） */
        private val RANDOM_HEIGHT_OFFSETS = listOf(-40, -20, 0, 20, 40, 60)
    }

    /** 全局滚动进度（由 Activity 更新） */
    var scrollProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    // ── ViewHolder ──

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: com.google.android.material.card.MaterialCardView =
            view as com.google.android.material.card.MaterialCardView
        val tvDateLabel: TextView = view.findViewById(R.id.tvDateLabel)
        val tvSummary: TextView = view.findViewById(R.id.tvSummary)
        val tvTag1: TextView = view.findViewById(R.id.tvTag1)
        val tvTag2: TextView = view.findViewById(R.id.tvTag2)
        val tvTag3: TextView = view.findViewById(R.id.tvTag3)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]

        // 日期标签
        holder.tvDateLabel.text = card.dateLabel

        // 诗意摘要
        holder.tvSummary.text = card.summary

        // 情感标签
        bindTags(holder, card.emotionTags)

        // ── 色温渐变：根据位置计算卡片背景色 ──
        val colorProgress = (position.toFloat() / (cards.size.coerceAtLeast(1))).coerceIn(0f, 1f)
        val cardColor = interpolateColor(colorProgress)
        holder.cardView.setCardBackgroundColor(cardColor)

        // ── 瀑布流高度变化：根据内容和位置微调高度，制造错落感 ──
        updateCardHeight(holder, position, card)

        // ── 长按事件 ──
        holder.cardView.setOnLongClickListener { view ->
            onCardLongClick(card, view, position)
            true
        }
    }

    override fun getItemCount(): Int = cards.size

    // ── 公开方法 ──

    /** 获取指定位置的卡片数据 */
    fun getItem(position: Int): MemoryCardData {
        return cards[position]
    }

    /** 添加全部卡片（首次加载） */
    fun addAll(newCards: List<MemoryCardData>) {
        val startPos = cards.size
        cards.addAll(newCards)
        notifyItemRangeInserted(startPos, newCards.size)
    }

    /** 移除指定位置的卡片 */
    fun removeAt(position: Int) {
        if (position < 0 || position >= cards.size) return
        cards.removeAt(position)
        notifyItemRemoved(position)
        // 刷新后续卡片的色温（因为位置变了）
        notifyItemRangeChanged(position, cards.size - position)
    }

    /** 清空所有卡片 */
    fun clear() {
        val count = cards.size
        cards.clear()
        notifyItemRangeRemoved(0, count)
    }

    // ── 私有方法 ──

    /**
     * 绑定情感标签到 TextView。
     * 最多显示 3 个标签，多余的隐藏。
     */
    private fun bindTags(holder: ViewHolder, tags: List<String>) {
        val tagViews = listOf(holder.tvTag1, holder.tvTag2, holder.tvTag3)

        for (i in tagViews.indices) {
            if (i < tags.size) {
                tagViews[i].text = tags[i]
                tagViews[i].visibility = View.VISIBLE
            } else {
                tagViews[i].visibility = View.GONE
            }
        }
    }

    /**
     * 色温插值：根据进度返回卡片背景色。
     *
     * 进度 0.0 → 淡樱粉 (#FDF0F0)
     * 进度 0.5 → 淡天蓝 (#D4E8F0)
     * 进度 1.0 → 浅灰 (#E8E8E8)
     */
    private fun interpolateColor(progress: Float): Int {
        val evaluator = ArgbEvaluator()
        return when {
            progress < 0.5f -> {
                // 暖色 → 冷色
                val p = progress / 0.5f
                evaluator.evaluate(p, WARM_COLOR, COOL_COLOR) as Int
            }
            else -> {
                // 冷色 → 灰色
                val p = (progress - 0.5f) / 0.5f
                evaluator.evaluate(p, COOL_COLOR, GRAY_COLOR) as Int
            }
        }
    }

    /**
     * 更新卡片高度，实现瀑布流错落效果。
     * 根据内容长度和随机偏移产生不同的卡片高度。
     */
    private fun updateCardHeight(holder: ViewHolder, position: Int, card: MemoryCardData) {
        val layoutParams = holder.cardView.layoutParams as? StaggeredGridLayoutManager.LayoutParams
            ?: return

        // 根据内容长度计算基础高度
        val contentLength = card.summary.length
        val baseHeight = when {
            contentLength < 30 -> 120
            contentLength < 60 -> 150
            contentLength < 100 -> 180
            else -> 220
        }

        // 加上随机偏移（基于 position 的确定性偏移，避免滚动时变化）
        val offset = RANDOM_HEIGHT_OFFSETS[position % RANDOM_HEIGHT_OFFSETS.size]

        // 转换为 dp 再设置（这里直接使用 dp 值，View 会自动处理）
        val targetHeightDp = baseHeight + offset

        // 设置最小高度（使用 px 转换）
        val density = holder.cardView.context.resources.displayMetrics.density
        val targetHeightPx = (targetHeightDp * density).toInt()

        // 使用 StaggeredGridLayoutManager.LayoutParams 设置全跨度
        // 注意：不设置确切高度，而是通过 minHeight 让内容自适应
        // 但为了瀑布流效果，我们设置一个最小高度约束
        holder.cardView.minimumHeight = targetHeightPx
    }
}