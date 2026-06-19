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
    @Volatile var state: State = State.IDLE
        private set
    private var currentFilePath: String? = null

    fun play(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) { callback?.onError("音频文件不存在: $filePath"); return }
        if (!file.canRead()) { callback?.onError("无法读取音频文件: $filePath"); return }
        if (state == State.PLAYING || state == State.PAUSED) { Log.d(TAG, "释放旧播放资源"); releasePlayer() }
        try {
            currentFilePath = filePath
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                setOnPreparedListener { mp -> mp.start(); state = State.PLAYING; callback?.onStart() }
                setOnCompletionListener { state = State.COMPLETED; callback?.onComplete() }
                setOnErrorListener { _, what, extra ->
                    state = State.IDLE; callback?.onError("音频播放错误 (what=$what, extra=$extra)"); true
                }
                prepareAsync()
            }
        } catch (e: IOException) { releasePlayer(); callback?.onError("播放音频文件失败: ${e.message}") }
        catch (e: Exception) { releasePlayer(); callback?.onError("播放音频异常: ${e.message}") }
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

    fun destroy() { Log.d(TAG, "释放播放器资源"); releasePlayer(); state = State.IDLE; callback = null }
}