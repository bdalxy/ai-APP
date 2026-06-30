package com.aicompanion.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            binding.layoutEmpty.visibility = View.VISIBLE
            return
        }
        binding.layoutEmpty.visibility = View.GONE
        val logs = crashFiles.map { file ->
            try {
                sanitizeLog(file.readText())
            } catch (e: Exception) {
                getString(R.string.crash_read_failed_fmt, file.name, e.message ?: "")
            }
        }
        binding.rvCrashLogs.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvCrashLogs.adapter = CrashLogAdapter(logs)
        CrashHandler.clearMarker(this)
    }

    /**
     * 脱敏崩溃日志中的敏感信息：
     * - API Key（sk- 开头）
     * - JSON 中 content 字段的长文本截断
     * - URL 中的 token 参数
     * - 手机号码
     */
    private fun sanitizeLog(log: String): String {
        var result = log
        // API Key: sk-xxxxxx → sk-***
        result = result.replace(Regex("sk-[a-zA-Z0-9]{20,}")) { _ -> "sk-***" }
        // URL token 参数: token=xxx → token=***
        result = result.replace(Regex("token=[^&\\s]+")) { "token=***" }
        // 手机号码（11位大陆手机号）
        result = result.replace(Regex("\\b1[3-9]\\d{9}\\b")) { "1**********" }
        // JSON content 字段长文本截断到50字符
        result = result.replace(Regex("\"content\"\\s*:\\s*\"([^\"]{50,})\"")) { match ->
            val content = match.groupValues[1]
            "\"content\": \"${content.take(50)}...[${getString(R.string.crash_log_truncated)}]\""
        }
        return result
    }

    private fun showDeleteAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.crash_delete_all_title))
            .setMessage(getString(R.string.crash_delete_all_msg_fmt, crashFiles.size))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                crashFiles.forEach { it.delete() }
                CrashHandler.clearMarker(this)
                loadCrashLogs()
                Toast.makeText(this, getString(R.string.crash_toast_deleted_all), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private inner class CrashLogAdapter(
        private val logs: List<String>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<CrashLogAdapter.ViewHolder>() {

        inner class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvLog: android.widget.TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvLog.text = logs[position]
        }

        override fun getItemCount(): Int = logs.size
    }
}