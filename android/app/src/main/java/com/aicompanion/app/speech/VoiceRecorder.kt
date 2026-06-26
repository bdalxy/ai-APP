package com.aicompanion.app.speech

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val DEFAULT_ENCODER = MediaRecorder.AudioEncoder.AAC
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_BIT_RATE = 128000
        private const val DEFAULT_OUTPUT_FORMAT = MediaRecorder.OutputFormat.MPEG_4
        private const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
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
            val outputDir = File(context.cacheDir, "voice_recordings")
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
            val filePath = outputFile?.absolutePath
            if (filePath != null && outputFile?.exists() == true && (outputFile?.length() ?: 0) > 0) {
                callback?.onComplete(filePath)
                return filePath
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