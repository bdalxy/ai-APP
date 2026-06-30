package com.aicompanion.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.aicompanion.app.databinding.ActivityCharacterPreviewBinding

class CharacterPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.previewRoot)
        val name = intent.getStringExtra("character_name") ?: ""
        val personality = intent.getStringExtra("character_personality") ?: ""
        val greeting = intent.getStringExtra("character_greeting") ?: ""
        val backstory = intent.getStringExtra("character_backstory") ?: ""
        binding.tvPreviewName.text = name.ifBlank { getString(R.string.char_unnamed) }
        binding.tvPreviewPersonality.text = personality.ifBlank { getString(R.string.char_no_personality) }
        binding.tvPreviewGreeting.text = greeting.ifBlank { getString(R.string.char_default_greeting) }
        if (personality.isNotBlank() || backstory.isNotBlank()) {
            binding.cardDetails.visibility = View.VISIBLE
            if (personality.isNotBlank()) {
                binding.tvDetailPersonality.visibility = View.VISIBLE
                binding.tvDetailPersonality.text = getString(R.string.char_detail_personality_fmt, personality)
            }
            if (backstory.isNotBlank()) {
                binding.tvDetailBackstory.visibility = View.VISIBLE
                binding.tvDetailBackstory.text = getString(R.string.char_detail_backstory_fmt, backstory)
            }
        } else {
            binding.cardDetails.visibility = View.GONE
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBackEdit.setOnClickListener { finish() }
        binding.btnConfirmCreate.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }
}