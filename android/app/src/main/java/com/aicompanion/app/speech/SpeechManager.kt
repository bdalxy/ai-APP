package com.aicompanion.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aicompanion.app.AppConfig
import java.util.Locale
import java.util.UUID

class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_PITCH = 1.0f
    }

    interface SpeechCallback {
        fun onSpeechResult(text: String)
        fun onSpeechError(error: String)
        fun onSpeechStart() = Unit
        fun onSpeechDone() = Unit
    }

    var callback: SpeechCallback? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile var isRecording = false
        private set
    @Volatile var isSpeaking = false
        private set

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
        if (tts != null) { Log.d(TAG, "TTS 引擎已初始化"); onReady?.invoke(ttsReady); return }
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文 TTS 不可用，使用默认语言")
                    tts?.setLanguage(Locale.getDefault())
                }
                val rate = AppConfig.getTtsSpeechRate(context)
                val pitch = AppConfig.getTtsPitch(context)
                tts?.setSpeechRate(rate)
                tts?.setPitch(pitch)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { Log.d(TAG, "TTS 开始播放"); isSpeaking = true; callback?.onSpeechStart() }
                    override fun onDone(utteranceId: String?) { Log.d(TAG, "TTS 播放完成"); isSpeaking = false; callback?.onSpeechDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { Log.e(TAG, "TTS 播放错误"); isSpeaking = false; callback?.onSpeechError("语音合成播放出错") }
                    override fun onError(utteranceId: String?, errorCode: Int) { Log.e(TAG, "TTS 播放错误, errorCode=$errorCode"); isSpeaking = false; callback?.onSpeechError("语音合成播放出错 (code=$errorCode)") }
                })
                Log.d(TAG, "TTS 引擎初始化成功")
            } else { Log.e(TAG, "TTS 引擎初始化失败"); callback?.onSpeechError("语音合成引擎初始化失败") }
            onReady?.invoke(ttsReady)
        }
    }

    fun startSpeaking(text: String, utteranceId: String? = null) {
        if (text.isBlank()) { Log.w(TAG, "播放文本为空"); return }
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS 引擎尚未初始化")
            initTts { ready -> if (ready) startSpeaking(text, utteranceId) else callback?.onSpeechError("语音合成引擎初始化失败") }
            return
        }
        try { tts?.setSpeechRate(AppConfig.getTtsSpeechRate(context)); tts?.setPitch(AppConfig.getTtsPitch(context)) }
        catch (e: Exception) { Log.w(TAG, "更新 TTS 参数失败: ${e.message}") }
        if (isSpeaking) stopSpeaking()
        val id = utteranceId ?: UUID.randomUUID().toString()
        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.SUCCESS) Log.d(TAG, "TTS 开始播放")
            else { Log.e(TAG, "TTS 播放失败"); callback?.onSpeechError("语音合成播放失败") }
        } catch (e: Exception) { Log.e(TAG, "TTS 播放异常", e); callback?.onSpeechError("语音合成播放异常: ${e.message}") }
    }

    fun stopSpeaking() {
        if (!isSpeaking) return
        try { tts?.stop(); isSpeaking = false; Log.d(TAG, "TTS 播放已停止") }
        catch (e: Exception) { Log.e(TAG, "停止 TTS 播放失败", e); isSpeaking = false }
    }

    fun destroy() {
        Log.d(TAG, "释放语音资源")
        try { speechRecognizer?.apply { cancel(); destroy() }; speechRecognizer = null; isRecording = false }
        catch (e: Exception) { Log.e(TAG, "释放 SpeechRecognizer 失败", e) }
        try { tts?.apply { stop(); shutdown() }; tts = null; ttsReady = false; isSpeaking = false }
        catch (e: Exception) { Log.e(TAG, "释放 TTS 引擎失败", e) }
        callback = null
    }
}