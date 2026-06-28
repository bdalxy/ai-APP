package com.aicompanion.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.aicompanion.app.AppConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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

        // ── Matcha 模型路径 ──
        private const val MATCHA_DIR = "matcha-icefall-zh-baker"
        private const val MATCHA_ACOUSTIC = "model-steps-3.onnx"
        private const val MATCHA_VOCODER = "vocos-22khz-univ.onnx"

        // ── VITS 模型路径 ──
        private const val VITS_DIR = "vits-aishell3"
        private const val VITS_MODEL = "model.onnx"

        // ── 共享文件 ──
        private const val TOKENS = "tokens.txt"
        private const val LEXICON = "lexicon.txt"
    }

    /** 当前使用的 TTS 模型类型 */
    enum class ModelType { VITS, MATCHA }

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
    private val audioTrackLock = Any()
    private val isSpeaking = AtomicBoolean(false)
    private val initScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job] ?: Job()) + Dispatchers.IO)
    private val playScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job] ?: Job()) + Dispatchers.IO)
    @Volatile var isInitialized = false
        private set
    @Volatile var isSynthesizing = false
        private set

    /** 降级模式：模型加载失败时设为 true，此时 TTS 不可用 */
    @Volatile var isFallbackMode = false
        private set

    /** 当前加载的模型类型，初始化成功后设置 */
    @Volatile var currentModelType: ModelType? = null
        private set

    /** 初始化竞态防护：防止多线程同时触发初始化 */
    private val isInitializing = AtomicBoolean(false)

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
        // CAS 防止多线程重复初始化
        if (!isInitializing.compareAndSet(false, true)) {
            Log.d(TAG, "SherpaTtsEngine 初始化已在进行中，跳过重复调用")
            return
        }

        initScope.launch {
            try {
                val assetManager = context.assets
                Log.i(TAG, "开始初始化 Sherpa-ONNX TTS 引擎...")

                // ── 根据用户偏好 + 可用性选择模型 ──
                val modelPref = AppConfig.getTtsModelType(context)
                val hasVits = try {
                    assetManager.open("$VITS_DIR/$VITS_MODEL").close()
                    true
                } catch (e: Exception) { false }
                val hasMatcha = try {
                    assetManager.open("$MATCHA_DIR/$MATCHA_ACOUSTIC").close()
                    true
                } catch (e: Exception) { false }

                when (modelPref) {
                    AppConfig.TTS_MODEL_VITS -> {
                        if (hasVits) initVitsModel(assetManager)
                        else {
                            Log.w(TAG, "用户选择 VITS 但模型不可用，进入降级模式")
                            isFallbackMode = true
                            withContext(Dispatchers.Main) {
                                callback?.onInitError("VITS 模型不存在，请在设置中切换模型或下载 VITS 模型")
                            }
                        }
                    }
                    AppConfig.TTS_MODEL_MATCHA -> {
                        if (hasMatcha) initMatchaModel(assetManager)
                        else {
                            Log.w(TAG, "用户选择 Matcha 但模型不可用，进入降级模式")
                            isFallbackMode = true
                            withContext(Dispatchers.Main) {
                                callback?.onInitError("Matcha 模型不存在，请在设置中切换模型")
                            }
                        }
                    }
                    else -> {
                        // 自动检测：VITS 优先，Matcha 回退
                        if (hasVits) initVitsModel(assetManager)
                        else if (hasMatcha) initMatchaModel(assetManager)
                        else {
                            Log.w(TAG, "无可用 TTS 模型，进入降级模式")
                            isFallbackMode = true
                            withContext(Dispatchers.Main) {
                                callback?.onInitError("未找到任何 TTS 模型文件，语音朗读已禁用")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa-ONNX TTS 引擎初始化失败，进入降级模式", e)
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("本地 TTS 引擎初始化失败，语音朗读已禁用")
                }
            } finally {
                isInitializing.set(false)
            }
        }
    }

    /** 初始化 VITS (aishell3) 模型 */
    private suspend fun initVitsModel(assetManager: android.content.res.AssetManager) {
        try {
            Log.i(TAG, "使用 VITS (aishell3) 模型...")

            // 确定 lexicon 和 tokens 路径：优先 VITS 目录，回退 Matcha 目录
            val vitsLexicon = "$VITS_DIR/$LEXICON"
            val vitsTokens = "$VITS_DIR/$TOKENS"
            val matchaLexicon = "$MATCHA_DIR/$LEXICON"
            val matchaTokens = "$MATCHA_DIR/$TOKENS"

            val lexiconPath = resolveAssetPath(assetManager, vitsLexicon, matchaLexicon)
            val tokensPath = resolveAssetPath(assetManager, vitsTokens, matchaTokens)

            // 验证必要文件
            try {
                assetManager.open("$VITS_DIR/$VITS_MODEL").close()
                assetManager.open(lexiconPath).close()
                assetManager.open(tokensPath).close()
            } catch (e: Exception) {
                Log.e(TAG, "VITS 模型文件不完整: ${e.message}")
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("VITS 模型文件不完整，语音朗读已禁用")
                }
                return
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$VITS_DIR/$VITS_MODEL",
                        lexicon = lexiconPath,
                        tokens = tokensPath,
                        dataDir = MATCHA_DIR,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )

            try {
                tts = OfflineTts(assetManager = assetManager, config = config)
            } catch (e: Exception) {
                Log.e(TAG, "VITS 模型创建失败: ${e.message}")
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("VITS 模型加载失败，语音朗读已禁用")
                }
                return
            }

            currentModelType = ModelType.VITS
            isInitialized = true
            Log.i(TAG, "VITS (aishell3) TTS 引擎初始化成功, 采样率=${tts?.sampleRate()}")

            withContext(Dispatchers.Main) {
                callback?.onInitSuccess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "VITS 模型初始化异常", e)
            isFallbackMode = true
            withContext(Dispatchers.Main) {
                callback?.onInitError("VITS 模型初始化失败，语音朗读已禁用")
            }
        }
    }

    /** 初始化 Matcha (baker) 模型（回退方案） */
    private suspend fun initMatchaModel(assetManager: android.content.res.AssetManager) {
        try {
            Log.i(TAG, "使用 Matcha (baker) 模型...")

            val acousticModel = "$MATCHA_DIR/$MATCHA_ACOUSTIC"
            val lexicon = "$MATCHA_DIR/$LEXICON"
            val tokens = "$MATCHA_DIR/$TOKENS"

            try {
                assetManager.open(acousticModel).close()
                assetManager.open(lexicon).close()
                assetManager.open(tokens).close()
                assetManager.open(MATCHA_VOCODER).close()
            } catch (e: Exception) {
                Log.e(TAG, "Matcha 模型文件不完整: ${e.message}")
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("TTS 模型文件不完整，语音朗读已禁用")
                }
                return
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = acousticModel,
                        vocoder = MATCHA_VOCODER,
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
                Log.e(TAG, "Matcha 模型创建失败，进入降级模式: ${e.message}")
                isFallbackMode = true
                withContext(Dispatchers.Main) {
                    callback?.onInitError("TTS 模型加载失败，语音朗读已禁用")
                }
                return
            }

            currentModelType = ModelType.MATCHA
            isInitialized = true
            Log.i(TAG, "Matcha (baker) TTS 引擎初始化成功, 采样率=${tts?.sampleRate()}")

            withContext(Dispatchers.Main) {
                callback?.onInitSuccess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Matcha 模型初始化异常", e)
            isFallbackMode = true
            withContext(Dispatchers.Main) {
                callback?.onInitError("本地 TTS 引擎初始化失败，语音朗读已禁用")
            }
        }
    }

    /**
     * 解析 assets 路径：优先使用 primaryPath，不存在时回退 fallbackPath。
     * 若两者都不存在则返回 primaryPath（让后续验证报错）。
     */
    private fun resolveAssetPath(
        assetManager: android.content.res.AssetManager,
        primaryPath: String,
        fallbackPath: String
    ): String {
        return try {
            assetManager.open(primaryPath).close()
            primaryPath
        } catch (e: Exception) {
            Log.d(TAG, "Asset 路径回退: $primaryPath -> $fallbackPath")
            fallbackPath
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
            synchronized(audioTrackLock) {
                audioTrack = track
            }

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
            synchronized(audioTrackLock) {
                audioTrack = null
            }

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
        synchronized(audioTrackLock) {
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
        isInitializing.set(false)
        callback = null
    }
}