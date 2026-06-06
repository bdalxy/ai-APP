package com.aicompanion.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AICompanion"
    }

    private lateinit var pythonMain: com.chaquo.python.PyObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initPython()
    }

    private fun initPython() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chaquopy_test")
                    module.callAttr("run_tests")
                }
                Log.i(TAG, "Python 初始化成功: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Python 初始化失败: ${e.message}", e)
            }
        }
    }
}
