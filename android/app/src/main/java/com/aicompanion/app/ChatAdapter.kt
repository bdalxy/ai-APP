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
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        /** 消息行容器（用于设置左右对齐） */
        val layoutMessageRow: View = view.findViewById(R.id.layoutMessageRow)
        /** 文字气泡 */
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        /** 打字指示器容器 */
        val layoutTyping: View = view.findViewById(R.id.layoutTyping)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        when {
            // 打字指示器：暗紫气泡 + 三点动画，左对齐
            message.isTyping -> {
                holder.tvContent.visibility = View.GONE
                holder.layoutTyping.visibility = View.VISIBLE
                // 左对齐
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
            }
            // 用户消息：右对齐，紫色渐变气泡，白色文字
            message.isUser -> {
                holder.tvContent.visibility = View.VISIBLE
                holder.layoutTyping.visibility = View.GONE
                holder.tvContent.text = message.content
                holder.tvContent.setBackgroundResource(R.drawable.bg_bubble_user)
                holder.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_user_text))
                // 右对齐
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.END
            }
            // AI 回复：左对齐，粉色气泡，深色文字
            else -> {
                holder.tvContent.visibility = View.VISIBLE
                holder.layoutTyping.visibility = View.GONE
                holder.tvContent.text = message.content
                holder.tvContent.setBackgroundResource(R.drawable.bg_bubble_ai)
                holder.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_ai_text))
                // 左对齐
                (holder.layoutMessageRow.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
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