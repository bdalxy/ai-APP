package com.aicompanion.app

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 聊天 APP 主界面
 * 通过 Chaquopy 调用 Python 聊天引擎，在 Android 设备上运行 AI 对话。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AICompanion"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initPython()
    }

    private fun initPython() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "正在初始化 Python 环境..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chaquopy_test")
                    module.callAttr("run_tests").toString()
                }
                Log.i(TAG, "Python 初始化成功: $result")
                tvStatus.text = "Python 初始化成功"
            } catch (e: Exception) {
                Log.e(TAG, "Python 初始化失败: ${e.message}", e)
                tvStatus.text = "初始化失败: ${e.message}"
            }
        }
    }
}