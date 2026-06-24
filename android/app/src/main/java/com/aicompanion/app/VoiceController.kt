package com.aicompanion.app

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.aicompanion.app.databinding.ActivityMainBinding
import com.aicompanion.app.utils.UiThread
import com.aicompanion.app.speech.SpeechManager
import com.aicompanion.app.speech.VoicePlayer
import com.aicompanion.app.speech.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

class VoiceController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val adapter: ChatAdapter,
    private val isStreamingProvider: () -> Boolean,
    private val hasRecordPermission: () -> Boolean,
    private val requestRecordPermission: () -> Unit
) {
    companion object {
        private const val TAG = "VoiceController"
    }

    interface VoiceCallback {
        fun onRecordingOverlayShow()
        fun onRecordingOverlayHide()
        fun onRecordingDurationUpdate(durationStr: String)
        fun onVoiceInputTriggered()
        /** 语音识别完成，自动发送文本 */
        fun onVoiceInputReady(text: String) = Unit
        /** 唤醒词检测到 */
        fun onWakeWordDetected() = Unit
        fun onError(error: String)
    }

    var callback: VoiceCallback? = null

    lateinit var speechManager: SpeechManager
        private set
    lateinit var voiceRecorder: VoiceRecorder
        private set
    lateinit var voicePlayer: VoicePlayer
        private set

    private var wasVoiceInput = false
    private var voiceRecordStartTime: Long = 0L
    private var isVoiceRecordingCancelled = false
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordingDurationRunnable: Runnable? = null

    // ── 唤醒词检测 ──
    private var wakeWordJob: Job? = null
    private var isWakeWordDetectionActive = false
    private val wakeWords = listOf("小星", "小星小星", "hey小星", "你好小星")

    fun init() {
        initSpeechManager()
        initVoiceRecorder()
        initVoicePlayer()
        Log.d(TAG, "语音管理器初始化完成")
    }

    private fun initSpeechManager() {
        speechManager = SpeechManager(context)
        speechManager.callback = object : SpeechManager.SpeechCallback {
            override fun onSpeechResult(text: String) {
                UiThread.run {
                    binding.etInput.setText(text)
                    binding.etInput.setSelection(text.length)
                    if (text.isNotBlank()) {
                        wasVoiceInput = true
                        callback?.onVoiceInputReady(text)
                    }
                }
            }
            override fun onSpeechError(error: String) {
                Log.e(TAG, "语音识别错误: $error")
                UiThread.run {
                    Toast.makeText(context, "语音识别失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onSpeechStart() { Log.d(TAG, "TTS 开始播放") }
            override fun onSpeechDone() { Log.d(TAG, "TTS 播放完成") }
        }
        speechManager.initTts()
    }

    private fun initVoiceRecorder() {
        voiceRecorder = VoiceRecorder(context)
        voiceRecorder.callback = object : VoiceRecorder.Callback {
            override fun onStart() {
                Log.d(TAG, "语音录制开始")
                voiceRecordStartTime = System.currentTimeMillis()
                UiThread.run { callback?.onRecordingOverlayShow() }
                startDurationUpdater()
            }
            override fun onComplete(audioFilePath: String) {
                Log.d(TAG, "语音录制完成: $audioFilePath")
                UiThread.run {
                    stopDurationUpdater()
                    callback?.onRecordingOverlayHide()
                    val durationMs = System.currentTimeMillis() - voiceRecordStartTime
                    if (durationMs < 1000) {
                        Toast.makeText(context, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                        File(audioFilePath).delete()
                    } else {
                        sendVoiceMessage(audioFilePath, durationMs)
                    }
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "语音录制错误: $error")
                UiThread.run {
                    stopDurationUpdater()
                    callback?.onRecordingOverlayHide()
                    Toast.makeText(context, "录制失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initVoicePlayer() {
        voicePlayer = VoicePlayer(context)
        voicePlayer.callback = object : VoicePlayer.Callback {
            override fun onStart() { Log.d(TAG, "语音播放开始") }
            override fun onComplete() {
                Log.d(TAG, "语音播放完成")
                UiThread.run {
                    val pos = adapter.playingPosition
                    if (pos >= 0) {
                        adapter.playingPosition = -1
                        adapter.notifyItemChanged(pos)
                    }
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "语音播放错误: $error")
                UiThread.run {
                    val pos = adapter.playingPosition
                    if (pos >= 0) {
                        adapter.playingPosition = -1
                        adapter.notifyItemChanged(pos)
                    }
                    Toast.makeText(context, "播放失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onVoiceButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isVoiceRecordingCancelled = false
                recordingHandler.postDelayed({
                    if (!isVoiceRecordingCancelled && !isStreamingProvider()) {
                        startVoiceRecord()
                    }
                }, 400)
                return true
            }
            MotionEvent.ACTION_UP -> {
                recordingHandler.removeCallbacksAndMessages(null)
                if (this::voiceRecorder.isInitialized && voiceRecorder.isRecording) {
                    stopVoiceRecord()
                } else if (!isVoiceRecordingCancelled && !isStreamingProvider()) {
                    onVoiceButtonClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recordingHandler.removeCallbacksAndMessages(null)
                if (this::voiceRecorder.isInitialized && voiceRecorder.isRecording) {
                    cancelVoiceRecord()
                }
                return true
            }
        }
        return false
    }

    fun onVoiceButtonClick() {
        if (isStreamingProvider()) {
            Toast.makeText(context, "AI 正在回复中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (speechManager.isRecording) {
            speechManager.stopRecording()
            return
        }
        if (!hasRecordPermission()) {
            requestRecordPermission()
            return
        }
        startVoiceRecognition()
    }

    fun startVoiceRecognition() {
        try {
            // 语音打断：用户开始说话时停止 AI 朗读
            if (speechManager.isSpeaking) {
                speechManager.interruptForVoiceInput()
                Log.d(TAG, "语音打断：停止 TTS 后开始语音识别")
            }
            speechManager.startRecording()
            binding.btnVoice.setColorFilter(Color.parseColor("#FF6B6B"))
            Toast.makeText(context, "正在聆听...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                while (speechManager.isRecording) { delay(200) }
                UiThread.run {
                    binding.btnVoice.clearColorFilter()
                    wasVoiceInput = true
                    callback?.onVoiceInputTriggered()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败", e)
            Toast.makeText(context, "启动语音识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
            binding.btnVoice.clearColorFilter()
        }
    }

    private fun startVoiceRecord() {
        if (!this::voiceRecorder.isInitialized) {
            Toast.makeText(context, "语音引擎未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (isStreamingProvider()) {
            Toast.makeText(context, "AI 正在回复中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // 语音打断：用户开始录音时停止 AI 朗读
            if (speechManager.isSpeaking) {
                speechManager.interruptForVoiceInput()
                Log.d(TAG, "语音打断：停止 TTS 后开始录音")
            }
            voiceRecorder.start()
            isVoiceRecordingCancelled = false
            binding.btnVoice.setColorFilter(Color.parseColor("#FF6B6B"))
        } catch (e: Exception) {
            Log.e(TAG, "启动语音录制失败", e)
            Toast.makeText(context, "启动语音录制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            binding.btnVoice.clearColorFilter()
        }
    }

    private fun stopVoiceRecord() {
        if (!voiceRecorder.isRecording) return
        try {
            voiceRecorder.stop()
            binding.btnVoice.clearColorFilter()
        } catch (e: Exception) {
            Log.e(TAG, "停止语音录制失败", e)
            stopDurationUpdater()
            callback?.onRecordingOverlayHide()
            binding.btnVoice.clearColorFilter()
        }
    }

    fun cancelVoiceRecord() {
        if (!voiceRecorder.isRecording) return
        try {
            voiceRecorder.cancel()
            stopDurationUpdater()
            callback?.onRecordingOverlayHide()
            binding.btnVoice.clearColorFilter()
            Toast.makeText(context, "已取消录音", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "取消语音录制失败", e)
            stopDurationUpdater()
            callback?.onRecordingOverlayHide()
            binding.btnVoice.clearColorFilter()
        }
    }

    private fun sendVoiceMessage(filePath: String, durationMs: Long) {
        val msg = Message(
            content = "",
            isUser = true,
            msgType = Message.MsgType.VOICE,
            voiceFilePath = filePath,
            voiceDurationMs = durationMs,
            voicePlayed = false
        )
        adapter.addMessage(msg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        Log.d(TAG, "语音消息已发送: $filePath, 时长=${durationMs}ms")
    }

    fun playVoiceMessage(filePath: String) {
        if (!this::voicePlayer.isInitialized) {
            Toast.makeText(context, "语音引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (voicePlayer.state == VoicePlayer.State.PAUSED && voicePlayer.getCurrentFilePath() == filePath) {
            voicePlayer.resume()
            return
        }
        if (voicePlayer.state == VoicePlayer.State.PLAYING || voicePlayer.state == VoicePlayer.State.PAUSED) {
            val oldPos = adapter.playingPosition
            if (oldPos >= 0) {
                adapter.playingPosition = -1
                adapter.notifyItemChanged(oldPos)
            }
            voicePlayer.stop()
        }
        val messages = adapter.getMessages()
        val pos = messages.indexOfFirst { it.msgType == Message.MsgType.VOICE && it.voiceFilePath == filePath }
        if (pos >= 0) {
            if (!messages[pos].voicePlayed) {
                val updated = messages[pos].copy(voicePlayed = true)
                adapter.updateMessage(pos, updated)
            }
            adapter.playingPosition = pos
            adapter.notifyItemChanged(pos)
        }
        voicePlayer.play(filePath)
    }

    fun pauseVoiceMessage() {
        if (voicePlayer.state == VoicePlayer.State.PLAYING) {
            voicePlayer.pause()
            val pos = adapter.playingPosition
            if (pos >= 0) {
                adapter.playingPosition = -1
                adapter.notifyItemChanged(pos)
            }
        }
    }

    fun speakAIContentIfNeeded(content: String) {
        val autoRead = AppConfig.getAutoReadAloud(context)
        if (autoRead && content.isNotBlank()) {
            speechManager.startSpeaking(content)
        } else if (wasVoiceInput && content.isNotBlank()) {
            wasVoiceInput = false
            speechManager.startSpeaking(content)
        }
    }

    /** 流式句子朗读：将句子加入 TTS 队列，顺序播放 */
    fun speakSentenceStreaming(sentence: String) {
        if (sentence.isBlank()) return
        speechManager.speakSentenceStreaming(sentence)
    }

    /** 停止 TTS 播放并清空句子队列（新消息到达时调用） */
    fun stopTtsAndClear() {
        speechManager.stopSpeakingAndClear()
    }

    private fun startDurationUpdater() {
        recordingDurationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - voiceRecordStartTime
                val totalSeconds = elapsed / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val durationStr = "$minutes:${seconds.toString().padStart(2, '0')}"
                callback?.onRecordingDurationUpdate(durationStr)
                recordingHandler.postDelayed(this, 200)
            }
        }
        recordingHandler.post(recordingDurationRunnable!!)
    }

    private fun stopDurationUpdater() {
        recordingDurationRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingDurationRunnable = null
    }

    // ── 唤醒词检测 ──

    /**
     * 启动唤醒词持续监听。
     * 在后台循环使用 SpeechRecognizer 监听，检测到唤醒词后触发完整语音识别。
     * 唤醒词列表：小星、小星小星、hey小星、你好小星
     */
    fun startWakeWordDetection() {
        if (isWakeWordDetectionActive) {
            Log.d(TAG, "唤醒词检测已在运行中")
            return
        }
        if (!hasRecordPermission()) {
            Log.w(TAG, "无录音权限，无法启动唤醒词检测")
            return
        }
        isWakeWordDetectionActive = true
        Log.d(TAG, "唤醒词检测已启动，监听词: $wakeWords")

        wakeWordJob = lifecycleScope.launch {
            while (isWakeWordDetectionActive && isActive) {
                try {
                    // 跳过正在流式回复或 TTS 播放中的情况
                    if (isStreamingProvider() || speechManager.isSpeaking) {
                        delay(500)
                        continue
                    }

                    // 使用 SpeechManager 进行一轮识别
                    startWakeWordRound()
                    // 等待识别完成（最多 3 秒）
                    var waited = 0L
                    while (speechManager.isRecording && waited < 3000) {
                        delay(100)
                        waited += 100
                    }
                    // 轮次间隔
                    delay(500)
                } catch (e: Exception) {
                    Log.e(TAG, "唤醒词检测循环异常: ${e.message}")
                    delay(1000)
                }
            }
            Log.d(TAG, "唤醒词检测循环已退出")
        }
    }

    /**
     * 执行一轮唤醒词检测。
     * 启动语音识别，在结果回调中检查是否包含唤醒词。
     */
    private fun startWakeWordRound() {
        if (!this::speechManager.isInitialized) return
        if (speechManager.isRecording) return

        // 临时设置唤醒词检测回调
        val originalCallback = speechManager.callback
        speechManager.callback = object : SpeechManager.SpeechCallback {
            override fun onSpeechResult(text: String) {
                Log.d(TAG, "唤醒词轮次识别结果: $text")
                val detected = wakeWords.any { wakeWord ->
                    text.contains(wakeWord, ignoreCase = true)
                }
                if (detected) {
                    Log.i(TAG, "✅ 检测到唤醒词: $text")
                    UiThread.run {
                        callback?.onWakeWordDetected()
                        // 唤醒后自动触发语音识别
                        if (!isStreamingProvider()) {
                            startVoiceRecognition()
                        }
                    }
                }
                // 恢复原始回调
                speechManager.callback = originalCallback
            }

            override fun onSpeechError(error: String) {
                Log.d(TAG, "唤醒词轮次识别错误: $error")
                speechManager.callback = originalCallback
            }

            override fun onSpeechStart() {}
            override fun onSpeechDone() {}
        }

        try {
            speechManager.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "启动唤醒词识别轮次失败: ${e.message}")
            speechManager.callback = originalCallback
        }
    }

    /**
     * 停止唤醒词持续监听。
     */
    fun stopWakeWordDetection() {
        isWakeWordDetectionActive = false
        wakeWordJob?.cancel()
        wakeWordJob = null
        Log.d(TAG, "唤醒词检测已停止")
    }

    /**
     * 唤醒词检测是否正在运行。
     */
    fun isWakeWordDetectionRunning(): Boolean = isWakeWordDetectionActive

    fun destroy() {
        stopWakeWordDetection()
        if (this::speechManager.isInitialized) { speechManager.destroy() }
        if (this::voiceRecorder.isInitialized) { voiceRecorder.destroy() }
        if (this::voicePlayer.isInitialized) { voicePlayer.destroy() }
        stopDurationUpdater()
        recordingHandler.removeCallbacksAndMessages(null)
        callback = null
        Log.d(TAG, "语音资源释放完成")
    }
}