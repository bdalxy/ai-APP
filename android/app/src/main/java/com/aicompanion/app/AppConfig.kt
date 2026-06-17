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

    // ── 主动消息配置（proactive）──
    private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
    private const val KEY_PROACTIVE_INTERVAL = "proactive_interval"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

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

    /** 清除免打扰时段 */
    fun clearQuietHours(context: Context) {
        getPrefs(context).edit().remove(KEY_QUIET_START).remove(KEY_QUIET_END).apply()
    }
}
