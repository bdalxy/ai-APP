package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicompanion.app.adapters.CharacterListAdapter
import com.aicompanion.app.databinding.ActivityCharacterManageBinding

class CharacterManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterManageBinding
    private lateinit var adapter: CharacterListAdapter
    private var characters: List<CharacterData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.characterManageRoot)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnNewCharacter.setOnClickListener { navigateToCreate() }
        binding.btnFabCreate.setOnClickListener { navigateToCreate() }
        binding.rvCharacters.layoutManager = LinearLayoutManager(this)
        loadCharacters()
    }

    override fun onResume() {
        super.onResume()
        loadCharacters()
    }

    private fun navigateToCreate() {
        startActivity(Intent(this, CharacterEditActivity::class.java))
    }

    private fun loadCharacters() {
        characters = CharacterStorage.loadAll(this)
        val currentId = CharacterStorage.getCurrent(this).id
        if (characters.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.rvCharacters.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.rvCharacters.visibility = View.VISIBLE
        }
        if (!::adapter.isInitialized) {
            adapter = CharacterListAdapter(
                showDelete = true,
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
                        Toast.makeText(this, getString(R.string.char_default_cannot_delete), Toast.LENGTH_SHORT).show()
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