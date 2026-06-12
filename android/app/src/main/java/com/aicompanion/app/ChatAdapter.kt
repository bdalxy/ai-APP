package com.aicompanion.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 聊天消息 RecyclerView 适配器。
 * 根据消息类型（用户/AI/打字指示器）显示不同的气泡样式。
 * 用户消息右对齐紫色渐变气泡，AI 消息左对齐粉色气泡，打字消息显示三点动画。
 */
class ChatAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    companion object {
        const val TYPING_TEXT = "对方正在输入..."

        private const val VIEW_TYPE_TYPING = 0
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutMessageRow: View = view.findViewById(R.id.layoutMessageRow)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val layoutTyping: View = view.findViewById(R.id.layoutTyping)
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            messages[position].isTyping -> VIEW_TYPE_TYPING
            messages[position].isUser -> VIEW_TYPE_USER
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        val holder = ViewHolder(view)
        val context = view.context

        // 按 viewType 预设置不变的样式，避免 onBind 重复设置
        when (viewType) {
            VIEW_TYPE_TYPING -> {
                holder.tvContent.visibility = View.GONE
                holder.layoutTyping.visibility = View.VISIBLE
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
            }
            VIEW_TYPE_USER -> {
                holder.tvContent.visibility = View.VISIBLE
                holder.layoutTyping.visibility = View.GONE
                holder.tvContent.setBackgroundResource(R.drawable.bg_bubble_user)
                holder.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_user_text))
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.END
            }
            VIEW_TYPE_AI -> {
                holder.tvContent.visibility = View.VISIBLE
                holder.layoutTyping.visibility = View.GONE
                holder.tvContent.setBackgroundResource(R.drawable.bg_bubble_ai)
                holder.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_ai_text))
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        // 仅设置动态内容，样式已在 onCreateViewHolder 中预设
        when (getItemViewType(position)) {
            VIEW_TYPE_TYPING -> { /* 打字指示器无动态内容 */ }
            VIEW_TYPE_USER, VIEW_TYPE_AI -> {
                holder.tvContent.text = message.content
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    /**
     * 移除指定位置的打字指示器消息。
     * @return 被移除的 Message，如果该位置不是 typing 消息则返回 null。
     */
    fun removeTypingAt(position: Int): Message? {
        if (position < 0 || position >= messages.size) return null
        val msg = messages[position]
        if (!msg.isTyping) return null
        messages.removeAt(position)
        notifyItemRemoved(position)
        return msg
    }

    fun clear() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }
}