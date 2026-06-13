package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.databinding.ActivityCharacterManageBinding

/**
 * 角色卡管理列表页。
 * 展示所有角色，支持切换当前角色、编辑和删除。
 */
class CharacterManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterManageBinding
    private lateinit var adapter: CharacterListAdapter
    private var characters: List<CharacterData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 适配刘海屏/挖孔屏/状态栏
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.characterManageRoot)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 新建角色按钮
        binding.btnNewCharacter.setOnClickListener {
            startActivity(Intent(this, CharacterEditActivity::class.java))
        }

        // 初始化 RecyclerView
        binding.rvCharacters.layoutManager = LinearLayoutManager(this)

        loadCharacters()
    }

    override fun onResume() {
        super.onResume()
        // 每次返回页面时刷新列表
        loadCharacters()
    }

    /** 从存储加载角色列表并刷新适配器。 */
    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id

        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                onSelect = { char ->
                    CharacterStorage.setCurrent(this, char.id)
                    setResult(RESULT_OK)
                    finish()
                },
                onEdit = { char ->
                    val intent = Intent(this, CharacterEditActivity::class.java)
                    intent.putExtra("character_id", char.id)
                    startActivity(intent)
                },
                onDelete = { char ->
                    if (char.isDefault) {
                        Toast.makeText(this, "默认角色不可删除", Toast.LENGTH_SHORT).show()
                        return@CharacterListAdapter
                    }
                    CharacterStorage.delete(this, char.id)
                    loadCharacters()
                }
            )
            binding.rvCharacters.adapter = adapter
        }
        adapter.replaceItems(characters, currentId)
    }

    /**
     * 角色列表 RecyclerView 适配器。
     * 每行展示角色头像、名称、简介预览，以及当前选中指示和操作按钮。
     */
    private class CharacterListAdapter(
        private val onSelect: (CharacterData) -> Unit,
        private val onEdit: (CharacterData) -> Unit,
        private val onDelete: (CharacterData) -> Unit
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

            // 当前选中角色显示绿点
            holder.ivCurrent.visibility = if (char.id == currentId) View.VISIBLE else View.GONE

            // 点击整行选中角色
            holder.itemView.setOnClickListener { onSelect(char) }
            holder.btnEdit.setOnClickListener { onEdit(char) }
            holder.btnDelete.setOnClickListener { onDelete(char) }
        }

        override fun getItemCount() = characters.size

        /** 使用 DiffUtil 替换列表数据 */
        fun replaceItems(newItems: List<CharacterData>, newCurrentId: String) {
            val diffResult = DiffUtil.calculateDiff(
                DiffCallback(characters, newItems)
            )
            characters = newItems
            currentId = newCurrentId
            diffResult.dispatchUpdatesTo(this)
        }

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
                return old.name == new.name &&
                    old.personality == new.personality &&
                    old.speakingStyle == new.speakingStyle &&
                    old.backstory == new.backstory
            }
        }
    }
}