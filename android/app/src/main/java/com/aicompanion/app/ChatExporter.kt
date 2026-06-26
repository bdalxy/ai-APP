package com.aicompanion.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatExporter {

    private const val MAX_EXPORT_MESSAGES = 5000
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun exportToJson(messages: List<Message>, characterName: String): String {
        val limitedMessages = messages.take(MAX_EXPORT_MESSAGES)
        val root = JSONObject()
        root.put("character_name", characterName)
        root.put("export_time", dateFormat.format(Date()))
        root.put("message_count", limitedMessages.count { !it.isTyping })
        if (messages.size > MAX_EXPORT_MESSAGES) {
            root.put("truncated", true)
            root.put("total_messages", messages.size)
        }
        val msgArray = JSONArray()
        for (msg in limitedMessages) {
            if (msg.isTyping) continue
            val obj = JSONObject()
            obj.put("sender", if (msg.isUser) "我" else (msg.senderName.ifEmpty { characterName }))
            obj.put("content", msg.content)
            obj.put("timestamp", msg.timestamp)
            obj.put("time", dateFormat.format(Date(msg.timestamp)))
            obj.put("type", if (msg.isUser) "user" else "ai")
            msgArray.put(obj)
        }
        root.put("messages", msgArray)
        return root.toString(2)
    }

    fun exportToTxt(messages: List<Message>, characterName: String): String {
        val limitedMessages = messages.take(MAX_EXPORT_MESSAGES)
        val sb = StringBuilder()
        sb.appendLine("=== 对话记录 ===")
        sb.appendLine("角色：$characterName")
        sb.appendLine("导出时间：${dateFormat.format(Date())}")
        sb.appendLine("消息总数：${limitedMessages.count { !it.isTyping }}")
        if (messages.size > MAX_EXPORT_MESSAGES) {
            sb.appendLine("（已截断，完整消息数：${messages.size}）")
        }
        sb.appendLine()
        sb.appendLine("--- 对话内容 ---")
        sb.appendLine()
        for (msg in limitedMessages) {
            if (msg.isTyping) continue
            val time = dateFormat.format(Date(msg.timestamp))
            val sender = if (msg.isUser) "我" else (msg.senderName.ifEmpty { characterName })
            sb.appendLine("[$time] $sender：")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    fun saveToFile(content: String, fileName: String, context: Context): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(content, fileName, context)
        } else {
            saveViaDirectFile(content, fileName, context)
        }
    }

    private fun saveViaMediaStore(content: String, fileName: String, context: Context): Uri? {
        val mimeType = if (fileName.endsWith(".json")) "application/json" else "text/plain"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        }
        return uri
    }

    private fun saveViaDirectFile(content: String, fileName: String, context: Context): Uri? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun generateFileName(format: String, characterName: String): String {
        val timestamp = fileNameFormat.format(Date())
        val safeName = characterName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ext = if (format == "json") "json" else "txt"
        return "${safeName}_${timestamp}.$ext"
    }
}