package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 应用配置管理（EncryptedSharedPreferences）。
 *
 * API Key 使用 AES-256 加密存储，防止 root 设备直接读取。
 * 其他非敏感配置（如 Token 预设、模型选择）使用普通 SharedPreferences。
 */
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

    // ── 语音设置（voice）──
    private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_AUTO_READ_ALOUD = "auto_read_aloud"
    private const val KEY_VOICE_RECOGNITION_LANG = "voice_recognition_lang"

    // ── 聊天常量 ──
    /** 消息列表最大保留条数 */
    const val MAX_MESSAGES = 50
    private const val KEY_TTS_VOICE_TIMBRE = "tts_voice_timbre"

    /** 默认 TTS 语速 */
    const val DEFAULT_TTS_SPEECH_RATE = 1.0f
    /** 默认 TTS 音调 */
    const val DEFAULT_TTS_PITCH = 1.0f
    /** 默认语音识别语言 */
    const val DEFAULT_VOICE_RECOGNITION_LANG = "zh-CN"
    /** 默认 TTS 音色 */
    const val DEFAULT_TTS_VOICE_TIMBRE = "default"

    // ── 记忆参数配置（memory）──
    private const val KEY_MEMORY_MAX_COUNT = "memory_max_count"
    private const val KEY_MEMORY_DEDUP_THRESHOLD = "memory_dedup_threshold"
    private const val KEY_MEMORY_DECAY_HALF_LIFE = "memory_decay_half_life"

    /** 默认记忆容量上限 */
    const val DEFAULT_MEMORY_MAX_COUNT = 1000
    /** 默认去重相似度阈值 */
    const val DEFAULT_MEMORY_DEDUP_THRESHOLD = 0.7f
    /** 默认衰减半衰期（天） */
    const val DEFAULT_MEMORY_DECAY_HALF_LIFE = 30

    // ── 主动消息配置（proactive）──
    private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
    private const val KEY_PROACTIVE_INTERVAL = "proactive_interval"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    // ── 联网搜索开关 ──
    private const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"

    // ── 主动消息间隔选项（公共常量，避免多处重复定义）──
    val INTERVAL_OPTIONS = arrayOf("每1小时", "每2小时", "每3小时", "每6小时", "每12小时", "每天")
    val INTERVAL_MS = longArrayOf(3600000L, 7200000L, 10800000L, 21600000L, 43200000L, 86400000L)
    val DEFAULT_INTERVAL_MS = 10800000L  // 默认 3 小时
    val MIN_INTERVAL_MS = 1800000L  // 最低间隔 30 分钟

    /** 缓存的加密 SharedPreferences 实例（避免重复创建 MasterKey） */
    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null
    private val securePrefsLock = Any()

    /** 普通配置（非敏感） */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 加密配置（API Key 等敏感数据，带缓存）
     * 如果加密存储初始化失败，抛出异常而非降级为明文存储。 */
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return cachedSecurePrefs ?: synchronized(securePrefsLock) {
            cachedSecurePrefs ?: run {
                try {
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
                } catch (e: Exception) {
                    // 加密存储初始化失败，不降级为明文存储
                    Log.e("AppConfig", "加密存储初始化失败，无法安全存储 API Key: ${e.message}")
                    throw SecurityException("设备不支持加密存储，无法安全保存 API Key", e)
                }
                cachedSecurePrefs!!
            }
        }
    }

    // ── API Key（加密存储）──

    fun getApiKey(context: Context): String {
        return getSecurePrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getSecurePrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    // ── 非敏感配置（明文存储）──

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

    // ── 独立对话参数 ──

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

    // ── 语音设置（voice）──

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

    fun getTtsVoiceTimbre(context: Context): String {
        return getPrefs(context).getString(KEY_TTS_VOICE_TIMBRE, DEFAULT_TTS_VOICE_TIMBRE) ?: DEFAULT_TTS_VOICE_TIMBRE
    }

    fun setTtsVoiceTimbre(context: Context, timbre: String) {
        getPrefs(context).edit().putString(KEY_TTS_VOICE_TIMBRE, timbre).apply()
    }

    // ── 主动消息配置（proactive）──

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

    /** 一次性设置免打扰时段 */
    fun setQuietHours(context: Context, start: String, end: String) {
        getPrefs(context).edit().putString(KEY_QUIET_START, start).putString(KEY_QUIET_END, end).apply()
    }

    // ── 引导页（onboarding）──
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    // ── 主题模式 ──
    private const val KEY_THEME_MODE = "theme_mode"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun getThemeMode(context: Context): String {
        return getPrefs(context).getString(KEY_THEME_MODE, THEME_LIGHT) ?: THEME_LIGHT
    }

    fun setThemeMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    /** 清除免打扰时段 */
    fun clearQuietHours(context: Context) {
        getPrefs(context).edit().remove(KEY_QUIET_START).remove(KEY_QUIET_END).apply()
    }

    // ── 联网搜索开关 ──

    fun getWebSearchEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WEB_SEARCH_ENABLED, false)
    }

    fun setWebSearchEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WEB_SEARCH_ENABLED, enabled).apply()
    }

    // ── 应用语言 ──
    private const val KEY_APP_LANGUAGE = "app_language"
    const val DEFAULT_APP_LANGUAGE = "zh"

    fun getAppLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE
    }

    fun setAppLanguage(context: Context, language: String) {
        getPrefs(context).edit().putString(KEY_APP_LANGUAGE, language).apply()
    }

    // ── 记忆参数配置（memory）──

    fun getMemoryMaxCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_MEMORY_MAX_COUNT, DEFAULT_MEMORY_MAX_COUNT)
    }

    fun setMemoryMaxCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_MEMORY_MAX_COUNT, count).apply()
    }

    fun getMemoryDedupThreshold(context: Context): Float {
        return getPrefs(context).getFloat(KEY_MEMORY_DEDUP_THRESHOLD, DEFAULT_MEMORY_DEDUP_THRESHOLD)
    }

    fun setMemoryDedupThreshold(context: Context, threshold: Float) {
        getPrefs(context).edit().putFloat(KEY_MEMORY_DEDUP_THRESHOLD, threshold).apply()
    }

    fun getMemoryDecayHalfLife(context: Context): Int {
        return getPrefs(context).getInt(KEY_MEMORY_DECAY_HALF_LIFE, DEFAULT_MEMORY_DECAY_HALF_LIFE)
    }

    fun setMemoryDecayHalfLife(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_MEMORY_DECAY_HALF_LIFE, days).apply()
    }
}