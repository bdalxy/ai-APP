package com.aicompanion.app

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 角色卡创建/编辑页。
 * 通过 intent extra "character_id" 区分创建模式和编辑模式。
 */
class CharacterEditActivity : AppCompatActivity() {

    private var editingId: String? = null
    private lateinit var etName: EditText
    private lateinit var etPersonality: EditText
    private lateinit var etSpeakingStyle: EditText
    private lateinit var etBackstory: EditText
    private lateinit var etGreeting: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_edit)

        // 返回按钮
        findViewById<TextView>(R.id.btnBack)?.setOnClickListener { finish() }

        // 绑定表单控件
        etName = findViewById(R.id.etName)
        etPersonality = findViewById(R.id.etPersonality)
        etSpeakingStyle = findViewById(R.id.etSpeakingStyle)
        etBackstory = findViewById(R.id.etBackstory)
        etGreeting = findViewById(R.id.etGreeting)

        // 检查是否为编辑模式
        editingId = intent.getStringExtra("character_id")
        if (editingId != null) {
            // 编辑模式：加载已有数据
            val char = CharacterStorage.loadAll(this).find { it.id == editingId }
            char?.let {
                etName.setText(it.name)
                etPersonality.setText(it.personality)
                etSpeakingStyle.setText(it.speakingStyle)
                etBackstory.setText(it.backstory)
                etGreeting.setText(it.greeting)
                findViewById<TextView>(R.id.tvPageTitle)?.text = "编辑角色"
                findViewById<TextView>(R.id.btnSave)?.text = "更新"
            }
        }

        // 保存按钮
        findViewById<TextView>(R.id.btnSave)?.setOnClickListener {
            saveCharacter()
        }
    }

    /** 保存角色卡到本地存储。 */
    private fun saveCharacter() {
        val name = etName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "请输入角色名称", Toast.LENGTH_SHORT).show()
            return
        }

        val char = CharacterData(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            personality = etPersonality.text.toString().trim(),
            speakingStyle = etSpeakingStyle.text.toString().trim(),
            backstory = etBackstory.text.toString().trim(),
            greeting = etGreeting.text.toString().trim(),
            isDefault = false,
            createdAt = System.currentTimeMillis()
        )

        CharacterStorage.save(this, char)
        // 保存后自动设为当前角色
        CharacterStorage.setCurrent(this, char.id)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}