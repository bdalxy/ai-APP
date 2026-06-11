package com.aicompanion.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 聊天消息 RecyclerView 适配器。
 * 根据消息类型（用户/AI/打字指示器）显示不同的气泡样式。
 */
class ChatAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    companion object {
        /** 打字指示器的占位文本 */
        const val TYPING_TEXT = "对方正在输入..."
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]

        when {
            // 打字指示器：灰色斜体 "对方正在输入..."
            message.isTyping -> {
                holder.tvSender.visibility = View.VISIBLE
                holder.tvSender.text = "小美"
                holder.tvContent.text = TYPING_TEXT
                holder.tvContent.setBackgroundColor(Color.parseColor("#E0E0E0"))
                holder.tvContent.setTextColor(Color.parseColor("#888888"))
                (holder.tvContent.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.setMargins(0, 0, 80, 0)
                }
            }
            // 用户消息：右对齐，蓝色背景
            message.isUser -> {
                holder.tvSender.visibility = View.GONE
                holder.tvContent.text = message.content
                holder.tvContent.setBackgroundColor(Color.parseColor("#2196F3"))
                holder.tvContent.setTextColor(Color.WHITE)
                (holder.tvContent.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.setMargins(80, 0, 0, 0)
                }
            }
            // AI 回复：左对齐，灰色背景
            else -> {
                holder.tvSender.visibility = View.VISIBLE
                holder.tvSender.text = "小美"
                holder.tvContent.text = message.content
                holder.tvContent.setBackgroundColor(Color.parseColor("#E0E0E0"))
                holder.tvContent.setTextColor(Color.BLACK)
                (holder.tvContent.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.setMargins(0, 0, 80, 0)
                }
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