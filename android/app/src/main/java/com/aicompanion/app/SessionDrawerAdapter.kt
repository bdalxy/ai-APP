package com.aicompanion.app

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话抽屉列表适配器。
 *
 * 显示会话名称、最后消息预览、时间戳，支持点击切换和滑动删除。
 */
class SessionDrawerAdapter(
    private val sessions: List<ConversationSession>,
    private val currentSessionId: String,
    private val onSelect: (ConversationSession) -> Unit,
    private val onDelete: (ConversationSession) -> Unit
) : RecyclerView.Adapter<SessionDrawerAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_drawer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        val ctx = holder.itemView.context
        val isCurrent = session.id == currentSessionId

        holder.tvName.text = session.name.ifEmpty { ctx.getString(R.string.session_unnamed) }
        holder.tvPreview.text = session.lastMessage.ifEmpty { ctx.getString(R.string.session_no_messages) }
        holder.tvTime.text = formatTime(session.updatedAt)
        holder.tvCount.text = "${session.messageCount}"

        // 当前会话高亮
        val nameColor = if (isCurrent) R.color.primary else R.color.text_primary
        holder.tvName.setTextColor(ContextCompat.getColor(ctx, nameColor))
        holder.tvName.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        holder.itemView.setOnClickListener { onSelect(session) }
        holder.itemView.setOnLongClickListener {
            onDelete(session)
            true
        }
    }

    override fun getItemCount(): Int = sessions.size

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return timeFormat.format(Date(timestamp))
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvSessionName)
        val tvPreview: TextView = view.findViewById(R.id.tvSessionPreview)
        val tvTime: TextView = view.findViewById(R.id.tvSessionTime)
        val tvCount: TextView = view.findViewById(R.id.tvSessionCount)
    }
}