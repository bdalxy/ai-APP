package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 应用配置管理（EncryptedSharedPreferences）。
 *
 * API Key 使用 AES-256 加密存储，防止 root 设备直接读取。
 * 其他非敏感配置（如 Token 预设、模型选择）使用普通 SharedPreferences。
 */
object AppConfig {
    private const val PREFS_NAME = "ai_companion_prefs"
    private const val SECURE_PREFS_NAME = "ai_companion_secure_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TOKEN_PRESET = "token_preset"
    private const val KEY_MODEL = "model"
    private const val KEY_CONTEXT_SIZE = "context_size"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_EXAMPLE_DIALOGUES = "example_dialogues"

    // ── 主动消息配置键 ──
    private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
    private const val KEY_PROACTIVE_INTERVAL = "proactive_interval"
    private const val KEY_PROACTIVE_QUIET_START = "proactive_quiet_start"
    private const val KEY_PROACTIVE_QUIET_END = "proactive_quiet_end"

    // ── 主动消息间隔选项（公共常量，避免多处重复定义）──
    val INTERVAL_OPTIONS = arrayOf("每1小时", "每2小时", "每3小时", "每6小时", "每12小时", "每天")
    val INTERVAL_MS = longArrayOf(3600000L, 7200000L, 10800000L, 21600000L, 43200000L, 86400000L)
    val DEFAULT_INTERVAL_MS = 10800000L  // 默认 3 小时

    /** 缓存的加密 SharedPreferences 实例（避免重复创建 MasterKey） */
    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null
    private val securePrefsLock = Any()

    /** 普通配置（非敏感） */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 加密配置（API Key 等敏感数据，带缓存） */
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

    // ── 主动消息配置 ──

    /** 主动消息是否开启，默认 true */
    fun isProactiveEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROACTIVE_ENABLED, true)
    }

    fun setProactiveEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    /** 主动消息发送间隔（小时），默认 3 */
    fun getProactiveInterval(context: Context): Int {
        return getPrefs(context).getInt(KEY_PROACTIVE_INTERVAL, 3)
    }

    fun setProactiveInterval(context: Context, hours: Int) {
        getPrefs(context).edit().putInt(KEY_PROACTIVE_INTERVAL, hours).apply()
    }

    /** 静默时段开始时间（HH:mm），默认 23:00 */
    fun getProactiveQuietStart(context: Context): String {
        return getPrefs(context).getString(KEY_PROACTIVE_QUIET_START, "23:00") ?: "23:00"
    }

    fun setProactiveQuietStart(context: Context, time: String) {
        getPrefs(context).edit().putString(KEY_PROACTIVE_QUIET_START, time).apply()
    }

    /** 静默时段结束时间（HH:mm），默认 07:00 */
    fun getProactiveQuietEnd(context: Context): String {
        return getPrefs(context).getString(KEY_PROACTIVE_QUIET_END, "07:00") ?: "07:00"
    }

    fun setProactiveQuietEnd(context: Context, time: String) {
        getPrefs(context).edit().putString(KEY_PROACTIVE_QUIET_END, time).apply()
    }
}