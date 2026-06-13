package com.aicompanion.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aicompanion.app.databinding.ActivityCharacterBinding

/**
 * 角色卡片页面 Activity。
 * 展示角色头像、名称、描述、性格特征、说话风格，支持角色切换。
 */
class CharacterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCharacterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
    }
}