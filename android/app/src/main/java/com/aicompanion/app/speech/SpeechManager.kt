package com.aicompanion.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.aicompanion.app.AppConfig
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

class SpeechManager(
    private val context: Context,
    private val lifecycleScope: CoroutineScope
) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    interface SpeechCallback {
        fun onSpeechResult(text: String)
        fun onSpeechError(error: String)
        fun onSpeechStart() = Unit
        fun onSpeechDone() = Unit
    }

    var callback: SpeechCallback? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var sherpaTts: SherpaTtsEngine? = null
    @Volatile var isRecording = false
        private set
    @Volatile var isSpeaking = false
        private set

    /** 流式句子队列：用于逐句 TTS 朗读，保证顺序播放 */
    private val sentenceQueue = mutableListOf<String>()
    private var isProcessingQueue = false

    fun startRecording() {
        if (isRecording) { Log.w(TAG, "已在录音中，忽略重复调用"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val errorMsg = "语音识别服务不可用"
            Log.e(TAG, errorMsg)
            callback?.onSpeechError(errorMsg)
            return
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppConfig.getVoiceRecognitionLang(context))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            speechRecognizer?.startListening(intent)
            isRecording = true
            Log.d(TAG, "语音识别已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败", e)
            callback?.onSpeechError("启动语音识别失败: ${e.message}")
        }
    }

    fun stopRecording() {
        if (!isRecording) { Log.w(TAG, "未在录音中，忽略 stopRecording"); return }
        try { speechRecognizer?.stopListening(); isRecording = false; Log.d(TAG, "语音识别已停止") }
        catch (e: Exception) { Log.e(TAG, "停止语音识别失败", e); isRecording = false }
    }

    fun cancelRecording() {
        if (!isRecording) { Log.w(TAG, "未在录音中，忽略 cancelRecording"); return }
        try { speechRecognizer?.cancel(); isRecording = false; Log.d(TAG, "语音识别已取消") }
        catch (e: Exception) { Log.e(TAG, "取消语音识别失败", e); isRecording = false }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "语音识别就绪") }
            override fun onBeginningOfSpeech() { Log.d(TAG, "检测到语音开始") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "语音结束"); isRecording = false }
            override fun onError(error: Int) {
                isRecording = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音内容"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务繁忙"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                    else -> "未知错误 (code=$error)"
                }
                Log.e(TAG, "语音识别错误: $errorMsg")
                callback?.onSpeechError(errorMsg)
            }
            override fun onResults(results: Bundle?) {
                isRecording = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "语音识别结果: $text")
                if (text.isNotBlank()) callback?.onSpeechResult(text)
                else callback?.onSpeechError("未识别到语音内容")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "语音识别部分结果: ${matches?.firstOrNull()}")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        if (sherpaTts?.isInitialized == true) {
            Log.d(TAG, "SherpaTtsEngine 已初始化")
            onReady?.invoke(true)
            return
        }

        if (sherpaTts == null) {
            sherpaTts = SherpaTtsEngine(context, lifecycleScope).apply {
                callback = object : SherpaTtsEngine.Callback {
                    override fun onInitSuccess() {
                        Log.d(TAG, "SherpaTtsEngine 初始化成功")
                        onReady?.invoke(true)
                    }

                    override fun onInitError(error: String) {
                        Log.e(TAG, "SherpaTtsEngine 初始化失败: $error")
                        this@SpeechManager.callback?.onSpeechError(error)
                        onReady?.invoke(false)
                    }

                    override fun onSynthesisStart() {
                        isSpeaking = true
                        this@SpeechManager.callback?.onSpeechStart()
                    }

                    override fun onSynthesisDone() {
                        // 合成完成，等待播放
                    }

                    override fun onPlayStart() {
                        isSpeaking = true
                    }

                    override fun onPlayDone() {
                        Log.d(TAG, "SherpaTtsEngine 播放完成")
                        isSpeaking = false
                        this@SpeechManager.callback?.onSpeechDone()
                        // 处理队列中的下一句
                        processNextSentence()
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "SherpaTtsEngine 错误: $error")
                        isSpeaking = false
                        this@SpeechManager.callback?.onSpeechError(error)
                        // 出错后继续处理队列中的下一句
                        processNextSentence()
                    }
                }
            }
        }

        sherpaTts?.initialize()
    }

    fun startSpeaking(text: String, utteranceId: String? = null) {
        if (text.isBlank()) { Log.w(TAG, "播放文本为空"); return }
        if (sherpaTts?.isAvailable() != true) {
            Log.w(TAG, "SherpaTtsEngine 不可用（降级模式），跳过 TTS")
            return
        }
        if (sherpaTts?.isInitialized != true) {
            Log.w(TAG, "SherpaTtsEngine 尚未初始化，尝试初始化...")
            initTts { ready ->
                if (ready) {
                    startSpeaking(text, utteranceId)
                } else {
                    callback?.onSpeechError("本地语音合成引擎不可用")
                }
            }
            return
        }

        val speed = AppConfig.getTtsSpeechRate(context)
        sherpaTts?.synthesize(text, speed)
    }

    /**
     * 流式句子朗读：将句子加入队列，按顺序逐个播放。
     * 与 startSpeaking 不同，此方法不会中断当前正在播放的内容。
     */
    fun speakSentenceStreaming(text: String) {
        if (text.isBlank()) return
        if (sherpaTts?.isAvailable() != true) {
            Log.w(TAG, "SherpaTtsEngine 不可用（降级模式），静默跳过 TTS 流式句子")
            return
        }
        if (sherpaTts?.isInitialized != true) {
            Log.w(TAG, "SherpaTtsEngine 尚未初始化，尝试初始化...")
            initTts { ready ->
                if (ready) speakSentenceStreaming(text)
            }
            return
        }
        synchronized(sentenceQueue) {
            sentenceQueue.add(text)
        }
        if (!isProcessingQueue) {
            processNextSentence()
        }
    }

    /** 从队列中取出下一句并播放 */
    private fun processNextSentence() {
        val next: String
        synchronized(sentenceQueue) {
            if (sentenceQueue.isEmpty()) {
                isProcessingQueue = false
                return
            }
            next = sentenceQueue.removeAt(0)
            isProcessingQueue = true
        }
        val speed = AppConfig.getTtsSpeechRate(context)
        sherpaTts?.synthesize(next, speed)
    }

    /** 停止播放并清空句子队列（新消息到达时调用） */
    fun stopSpeakingAndClear() {
        synchronized(sentenceQueue) {
            sentenceQueue.clear()
            isProcessingQueue = false
        }
        stopSpeaking()
    }

    fun stopSpeaking() {
        if (!isSpeaking) return
        sherpaTts?.stopSpeaking()
        isSpeaking = false
        Log.d(TAG, "语音播放已停止")
    }

    /**
     * 语音打断：停止 TTS 播放并清空句子队列。
     * 用于用户开始说话时打断 AI 朗读。
     */
    fun interruptForVoiceInput() {
        Log.d(TAG, "语音打断：用户开始说话，停止 TTS")
        synchronized(sentenceQueue) {
            sentenceQueue.clear()
            isProcessingQueue = false
        }
        stopSpeaking()
    }

    fun destroy() {
        Log.d(TAG, "释放语音资源")
        try { speechRecognizer?.apply { cancel(); destroy() }; speechRecognizer = null; isRecording = false }
        catch (e: Exception) { Log.e(TAG, "释放 SpeechRecognizer 失败", e) }
        try { sherpaTts?.destroy(); sherpaTts = null; isSpeaking = false }
        catch (e: Exception) { Log.e(TAG, "释放 SherpaTtsEngine 失败", e) }
        callback = null
    }
}