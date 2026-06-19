package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppConfig {
    private const val PREFS_NAME = "app_prefs"
    private const val SECURE_PREFS_NAME = "ai_companion_secure_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TOKEN_PRESET = "token_preset"
    private const val KEY_MODEL = "model"
    private const val KEY_CONTEXT_SIZE = "context_size"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_EXAMPLE_DIALOGUES = "example_dialogues"
    private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_AUTO_READ_ALOUD = "auto_read_aloud"
    private const val KEY_VOICE_RECOGNITION_LANG = "voice_recognition_lang"
    const val DEFAULT_TTS_SPEECH_RATE = 1.0f
    const val DEFAULT_TTS_PITCH = 1.0f
    const val DEFAULT_VOICE_RECOGNITION_LANG = "zh-CN"
    private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
    private const val KEY_PROACTIVE_INTERVAL = "proactive_interval"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"
    val INTERVAL_OPTIONS = arrayOf("每1小时", "每2小时", "每3小时", "每6小时", "每12小时", "每天")
    val INTERVAL_MS = longArrayOf(3600000L, 7200000L, 10800000L, 21600000L, 43200000L, 86400000L)
    val DEFAULT_INTERVAL_MS = 10800000L
    val MIN_INTERVAL_MS = 1800000L
    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null
    private val securePrefsLock = Any()
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return cachedSecurePrefs ?: synchronized(securePrefsLock) {
            cachedSecurePrefs ?: run {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedSecurePrefs = it }
            }
        }
    }
    fun getApiKey(context: Context): String {
        return getSecurePrefs(context).getString(KEY_API_KEY, "") ?: ""
    }
    fun setApiKey(context: Context, key: String) {
        getSecurePrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }
    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }
    fun getTokenPreset(context: Context): String {
        return getPrefs(context).getString(KEY_TOKEN_PRESET, "balanced") ?: "balanced"
    }
    fun setTokenPreset(context: Context, preset: String) {
        getPrefs(context).edit().putString(KEY_TOKEN_PRESET, preset).apply()
    }
    fun getModel(context: Context): String {
        return getPrefs(context).getString(KEY_MODEL, "") ?: ""
    }
    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model).apply()
    }
    fun getContextSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONTEXT_SIZE, 2000)
    }
    fun setContextSize(context: Context, size: Int) {
        getPrefs(context).edit().putInt(KEY_CONTEXT_SIZE, size).apply()
    }
    fun getTemperature(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TEMPERATURE, 0.7f)
    }
    fun setTemperature(context: Context, temp: Float) {
        getPrefs(context).edit().putFloat(KEY_TEMPERATURE, temp).apply()
    }
    fun getMaxTokens(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_TOKENS, 1000)
    }
    fun setMaxTokens(context: Context, tokens: Int) {
        getPrefs(context).edit().putInt(KEY_MAX_TOKENS, tokens).apply()
    }
    fun getExampleDialogues(context: Context): Int {
        return getPrefs(context).getInt(KEY_EXAMPLE_DIALOGUES, 1)
    }
    fun setExampleDialogues(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_EXAMPLE_DIALOGUES, count).apply()
    }
    fun getTtsSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TTS_SPEECH_RATE, DEFAULT_TTS_SPEECH_RATE)
    }
    fun setTtsSpeechRate(context: Context, rate: Float) {
        getPrefs(context).edit().putFloat(KEY_TTS_SPEECH_RATE, rate).apply()
    }
    fun getTtsPitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TTS_PITCH, DEFAULT_TTS_PITCH)
    }
    fun setTtsPitch(context: Context, pitch: Float) {
        getPrefs(context).edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    }
    fun getAutoReadAloud(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_READ_ALOUD, false)
    }
    fun setAutoReadAloud(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_READ_ALOUD, enabled).apply()
    }
    fun getVoiceRecognitionLang(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_RECOGNITION_LANG, DEFAULT_VOICE_RECOGNITION_LANG) ?: DEFAULT_VOICE_RECOGNITION_LANG
    }
    fun setVoiceRecognitionLang(context: Context, lang: String) {
        getPrefs(context).edit().putString(KEY_VOICE_RECOGNITION_LANG, lang).apply()
    }
    fun getProactiveEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROACTIVE_ENABLED, false)
    }
    fun setProactiveEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }
    fun getProactiveInterval(context: Context): Long {
        return getPrefs(context).getLong(KEY_PROACTIVE_INTERVAL, DEFAULT_INTERVAL_MS)
    }
    fun setProactiveInterval(context: Context, intervalMs: Long) {
        getPrefs(context).edit().putLong(KEY_PROACTIVE_INTERVAL, intervalMs).apply()
    }
    fun getQuietStart(context: Context): String {
        return getPrefs(context).getString(KEY_QUIET_START, "") ?: ""
    }
    fun setQuietStart(context: Context, start: String) {
        getPrefs(context).edit().putString(KEY_QUIET_START, start).apply()
    }
    fun getQuietEnd(context: Context): String {
        return getPrefs(context).getString(KEY_QUIET_END, "") ?: ""
    }
    fun setQuietEnd(context: Context, end: String) {
        getPrefs(context).edit().putString(KEY_QUIET_END, end).apply()
    }
    fun setQuietHours(context: Context, start: String, end: String) {
        getPrefs(context).edit().putString(KEY_QUIET_START, start).putString(KEY_QUIET_END, end).apply()
    }
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }
    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }
    fun clearQuietHours(context: Context) {
        getPrefs(context).edit().remove(KEY_QUIET_START).remove(KEY_QUIET_END).apply()
    }
}