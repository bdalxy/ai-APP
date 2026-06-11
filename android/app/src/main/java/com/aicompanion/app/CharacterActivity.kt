package com.aicompanion.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 角色卡片页面 Activity。
 * 展示角色头像、名称、描述、性格特征、说话风格，支持角色切换。
 */
class CharacterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character)

        findViewById<android.widget.TextView>(R.id.btnBack)?.setOnClickListener {
            finish()
        }
    }
}