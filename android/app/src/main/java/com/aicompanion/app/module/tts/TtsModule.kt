package com.aicompanion.app.module.tts

interface TtsModule {

    /** 朗读文本 */
    fun speak(text: String, speed: Float = 1.0f)

    /** 停止朗读 */
    fun stop()

    /** 是否正在朗读 */
    fun isSpeaking(): Boolean

    /** 设置 TTS 是否启用 */
    fun setEnabled(enabled: Boolean)

    /** TTS 是否可用 */
    fun isAvailable(): Boolean
}