package com.aicompanion.app

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 世界书常识条目适配器。
 *
 * 功能：
 * - 左滑露出编辑/遗忘按钮
 * - 点击条目触发编辑
 * - 遗忘动画：颜色变冷变白 → 缩小消失
 */
class WorldBookAdapter(
    private val context: android.content.Context,
    private val entries: MutableList<WorldBookEntry>,
    private val onEdit: (WorldBookEntry) -> Unit,
    private val onDelete: (WorldBookEntry, Int) -> Unit
) : RecyclerView.Adapter<WorldBookAdapter.ViewHolder>() {

    private val swipeThreshold = (56 * context.resources.displayMetrics.density).toInt()
    private var isSwiping = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutCard: View = view.findViewById(R.id.layoutCard)
        val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val btnEdit: View = view.findViewById(R.id.btnEdit)
        val btnForget: View = view.findViewById(R.id.btnForget)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_world_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvCategory.text = entry.category.ifEmpty { context.getString(R.string.uncategorized) }
        holder.tvContent.text = entry.content
        val time = if (entry.updatedAt.length >= 10) {
            entry.updatedAt.substring(0, 10)
        } else {
            entry.updatedAt
        }
        holder.tvTime.text = time
        holder.layoutCard.translationX = 0f
        holder.btnEdit.setOnClickListener { resetSwipe(holder); onEdit(entry) }
        holder.btnForget.setOnClickListener { resetSwipe(holder); onDelete(entry, holder.bindingAdapterPosition) }
        holder.layoutCard.setOnClickListener {
            if (!isSwiping) {
                resetSwipe(holder); onEdit(entry)
            }
            isSwiping = false
        }
        setupSwipeGesture(holder)
    }

    override fun getItemCount(): Int = entries.size

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeGesture(holder: ViewHolder) {
        var startX = 0f
        var isSwipingLocal = false
        var swipeOffset = 0f
        holder.layoutCard.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; isSwipingLocal = false
                    isSwiping = false
                    swipeOffset = holder.layoutCard.translationX; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    if (kotlin.math.abs(deltaX) > 10) {
                        isSwipingLocal = true
                        isSwiping = true
                        val newX = (swipeOffset + deltaX).coerceIn(-swipeThreshold.toFloat() * 2, 0f)
                        holder.layoutCard.translationX = newX; true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwipingLocal) {
                        if (holder.layoutCard.translationX < -swipeThreshold) {
                            animateSwipeOpen(holder)
                        } else animateSwipeClose(holder)
                    }; true
                }
                else -> false
            }
        }
    }

    private fun animateSwipeOpen(holder: ViewHolder) {
        ObjectAnimator.ofFloat(holder.layoutCard, "translationX", holder.layoutCard.translationX, -swipeThreshold.toFloat() * 2)
            .apply { duration = 200; start() }
    }

    private fun animateSwipeClose(holder: ViewHolder) {
        ObjectAnimator.ofFloat(holder.layoutCard, "translationX", holder.layoutCard.translationX, 0f)
            .apply { duration = 200; start() }
    }

    private fun resetSwipe(holder: ViewHolder) { holder.layoutCard.translationX = 0f }

    fun animateForget(holder: ViewHolder, onDone: () -> Unit) {
        val card = holder.layoutCard
        val colorAnim = ObjectAnimator.ofArgb(
            card, "backgroundColor",
            ContextCompat.getColor(context, R.color.wb_card_bg),
            ContextCompat.getColor(context, R.color.wb_forget_cold),
            ContextCompat.getColor(context, android.R.color.white)
        ).apply { duration = 400; setEvaluator(android.animation.ArgbEvaluator()) }
        val scaleX = ObjectAnimator.ofFloat(card, "scaleX", 1f, 0.6f, 0f).apply { duration = 500; startDelay = 200 }
        val scaleY = ObjectAnimator.ofFloat(card, "scaleY", 1f, 0.6f, 0f).apply { duration = 500; startDelay = 200 }
        val alpha = ObjectAnimator.ofFloat(card, "alpha", 1f, 0.5f, 0f).apply { duration = 500; startDelay = 200 }
        val set = AnimatorSet()
        set.playTogether(colorAnim, scaleX, scaleY, alpha)
        set.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) { onDone() }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })
        set.start()
    }

    fun addItem(entry: WorldBookEntry) { entries.add(0, entry); notifyItemInserted(0) }

    fun updateItem(position: Int, entry: WorldBookEntry) {
        if (position < 0 || position >= entries.size) return
        entries[position] = entry; notifyItemChanged(position)
    }

    fun removeItem(position: Int) {
        if (position < 0 || position >= entries.size) return
        entries.removeAt(position); notifyItemRemoved(position)
    }
}