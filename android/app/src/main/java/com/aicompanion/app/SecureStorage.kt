package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * 安全存储工具类。
 *
 * 提供 EncryptedSharedPreferences 和 EncryptedFile 的统一封装，
 * 用于存储会话消息、角色卡、世界书、用户设置等敏感数据。
 *
 * 加密失败时抛出 SecurityException，不降级为明文存储。
 */
object SecureStorage {

    private const val TAG = "SecureStorage"

    /** 缓存已创建的 MasterKey，避免重复初始化 */
    private var cachedMasterKey: MasterKey? = null

    private fun getMasterKey(context: Context): MasterKey {
        return cachedMasterKey ?: synchronized(this) {
            cachedMasterKey ?: MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                .also { cachedMasterKey = it }
        }
    }

    // ── EncryptedSharedPreferences ──

    /**
     * 获取加密的 SharedPreferences 实例。
     *
     * @param context 上下文
     * @param name SharedPreferences 名称
     * @return 加密的 SharedPreferences 实例
     * @throws SecurityException 如果设备不支持加密存储
     */
    fun getEncryptedPrefs(context: Context, name: String): SharedPreferences {
        return try {
            val masterKey = getMasterKey(context)
            EncryptedSharedPreferences.create(
                context.applicationContext,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密 SharedPreferences 初始化失败: $name, ${e.message}")
            throw SecurityException("设备不支持加密存储", e)
        }
    }

    // ── EncryptedFile ──

    /**
     * 将内容加密写入文件。
     *
     * @param context 上下文
     * @param filename 文件名（相对于 filesDir）
     * @param content 要写入的内容
     * @throws SecurityException 如果加密失败
     */
    fun writeEncryptedFile(context: Context, filename: String, content: String) {
        try {
            val masterKey = getMasterKey(context)
            val file = File(context.filesDir, filename)
            val encryptedFile = EncryptedFile.Builder(
                context.applicationContext,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encryptedFile.openFileOutput().use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "加密写入文件失败: $filename, ${e.message}")
            throw SecurityException("加密写入文件失败", e)
        }
    }

    /**
     * 从加密文件读取内容。
     *
     * @param context 上下文
     * @param filename 文件名（相对于 filesDir）
     * @return 文件内容，如果文件不存在或加密失败则返回 null
     */
    fun readEncryptedFile(context: Context, filename: String): String? {
        return try {
            val masterKey = getMasterKey(context)
            val file = File(context.filesDir, filename)
            if (!file.exists()) return null
            val encryptedFile = EncryptedFile.Builder(
                context.applicationContext,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encryptedFile.openFileInput().use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加密读取文件失败: $filename, ${e.message}")
            null
        }
    }

    /**
     * 删除加密文件。
     *
     * @param context 上下文
     * @param filename 文件名（相对于 filesDir）
     */
    fun deleteEncryptedFile(context: Context, filename: String) {
        val file = File(context.filesDir, filename)
        if (file.exists()) {
            file.delete()
        }
    }
}