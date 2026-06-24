package com.aicompanion.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SherpaTtsEngine(
    private val context: Context,
    private val lifecycleScope: CoroutineScope
) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val MODEL_DIR = "matcha-icefall-zh-baker"
        private const val ACOUSTIC_MODEL = "model-steps-3.onnx"
        private const val VOCODER = "vocos-22khz-univ.onnx"
        private const val LEXICON = "lexicon.txt"
    }

    interface Callback {
        fun onInitSuccess() = Unit
        fun onInitError(error: String) = Unit
        fun onSynthesisStart() = Unit
        fun onSynthesisDone() = Unit
        fun onPlayStart() = Unit
        fun onPlayDone() = Unit
        fun onError(error: String) = Unit
    }

    var callback: Callback? = null

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val isSpeaking = AtomicBoolean(false)
    private val initScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.IO)
    private val playScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.IO)
    @Volatile var isInitialized = false
        private set
    @Volatile var isSynthesizing = false
        private set

    /** 降级模式：模型加载失败时设为 true，此时 TTS 不可用 */
    @Volatile var isFallbackMode = false
        private set

    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "SherpaTtsEngine 已初始化")
            callback?.onInitSuccess()
            return
        }
        if (isFallbackMode) {
            Log.w(TAG, "SherpaTtsEngine 处于降级模式，跳过初始化")
            callback?.onInitError("TTS 模型不可用，语音朗读已禁用")
            return
        }

        initScope.launch {
            try {
                Log.i(TAG, "开始初始化 Sherpa-ONNX TTS 引擎...")
                val assetManager = context.assets

                // 验证模型文件存在
                val modelDir = "$MODEL_DIR"
                val acousticModel = "$modelDir/$ACOUSTIC_MODEL"
                val lexicon = "$modelDir/$LEXICON"
                val tokens = "$modelDir/tokens.txt"

                try {
                    assetManager.open(acousticModel).close()
                    assetManager.open(lexicon).close()
                    assetManager.open(tokens).close()
                    assetManager.open(VOCODER).close()
                } catch (e: Exception) {
                    Log.e(TAG, "模型文件不完整: ${e.message}")
                    isFallbackMode = true
                    withContext(Dispatchers.Main) {
                        callback?.onInitError("TTS 模型文件不完整，语音朗读已禁用")
                    }
                    return@launch
                }

                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = acousticModel,
                            vocoder = VOCODER,
                            lexicon = lexicon,
                            tokens = tokens,
                            dataDir = "",
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )

                try {
                    tts = OfflineTts(assetManager = assetManager, config = config)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS 模型创建失败，进入降级模式: ${e.message}")
                    isFallbackMode = true
                    withContext(Dispatchers.Main) {
                        callback?.onInitError("TTS 模型加载失败，语音朗读已禁用")
                    }
                    return@launch
                }

                isInitialized = true
                Log.i(TAG, "Sherpa-ONNX TTS 引擎初始化成功, 采样率=${tts?.sampleRate()}")

                withContext(Dispatchers.Main) {
                    callback?.onInitSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa-ONNX TTS 引擎初始化失败，进入降级模式", e)
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("本地 TTS 引擎初始化失败，语音朗读已禁用")
                }
            }
        }
    }

    fun synthesize(text: String, speed: Float = 1.0f) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS 引擎未初始化，尝试初始化...")
            callback?.onError("TTS 引擎未初始化")
            initialize()
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "合成文本为空")
            return
        }

        if (isSpeaking.get()) {
            Log.d(TAG, "正在播放中，先停止")
            stopSpeaking()
        }

        isSynthesizing = true
        isSpeaking.set(true)

        playScope.launch {
            try {
                Log.d(TAG, "开始合成: ${text.take(50)}...")
                withContext(Dispatchers.Main) { callback?.onSynthesisStart() }

                val genConfig = GenerationConfig(speed = speed)
                val audio = withContext(Dispatchers.IO) {
                    tts?.generateWithConfig(text = text, config = genConfig)
                }

                if (audio == null || audio.samples.isEmpty()) {
                    Log.e(TAG, "合成结果为空")
                    isSynthesizing = false
                    isSpeaking.set(false)
                    withContext(Dispatchers.Main) {
                        callback?.onError("语音合成失败，结果为空")
                    }
                    return@launch
                }

                Log.d(TAG, "合成完成, samples=${audio.samples.size}, sampleRate=${audio.sampleRate}")
                isSynthesizing = false
                withContext(Dispatchers.Main) { callback?.onSynthesisDone() }

                // 播放音频
                playAudio(audio.samples, audio.sampleRate)
            } catch (e: CancellationException) {
                Log.d(TAG, "合成任务被取消")
                isSynthesizing = false
                isSpeaking.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "语音合成异常", e)
                isSynthesizing = false
                isSpeaking.set(false)
                withContext(Dispatchers.Main) {
                    callback?.onError("语音合成异常: ${e.message}")
                }
            }
        }
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        try {
            val bufLength = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            val attr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRate)
                .build()

            val track = AudioTrack(
                attr, format, bufLength, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack = track

            track.play()
            withContext(Dispatchers.Main) { callback?.onPlayStart() }

            // 分块写入，避免一次性写入大量数据
            val chunkSize = maxOf(bufLength / 2, 1024)
            var offset = 0
            while (offset < samples.size && isSpeaking.get()) {
                val remaining = samples.size - offset
                val size = minOf(chunkSize, remaining)
                track.write(samples, offset, size, AudioTrack.WRITE_BLOCKING)
                offset += size
            }

            track.stop()
            track.release()
            audioTrack = null

            isSpeaking.set(false)
            withContext(Dispatchers.Main) { callback?.onPlayDone() }
            Log.d(TAG, "播放完成")
        } catch (e: Exception) {
            Log.e(TAG, "音频播放异常", e)
            audioTrack?.release()
            audioTrack = null
            isSpeaking.set(false)
            withContext(Dispatchers.Main) {
                callback?.onError("音频播放异常: ${e.message}")
            }
        }
    }

    fun stopSpeaking() {
        isSpeaking.set(false)
        try {
            audioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "停止播放异常: ${e.message}")
        }
    }

    fun isSpeaking(): Boolean = isSpeaking.get()

    /** 返回 TTS 是否可用（初始化成功且未进入降级模式） */
    fun isAvailable(): Boolean = isInitialized && !isFallbackMode

    fun destroy() {
        Log.d(TAG, "释放 SherpaTtsEngine 资源")
        stopSpeaking()
        initScope.cancel()
        playScope.cancel()
        try {
            tts?.release()
            tts = null
        } catch (e: Exception) {
            Log.w(TAG, "释放 TTS 异常: ${e.message}")
        }
        isInitialized = false
        callback = null
    }
}