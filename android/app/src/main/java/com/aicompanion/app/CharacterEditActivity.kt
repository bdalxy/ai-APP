package com.aicompanion.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aicompanion.app.databinding.ActivityCharacterEditBinding

class CharacterEditActivity : AppCompatActivity() {

    private var editingId: String? = null
    private var isProMode = false
    private lateinit var binding: ActivityCharacterEditBinding

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            saveCharacter()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.characterEditRoot)
        editingId = intent.getStringExtra("character_id")
        if (editingId != null) {
            setupEditMode()
        } else {
            setupCreateMode()
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.optionBasic.setOnClickListener {
            if (isProMode) {
                isProMode = false
                updateModeUI()
            }
        }
        binding.optionPro.setOnClickListener {
            if (!isProMode) {
                isProMode = true
                updateModeUI()
            }
        }
        binding.btnPreview.setOnClickListener { previewCharacter() }
    }

    private fun setupEditMode() {
        binding.tvPageTitle.text = "编辑角色"
        binding.cardModeSelection.visibility = View.GONE
        binding.bottomActions.visibility = View.GONE
        binding.btnSave.visibility = View.VISIBLE
        binding.tvProSection.visibility = View.VISIBLE
        binding.proFields.visibility = View.VISIBLE
        isProMode = true
        val char = CharacterStorage.loadAll(this).find { it.id == editingId }
        char?.let {
            binding.etName.setText(it.name)
            binding.etPersonality.setText(it.personality)
            binding.etGreeting.setText(it.greeting)
            binding.etSpeakingStyle.setText(it.speakingStyle)
            binding.etBackstory.setText(it.backstory)
            binding.etCoreTraits.setText(it.coreTraits)
            binding.etTabooTopics.setText(it.tabooTopics)
            binding.etRoleAnchor.setText(it.roleAnchor)
        }
        binding.btnSave.setOnClickListener { saveCharacter() }
    }

    private fun setupCreateMode() {
        binding.tvPageTitle.text = "创建角色"
        binding.cardModeSelection.visibility = View.VISIBLE
        binding.bottomActions.visibility = View.VISIBLE
        binding.btnSave.visibility = View.GONE
        isProMode = false
        updateModeUI()
    }

    private fun updateModeUI() {
        if (isProMode) {
            binding.dotBasic.visibility = View.INVISIBLE
            binding.dotPro.background = getDrawable(com.aicompanion.app.R.drawable.bg_status_online)
            binding.dotPro.visibility = View.VISIBLE
            binding.tvProSection.visibility = View.VISIBLE
            binding.proFields.visibility = View.VISIBLE
        } else {
            binding.dotBasic.background = getDrawable(com.aicompanion.app.R.drawable.bg_status_online)
            binding.dotBasic.visibility = View.VISIBLE
            binding.dotPro.background = getDrawable(com.aicompanion.app.R.drawable.bg_input_field)
            binding.dotPro.visibility = View.INVISIBLE
            binding.tvProSection.visibility = View.GONE
            binding.proFields.visibility = View.GONE
        }
    }

    private fun previewCharacter() {
        val name = binding.etName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "请输入角色名称", Toast.LENGTH_SHORT).show()
            return
        }
        val char = buildCharacterData()
        val intent = Intent(this, CharacterPreviewActivity::class.java)
        intent.putExtra("character_name", char.name)
        intent.putExtra("character_personality", char.personality)
        intent.putExtra("character_greeting", char.greeting)
        intent.putExtra("character_speaking_style", char.speakingStyle)
        intent.putExtra("character_backstory", char.backstory)
        intent.putExtra("character_core_traits", char.coreTraits)
        intent.putExtra("character_taboo_topics", char.tabooTopics)
        intent.putExtra("character_role_anchor", char.roleAnchor)
        previewLauncher.launch(intent)
    }

    private fun saveCharacter() {
        val name = binding.etName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "请输入角色名称", Toast.LENGTH_SHORT).show()
            return
        }
        val char = buildCharacterData()
        CharacterStorage.save(this, char)
        CharacterStorage.setCurrent(this, char.id)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buildCharacterData(): CharacterData {
        val existingChar = if (editingId != null) {
            CharacterStorage.loadAll(this).find { it.id == editingId }
        } else null
        return CharacterData(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = binding.etName.text.toString().trim(),
            personality = binding.etPersonality.text.toString().trim(),
            speakingStyle = binding.etSpeakingStyle.text.toString().trim(),
            backstory = binding.etBackstory.text.toString().trim(),
            greeting = binding.etGreeting.text.toString().trim(),
            coreTraits = binding.etCoreTraits.text.toString().trim(),
            tabooTopics = binding.etTabooTopics.text.toString().trim(),
            roleAnchor = binding.etRoleAnchor.text.toString().trim(),
            isDefault = false,
            createdAt = existingChar?.createdAt ?: System.currentTimeMillis()
        )
    }
}