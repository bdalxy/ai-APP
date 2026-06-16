package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.adapters.CharacterListAdapter
import com.aicompanion.app.databinding.ActivityCharacterManageBinding

/**
 * 角色卡管理列表页（管理模式）。
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

    /** 从存储加载角色列表并刷新适配器 */
    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id

        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                showDelete = true, // 管理模式显示删除按钮
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
}