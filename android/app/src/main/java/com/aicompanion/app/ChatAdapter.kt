package com.aicompanion.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天消息 RecyclerView 适配器。
 * 支持 AI 消息（左对齐头像+气泡）、用户消息（右对齐气泡+状态）、打字指示器。
 * 支持连续消息合并：同组消息只显示首条头像/昵称，末条时间戳。
 */
class ChatAdapter(
    private val messages: MutableList<Message>,
    private val onMessageLongClick: ((Message, Int) -> Unit)? = null
) : RecyclerView.Adapter<ChatAdapter.BaseViewHolder>() {

    companion object {
        private const val VIEW_TYPE_TYPING = 0
        private const val VIEW_TYPE_AI = 1
        private const val VIEW_TYPE_USER = 2
        private const val MAX_MESSAGES = 500

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    // ======================== ViewHolder ========================

    abstract class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(message: Message)
    }

    /** AI 消息 ViewHolder */
    class AiViewHolder(view: View) : BaseViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)

        override fun bind(message: Message) {
            tvContent.text = message.content
        }
    }

    /** 用户消息 ViewHolder */
    class UserViewHolder(view: View) : BaseViewHolder(view) {
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
        val layoutStatus: View = view.findViewById(R.id.layoutStatus)

        override fun bind(message: Message) {
            tvContent.text = message.content
        }
    }

    /** 打字指示器 ViewHolder */
    class TypingViewHolder(view: View) : BaseViewHolder(view) {
        override fun bind(message: Message) { /* 无动态内容 */ }
    }

    // ======================== Adapter 方法 ========================

    override fun getItemViewType(position: Int): Int {
        return when {
            messages[position].isTyping -> VIEW_TYPE_TYPING
            messages[position].isUser -> VIEW_TYPE_USER
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val context = parent.context
        return when (viewType) {
            VIEW_TYPE_TYPING -> {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message, parent, false)
                val holder = TypingViewHolder(view)
                val tvContent = view.findViewById<TextView>(R.id.tvContent)
                tvContent.visibility = View.GONE
                view.findViewById<View>(R.id.layoutTyping).visibility = View.VISIBLE
                (view.findViewById<View>(R.id.layoutMessageRow).layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
                holder
            }
            VIEW_TYPE_AI -> {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message_ai, parent, false)
                AiViewHolder(view)
            }
            VIEW_TYPE_USER -> {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message_self, parent, false)
                UserViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)

        // 长按菜单
        holder.itemView.setOnLongClickListener {
            onMessageLongClick?.invoke(message, position)
            true
        }

        when (holder) {
            is AiViewHolder -> bindAiMessage(holder, message, position)
            is UserViewHolder -> bindUserMessage(holder, message, position)
            is TypingViewHolder -> { /* 无动态内容 */ }
        }
    }

    /** 绑定 AI 消息 */
    private fun bindAiMessage(holder: AiViewHolder, message: Message, position: Int) {
        val context = holder.itemView.context

        // 头像可见性：组首显示
        holder.ivAvatar.visibility = if (message.isGroupStart) View.VISIBLE else View.INVISIBLE

        // 昵称可见性：组首显示
        holder.tvSenderName.visibility = if (message.isGroupStart && message.senderName.isNotEmpty()) {
            holder.tvSenderName.text = message.senderName
            View.VISIBLE
        } else {
            View.GONE
        }

        // 时间戳可见性：组尾显示
        holder.tvTimestamp.visibility = if (message.isGroupEnd) {
            holder.tvTimestamp.text = formatTime(message.timestamp)
            View.VISIBLE
        } else {
            View.GONE
        }

        // 气泡样式：根据组位置调整圆角
        val bubbleRes = when {
            !message.isGroupStart && message.isGroupEnd -> R.drawable.bg_bubble_ai_middle
            !message.isGroupStart -> R.drawable.bg_bubble_ai_middle
            message.isGroupStart && !message.isGroupEnd -> R.drawable.bg_bubble_ai_top
            else -> R.drawable.bg_bubble_ai
        }
        holder.tvContent.setBackgroundResource(bubbleRes)
    }

    /** 绑定用户消息 */
    private fun bindUserMessage(holder: UserViewHolder, message: Message, position: Int) {
        val context = holder.itemView.context

        // 时间戳和状态：组尾显示
        if (message.isGroupEnd) {
            holder.layoutStatus.visibility = View.VISIBLE
            holder.tvTimestamp.text = formatTime(message.timestamp)

            // 状态图标
            holder.ivStatus.visibility = View.VISIBLE
            when (message.status) {
                Message.MessageStatus.SENDING -> {
                    holder.ivStatus.setImageResource(R.drawable.ic_clock)
                    holder.ivStatus.setColorFilter(ContextCompat.getColor(context, R.color.text_tertiary))
                }
                Message.MessageStatus.SENT -> {
                    holder.ivStatus.setImageResource(R.drawable.ic_check)
                    holder.ivStatus.setColorFilter(ContextCompat.getColor(context, R.color.text_tertiary))
                }
                Message.MessageStatus.DELIVERED -> {
                    holder.ivStatus.setImageResource(R.drawable.ic_check_double)
                    holder.ivStatus.setColorFilter(ContextCompat.getColor(context, R.color.text_tertiary))
                }
                Message.MessageStatus.READ -> {
                    holder.ivStatus.setImageResource(R.drawable.ic_check_double)
                    holder.ivStatus.setColorFilter(ContextCompat.getColor(context, R.color.accent_green))
                }
                Message.MessageStatus.ERROR -> {
                    holder.ivStatus.setImageResource(R.drawable.ic_error)
                    holder.ivStatus.setColorFilter(ContextCompat.getColor(context, R.color.accent_red))
                }
            }
        } else {
            holder.layoutStatus.visibility = View.GONE
        }

        // 气泡样式：根据组位置调整圆角
        val bubbleRes = when {
            !message.isGroupStart && message.isGroupEnd -> R.drawable.bg_bubble_user_bottom
            !message.isGroupStart -> R.drawable.bg_bubble_user_middle
            message.isGroupStart && !message.isGroupEnd -> R.drawable.bg_bubble_user_top
            else -> R.drawable.bg_bubble_user
        }
        holder.tvContent.setBackgroundResource(bubbleRes)
    }

    override fun getItemCount(): Int = messages.size

    // ======================== 消息管理 ========================

    /**
     * 添加消息，自动处理连续消息合并逻辑。
     * 如果上一条消息的发送者相同，则合并为一组。
     */
    fun addMessage(message: Message) {
        if (messages.size >= MAX_MESSAGES) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }

        // 连续消息合并：检查上一条消息
        val mergedMessage = if (messages.isNotEmpty()) {
            val lastMsg = messages.last()
            if (lastMsg.isUser == message.isUser && !lastMsg.isTyping && !message.isTyping) {
                // 同一发送者，合并到同一组
                val groupId = lastMsg.groupId
                // 更新上一条消息为非组尾
                val updatedLast = lastMsg.copy(isGroupEnd = false)
                messages[messages.size - 1] = updatedLast
                notifyItemChanged(messages.size - 1)

                message.copy(
                    groupId = groupId,
                    isGroupStart = false,
                    isGroupEnd = true
                )
            } else {
                message
            }
        } else {
            message
        }

        messages.add(mergedMessage)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessages(): List<Message> = messages.toList()

    fun replaceAll(newMessages: List<Message>) {
        replaceMessages(newMessages)
    }

    fun removeTypingAt(position: Int): Message? {
        if (position < 0 || position >= messages.size) return null
        val msg = messages[position]
        if (!msg.isTyping) return null
        messages.removeAt(position)
        notifyItemRemoved(position)
        return msg
    }

    fun updateMessage(position: Int, message: Message) {
        if (position < 0 || position >= messages.size) return
        messages[position] = message
        notifyItemChanged(position)
    }

    fun clear() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun replaceMessages(newMessages: List<Message>) {
        val diffResult = DiffUtil.calculateDiff(MessageDiffCallback(messages, newMessages))
        messages.clear()
        messages.addAll(newMessages)
        diffResult.dispatchUpdatesTo(this)
    }

    // ======================== 工具方法 ========================

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> timeFormat.format(Date(timestamp))
            diff < 604800_000 -> {
                val days = diff / 86400_000
                "${days}天前 ${timeFormat.format(Date(timestamp))}"
            }
            else -> dateFormat.format(Date(timestamp))
        }
    }

    // ======================== DiffUtil ========================

    private class MessageDiffCallback(
        private val oldList: List<Message>,
        private val newList: List<Message>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].id == newList[newPos].id
        }
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return old.content == new.content &&
                old.status == new.status &&
                old.isGroupStart == new.isGroupStart &&
                old.isGroupEnd == new.isGroupEnd
        }
    }
}