package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.adapters.CharacterListAdapter
import com.aicompanion.app.databinding.ActivityCharacterManageBinding

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
        binding.btnFabCreate.setOnClickListener {
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
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun finishWithAnimation() {
        finish()
    }

    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id

        // 只有一个角色时，自动选中并返回，跳过选择界面
        if (characters.size == 1) {
            val singleChar = characters.first()
            CharacterStorage.setCurrent(this, singleChar.id)
            setResult(RESULT_OK)
            finishWithAnimation()
            return
        }

        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                showDelete = false,
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