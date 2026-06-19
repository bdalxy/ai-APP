package com.aicompanion.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aicompanion.app.databinding.ActivityCrashLogViewerBinding
import java.io.File

class CrashLogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashLogViewerBinding
    private var crashFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.crashLogRoot)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDeleteAll.setOnClickListener { showDeleteAllDialog() }
        loadCrashLogs()
    }

    private fun loadCrashLogs() {
        crashFiles = CrashHandler.getCrashLogFiles(this)
        if (crashFiles.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.llContent.visibility = View.GONE
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.llContent.visibility = View.VISIBLE
        val sb = StringBuilder()
        for (file in crashFiles) {
            try {
                sb.appendLine(file.readText())
                sb.appendLine()
            } catch (e: Exception) {
                sb.appendLine("[读取失败] ${file.name}: ${e.message}")
            }
        }
        binding.tvCrashLogs.text = sb.toString()
        CrashHandler.clearMarker(this)
    }

    private fun showDeleteAllDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除全部崩溃日志")
            .setMessage("确定要删除全部 ${crashFiles.size} 条崩溃日志吗？")
            .setPositiveButton("删除") { _, _ ->
                crashFiles.forEach { it.delete() }
                CrashHandler.clearMarker(this)
                loadCrashLogs()
                Toast.makeText(this, "已删除全部崩溃日志", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}