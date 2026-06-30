package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据备份/恢复工具类。
 *
 * 将 App 数据目录（conversations/、memory/、characters/、world_books/）打包为 ZIP 文件，
 * 并可选使用 AES-256-GCM 加密，或从加密 ZIP 文件恢复数据。
 *
 * 兼容 Android 10+ SAF（Storage Access Framework）。
 */
object DataBackupHelper {

    private const val TAG = "DataBackupHelper"
    private const val PREFS_NAME = "backup_prefs"
    private const val KEY_BACKUP_PASSWORD = "backup_password_hash"

    /** 需要备份的子目录名称列表 */
    private val BACKUP_DIRS = listOf("conversations", "memory", "characters", "world_books")

    /** 单个 entry 最大解压大小（50MB，防止 ZIP 炸弹） */
    private const val MAX_ENTRY_SIZE = 50 * 1024 * 1024L
    /** 累计最大解压大小（200MB） */
    private const val MAX_TOTAL_SIZE = 200 * 1024 * 1024L

    // ── AES 加密参数 ──
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_SIZE = 12          // 96 bits，推荐值
    private const val GCM_TAG_SIZE = 128        // 128 bits
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_SALT_SIZE = 16

    /** 备份文件名格式 */
    private val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ── 密码管理 ──

    /**
     * 获取备份密码的 SharedPreferences。
     */
    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 是否已设置备份密码。
     */
    fun isPasswordSet(context: Context): Boolean {
        return getPrefs(context).getString(KEY_BACKUP_PASSWORD, null) != null
    }

    /**
     * 设置备份密码（存储 SHA-256 哈希，不存储明文）。
     */
    fun setPassword(context: Context, password: String) {
        val hash = hashPassword(password)
        getPrefs(context).edit().putString(KEY_BACKUP_PASSWORD, hash).apply()
    }

    /**
     * 验证备份密码是否正确。
     */
    fun verifyPassword(context: Context, password: String): Boolean {
        val stored = getPrefs(context).getString(KEY_BACKUP_PASSWORD, null) ?: return false
        return hashPassword(password) == stored
    }

    /**
     * 清除备份密码。
     */
    fun clearPassword(context: Context) {
        getPrefs(context).edit().remove(KEY_BACKUP_PASSWORD).apply()
    }

    private fun hashPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── 文件名生成 ──

    /**
     * 生成备份文件名（未加密）。
     */
    fun generateFileName(): String {
        return "AICompanion_Backup_${FILE_NAME_FORMAT.format(Date())}.zip"
    }

    /**
     * 生成加密备份文件名。
     */
    fun generateEncryptedFileName(): String {
        return "AICompanion_Backup_${FILE_NAME_FORMAT.format(Date())}.zip.enc"
    }

    // ── 备份 ──

    /**
     * 备份：将 App 数据目录打包为加密 ZIP，写入到指定的 URI。
     *
     * 加密使用 AES-256-GCM，密钥由用户设置的备份密码通过 PBKDF2 派生。
     *
     * @param context 应用上下文。
     * @param outputUri SAF 输出 URI（由用户通过文件选择器选择）。
     * @param password 备份密码（必须与已存储的密码一致）。
     * @return 成功返回 true，失败返回 false。
     */
    fun backup(context: Context, outputUri: Uri, password: String? = null): Boolean {
        return try {
            val filesDir = context.filesDir
            val existingDirs = BACKUP_DIRS.filter { dirName ->
                File(filesDir, dirName).exists() && File(filesDir, dirName).isDirectory
            }

            if (existingDirs.isEmpty()) {
                Log.w(TAG, "备份: 没有可备份的数据目录")
                return false
            }

            // 1. 打包 ZIP 到临时文件
            val tempZipFile = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}.zip")
            try {
                FileOutputStream(tempZipFile).use { fos ->
                    ZipOutputStream(fos).use { zipOut ->
                        for (dirName in existingDirs) {
                            val dir = File(filesDir, dirName)
                            zipDirectory(dir, dirName, zipOut)
                        }
                        val dbFile = File(filesDir, "memories.db")
                        if (dbFile.exists()) {
                            zipFile(dbFile, "memories.db", zipOut)
                        }
                    }
                }

                // 2. 加密并写入目标 URI
                val effectivePassword = password ?: getStoredPasswordOrNull(context)
                if (effectivePassword != null) {
                    writeEncryptedFile(context, tempZipFile, outputUri, effectivePassword)
                } else {
                    // 无密码：直接写入 ZIP（兼容旧行为）
                    context.contentResolver.openOutputStream(outputUri)?.use { os ->
                        FileInputStream(tempZipFile).use { it.copyTo(os) }
                    }
                }

                Log.i(TAG, "备份完成: 已备份 ${existingDirs.size} 个目录${if (effectivePassword != null) "（已加密）" else ""}")
                true
            } finally {
                tempZipFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "备份失败: ${e.message}", e)
            false
        }
    }

    /**
     * 尝试获取已存储的备份密码明文，用于自动备份加密。
     *
     * ## 设计说明：为什么返回 null 是正确的
     *
     * 1. **密码只存储哈希，不存储明文**：`setPassword()` 存储的是 SHA-256 哈希值，
     *    无法逆向还原为明文密码，这是安全设计，不是 bug。
     * 2. **自动备份无法加密是设计如此**：自动备份场景下用户不在场，无法输入密码，
     *    因此自动备份生成的是未加密的 ZIP 文件。加密备份需要用户手动触发并提供密码。
     * 3. **向后兼容**：`backup()` 方法的 `password` 参数为可选，当 `password` 为 null
     *    且此方法返回 null 时，备份文件不加密（兼容旧行为）。
     *
     * @return 始终返回 null（密码不在内存中持久化存储）
     */
    /**
     * 获取存储的备份密码（用于自动备份场景）。
     *
     * ISS-098: 自动备份不支持加密，原因：
     * 1. 加密密码需要用户手动输入，自动备份时无法获取
     * 2. 使用设备密钥自动派生密码存在密钥轮换风险
     * 3. 自动备份的目标是防止数据丢失，加密是手动备份的附加功能
     *
     * 需要加密备份的用户请使用手动备份功能，并设置备份密码。
     *
     * @return 始终返回 null（自动备份不加密）
     */
    private fun getStoredPasswordOrNull(context: Context): String? {
        return null
    }

    // ── 恢复 ──

    /**
     * 恢复：从加密 ZIP 文件恢复数据到 App 数据目录。
     *
     * @param context 应用上下文。
     * @param inputUri SAF 输入 URI（用户选择的 .zip 或 .zip.enc 文件）。
     * @param password 备份密码（如果文件已加密则必须提供）。
     * @return 成功返回 true，失败返回 false。
     */
    fun restore(context: Context, inputUri: Uri, password: String? = null): Boolean {
        return try {
            val filesDir = context.filesDir
            val tempDir = File(filesDir, "_restore_temp")
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            // 1. 读取输入文件，检测是否加密
            val tempInputFile = File(context.cacheDir, "restore_input_${System.currentTimeMillis()}")
            try {
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    FileOutputStream(tempInputFile).use { input.copyTo(it) }
                }

                // 检测是否为加密文件（通过 magic bytes 或文件扩展名）
                val isEncrypted = isEncryptedFile(tempInputFile)

                val tempZipFile: File
                if (isEncrypted) {
                    if (password == null) {
                        tempDir.deleteRecursively()
                        tempInputFile.delete()
                        Log.e(TAG, "恢复: 文件已加密但未提供密码")
                        return false
                    }
                    tempZipFile = File(context.cacheDir, "restore_decrypted_${System.currentTimeMillis()}.zip")
                    try {
                        decryptFile(tempInputFile, tempZipFile, password)
                    } catch (e: Exception) {
                        tempDir.deleteRecursively()
                        tempInputFile.delete()
                        tempZipFile.delete()
                        Log.e(TAG, "恢复: 解密失败（密码错误或文件损坏）: ${e.message}")
                        return false
                    }
                } else {
                    tempZipFile = tempInputFile
                }

                try {
                    // 2. 解压 ZIP（带大小限制，防止 ZIP 炸弹）
                    var totalUncompressedSize = 0L
                    FileInputStream(tempZipFile).use { fis ->
                        ZipInputStream(fis).use { zipIn ->
                            var entry = zipIn.nextEntry
                            while (entry != null) {
                                val entrySize = entry.size
                                if (entrySize > MAX_ENTRY_SIZE) {
                                    tempDir.deleteRecursively()
                                    Log.e(TAG, "恢复: ZIP 条目过大 (${entrySize} > ${MAX_ENTRY_SIZE}), 条目: ${entry.name}")
                                    return false
                                }
                                totalUncompressedSize += entrySize
                                if (totalUncompressedSize > MAX_TOTAL_SIZE) {
                                    tempDir.deleteRecursively()
                                    Log.e(TAG, "恢复: ZIP 累计大小超限 (${totalUncompressedSize} > ${MAX_TOTAL_SIZE})")
                                    return false
                                }
                                val entryFile = File(tempDir, entry.name)
                                if (!entryFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                                    tempDir.deleteRecursively()
                                    Log.e(TAG, "恢复: 检测到路径穿越攻击, 条目: ${entry.name}")
                                    return false
                                }
                                if (entry.isDirectory) {
                                    entryFile.mkdirs()
                                } else {
                                    entryFile.parentFile?.mkdirs()
                                    FileOutputStream(entryFile).use { fos ->
                                        zipIn.copyTo(fos)
                                    }
                                }
                                zipIn.closeEntry()
                                entry = zipIn.nextEntry
                            }
                        }
                    }
                } finally {
                    // 清理加密解密临时文件
                    tempInputFile.delete()
                    if (isEncrypted) {
                        tempZipFile.delete()
                    }
                }
            } catch (e: Exception) {
                tempDir.deleteRecursively()
                tempInputFile.delete()
                throw e
            }

            // 3. 验证备份完整性
            val hasValidData = BACKUP_DIRS.any { dirName ->
                File(tempDir, dirName).exists() && File(tempDir, dirName).isDirectory
            }
            if (!hasValidData) {
                tempDir.deleteRecursively()
                Log.w(TAG, "恢复: ZIP 文件中没有有效数据")
                return false
            }

            // 4. 恢复前备份当前数据（防止恢复失败导致数据丢失）
            val backupDir = File(filesDir, "_restore_backup")
            backupDir.deleteRecursively()
            try {
                for (dirName in BACKUP_DIRS) {
                    val srcDir = File(filesDir, dirName)
                    if (srcDir.exists() && srcDir.isDirectory) {
                        val destDir = File(backupDir, dirName)
                        copyDirectory(srcDir, destDir)
                    }
                }
                val currentDb = File(filesDir, "memories.db")
                if (currentDb.exists()) {
                    currentDb.copyTo(File(backupDir, "memories.db"), overwrite = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复前备份失败（不影响恢复）: ${e.message}")
                backupDir.deleteRecursively()
            }

            // 5. 复制到 App 数据目录
            try {
                for (dirName in BACKUP_DIRS) {
                    val srcDir = File(tempDir, dirName)
                    if (srcDir.exists() && srcDir.isDirectory) {
                        val destDir = File(filesDir, dirName)
                        copyDirectory(srcDir, destDir)
                    }
                }
                val srcDb = File(tempDir, "memories.db")
                if (srcDb.exists()) {
                    val destDb = File(filesDir, "memories.db")
                    srcDb.copyTo(destDb, overwrite = true)
                }
            } catch (e: Exception) {
                // 回滚：恢复备份数据
                Log.e(TAG, "恢复失败，尝试回滚: ${e.message}", e)
                try {
                    for (dirName in BACKUP_DIRS) {
                        val bkDir = File(backupDir, dirName)
                        if (bkDir.exists() && bkDir.isDirectory) {
                            val destDir = File(filesDir, dirName)
                            copyDirectory(bkDir, destDir)
                        }
                    }
                    val bkDb = File(backupDir, "memories.db")
                    if (bkDb.exists()) {
                        bkDb.copyTo(File(filesDir, "memories.db"), overwrite = true)
                    }
                } catch (rollbackErr: Exception) {
                    Log.e(TAG, "回滚也失败: ${rollbackErr.message}", rollbackErr)
                }
                tempDir.deleteRecursively()
                backupDir.deleteRecursively()
                return false
            }

            // 6. 清理临时目录和备份
            tempDir.deleteRecursively()
            backupDir.deleteRecursively()

            Log.i(TAG, "恢复完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败: ${e.message}", e)
            try {
                File(context.filesDir, "_restore_temp").deleteRecursively()
            } catch (_: Exception) {}
            false
        }
    }

    // ── AES 加密/解密 ──

    /**
     * 使用 AES-256-GCM 加密文件，写入到 SAF URI。
     *
     * 文件格式：[salt(16B)][iv(12B)][ciphertext...]
     */
    private fun writeEncryptedFile(context: Context, inputFile: File, outputUri: Uri, password: String) {
        val salt = ByteArray(PBKDF2_SALT_SIZE)
        SecureRandom().nextBytes(salt)

        val iv = ByteArray(GCM_IV_SIZE)
        SecureRandom().nextBytes(iv)

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))

        context.contentResolver.openOutputStream(outputUri)?.use { os ->
            os.write(salt)  // 写入 salt
            os.write(iv)    // 写入 IV
            CipherOutputStream(os, cipher).use { cos ->
                FileInputStream(inputFile).use { it.copyTo(cos) }
            }
        }
    }

    /**
     * 解密文件。
     *
     * 文件格式：[salt(16B)][iv(12B)][ciphertext...]
     */
    private fun decryptFile(inputFile: File, outputFile: File, password: String) {
        FileInputStream(inputFile).use { fis ->
            val salt = ByteArray(PBKDF2_SALT_SIZE)
            fis.read(salt)

            val iv = ByteArray(GCM_IV_SIZE)
            fis.read(iv)

            val key = deriveKey(password, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))

            FileOutputStream(outputFile).use { fos ->
                CipherInputStream(fis, cipher).use { cis ->
                    cis.copyTo(fos)
                }
            }
        }
    }

    /**
     * 从密码和 salt 派生 AES-256 密钥。
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * 检测文件是否为加密文件。
     * 简单启发式：检查文件是否以有效的 ZIP magic bytes 开头。
     * ZIP 文件以 "PK" (0x50 0x4B) 开头。
     */
    private fun isEncryptedFile(file: File): Boolean {
        if (file.length() < 4) return false
        FileInputStream(file).use { fis ->
            val header = ByteArray(4)
            fis.read(header)
            // ZIP magic bytes: 0x50 0x4B 0x03 0x04 or 0x50 0x4B 0x05 0x06 or 0x50 0x4B 0x07 0x08
            return !(header[0] == 0x50.toByte() && header[1] == 0x4B.toByte())
        }
    }

    // ── ZIP 工具方法 ──

    /**
     * 递归压缩目录。
     */
    private fun zipDirectory(dir: File, basePath: String, zipOut: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val entryName = "$basePath/${file.name}"
            if (file.isDirectory) {
                zipDirectory(file, entryName, zipOut)
            } else {
                zipFile(file, entryName, zipOut)
            }
        }
    }

    /**
     * 压缩单个文件。
     */
    private fun zipFile(file: File, entryName: String, zipOut: ZipOutputStream) {
        zipOut.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zipOut) }
        zipOut.closeEntry()
    }

    /**
     * 递归复制目录。
     */
    private fun copyDirectory(src: File, dest: File) {
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        dest.mkdirs()
        src.listFiles()?.forEach { file ->
            val destFile = File(dest, file.name)
            if (file.isDirectory) {
                copyDirectory(file, destFile)
            } else {
                file.copyTo(destFile, overwrite = true)
            }
        }
    }
}