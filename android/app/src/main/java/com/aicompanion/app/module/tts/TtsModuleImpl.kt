package com.aicompanion.app.module.tts

import android.util.Log
import com.aicompanion.app.module.ModuleEventBus
import com.aicompanion.app.speech.SpeechManager

/**
 * TTS 模块实现。
 *
 * 桥接 [SpeechManager]（封装 SherpaTtsEngine），提供文本朗读功能。
 * 通过 ModuleRegistry 注册后供全局访问，支持立即朗读和流式句子朗读两种模式。
 *
 * 注意：此实现依赖外部注入 SpeechManager 实例（在 MainActivity 初始化后设置）。
 * 在 SpeechManager 未就绪时，所有朗读操作静默跳过。
 */
class TtsModuleImpl : TtsModule {

    companion object {
        private const val TAG = "TtsModule"
    }

    /**
     * 外部注入的 SpeechManager 实例。
     * 由 MainActivity 在 VoiceController 初始化完成后调用 [setSpeechManager] 设置。
     */
    @Volatile
    private var speechManager: SpeechManager? = null

    /**
     * TTS 是否被用户手动启用（通过设置页面控制）。
     * 默认 true（有 TTS 引擎时自动可用）。
     */
    @Volatile
    private var userEnabled: Boolean = true

    // ======================== SpeechManager 注入 ========================

    /**
     * 设置 SpeechManager 实例。
     * 在 MainActivity 中 VoiceController 初始化完成后调用。
     *
     * @param manager SpeechManager 实例
     */
    fun setSpeechManager(manager: SpeechManager) {
        speechManager = manager
        Log.d(TAG, "SpeechManager 已注入")
        ModuleEventBus.emit(ModuleEventBus.EventType.TTS_STATE_CHANGED, isAvailable())
    }

    /**
     * 清除 SpeechManager 引用（Activity 销毁时调用）。
     */
    fun clearSpeechManager() {
        speechManager = null
        Log.d(TAG, "SpeechManager 已清除")
    }

    // ======================== TtsModule 接口实现 ========================

    override fun speak(text: String, speed: Float) {
        if (!userEnabled) {
            Log.d(TAG, "TTS 用户禁用，跳过朗读")
            return
        }
        val manager = speechManager
        if (manager == null) {
            Log.w(TAG, "SpeechManager 未注入，跳过朗读")
            return
        }
        if (text.isBlank()) return

        try {
            manager.startSpeaking(text)
            Log.d(TAG, "开始朗读: ${text.take(30)}...")
        } catch (e: Exception) {
            Log.e(TAG, "朗读失败", e)
        }
    }

    override fun stop() {
        try {
            speechManager?.stopSpeaking()
            Log.d(TAG, "朗读已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止朗读失败", e)
        }
    }

    override fun isSpeaking(): Boolean {
        return speechManager?.isSpeaking ?: false
    }

    override fun setEnabled(enabled: Boolean) {
        userEnabled = enabled
        Log.d(TAG, "TTS 用户启用状态: $enabled")
        if (!enabled) {
            // 禁用时停止当前播放
            stop()
        }
        ModuleEventBus.emit(ModuleEventBus.EventType.TTS_STATE_CHANGED, enabled)
    }

    override fun isAvailable(): Boolean {
        val manager = speechManager ?: return false
        return manager.isTtsReady()
    }

    override fun speakStreaming(text: String) {
        if (!userEnabled) {
            Log.d(TAG, "TTS 用户禁用，跳过流式朗读")
            return
        }
        val manager = speechManager
        if (manager == null) {
            Log.w(TAG, "SpeechManager 未注入，跳过流式朗读")
            return
        }
        if (text.isBlank()) return

        try {
            manager.speakSentenceStreaming(text)
        } catch (e: Exception) {
            Log.e(TAG, "流式朗读失败", e)
        }
    }

    override fun stopAndClear() {
        try {
            speechManager?.stopSpeakingAndClear()
            Log.d(TAG, "朗读已停止，队列已清空")
        } catch (e: Exception) {
            Log.e(TAG, "停止并清空朗读失败", e)
        }
    }
}