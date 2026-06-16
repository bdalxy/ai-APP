package com.aicompanion.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.CharacterData
import com.aicompanion.app.R

/**
 * 角色列表公共 RecyclerView 适配器。
 * 供 CharacterSelectActivity（选择模式）和 CharacterManageActivity（管理模式）共用。
 *
 * @param showDelete 是否显示删除按钮（选择模式不显示，管理模式显示）
 * @param onSelect 选中角色的回调
 * @param onEdit 编辑角色的回调
 * @param onDelete 删除角色的回调（仅管理模式需要）
 */
class CharacterListAdapter(
    private val showDelete: Boolean = false,
    private val onSelect: (CharacterData) -> Unit,
    private val onEdit: (CharacterData) -> Unit,
    private val onDelete: ((CharacterData) -> Unit)? = null
) : RecyclerView.Adapter<CharacterListAdapter.ViewHolder>() {

    private var characters: List<CharacterData> = emptyList()
    private var currentId: String = ""

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivCharacterAvatar)
        val tvName: TextView = view.findViewById(R.id.tvCharacterName)
        val tvPreview: TextView = view.findViewById(R.id.tvCharacterPreview)
        val ivCurrent: View = view.findViewById(R.id.ivCurrentDot)
        val btnEdit: TextView = view.findViewById(R.id.btnEditCharacter)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteCharacter)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_character_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val char = characters[position]
        holder.tvName.text = char.name
        holder.tvPreview.text = "${char.personality.take(30)}..."

        // 当前选中角色显示绿点指示
        holder.ivCurrent.visibility = if (char.id == currentId) View.VISIBLE else View.GONE

        // 删除按钮可见性控制
        holder.btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE

        // 点击事件
        holder.itemView.setOnClickListener { onSelect(char) }
        holder.btnEdit.setOnClickListener { onEdit(char) }
        if (showDelete) {
            holder.btnDelete.setOnClickListener { onDelete?.invoke(char) }
        }
    }

    override fun getItemCount() = characters.size

    /** 使用 DiffUtil 替换列表数据，实现高效局部刷新 */
    fun replaceItems(newItems: List<CharacterData>, newCurrentId: String) {
        val diffResult = DiffUtil.calculateDiff(
            DiffCallback(characters, newItems)
        )
        characters = newItems
        currentId = newCurrentId
        diffResult.dispatchUpdatesTo(this)
    }

    /** DiffUtil 回调：比较两个角色列表的差异 */
    private class DiffCallback(
        private val oldList: List<CharacterData>,
        private val newList: List<CharacterData>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].id == newList[newPos].id
        }
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            // 比较所有可能变化的字段（avatarUri 暂不可变，故不比较）
            return old.name == new.name &&
                old.personality == new.personality &&
                old.speakingStyle == new.speakingStyle &&
                old.backstory == new.backstory &&
                old.greeting == new.greeting
        }
    }
}