package com.aicompanion.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.aicompanion.app.databinding.ActivityCharacterEditBinding
import java.io.File
import java.io.FileOutputStream

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

    /** 头像选择 launcher */
    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onAvatarSelected(it) }
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
        // 头像点击
        binding.ivAvatar.setOnClickListener {
            avatarPickerLauncher.launch("image/*")
        }
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
            binding.etSelfIdentity.setText(it.selfIdentity)
            setEmotionalTendency(it.emotionalTendency)
            // 加载已有头像
            loadAvatar(it.avatarUri)
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
            emotionalTendency = getEmotionalTendency(),
            selfIdentity = binding.etSelfIdentity.text.toString().trim(),
            avatarUri = pendingAvatarPath ?: existingChar?.avatarUri ?: "",
            isDefault = false,
            createdAt = existingChar?.createdAt ?: System.currentTimeMillis()
        )
    }

    /** 临时头像路径（选择后但未保存前） */
    private var pendingAvatarPath: String? = null

    /** 头像选择回调 */
    private fun onAvatarSelected(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
                return
            }
            // 圆形裁剪预览
            val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap)
            rounded.isCircular = true
            binding.ivAvatar.setImageDrawable(rounded)

            // 保存到临时文件
            val avatarDir = File(filesDir, "avatars")
            avatarDir.mkdirs()
            val charId = editingId ?: java.util.UUID.randomUUID().toString()
            val avatarFile = File(avatarDir, "${charId}.jpg")
            FileOutputStream(avatarFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            pendingAvatarPath = avatarFile.absolutePath
            Toast.makeText(this, R.string.toast_avatar_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载头像失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 加载已有头像到 ImageView */
    private fun loadAvatar(avatarPath: String) {
        if (avatarPath.isBlank()) return
        try {
            val file = File(avatarPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(avatarPath)
                if (bitmap != null) {
                    val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap)
                    rounded.isCircular = true
                    binding.ivAvatar.setImageDrawable(rounded)
                }
            }
        } catch (e: Exception) {
            Log.w("CharacterEditActivity", "加载头像失败: ${e.message}")
        }
    }

    /** 情感倾向选项列表 */
    private val emotionalTendencyOptions = arrayOf("", "乐观", "中性", "悲观", "热情", "冷静")

    /** 设置情感倾向下拉选中值 */
    private fun setEmotionalTendency(value: String) {
        val idx = emotionalTendencyOptions.indexOf(value)
        if (idx >= 0 && binding.spEmotionalTendency != null) {
            binding.spEmotionalTendency.setSelection(idx)
        }
    }

    /** 获取当前选中的情感倾向值 */
    private fun getEmotionalTendency(): String {
        val pos = binding.spEmotionalTendency?.selectedItemPosition ?: 0
        return if (pos in emotionalTendencyOptions.indices) emotionalTendencyOptions[pos] else ""
    }
}