package com.aicompanion.app

import android.animation.ValueAnimator
import android.graphics.Color
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.speech.VoicePlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天消息 RecyclerView 适配器（基于 ListAdapter + DiffUtil）。
 * 使用 DiffUtil.ItemCallback 进行高效增量更新，替代全量 notifyDataSetChanged()。
 *
 * 支持 AI 消息（左对齐头像+气泡）、用户消息（右对齐气泡+状态）、打字指示器。
 * 支持连续消息合并：同组消息只显示首条头像/昵称，末条时间戳。
 *
 * 注意：由于流式输出需要频繁更新单条消息，Adapter 内部维护一份 localMessages
 * 作为本地缓存，确保 submitList() 异步期间数据一致性。所有读操作（getItemViewType、
 * onBindViewHolder、getItemCount 等）都基于 localMessages。
 */
class ChatAdapter(
    private val onMessageLongClick: ((Message, Int) -> Unit)? = null,
    /** 语音播放回调：(voiceFilePath, play) -> 播放/暂停 */
    private val onVoiceClick: ((String, Boolean) -> Unit)? = null,
    /** 消息被裁剪回调：当消息数量超过上限，旧消息被移除时触发 */
    private val onMessagesTrimmed: (() -> Unit)? = null,
    /** 数据变更回调：添加/删除/替换/清空消息时触发 */
    private val onDataChanged: (() -> Unit)? = null
) : ListAdapter<Message, ChatAdapter.BaseViewHolder>(MessageDiffCallback) {

    init {
        setHasStableIds(true)
    }

    // ======================== DiffUtil.ItemCallback ========================

    /**
     * Message 的 DiffUtil 比较回调。
     * 基于消息 id 判断是否为同一项，基于内容哈希判断内容是否变化。
     * 作为 companion object 提供，供 ListAdapter 构造函数使用。
     */
    companion object {
        private const val VIEW_TYPE_TYPING = 0
        private const val VIEW_TYPE_AI = 1
        private const val VIEW_TYPE_USER = 2
        /** 语音消息 viewType */
        private const val VIEW_TYPE_AI_VOICE = 3
        private const val VIEW_TYPE_USER_VOICE = 4
        private val MAX_MESSAGES = AppConfig.MAX_MESSAGES
        private const val TAG = "ChatAdapter"

        private val timeFormat = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        private val dateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        }

        /** 格式化语音时长（毫秒 -> MM:SS） */
        fun formatVoiceDuration(durationMs: Long): String {
            if (durationMs <= 0) return "0:00"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    /**
     * DiffUtil.ItemCallback 实现：基于 Message.id 判断是否为同一项，
     * 基于内容相关字段的哈希判断内容是否变化。
     */
    object MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.content == newItem.content &&
                oldItem.status == newItem.status &&
                oldItem.isGroupStart == newItem.isGroupStart &&
                oldItem.isGroupEnd == newItem.isGroupEnd &&
                oldItem.msgType == newItem.msgType &&
                oldItem.voiceFilePath == newItem.voiceFilePath &&
                oldItem.voiceDurationMs == newItem.voiceDurationMs &&
                oldItem.voicePlayed == newItem.voicePlayed &&
                oldItem.isEdited == newItem.isEdited
        }

        override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
            // 返回变更内容用于局部刷新，当前返回 null 触发完整 onBindViewHolder
            return null
        }
    }

    // ======================== 本地消息缓存 ========================

    /**
     * 本地消息列表缓存。
     * ListAdapter 的 submitList() 是异步的（DiffUtil 在后台线程计算），
     * 流式输出场景需要频繁读写最新数据，因此维护本地缓存作为数据源。
     * 所有公开的读方法（getItemViewType、onBindViewHolder、getItemCount 等）
     * 都基于此列表，确保数据一致性。
     */
    private val localMessages = mutableListOf<Message>()

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
        val tvEditedTag: TextView = view.findViewById(R.id.tvEditedTag)

        override fun bind(message: Message) {
            tvContent.text = message.content
        }
    }

    /** 打字指示器 ViewHolder */
    class TypingViewHolder(view: View) : BaseViewHolder(view) {
        override fun bind(message: Message) { /* 无动态内容 */ }
    }

    /** AI 语音消息 ViewHolder */
    class VoiceAiViewHolder(view: View) : BaseViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val ivPlayPause: ImageView = view.findViewById(R.id.ivPlayPause)
        val ivWaveform: ImageView = view.findViewById(R.id.ivWaveform)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val vUnreadDot: View = view.findViewById(R.id.vUnreadDot)

        override fun bind(message: Message) {
            tvDuration.text = formatVoiceDuration(message.voiceDurationMs)
        }
    }

    /** 用户语音消息 ViewHolder */
    class VoiceUserViewHolder(view: View) : BaseViewHolder(view) {
        val ivPlayPause: ImageView = view.findViewById(R.id.ivPlayPause)
        val ivWaveform: ImageView = view.findViewById(R.id.ivWaveform)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val vUnreadDot: View = view.findViewById(R.id.vUnreadDot)

        override fun bind(message: Message) {
            tvDuration.text = formatVoiceDuration(message.voiceDurationMs)
        }
    }

    // ======================== 播放状态 ========================

    /** 当前正在播放的语音消息 position（用于 UI 更新），-1 表示无 */
    @Volatile
    var playingPosition: Int = -1

    // ======================== 搜索高亮 ========================

    /** 搜索关键词（用于高亮显示），为空时不进行高亮。设置时自动刷新列表 */
    @Volatile
    var highlightKeyword: String = ""
        set(value) {
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    /** 判断指定消息是否匹配搜索关键词 */
    fun isMessageMatch(message: Message, keyword: String): Boolean {
        if (keyword.isBlank() || message.isTyping) return false
        return message.content.lowercase(Locale.getDefault())
            .contains(keyword.lowercase(Locale.getDefault()))
    }

    /**
     * 对文本内容应用搜索关键词高亮。
     * 使用淡樱粉色背景 + 深色文字，不区分大小写。
     */
    private fun applyHighlight(text: String): CharSequence {
        if (highlightKeyword.isBlank()) return text
        val keywordLower = highlightKeyword.lowercase(Locale.getDefault())
        val textLower = text.lowercase(Locale.getDefault())
        val spannable = SpannableString(text)

        var startIndex = 0
        while (startIndex < textLower.length) {
            val matchIndex = textLower.indexOf(keywordLower, startIndex)
            if (matchIndex == -1) break
            // 淡樱粉色背景 + 深色文字
            spannable.setSpan(
                BackgroundColorSpan(Color.parseColor("#FFD0D9")),
                matchIndex,
                matchIndex + highlightKeyword.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#2D1B3A")),
                matchIndex,
                matchIndex + highlightKeyword.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIndex = matchIndex + highlightKeyword.length
        }
        return spannable
    }

    // ======================== ListAdapter 核心方法 ========================

    override fun getItemViewType(position: Int): Int {
        val msg = localMessages[position]
        return when {
            msg.isTyping -> VIEW_TYPE_TYPING
            msg.msgType == Message.MsgType.VOICE && msg.isUser -> VIEW_TYPE_USER_VOICE
            msg.msgType == Message.MsgType.VOICE && !msg.isUser -> VIEW_TYPE_AI_VOICE
            msg.isUser -> VIEW_TYPE_USER
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
            VIEW_TYPE_AI_VOICE -> {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message_voice_ai, parent, false)
                VoiceAiViewHolder(view)
            }
            VIEW_TYPE_USER_VOICE -> {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message_voice_self, parent, false)
                VoiceUserViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val message = localMessages[position]
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
            is VoiceAiViewHolder -> bindVoiceAiMessage(holder, message, position)
            is VoiceUserViewHolder -> bindVoiceUserMessage(holder, message, position)
        }
    }

    /** 绑定 AI 消息 */
    private fun bindAiMessage(holder: AiViewHolder, message: Message, position: Int) {
        val context = holder.itemView.context

        // 消息内容（应用搜索高亮）
        holder.tvContent.text = if (highlightKeyword.isNotBlank()) {
            applyHighlight(message.content)
        } else {
            message.content
        }

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
            !message.isGroupStart && message.isGroupEnd -> R.drawable.bg_bubble_ai_bottom
            !message.isGroupStart -> R.drawable.bg_bubble_ai_middle
            message.isGroupStart && !message.isGroupEnd -> R.drawable.bg_bubble_ai_top
            else -> R.drawable.bg_bubble_ai
        }
        holder.tvContent.setBackgroundResource(bubbleRes)
    }

    /** 绑定用户消息 */
    private fun bindUserMessage(holder: UserViewHolder, message: Message, position: Int) {
        val context = holder.itemView.context

        // 消息内容（应用搜索高亮）
        holder.tvContent.text = if (highlightKeyword.isNotBlank()) {
            applyHighlight(message.content)
        } else {
            message.content
        }

        // 编辑标记
        holder.tvEditedTag.visibility = if (message.isEdited) View.VISIBLE else View.GONE

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

    /** 绑定 AI 语音消息 */
    private fun bindVoiceAiMessage(holder: VoiceAiViewHolder, message: Message, position: Int) {
        val isPlaying = playingPosition == position

        // 头像（始终显示）
        holder.ivAvatar.visibility = View.VISIBLE

        // 播放/暂停按钮图标
        holder.ivPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // 波形动画：播放中时脉冲缩放
        animateWaveform(holder.ivWaveform, isPlaying)

        // 未读红点：未播放时显示
        holder.vUnreadDot.visibility = if (!message.voicePlayed && !isPlaying) View.VISIBLE else View.GONE

        // 点击播放按钮
        holder.ivPlayPause.setOnClickListener {
            onVoiceClick?.invoke(message.voiceFilePath, !isPlaying)
        }

        // 设置时长
        holder.tvDuration.text = formatVoiceDuration(message.voiceDurationMs)
    }

    /** 绑定用户语音消息 */
    private fun bindVoiceUserMessage(holder: VoiceUserViewHolder, message: Message, position: Int) {
        val isPlaying = playingPosition == position

        // 播放/暂停按钮图标
        holder.ivPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // 波形动画：播放中时脉冲缩放
        animateWaveform(holder.ivWaveform, isPlaying)

        // 未读红点：未播放时显示
        holder.vUnreadDot.visibility = if (!message.voicePlayed && !isPlaying) View.VISIBLE else View.GONE

        // 点击播放按钮
        holder.ivPlayPause.setOnClickListener {
            onVoiceClick?.invoke(message.voiceFilePath, !isPlaying)
        }

        // 设置时长
        holder.tvDuration.text = formatVoiceDuration(message.voiceDurationMs)
    }

    /** 波形动画：播放中时脉冲缩放 */
    private fun animateWaveform(imageView: ImageView, isPlaying: Boolean) {
        imageView.clearAnimation()
        val tagKey = R.id.animation_tag_key
        if (isPlaying) {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val scale = 1f + (animation.animatedValue as Float) * 0.15f
                    imageView.scaleX = scale
                    imageView.scaleY = scale
                }
            }
            animator.start()
            imageView.setTag(tagKey, animator)
        } else {
            imageView.scaleX = 1f
            imageView.scaleY = 1f
            val existingAnimator = imageView.getTag(tagKey) as? ValueAnimator
            existingAnimator?.cancel()
            imageView.setTag(tagKey, null)
        }
    }

    /** 返回本地缓存列表大小，确保 submitList() 异步期间数据一致性 */
    override fun getItemCount(): Int = localMessages.size

    override fun getItemId(position: Int): Long = localMessages[position].id.hashCode().toLong()

    // ======================== 消息管理 API ========================

    /**
     * 添加消息，自动处理连续消息合并逻辑。
     * 如果上一条消息的发送者相同，则合并为一组。
     *
     * 使用 ListAdapter 的 submitList() 提交数据，DiffUtil 自动计算增量更新。
     * 由于 submitList() 是异步的（DiffUtil 在后台线程计算），本方法维护 localMessages
     * 作为本地缓存，确保调用者可以立即获取正确的消息数量。
     *
     * @param message 要添加的消息
     * @return 新消息在列表中的索引位置
     */
    fun addMessage(message: Message): Int {
        if (localMessages.size >= MAX_MESSAGES) {
            Log.w(TAG, "消息数量达到上限($MAX_MESSAGES)，移除最早消息")
            localMessages.removeAt(0)
            onMessagesTrimmed?.invoke()
        }

        // 连续消息合并：检查上一条消息
        val mergedMessage = if (localMessages.isNotEmpty()) {
            val lastMsg = localMessages.last()
            if (lastMsg.isUser == message.isUser && !lastMsg.isTyping && !message.isTyping) {
                // 同一发送者，合并到同一组
                val groupId = lastMsg.groupId
                // 更新上一条消息为非组尾
                localMessages[localMessages.size - 1] = lastMsg.copy(isGroupEnd = false)

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

        localMessages.add(mergedMessage)
        val newPosition = localMessages.size - 1
        submitList(localMessages.toList())
        onDataChanged?.invoke()
        return newPosition
    }

    /** 获取当前消息列表的只读副本 */
    fun getMessages(): List<Message> = localMessages.toList()

    /** 替换全部消息（兼容旧 API） */
    fun replaceAll(newMessages: List<Message>) {
        replaceMessages(newMessages)
    }

    /**
     * 移除指定位置的消息（仅限打字指示器）。
     * @return 被移除的消息，如果不是打字指示器则返回 null
     */
    fun removeTypingAt(position: Int): Message? {
        if (position < 0 || position >= localMessages.size) return null
        val msg = localMessages[position]
        if (!msg.isTyping) return null
        localMessages.removeAt(position)
        submitList(localMessages.toList())
        onDataChanged?.invoke()
        return msg
    }

    /**
     * 更新指定位置的消息。
     * 使用 submitList() 提交更新后的列表，DiffUtil 自动计算增量更新。
     */
    fun updateMessage(position: Int, message: Message) {
        if (position < 0 || position >= localMessages.size) return
        localMessages[position] = message
        submitList(localMessages.toList())
    }

    /** 清空所有消息 */
    fun clear() {
        localMessages.clear()
        submitList(emptyList())
        onDataChanged?.invoke()
    }

    /**
     * 替换全部消息。
     * 使用 submitList() 提交，ListAdapter 内部通过 DiffUtil 自动计算增量更新。
     */
    fun replaceMessages(newMessages: List<Message>) {
        localMessages.clear()
        localMessages.addAll(newMessages)
        submitList(localMessages.toList())
        onDataChanged?.invoke()
    }

    // ======================== 工具方法 ========================

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> timeFormat.get()!!.format(Date(timestamp))
            diff < 604800_000 -> {
                val days = diff / 86400_000
                "${days}天前 ${timeFormat.get()!!.format(Date(timestamp))}"
            }
            else -> dateFormat.get()!!.format(Date(timestamp))
        }
    }
}