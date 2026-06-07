package com.aicompanion.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置管理（SharedPreferences）。
 *
 * 管理 API Key 和 Token 预设等持久化配置。
 */
object AppConfig {
    private const val PREFS_NAME = "ai_companion_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TOKEN_PRESET = "token_preset"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key).apply()
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
}