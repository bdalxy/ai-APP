package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.adapters.CharacterListAdapter
import com.aicompanion.app.databinding.ActivityCharacterBinding

/**
 * 角色详情展示页。
 * 展示当前角色的完整信息（头像、名称、性格、说话风格、背景故事、开场白），
 * 底部提供动态角色列表用于快速切换，以及创建/管理入口。
 */
class CharacterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCharacterBinding
    private lateinit var listAdapter: CharacterListAdapter
    private var characters: List<CharacterData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 初始化角色列表 RecyclerView（嵌套在 ScrollView 中，禁用自身滚动）
        binding.rvCharacterList.layoutManager = LinearLayoutManager(this)
        binding.rvCharacterList.isNestedScrollingEnabled = false

        // 创建自定义角色卡 → 跳转编辑页
        binding.btnNewCharacter.setOnClickListener {
            startActivity(Intent(this, CharacterEditActivity::class.java))
        }

        // 管理角色 → 跳转管理页
        binding.btnManageCharacters.setOnClickListener {
            startActivity(Intent(this, CharacterManageActivity::class.java))
        }

        // 初始加载
        loadAndDisplay()
    }

    override fun onResume() {
        super.onResume()
        // 从子页面（编辑/管理）返回后刷新
        loadAndDisplay()
    }

    /** 从存储加载数据并刷新 UI 和角色列表 */
    private fun loadAndDisplay() {
        characters = CharacterStorage.loadAll(this)
        val currentChar = CharacterStorage.getCurrent(this)

        // 填充当前角色详情
        fillCharacterUI(currentChar)

        // 初始化或刷新角色列表适配器
        if (!::listAdapter.isInitialized) {
            listAdapter = CharacterListAdapter(
                showDelete = false, // 详情页不显示删除按钮
                onSelect = { char ->
                    // 点击切换角色，刷新页面
                    CharacterStorage.setCurrent(this, char.id)
                    loadAndDisplay()
                },
                onEdit = { char ->
                    // 编辑角色
                    val intent = Intent(this, CharacterEditActivity::class.java)
                    intent.putExtra("character_id", char.id)
                    startActivity(intent)
                }
            )
            binding.rvCharacterList.adapter = listAdapter
        }
        listAdapter.replaceItems(characters, currentChar.id)
    }

    /** 填充角色详情到 UI 组件 */
    private fun fillCharacterUI(char: CharacterData) {
        binding.tvCharacterName.text = char.name
        binding.tvCharacterDesc.text = char.backstory.take(50) // 简介用背景故事前50字
        binding.tvPersonality.text = char.personality
        binding.tvSpeakingStyle.text = char.speakingStyle
        binding.tvBackstory.text = char.backstory
        binding.tvGreeting.text = char.greeting
    }
}