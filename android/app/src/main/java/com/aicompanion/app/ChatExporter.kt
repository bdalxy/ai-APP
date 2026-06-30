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
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object ChatExporter {

    private const val MAX_EXPORT_MESSAGES = 5000
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** 隐私提醒文本 */
    const val PRIVACY_NOTICE = "此文件包含私人对话记录，请妥善保管，不要分享给不信任的第三方。"

    // ── AES 加密参数 ──
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_SIZE = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_SALT_SIZE = 16

    /**
     * 导出为 JSON 格式。
     *
     * ISS-097: encrypt 和 password 参数仅用于 API 兼容性。JSON 格式仅支持明文导出，
     * 如需加密导出，请使用 saveToFileEncrypted() 方法。
     *
     * @param encrypt 保留参数，本方法不实现加密（请使用 saveToFileEncrypted）
     * @param password 保留参数，本方法不实现加密（请使用 saveToFileEncrypted）
     */
    fun exportToJson(messages: List<Message>, characterName: String, context: Context, encrypt: Boolean = false, password: String = ""): String {
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
            obj.put("sender", if (msg.isUser) context.getString(R.string.label_sender_me) else (msg.senderName.ifEmpty { characterName }))
            obj.put("content", msg.content)
            obj.put("timestamp", msg.timestamp)
            obj.put("time", dateFormat.format(Date(msg.timestamp)))
            obj.put("type", if (msg.isUser) "user" else "ai")
            msgArray.put(obj)
        }
        root.put("messages", msgArray)

        // 隐私提醒以注释形式添加在 JSON 文件头部（不作为 JSON 解析内容）
        val privacyLine = "// $PRIVACY_NOTICE\n"
        return privacyLine + root.toString(2)
    }

    /**
     * 导出为 TXT 格式。
     *
     * ISS-097: encrypt 和 password 参数仅用于 API 兼容性。TXT 格式仅支持明文导出，
     * 如需加密导出，请使用 saveToFileEncrypted() 方法。
     *
     * @param encrypt 保留参数，本方法不实现加密（请使用 saveToFileEncrypted）
     * @param password 保留参数，本方法不实现加密（请使用 saveToFileEncrypted）
     */
    fun exportToTxt(messages: List<Message>, characterName: String, context: Context, encrypt: Boolean = false, password: String = ""): String {
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
        sb.appendLine("--- 隐私提醒 ---")
        sb.appendLine(PRIVACY_NOTICE)
        sb.appendLine()
        sb.appendLine("--- 对话内容 ---")
        sb.appendLine()
        for (msg in limitedMessages) {
            if (msg.isTyping) continue
            val time = dateFormat.format(Date(msg.timestamp))
            val sender = if (msg.isUser) context.getString(R.string.label_sender_me) else (msg.senderName.ifEmpty { characterName })
            sb.appendLine("[$time] $sender：")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    // ── 文件保存 ──

    fun saveToFile(content: String, fileName: String, context: Context): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(content, fileName, context)
        } else {
            saveViaDirectFile(content, fileName, context)
        }
    }

    /**
     * 加密并保存到文件。
     *
     * 使用 AES-256-GCM 加密，输出文件扩展名自动添加 .enc 后缀。
     *
     * @param content 明文内容。
     * @param fileName 文件名（不含 .enc 后缀）。
     * @param context 上下文。
     * @param password 加密密码。
     * @return 成功返回 URI，失败返回 null。
     */
    fun saveToFileEncrypted(content: String, fileName: String, context: Context, password: String): Uri? {
        return try {
            val encryptedBytes = encryptContent(content, password)
            val encFileName = "$fileName.enc"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveBytesViaMediaStore(encryptedBytes, encFileName, context)
            } else {
                saveBytesViaDirectFile(encryptedBytes, encFileName, context)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatExporter", "加密保存失败: ${e.message}", e)
            null
        }
    }

    // ── AES 加密 ──

    /**
     * 加密文本内容，返回加密后的字节数组。
     *
     * 格式：[salt(16B)][iv(12B)][ciphertext...]
     */
    private fun encryptContent(content: String, password: String): ByteArray {
        val salt = ByteArray(PBKDF2_SALT_SIZE)
        SecureRandom().nextBytes(salt)

        val iv = ByteArray(GCM_IV_SIZE)
        SecureRandom().nextBytes(iv)

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))

        val plainBytes = content.toByteArray(Charsets.UTF_8)
        val encryptedBytes = cipher.doFinal(plainBytes)

        // 拼接 salt + iv + ciphertext
        val result = ByteArray(salt.size + iv.size + encryptedBytes.size)
        System.arraycopy(salt, 0, result, 0, salt.size)
        System.arraycopy(iv, 0, result, salt.size, iv.size)
        System.arraycopy(encryptedBytes, 0, result, salt.size + iv.size, encryptedBytes.size)
        return result
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // ── 内部保存方法 ──

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

    @Suppress("DEPRECATION")
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

    private fun saveBytesViaMediaStore(bytes: ByteArray, fileName: String, context: Context): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveBytesViaDirectFile(bytes: ByteArray, fileName: String, context: Context): Uri? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, fileName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // ── 文件名生成 ──

    fun generateFileName(format: String, characterName: String): String {
        val timestamp = fileNameFormat.format(Date())
        val safeName = characterName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ext = if (format == "json") "json" else "txt"
        return "${safeName}_${timestamp}.$ext"
    }
}