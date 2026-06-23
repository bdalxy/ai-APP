package com.aicompanion.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 多语言切换工具。
 * 在 Application.onCreate() 和 Activity.attachBaseContext() 中调用，
 * 确保所有 Activity 使用正确的语言资源。
 */
object LocaleHelper {

    /** 支持的语言列表 */
    val SUPPORTED_LANGUAGES = listOf(
        LanguageOption("zh", "中文"),
        LanguageOption("en", "English"),
        LanguageOption("ja", "日本語")
    )

    data class LanguageOption(val code: String, val displayName: String)

    /**
     * 获取当前保存的语言代码，默认返回 "zh"
     */
    fun getCurrentLanguage(context: Context): String {
        return AppConfig.getAppLanguage(context)
    }

    /**
     * 设置语言并保存到 SharedPreferences
     */
    fun setLanguage(context: Context, languageCode: String) {
        AppConfig.setAppLanguage(context, languageCode)
    }

    /**
     * 根据语言代码创建 Locale
     */
    fun getLocale(languageCode: String): Locale {
        return when (languageCode) {
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    /**
     * 应用语言到 Context。
     * 在 Activity.attachBaseContext() 中调用。
     */
    fun applyLanguage(context: Context): Context {
        val languageCode = getCurrentLanguage(context)
        val locale = getLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}