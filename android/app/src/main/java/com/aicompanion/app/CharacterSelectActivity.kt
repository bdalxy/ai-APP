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
 * 角色选择页面。
 * 从主页面左滑或点击头像进入，选择角色后返回主页面。
 */
class CharacterSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterManageBinding
    private lateinit var adapter: CharacterListAdapter
    private var characters: List<CharacterData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.characterManageRoot)

        binding.btnBack.setOnClickListener { finishWithAnimation() }
        binding.btnNewCharacter.setOnClickListener {
            startActivity(Intent(this, CharacterEditActivity::class.java))
        }

        binding.rvCharacters.layoutManager = LinearLayoutManager(this)

        loadCharacters()
    }

    override fun onResume() {
        super.onResume()
        loadCharacters()
    }

    override fun finish() {
        super.finish()
        // 返回时从右滑出
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun finishWithAnimation() {
        finish()
    }

    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id

        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                onSelect = { char ->
                    CharacterStorage.setCurrent(this, char.id)
                    setResult(RESULT_OK)
                    finishWithAnimation()
                },
                onEdit = { char ->
                    val intent = Intent(this, CharacterEditActivity::class.java)
                    intent.putExtra("character_id", char.id)
                    startActivity(intent)
                }
            )
            binding.rvCharacters.adapter = adapter
        }
        adapter.replaceItems(characters, currentId)
    }

    private class CharacterListAdapter(
        private val onSelect: (CharacterData) -> Unit,
        private val onEdit: (CharacterData) -> Unit,
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
            holder.ivCurrent.visibility = if (char.id == currentId) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onSelect(char) }
            holder.btnEdit.setOnClickListener { onEdit(char) }
            holder.btnDelete.visibility = View.GONE // 选择页不显示删除按钮
        }

        override fun getItemCount() = characters.size

        fun replaceItems(newItems: List<CharacterData>, newCurrentId: String) {
            val diffResult = DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = characters.size
                    override fun getNewListSize() = newItems.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        characters[oldPos].id == newItems[newPos].id
                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                        val old = characters[oldPos]; val new = newItems[newPos]
                        return old.name == new.name && old.personality == new.personality
                    }
                }
            )
            characters = newItems
            currentId = newCurrentId
            diffResult.dispatchUpdatesTo(this)
        }
    }
}