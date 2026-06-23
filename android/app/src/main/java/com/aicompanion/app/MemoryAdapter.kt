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
 * 记忆列表 RecyclerView 适配器（支持分组标题 + 记忆条目）。
 *
 * 每个 item 显示：
 * - 分组标题（分类名 + 条数 + 颜色指示）
 * - 类型标签（不同颜色区分细粒度分类）
 * - 内容预览（最多 2 行）
 * - 创建时间
 *
 * 支持：点击查看详情、长按删除。
 */

/** 列表条目 — 分组标题或记忆项 */
sealed class MemoryListEntry {
    data class Header(
        val type: String,
        val label: String,
        val count: Int
    ) : MemoryListEntry()

    data class Item(val memory: MemoryItem) : MemoryListEntry()
}

class MemoryAdapter(
    private val context: Context,
    private val entries: MutableList<MemoryListEntry>,
    private val onDetailClick: (MemoryItem) -> Unit,
    private val onDeleteClick: (MemoryItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1

        /** 父类型 → 颜色资源 */
        private val PARENT_COLOR_RES = mapOf(
            "episodic" to R.color.memory_episodic,
            "semantic" to R.color.memory_semantic,
            "user_fact" to R.color.memory_user_fact,
            "emotional" to R.color.memory_emotional,
            "summary" to R.color.memory_summary,
        )
        private val DEFAULT_COLOR_RES = R.color.memory_default
    }

    /** 类型 → 本地化标签 resId */
    private val typeLabelMap: Map<String, Int> = mapOf(
        "episodic" to R.string.label_memory_type_episodic,
        "episodic_event" to R.string.label_memory_type_episodic_event,
        "episodic_experience" to R.string.label_memory_type_episodic_experience,
        "episodic_activity" to R.string.label_memory_type_episodic_activity,
        "semantic" to R.string.label_memory_type_semantic,
        "semantic_knowledge" to R.string.label_memory_type_semantic_knowledge,
        "semantic_opinion" to R.string.label_memory_type_semantic_opinion,
        "semantic_concept" to R.string.label_memory_type_semantic_concept,
        "user_fact" to R.string.label_memory_type_user_fact,
        "user_identity" to R.string.label_memory_type_user_identity,
        "user_preference" to R.string.label_memory_type_user_preference,
        "user_attribute" to R.string.label_memory_type_user_attribute,
        "user_relationship" to R.string.label_memory_type_user_relationship,
        "user_status" to R.string.label_memory_type_user_status,
        "emotional" to R.string.label_memory_type_emotional,
        "emotional_mood" to R.string.label_memory_type_emotional_mood,
        "emotional_sentiment" to R.string.label_memory_type_emotional_sentiment,
        "summary" to R.string.label_memory_type_summary,
    )

    inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorIndicator: View = view.findViewById(R.id.vColorIndicator)
        val tvSectionLabel: TextView = view.findViewById(R.id.tvSectionLabel)
        val tvSectionCount: TextView = view.findViewById(R.id.tvSectionCount)
    }

    inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun getItemViewType(position: Int): Int = when (entries[position]) {
        is MemoryListEntry.Header -> VIEW_TYPE_HEADER
        is MemoryListEntry.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_memory_section_header, parent, false)
                HeaderHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_memory, parent, false)
                ItemHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is MemoryListEntry.Header -> bindHeader(holder as HeaderHolder, entry)
            is MemoryListEntry.Item -> bindItem(holder as ItemHolder, entry)
        }
    }

    private fun bindHeader(holder: HeaderHolder, header: MemoryListEntry.Header) {
        holder.tvSectionLabel.text = header.label
        holder.tvSectionCount.text = context.getString(R.string.label_section_count_format, header.count)
        val colorRes = PARENT_COLOR_RES[header.type] ?: DEFAULT_COLOR_RES
        holder.colorIndicator.background.setTint(ContextCompat.getColor(context, colorRes))
    }

    private fun bindItem(holder: ItemHolder, item: MemoryListEntry.Item) {
        val memory = item.memory

        // 类型标签
        val typeLabel = getTypeLabel(memory.type)
        val typeColorRes = PARENT_COLOR_RES[memory.parentType] ?: DEFAULT_COLOR_RES
        holder.tvType.text = typeLabel
        holder.tvType.background.setTint(ContextCompat.getColor(context, typeColorRes))

        // 内容预览
        holder.tvContent.text = memory.content

        // 创建时间
        holder.tvTime.text = if (memory.createdAt.length >= 19) {
            memory.createdAt.substring(0, 19)
        } else {
            memory.createdAt
        }

        // 点击 → 详情
        holder.itemView.setOnClickListener {
            onDetailClick(memory)
        }

        // 长按 → 删除
        holder.itemView.setOnLongClickListener {
            onDeleteClick(memory)
            true
        }
    }

    override fun getItemCount(): Int = entries.size

    private fun getTypeLabel(type: String): String {
        val resId = typeLabelMap[type]
        return if (resId != null) context.getString(resId) else type
    }

    // ── 数据操作 ──

    /** 替换全部数据（使用 DiffUtil） */
    fun replaceEntries(newEntries: List<MemoryListEntry>) {
        val diffResult = DiffUtil.calculateDiff(EntryDiffCallback(entries.toList(), newEntries))
        entries.clear()
        entries.addAll(newEntries)
        diffResult.dispatchUpdatesTo(this)
    }

    /** 清空 */
    fun clear() {
        val count = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, count)
    }

    /** 移除指定位置的条目 */
    fun removeAt(position: Int) {
        if (position < 0 || position >= entries.size) return
        entries.removeAt(position)
        notifyItemRemoved(position)
    }

    /** 查找 MemoryItem 在列表中的位置 */
    fun indexOfItem(rowid: Int): Int {
        return entries.indexOfFirst {
            it is MemoryListEntry.Item && it.memory.rowid == rowid
        }
    }

    private class EntryDiffCallback(
        private val oldList: List<MemoryListEntry>,
        private val newList: List<MemoryListEntry>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            if (old is MemoryListEntry.Header && new is MemoryListEntry.Header) {
                return old.type == new.type
            }
            if (old is MemoryListEntry.Item && new is MemoryListEntry.Item) {
                return old.memory.rowid == new.memory.rowid
            }
            return false
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            if (old is MemoryListEntry.Header && new is MemoryListEntry.Header) {
                return old.type == new.type && old.label == new.label && old.count == new.count
            }
            if (old is MemoryListEntry.Item && new is MemoryListEntry.Item) {
                val o = old.memory
                val n = new.memory
                return o.content == n.content && o.type == n.type && o.createdAt == n.createdAt
            }
            return false
        }
    }
}