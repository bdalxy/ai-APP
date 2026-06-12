package com.aicompanion.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * 记忆列表 RecyclerView 适配器。
 *
 * 每个 item 显示：
 * - 类型标签（不同颜色区分 episodic / semantic / user_fact）
 * - 内容预览（最多 2 行）
 * - 创建时间
 *
 * 支持长按删除回调。
 */
class MemoryAdapter(
    private val items: MutableList<MemoryItem>,
    private val onDeleteClick: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    /** 类型对应的标签颜色 */
    companion object {
        private val TYPE_COLORS = mapOf(
            "episodic" to Color.parseColor("#2196F3"),    // 蓝色
            "semantic" to Color.parseColor("#4CAF50"),    // 绿色
            "user_fact" to Color.parseColor("#FF9800")    // 橙色
        )
        private val TYPE_LABELS = mapOf(
            "episodic" to "情景记忆",
            "semantic" to "语义记忆",
            "user_fact" to "用户事实"
        )
        private const val DEFAULT_COLOR = 0xFF9E9E9E.toInt() // 灰色
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 类型标签 — 用不同颜色区分
        val typeLabel = TYPE_LABELS[item.type] ?: item.type
        val typeColor = TYPE_COLORS[item.type] ?: DEFAULT_COLOR
        holder.tvType.text = typeLabel
        holder.tvType.setBackgroundColor(typeColor)

        // 内容预览
        holder.tvContent.text = item.content

        // 创建时间 — 截取前 19 个字符（yyyy-MM-dd HH:mm:ss）
        holder.tvTime.text = if (item.createdAt.length >= 19) {
            item.createdAt.substring(0, 19)
        } else {
            item.createdAt
        }

        // 长按删除
        holder.itemView.setOnLongClickListener {
            onDeleteClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    /** 追加一批记忆到列表末尾（用于分页加载） */
    fun addItems(newItems: List<MemoryItem>) {
        val startPos = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(startPos, newItems.size)
    }

    /** 替换全部数据（使用 DiffUtil 优化刷新） */
    fun replaceItems(newItems: List<MemoryItem>) {
        val diffResult = DiffUtil.calculateDiff(DiffCallback(items, newItems))
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    /** 清空列表 */
    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    /** 移除指定项 */
    fun removeAt(position: Int) {
        if (position < 0 || position >= items.size) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    private class DiffCallback(
        private val oldList: List<MemoryItem>,
        private val newList: List<MemoryItem>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].rowid == newList[newPos].rowid
        }
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return old.content == new.content && old.type == new.type && old.createdAt == new.createdAt
        }
    }
}