package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.adapters.CharacterListAdapter
import com.aicompanion.app.databinding.ActivityCharacterManageBinding

/**
 * 角色选择页面（选择模式）。
 * 从主页面左滑或点击头像进入，选择角色后返回主页面。
 * 不显示删除按钮，仅支持选择和编辑。
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

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        // 返回时从右滑出
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun finishWithAnimation() {
        finish()
    }

    /** 从存储加载角色列表并刷新适配器 */
    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id

        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                showDelete = false, // 选择模式不显示删除按钮
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
}