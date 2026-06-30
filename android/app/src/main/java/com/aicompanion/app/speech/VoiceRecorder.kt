package com.aicompanion.app.speech

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音器。
 * 录音文件使用 AES-256-GCM 加密存储（AndroidX Security Crypto EncryptedFile）。
 * 录音结束后自动加密原始文件并删除明文。
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val DEFAULT_ENCODER = MediaRecorder.AudioEncoder.AAC
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_BIT_RATE = 128000
        private const val DEFAULT_OUTPUT_FORMAT = MediaRecorder.OutputFormat.MPEG_4
        private const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        private const val RECORDING_DIR = "voice_recordings"
        private const val ENCRYPTED_EXT = ".enc"

        /** 缓存的 MasterKey，避免重复创建 */
        @Volatile
        private var cachedMasterKey: MasterKey? = null

        @Synchronized
        private fun getMasterKey(context: Context): MasterKey {
            if (cachedMasterKey == null) {
                cachedMasterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            }
            return cachedMasterKey!!
        }

        /**
         * 清理超过指定时间的过期录音文件。
         * @param context 应用上下文
         * @param maxAgeMs 最大保留时间（毫秒），默认 24 小时
         */
        fun cleanupOldRecordings(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
            try {
                val dir = File(context.cacheDir, RECORDING_DIR)
                if (!dir.exists() || !dir.isDirectory) return
                val now = System.currentTimeMillis()
                var deletedCount = 0
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (now - file.lastModified()) > maxAgeMs) {
                        if (file.delete()) deletedCount++
                    }
                }
                if (deletedCount > 0) {
                    Log.d(TAG, "已清理 $deletedCount 个过期录音文件（maxAgeMs=$maxAgeMs）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理过期录音文件失败: ${e.message}")
            }
        }

        /**
         * 解密加密的录音文件到临时文件，用于播放。
         * 调用方负责在使用后删除临时文件。
         * @return 解密后的临时文件，失败返回 null
         */
        fun decryptToTempFile(context: Context, encryptedPath: String): File? {
            return try {
                val encryptedFile = File(encryptedPath)
                if (!encryptedFile.exists()) {
                    Log.e(TAG, "加密文件不存在: $encryptedPath")
                    return null
                }
                val key = getMasterKey(context)
                val encryptedFileWrapper = EncryptedFile.Builder(
                    context,
                    encryptedFile,
                    key,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()
                val tempFile = File(encryptedFile.parentFile, encryptedFile.nameWithoutExtension)
                encryptedFileWrapper.openFileInput().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "录音文件已解密到临时文件: ${tempFile.name}")
                tempFile
            } catch (e: Exception) {
                Log.e(TAG, "解密录音文件失败: ${e.message}", e)
                null
            }
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    @Volatile var isRecording = false
        private set

    interface Callback {
        fun onStart() = Unit
        fun onComplete(audioFilePath: String)
        fun onError(error: String)
    }

    var callback: Callback? = null

    fun start() {
        if (isRecording) { Log.w(TAG, "已在录制中"); return }
        try {
            val outputDir = File(context.cacheDir, RECORDING_DIR)
            if (!outputDir.exists()) outputDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(outputDir, "voice_$timestamp.m4a")
            outputFile = file

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(DEFAULT_AUDIO_SOURCE)
                setOutputFormat(DEFAULT_OUTPUT_FORMAT)
                setAudioEncoder(DEFAULT_ENCODER)
                setAudioSamplingRate(DEFAULT_SAMPLE_RATE)
                setAudioEncodingBitRate(DEFAULT_BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d(TAG, "音频录制已开始")
            callback?.onStart()
        } catch (e: IOException) {
            Log.e(TAG, "音频录制器初始化失败", e)
            resetRecorder()
            callback?.onError("音频录制器初始化失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "启动录制失败", e)
            resetRecorder()
            callback?.onError("启动录制失败: ${e.message}")
        }
    }

    fun stop(): String? {
        if (!isRecording) { Log.w(TAG, "未在录制中"); return null }
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            val rawFile = outputFile
            if (rawFile != null && rawFile.exists() && rawFile.length() > 0) {
                // 加密录音文件
                val encryptedFile = encryptFile(rawFile)
                // 删除原始未加密文件
                if (!rawFile.delete()) {
                    Log.w(TAG, "无法删除原始录音文件: ${rawFile.absolutePath}")
                }
                if (encryptedFile != null) {
                    val encryptedPath = encryptedFile.absolutePath
                    Log.d(TAG, "录音已加密保存: $encryptedPath")
                    callback?.onComplete(encryptedPath)
                    return encryptedPath
                } else {
                    callback?.onError("录音文件加密失败")
                    return null
                }
            } else {
                callback?.onError("录制文件为空或不存在")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止录制失败", e)
            isRecording = false
            resetRecorder()
            callback?.onError("停止录制失败: ${e.message}")
            return null
        }
    }

    fun cancel() {
        if (!isRecording) { Log.w(TAG, "未在录制中"); return }
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            outputFile?.let { if (it.exists()) it.delete() }
            outputFile = null
        } catch (e: Exception) {
            Log.e(TAG, "取消录制失败", e)
            isRecording = false
            resetRecorder()
        }
    }

    fun getOutputFile(): String? = outputFile?.absolutePath

    fun getDurationMs(): Long {
        if (!isRecording || outputFile == null) return 0
        val fileSize = outputFile?.length() ?: 0
        if (fileSize <= 0) return 0
        return (fileSize * 8 * 1000) / DEFAULT_BIT_RATE
    }

    /**
     * 使用 AES-256-GCM 加密原始录音文件。
     * @return 加密后的文件，失败返回 null
     */
    private fun encryptFile(rawFile: File): File? {
        return try {
            val key = getMasterKey(context)
            val encryptedFile = File(rawFile.parentFile, rawFile.name + ENCRYPTED_EXT)
            val encryptedFileWrapper = EncryptedFile.Builder(
                context,
                encryptedFile,
                key,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            rawFile.inputStream().use { input ->
                encryptedFileWrapper.openFileOutput().use { output ->
                    input.copyTo(output)
                }
            }
            encryptedFile
        } catch (e: Exception) {
            Log.e(TAG, "加密录音文件失败: ${e.message}", e)
            null
        }
    }

    private fun resetRecorder() {
        try { mediaRecorder?.release() } catch (e: Exception) {
                Log.w(TAG, "释放 MediaRecorder 失败（不影响功能）: ${e.message}")
            }
        mediaRecorder = null
        isRecording = false
        outputFile = null
    }

    fun destroy() {
        Log.d(TAG, "释放录制器资源")
        if (isRecording) cancel()
        resetRecorder()
        callback = null
    }
}