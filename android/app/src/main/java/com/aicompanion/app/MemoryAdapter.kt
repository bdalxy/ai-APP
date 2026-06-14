package com.aicompanion.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private val context: Context,
    private val items: MutableList<MemoryItem>,
    private val onDeleteClick: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    /** 类型对应的标签颜色（使用资源引用，支持深色模式） */
    companion object {
        private val TYPE_COLOR_RES = mapOf(
            "episodic" to R.color.memory_episodic,   // 蓝色
            "semantic" to R.color.memory_semantic,   // 绿色
            "user_fact" to R.color.memory_user_fact, // 橙色
        )
        private val DEFAULT_COLOR_RES = R.color.memory_default  // 灰色
    }

    /** 根据类型获取本地化标签 */
    private val typeLabelMap: Map<String, Int> by lazy {
        mapOf(
            "episodic" to R.string.label_memory_type_episodic,
            "semantic" to R.string.label_memory_type_semantic,
            "user_fact" to R.string.label_memory_type_user_fact
        )
    }

    /** 获取类型的本地化标签文本 */
    private fun getTypeLabel(type: String): String {
        val resId = typeLabelMap[type]
        return if (resId != null) context.getString(resId) else type
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

        // 类型标签 — 用不同颜色区分（支持深色模式）
        val typeLabel = getTypeLabel(item.type)
        val typeColorRes = TYPE_COLOR_RES[item.type] ?: DEFAULT_COLOR_RES
        holder.tvType.text = typeLabel
        holder.tvType.setBackgroundColor(ContextCompat.getColor(context, typeColorRes))

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