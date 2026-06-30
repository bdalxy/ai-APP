package com.aicompanion.app.speech

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

class VoicePlayer(private val context: Context) {

    companion object { private const val TAG = "VoicePlayer" }

    enum class State { IDLE, PLAYING, PAUSED, STOPPED, COMPLETED }

    interface Callback {
        fun onStart() = Unit
        fun onPause() = Unit
        fun onResume() = Unit
        fun onComplete() = Unit
        fun onError(error: String) = Unit
    }

    var callback: Callback? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var destroyed = false
    @Volatile var state: State = State.IDLE
        private set
    private var currentFilePath: String? = null
    /** 解密后的临时文件（播放完成后需要删除） */
    private var decryptedTempFile: File? = null

    /**
     * 播放音频文件。
     * 如果文件是加密的（.enc 后缀），会先解密到临时文件再播放，播放完成后自动清理临时文件。
     */
    fun play(filePath: String) {
        if (destroyed) return

        // 处理加密文件：先解密到临时文件
        val actualPlayPath = if (filePath.endsWith(".enc")) {
            val tempFile = VoiceRecorder.decryptToTempFile(context, filePath)
            if (tempFile == null) {
                callback?.onError("音频文件解密失败: $filePath")
                return
            }
            // 清理旧的临时文件
            decryptedTempFile?.let { if (it.exists()) it.delete() }
            decryptedTempFile = tempFile
            tempFile.absolutePath
        } else {
            filePath
        }

        val file = File(actualPlayPath)
        if (!file.exists()) { callback?.onError("音频文件不存在: $actualPlayPath"); return }
        if (!file.canRead()) { callback?.onError("无法读取音频文件: $actualPlayPath"); return }
        if (state == State.PLAYING || state == State.PAUSED) { Log.d(TAG, "释放旧播放资源"); releasePlayer() }
        try {
            currentFilePath = filePath
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                setOnPreparedListener { mp ->
                    if (destroyed) { mp.release(); return@setOnPreparedListener }
                    mp.start(); state = State.PLAYING; callback?.onStart()
                }
                setOnCompletionListener {
                    if (destroyed) return@setOnCompletionListener
                    state = State.COMPLETED
                    cleanupDecryptedTemp()
                    callback?.onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    state = State.IDLE
                    cleanupDecryptedTemp()
                    callback?.onError("音频播放错误 (what=$what, extra=$extra)"); true
                }
                prepareAsync()
            }
        } catch (e: IOException) { releasePlayer(); cleanupDecryptedTemp(); callback?.onError("播放音频文件失败: ${e.message}") }
        catch (e: Exception) { releasePlayer(); cleanupDecryptedTemp(); callback?.onError("播放音频异常: ${e.message}") }
    }

    fun pause() {
        if (state != State.PLAYING) return
        try { mediaPlayer?.pause(); state = State.PAUSED; callback?.onPause() }
        catch (e: Exception) { callback?.onError("暂停播放失败: ${e.message}") }
    }

    fun resume() {
        if (state != State.PAUSED) return
        try { mediaPlayer?.start(); state = State.PLAYING; callback?.onResume() }
        catch (e: Exception) { callback?.onError("恢复播放失败: ${e.message}") }
    }

    fun stop() {
        if (state == State.IDLE || state == State.STOPPED) return
        try { releasePlayer(); state = State.STOPPED }
        catch (e: Exception) { state = State.IDLE }
    }

    fun togglePlayPause() { when (state) { State.PLAYING -> pause(); State.PAUSED -> resume(); else -> {} } }

    fun getCurrentPosition(): Int = try { mediaPlayer?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun getDuration(): Int = try { mediaPlayer?.duration ?: 0 } catch (e: Exception) { 0 }
    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying ?: false } catch (e: Exception) { false }
    fun getCurrentFilePath(): String? = currentFilePath

    private fun releasePlayer() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() }; mediaPlayer = null; currentFilePath = null }
        catch (e: Exception) { mediaPlayer = null; currentFilePath = null }
    }

    /** 清理解密后的临时文件 */
    private fun cleanupDecryptedTemp() {
        decryptedTempFile?.let {
            if (it.exists()) {
                if (it.delete()) {
                    Log.d(TAG, "已删除解密临时文件: ${it.name}")
                } else {
                    Log.w(TAG, "无法删除解密临时文件: ${it.name}")
                }
            }
            decryptedTempFile = null
        }
    }

    fun destroy() {
        Log.d(TAG, "释放播放器资源")
        destroyed = true
        releasePlayer()
        cleanupDecryptedTemp()
        state = State.IDLE
        callback = null
    }
}