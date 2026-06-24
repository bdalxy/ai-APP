package com.aicompanion.app

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份/恢复工具类。
 *
 * 将 App 数据目录（conversations/、memory/、characters/、world_books/）打包为 ZIP 文件，
 * 或从 ZIP 文件恢复数据。
 *
 * 兼容 Android 10+ SAF（Storage Access Framework）。
 */
object DataBackupHelper {

    private const val TAG = "DataBackupHelper"

    /** 需要备份的子目录名称列表 */
    private val BACKUP_DIRS = listOf("conversations", "memory", "characters", "world_books")

    /** 备份文件名格式 */
    private val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 生成备份文件名。
     */
    fun generateFileName(): String {
        return "AICompanion_Backup_${FILE_NAME_FORMAT.format(Date())}.zip"
    }

    /**
     * 备份：将 App 数据目录打包为 ZIP，写入到指定的 URI。
     *
     * @param context 应用上下文。
     * @param outputUri SAF 输出 URI（由用户通过文件选择器选择）。
     * @return 成功返回 true，失败返回 false。
     */
    fun backup(context: Context, outputUri: Uri): Boolean {
        return try {
            val filesDir = context.filesDir
            val existingDirs = BACKUP_DIRS.filter { dirName ->
                File(filesDir, dirName).exists() && File(filesDir, dirName).isDirectory
            }

            if (existingDirs.isEmpty()) {
                Log.w(TAG, "备份: 没有可备份的数据目录")
                return false
            }

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    for (dirName in existingDirs) {
                        val dir = File(filesDir, dirName)
                        zipDirectory(dir, dirName, zipOut)
                    }
                    // 同时备份 memories.db（如果存在）
                    val dbFile = File(filesDir, "memories.db")
                    if (dbFile.exists()) {
                        zipFile(dbFile, "memories.db", zipOut)
                    }
                }
            }

            Log.i(TAG, "备份完成: 已备份 ${existingDirs.size} 个目录")
            true
        } catch (e: Exception) {
            Log.e(TAG, "备份失败: ${e.message}", e)
            false
        }
    }

    /**
     * 恢复：从 ZIP 文件恢复数据到 App 数据目录。
     *
     * @param context 应用上下文。
     * @param inputUri SAF 输入 URI（用户选择的 ZIP 文件）。
     * @return 成功返回 true，失败返回 false。
     */
    fun restore(context: Context, inputUri: Uri): Boolean {
        return try {
            val filesDir = context.filesDir
            val tempDir = File(filesDir, "_restore_temp")
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            // 1. 解压到临时目录
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val entryFile = File(tempDir, entry.name)
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

            // 2. 验证备份完整性
            val hasValidData = BACKUP_DIRS.any { dirName ->
                File(tempDir, dirName).exists() && File(tempDir, dirName).isDirectory
            }
            if (!hasValidData) {
                tempDir.deleteRecursively()
                Log.w(TAG, "恢复: ZIP 文件中没有有效数据")
                return false
            }

            // 3. 复制到 App 数据目录
            for (dirName in BACKUP_DIRS) {
                val srcDir = File(tempDir, dirName)
                if (srcDir.exists() && srcDir.isDirectory) {
                    val destDir = File(filesDir, dirName)
                    copyDirectory(srcDir, destDir)
                }
            }

            // 恢复 memories.db
            val srcDb = File(tempDir, "memories.db")
            if (srcDb.exists()) {
                val destDb = File(filesDir, "memories.db")
                srcDb.copyTo(destDb, overwrite = true)
            }

            // 4. 清理临时目录
            tempDir.deleteRecursively()

            Log.i(TAG, "恢复完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败: ${e.message}", e)
            // 清理临时目录
            try {
                File(context.filesDir, "_restore_temp").deleteRecursively()
            } catch (_: Exception) {}
            false
        }
    }

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