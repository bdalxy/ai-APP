package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 角色卡管理列表页。
 * 展示所有角色，支持切换当前角色、编辑和删除。
 */
class CharacterManageActivity : AppCompatActivity() {

    private lateinit var rvCharacters: RecyclerView
    private lateinit var adapter: CharacterListAdapter
    private var characters: List<CharacterData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_manage)

        // 适配刘海屏/挖孔屏/状态栏
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(findViewById(R.id.character_manage_root))

        // 返回按钮
        findViewById<TextView>(R.id.btnBack)?.setOnClickListener { finish() }

        // 新建角色按钮
        findViewById<TextView>(R.id.btnNewCharacter)?.setOnClickListener {
            startActivity(Intent(this, CharacterEditActivity::class.java))
        }

        // 初始化 RecyclerView
        rvCharacters = findViewById(R.id.rvCharacters)
        rvCharacters.layoutManager = LinearLayoutManager(this)

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
        adapter = CharacterListAdapter(characters, currentId,
            onSelect = { char ->
                // 切换当前角色，返回聊天页
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
        rvCharacters.adapter = adapter
    }

    /**
     * 角色列表 RecyclerView 适配器。
     * 每行展示角色头像、名称、简介预览，以及当前选中指示和操作按钮。
     */
    private class CharacterListAdapter(
        private val characters: List<CharacterData>,
        private val currentId: String,
        private val onSelect: (CharacterData) -> Unit,
        private val onEdit: (CharacterData) -> Unit,
        private val onDelete: (CharacterData) -> Unit
    ) : RecyclerView.Adapter<CharacterListAdapter.ViewHolder>() {

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
    }

    // ======================== 适配辅助方法 ========================
}