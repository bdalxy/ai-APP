package com.aicompanion.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 插件列表 RecyclerView 适配器。
 * 使用 ListAdapter + DiffUtil 实现高效刷新。
 */
class PluginAdapter(
    private val onToggle: (PluginItem, Boolean) -> Unit,
    private val onDetailClick: (PluginItem) -> Unit
) : ListAdapter<PluginItem, PluginAdapter.ViewHolder>(DiffCallback) {

    private var allDisabled: Boolean = false

    fun setAllDisabled(disabled: Boolean) {
        allDisabled = disabled
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context = itemView.context
        private val nameTv: TextView = itemView.findViewById(R.id.tvPluginName)
        private val descTv: TextView = itemView.findViewById(R.id.tvPluginDesc)
        private val categoryLabel: TextView = itemView.findViewById(R.id.tvCategoryLabel)
        private val statusLabel: TextView = itemView.findViewById(R.id.tvStatusLabel)
        private val callCountTv: TextView = itemView.findViewById(R.id.tvCallCount)
        private val switchView: View = itemView.findViewById(R.id.switchPlugin)

        @SuppressLint("SetTextI18n")
        fun bind(plugin: PluginItem) {
            nameTv.text = plugin.name
            descTv.text = plugin.description
            categoryLabel.text = plugin.categoryLabel
            statusLabel.text = plugin.statusLabel

            // 状态颜色
            val statusColor = if (plugin.enabled) R.color.elysian_purple else R.color.text_tertiary
            statusLabel.setTextColor(ContextCompat.getColor(context, statusColor))

            // 调用次数
            callCountTv.text = "调用 ${plugin.callCount} 次"

            // 开关点击
            switchView.setOnClickListener {
                onToggle(plugin, !plugin.enabled)
            }

            // 整卡点击查看详情
            itemView.setOnClickListener {
                onDetailClick(plugin)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<PluginItem>() {
        override fun areItemsTheSame(oldItem: PluginItem, newItem: PluginItem): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: PluginItem, newItem: PluginItem): Boolean =
            oldItem == newItem
    }
}