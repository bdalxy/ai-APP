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
        binding.tvPreviewName.text = name.ifBlank { "未命名角色" }
        binding.tvPreviewPersonality.text = personality.ifBlank { "暂无性格描述" }
        binding.tvPreviewGreeting.text = greeting.ifBlank { "你好呀~今天过得怎么样？" }
        if (personality.isNotBlank() || backstory.isNotBlank()) {
            binding.cardDetails.visibility = View.VISIBLE
            if (personality.isNotBlank()) {
                binding.tvDetailPersonality.visibility = View.VISIBLE
                binding.tvDetailPersonality.text = "性格特征：$personality"
            }
            if (backstory.isNotBlank()) {
                binding.tvDetailBackstory.visibility = View.VISIBLE
                binding.tvDetailBackstory.text = "背景故事：$backstory"
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