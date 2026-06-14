package com.aicompanion.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aicompanion.app.databinding.ActivityCharacterEditBinding

/**
 * 角色卡创建/编辑页。
 * 通过 intent extra "character_id" 区分创建模式和编辑模式。
 */
class CharacterEditActivity : AppCompatActivity() {

    private var editingId: String? = null
    private lateinit var binding: ActivityCharacterEditBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 适配刘海屏/挖孔屏/状态栏
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.characterEditRoot)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 检查是否为编辑模式
        editingId = intent.getStringExtra("character_id")
        if (editingId != null) {
            // 编辑模式：加载已有数据
            val char = CharacterStorage.loadAll(this).find { it.id == editingId }
            char?.let {
                binding.etName.setText(it.name)
                binding.etPersonality.setText(it.personality)
                binding.etSpeakingStyle.setText(it.speakingStyle)
                binding.etBackstory.setText(it.backstory)
                binding.etGreeting.setText(it.greeting)
                binding.tvPageTitle.text = "编辑角色"
                binding.btnSave.text = "更新"
            }
        }

        // 保存按钮
        binding.btnSave.setOnClickListener { saveCharacter() }
    }

    /** 保存角色卡到本地存储。 */
    private fun saveCharacter() {
        val name = binding.etName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "请输入角色名称", Toast.LENGTH_SHORT).show()
            return
        }

        // 编辑模式下保留原始创建时间
        val existingChar = if (editingId != null) {
            CharacterStorage.loadAll(this).find { it.id == editingId }
        } else null

        val char = CharacterData(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            personality = binding.etPersonality.text.toString().trim(),
            speakingStyle = binding.etSpeakingStyle.text.toString().trim(),
            backstory = binding.etBackstory.text.toString().trim(),
            greeting = binding.etGreeting.text.toString().trim(),
            isDefault = false,
            createdAt = existingChar?.createdAt ?: System.currentTimeMillis()
        )

        CharacterStorage.save(this, char)
        // 保存后自动设为当前角色
        CharacterStorage.setCurrent(this, char.id)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ======================== 适配辅助方法 ========================
}