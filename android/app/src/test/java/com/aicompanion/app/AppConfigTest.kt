package com.aicompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import org.junit.Ignore
import org.robolectric.RobolectricTestRunner

/**
 * AppConfig 单元测试（Robolectric）。
 *
 * 覆盖：
 *  - 所有配置项读写（get/set 往返）
 *  - 默认值验证
 *  - 类型安全（Int/Float/Long/String/Boolean）
 *  - 边界值
 *  - 引导页状态
 *  - 主题模式
 *  - 显示设置
 *  - 记忆参数
 *  - 主动消息
 *  - 免打扰时段
 *  - 联网搜索
 *  - 语言设置
 *  - API Key 加密存储（有/无）
 */
@RunWith(Enclosed::class)
class AppConfigTest {

    // =====================================================================
    // API Key（加密存储）
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class ApiKey {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultApiKey() {
            val key = AppConfig.getApiKey(context)
            assertEquals("", key)
        }

        @Test
        fun testHasApiKeyDefault() {
            assertFalse(AppConfig.hasApiKey(context))
        }

        @Test
        fun testSetAndGetApiKey() {
            AppConfig.setApiKey(context, "sk-test-key-12345")
            assertEquals("sk-test-key-12345", AppConfig.getApiKey(context))
        }

        @Test
        fun testHasApiKeyAfterSet() {
            AppConfig.setApiKey(context, "sk-test-key")
            assertTrue(AppConfig.hasApiKey(context))
        }

        @Test
        fun testHasApiKeyWithBlank() {
            AppConfig.setApiKey(context, "   ")
            assertFalse(AppConfig.hasApiKey(context))
        }
    }

    // =====================================================================
    // 非敏感配置
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class TokenPreset {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultTokenPreset() {
            assertEquals("balanced", AppConfig.getTokenPreset(context))
        }

        @Test
        fun testSetTokenPreset() {
            AppConfig.setTokenPreset(context, "precise")
            assertEquals("precise", AppConfig.getTokenPreset(context))
        }
    }

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Model {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultModel() {
            assertEquals("", AppConfig.getModel(context))
        }

        @Test
        fun testSetModel() {
            AppConfig.setModel(context, "deepseek-v4-flash")
            assertEquals("deepseek-v4-flash", AppConfig.getModel(context))
        }
    }

    // =====================================================================
    // 对话参数
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class ConversationParams {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultContextSize() {
            assertEquals(2000, AppConfig.getContextSize(context))
        }

        @Test
        fun testSetContextSize() {
            AppConfig.setContextSize(context, 4000)
            assertEquals(4000, AppConfig.getContextSize(context))
        }

        @Test
        fun testContextSizeZero() {
            AppConfig.setContextSize(context, 0)
            assertEquals(0, AppConfig.getContextSize(context))
        }

        @Test
        fun testDefaultTemperature() {
            assertEquals(0.7f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        fun testSetTemperature() {
            AppConfig.setTemperature(context, 1.5f)
            assertEquals(1.5f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        fun testTemperatureZero() {
            AppConfig.setTemperature(context, 0.0f)
            assertEquals(0.0f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        fun testTemperatureMax() {
            AppConfig.setTemperature(context, 2.0f)
            assertEquals(2.0f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        fun testDefaultTopP() {
            assertEquals(0.9f, AppConfig.getTopP(context), 0.001f)
        }

        @Test
        fun testSetTopP() {
            AppConfig.setTopP(context, 0.5f)
            assertEquals(0.5f, AppConfig.getTopP(context), 0.001f)
        }

        @Test
        fun testDefaultFrequencyPenalty() {
            assertEquals(0.0f, AppConfig.getFrequencyPenalty(context), 0.001f)
        }

        @Test
        fun testSetFrequencyPenalty() {
            AppConfig.setFrequencyPenalty(context, 0.5f)
            assertEquals(0.5f, AppConfig.getFrequencyPenalty(context), 0.001f)
        }

        @Test
        fun testDefaultPresencePenalty() {
            assertEquals(0.0f, AppConfig.getPresencePenalty(context), 0.001f)
        }

        @Test
        fun testDefaultMaxTokens() {
            assertEquals(1000, AppConfig.getMaxTokens(context))
        }

        @Test
        fun testSetMaxTokens() {
            AppConfig.setMaxTokens(context, 2000)
            assertEquals(2000, AppConfig.getMaxTokens(context))
        }

        @Test
        fun testDefaultExampleDialogues() {
            assertEquals(1, AppConfig.getExampleDialogues(context))
        }
    }

    // =====================================================================
    // 语音设置
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class VoiceSettings {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultTtsSpeechRate() {
            assertEquals(1.0f, AppConfig.getTtsSpeechRate(context), 0.001f)
        }

        @Test
        fun testDefaultTtsPitch() {
            assertEquals(1.0f, AppConfig.getTtsPitch(context), 0.001f)
        }

        @Test
        fun testDefaultAutoReadAloud() {
            assertFalse(AppConfig.getAutoReadAloud(context))
        }

        @Test
        fun testSetAutoReadAloud() {
            AppConfig.setAutoReadAloud(context, true)
            assertTrue(AppConfig.getAutoReadAloud(context))
        }

        @Test
        fun testDefaultVoiceRecognitionLang() {
            assertEquals("zh-CN", AppConfig.getVoiceRecognitionLang(context))
        }

        @Test
        fun testSetVoiceRecognitionLang() {
            AppConfig.setVoiceRecognitionLang(context, "en-US")
            assertEquals("en-US", AppConfig.getVoiceRecognitionLang(context))
        }

        @Test
        fun testDefaultVoiceTimbre() {
            assertEquals("default", AppConfig.getTtsVoiceTimbre(context))
        }

        @Test
        fun testDefaultTtsModelType() {
            assertEquals("auto", AppConfig.getTtsModelType(context))
        }

        @Test
        fun testDefaultSpeakerId() {
            assertEquals(0, AppConfig.getTtsSpeakerId(context))
        }

        @Test
        fun testSetSpeakerId() {
            AppConfig.setTtsSpeakerId(context, 42)
            assertEquals(42, AppConfig.getTtsSpeakerId(context))
        }
    }

    // =====================================================================
    // VITS 参数
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class VitsParams {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultNoiseScale() {
            assertEquals(0.667f, AppConfig.getTtsVitsNoiseScale(context), 0.001f)
        }

        @Test
        fun testDefaultNoiseScaleW() {
            assertEquals(0.8f, AppConfig.getTtsVitsNoiseScaleW(context), 0.001f)
        }

        @Test
        fun testDefaultLengthScale() {
            assertEquals(1.0f, AppConfig.getTtsVitsLengthScale(context), 0.001f)
        }
    }

    // =====================================================================
    // 主动消息
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Proactive {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultProactiveEnabled() {
            assertFalse(AppConfig.getProactiveEnabled(context))
        }

        @Test
        fun testSetProactiveEnabled() {
            AppConfig.setProactiveEnabled(context, true)
            assertTrue(AppConfig.getProactiveEnabled(context))
        }

        @Test
        fun testDefaultProactiveInterval() {
            assertEquals(10800000L, AppConfig.getProactiveInterval(context))
        }

        @Test
        fun testSetProactiveInterval() {
            AppConfig.setProactiveInterval(context, 3600000L)
            assertEquals(3600000L, AppConfig.getProactiveInterval(context))
        }

        @Test
        fun testDefaultQuietStart() {
            assertEquals("", AppConfig.getQuietStart(context))
        }

        @Test
        fun testDefaultQuietEnd() {
            assertEquals("", AppConfig.getQuietEnd(context))
        }

        @Test
        fun testSetQuietHours() {
            AppConfig.setQuietHours(context, "22:00", "08:00")
            assertEquals("22:00", AppConfig.getQuietStart(context))
            assertEquals("08:00", AppConfig.getQuietEnd(context))
        }

        @Test
        fun testClearQuietHours() {
            AppConfig.setQuietHours(context, "22:00", "08:00")
            AppConfig.clearQuietHours(context)
            assertEquals("", AppConfig.getQuietStart(context))
            assertEquals("", AppConfig.getQuietEnd(context))
        }
    }

    // =====================================================================
    // 引导页
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Onboarding {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultOnboarding() {
            assertFalse(AppConfig.isOnboardingCompleted(context))
        }

        @Test
        fun testSetOnboardingCompleted() {
            AppConfig.setOnboardingCompleted(context, true)
            assertTrue(AppConfig.isOnboardingCompleted(context))
        }
    }

    // =====================================================================
    // 主题模式
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Theme {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultTheme() {
            assertEquals(AppConfig.THEME_LIGHT, AppConfig.getThemeMode(context))
        }

        @Test
        fun testSetDarkTheme() {
            AppConfig.setThemeMode(context, AppConfig.THEME_DARK)
            assertEquals(AppConfig.THEME_DARK, AppConfig.getThemeMode(context))
            assertTrue(AppConfig.isDarkMode(context))
            assertFalse(AppConfig.isFollowSystem(context))
        }

        @Test
        fun testSetSystemTheme() {
            AppConfig.setThemeMode(context, AppConfig.THEME_SYSTEM)
            assertEquals(AppConfig.THEME_SYSTEM, AppConfig.getThemeMode(context))
            assertFalse(AppConfig.isDarkMode(context))
            assertTrue(AppConfig.isFollowSystem(context))
        }

        @Test
        fun testSetLightTheme() {
            AppConfig.setThemeMode(context, AppConfig.THEME_LIGHT)
            assertEquals(AppConfig.THEME_LIGHT, AppConfig.getThemeMode(context))
            assertFalse(AppConfig.isDarkMode(context))
            assertFalse(AppConfig.isFollowSystem(context))
        }
    }

    // =====================================================================
    // 显示设置
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Display {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultFontSize() {
            assertEquals("medium", AppConfig.getFontSize(context))
        }

        @Test
        fun testSetFontSize() {
            AppConfig.setFontSize(context, "large")
            assertEquals("large", AppConfig.getFontSize(context))
        }

        @Test
        fun testDefaultBubbleRadius() {
            assertEquals(16, AppConfig.getBubbleRadius(context))
        }

        @Test
        fun testSetBubbleRadius() {
            AppConfig.setBubbleRadius(context, 24)
            assertEquals(24, AppConfig.getBubbleRadius(context))
        }

        @Test
        fun testBubbleRadiusZero() {
            AppConfig.setBubbleRadius(context, 0)
            assertEquals(0, AppConfig.getBubbleRadius(context))
        }

        @Test
        fun testDefaultShowTimestamp() {
            assertTrue(AppConfig.getShowTimestamp(context))
        }

        @Test
        fun testSetShowTimestamp() {
            AppConfig.setShowTimestamp(context, false)
            assertFalse(AppConfig.getShowTimestamp(context))
        }
    }

    // =====================================================================
    // 联网搜索
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class WebSearch {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultWebSearch() {
            assertFalse(AppConfig.getWebSearchEnabled(context))
        }

        @Test
        fun testSetWebSearch() {
            AppConfig.setWebSearchEnabled(context, true)
            assertTrue(AppConfig.getWebSearchEnabled(context))
        }
    }

    // =====================================================================
    // 应用语言
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class AppLanguage {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultLanguage() {
            assertEquals("zh", AppConfig.getAppLanguage(context))
        }

        @Test
        fun testSetLanguage() {
            AppConfig.setAppLanguage(context, "en")
            assertEquals("en", AppConfig.getAppLanguage(context))
        }
    }

    // =====================================================================
    // 记忆参数
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Memory {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testDefaultMemoryMaxCount() {
            assertEquals(1000, AppConfig.getMemoryMaxCount(context))
        }

        @Test
        fun testSetMemoryMaxCount() {
            AppConfig.setMemoryMaxCount(context, 500)
            assertEquals(500, AppConfig.getMemoryMaxCount(context))
        }

        @Test
        fun testDefaultDedupThreshold() {
            assertEquals(0.7f, AppConfig.getMemoryDedupThreshold(context), 0.001f)
        }

        @Test
        fun testSetDedupThreshold() {
            AppConfig.setMemoryDedupThreshold(context, 0.85f)
            assertEquals(0.85f, AppConfig.getMemoryDedupThreshold(context), 0.001f)
        }

        @Test
        fun testDefaultDecayHalfLife() {
            assertEquals(30, AppConfig.getMemoryDecayHalfLife(context))
        }

        @Test
        fun testSetDecayHalfLife() {
            AppConfig.setMemoryDecayHalfLife(context, 60)
            assertEquals(60, AppConfig.getMemoryDecayHalfLife(context))
        }
    }

    // =====================================================================
    // 常量值
    // =====================================================================

    @Ignore("Robolectric: EncryptedSharedPreferences native library not supported")
    @RunWith(RobolectricTestRunner::class)
    class Constants {

        private lateinit var context: Context

        @Before
        fun setUp() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun testMaxMessagesConstant() {
            assertEquals(50, AppConfig.MAX_MESSAGES)
        }

        @Test
        fun testDefaultTtsSpeechRateConstant() {
            assertEquals(1.0f, AppConfig.DEFAULT_TTS_SPEECH_RATE)
        }

        @Test
        fun testDefaultMemoryMaxCountConstant() {
            assertEquals(1000, AppConfig.DEFAULT_MEMORY_MAX_COUNT)
        }

        @Test
        fun testMinIntervalMs() {
            assertEquals(1800000L, AppConfig.MIN_INTERVAL_MS)
        }

        @Test
        fun testTtsModelConstants() {
            assertEquals("auto", AppConfig.TTS_MODEL_AUTO)
            assertEquals("vits", AppConfig.TTS_MODEL_VITS)
            assertEquals("matcha", AppConfig.TTS_MODEL_MATCHA)
        }
    }
}