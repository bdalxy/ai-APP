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
            binding.layoutEmpty.visibility = View.VISIBLE
            return
        }
        binding.layoutEmpty.visibility = View.GONE
        val logs = crashFiles.map { file ->
            try {
                file.readText()
            } catch (e: Exception) {
                "[读取失败] ${file.name}: ${e.message}"
            }
        }
        binding.rvCrashLogs.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvCrashLogs.adapter = CrashLogAdapter(logs)
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