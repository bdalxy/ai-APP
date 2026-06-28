package com.aicompanion.app.module.tts

interface TtsModule {

    /** 朗读文本（立即播放，会中断当前播放） */
    fun speak(text: String, speed: Float = 1.0f)

    /** 停止朗读 */
    fun stop()

    /** 是否正在朗读 */
    fun isSpeaking(): Boolean

    /** 设置 TTS 是否启用 */
    fun setEnabled(enabled: Boolean)

    /** TTS 是否可用 */
    fun isAvailable(): Boolean

    /** 流式句子朗读：将句子加入播放队列，不中断当前播放 */
    fun speakStreaming(text: String)

    /** 停止播放并清空句子队列 */
    fun stopAndClear()
}