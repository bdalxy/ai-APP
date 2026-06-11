package com.aicompanion.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页面 Activity。
 * 提供账户设置、对话设置、主动消息、记忆管理、关于等入口。
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 返回按钮
        findViewById<android.widget.TextView>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        // TODO: 绑定额外的设置项交互
        // 点击API Key → 弹窗修改
        // 点击角色预设 → 弹窗选择
        // 点击Token预设 → 弹窗选择
        // 主动消息开关 → 切换
        // 点击记忆管理 → 跳转 MemoryManageActivity
    }
}