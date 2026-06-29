package com.aicompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
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
@RunWith(RobolectricTestRunner::class)
@DisplayName("AppConfig")
class AppConfigTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // =====================================================================
    // API Key（加密存储）
    // =====================================================================

    @Nested
    @DisplayName("API Key")
    inner class ApiKey {

        @Test
        @DisplayName("默认无 API Key 返回空字符串")
        fun testDefaultApiKey() {
            val key = AppConfig.getApiKey(context)
            assertEquals("", key)
        }

        @Test
        @DisplayName("hasApiKey 默认返回 false")
        fun testHasApiKeyDefault() {
            assertFalse(AppConfig.hasApiKey(context))
        }

        @Test
        @DisplayName("设置 API Key 后可读取")
        fun testSetAndGetApiKey() {
            AppConfig.setApiKey(context, "sk-test-key-12345")
            assertEquals("sk-test-key-12345", AppConfig.getApiKey(context))
        }

        @Test
        @DisplayName("设置 API Key 后 hasApiKey 返回 true")
        fun testHasApiKeyAfterSet() {
            AppConfig.setApiKey(context, "sk-test-key")
            assertTrue(AppConfig.hasApiKey(context))
        }

        @Test
        @DisplayName("设置空白 API Key 后 hasApiKey 返回 false")
        fun testHasApiKeyWithBlank() {
            AppConfig.setApiKey(context, "   ")
            assertFalse(AppConfig.hasApiKey(context))
        }
    }

    // =====================================================================
    // 非敏感配置
    // =====================================================================

    @Nested
    @DisplayName("Token 预设")
    inner class TokenPreset {

        @Test
        @DisplayName("默认值为 balanced")
        fun testDefaultTokenPreset() {
            assertEquals("balanced", AppConfig.getTokenPreset(context))
        }

        @Test
        @DisplayName("设置后读取一致")
        fun testSetTokenPreset() {
            AppConfig.setTokenPreset(context, "precise")
            assertEquals("precise", AppConfig.getTokenPreset(context))
        }
    }

    @Nested
    @DisplayName("模型")
    inner class Model {

        @Test
        @DisplayName("默认值为空字符串")
        fun testDefaultModel() {
            assertEquals("", AppConfig.getModel(context))
        }

        @Test
        @DisplayName("设置后读取一致")
        fun testSetModel() {
            AppConfig.setModel(context, "deepseek-v4-flash")
            assertEquals("deepseek-v4-flash", AppConfig.getModel(context))
        }
    }

    // =====================================================================
    // 对话参数
    // =====================================================================

    @Nested
    @DisplayName("对话参数")
    inner class ConversationParams {

        @Test
        @DisplayName("contextSize 默认 2000")
        fun testDefaultContextSize() {
            assertEquals(2000, AppConfig.getContextSize(context))
        }

        @Test
        @DisplayName("contextSize 设置后读取")
        fun testSetContextSize() {
            AppConfig.setContextSize(context, 4000)
            assertEquals(4000, AppConfig.getContextSize(context))
        }

        @Test
        @DisplayName("contextSize 边界值 0")
        fun testContextSizeZero() {
            AppConfig.setContextSize(context, 0)
            assertEquals(0, AppConfig.getContextSize(context))
        }

        @Test
        @DisplayName("temperature 默认 0.7")
        fun testDefaultTemperature() {
            assertEquals(0.7f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        @DisplayName("temperature 设置后读取")
        fun testSetTemperature() {
            AppConfig.setTemperature(context, 1.5f)
            assertEquals(1.5f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        @DisplayName("temperature 边界值 0")
        fun testTemperatureZero() {
            AppConfig.setTemperature(context, 0.0f)
            assertEquals(0.0f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        @DisplayName("temperature 边界值 2.0")
        fun testTemperatureMax() {
            AppConfig.setTemperature(context, 2.0f)
            assertEquals(2.0f, AppConfig.getTemperature(context), 0.001f)
        }

        @Test
        @DisplayName("topP 默认 0.9")
        fun testDefaultTopP() {
            assertEquals(0.9f, AppConfig.getTopP(context), 0.001f)
        }

        @Test
        @DisplayName("topP 设置后读取")
        fun testSetTopP() {
            AppConfig.setTopP(context, 0.5f)
            assertEquals(0.5f, AppConfig.getTopP(context), 0.001f)
        }

        @Test
        @DisplayName("frequencyPenalty 默认 0.0")
        fun testDefaultFrequencyPenalty() {
            assertEquals(0.0f, AppConfig.getFrequencyPenalty(context), 0.001f)
        }

        @Test
        @DisplayName("frequencyPenalty 设置后读取")
        fun testSetFrequencyPenalty() {
            AppConfig.setFrequencyPenalty(context, 0.5f)
            assertEquals(0.5f, AppConfig.getFrequencyPenalty(context), 0.001f)
        }

        @Test
        @DisplayName("presencePenalty 默认 0.0")
        fun testDefaultPresencePenalty() {
            assertEquals(0.0f, AppConfig.getPresencePenalty(context), 0.001f)
        }

        @Test
        @DisplayName("maxTokens 默认 1000")
        fun testDefaultMaxTokens() {
            assertEquals(1000, AppConfig.getMaxTokens(context))
        }

        @Test
        @DisplayName("maxTokens 设置后读取")
        fun testSetMaxTokens() {
            AppConfig.setMaxTokens(context, 2000)
            assertEquals(2000, AppConfig.getMaxTokens(context))
        }

        @Test
        @DisplayName("exampleDialogues 默认 1")
        fun testDefaultExampleDialogues() {
            assertEquals(1, AppConfig.getExampleDialogues(context))
        }
    }

    // =====================================================================
    // 语音设置
    // =====================================================================

    @Nested
    @DisplayName("语音设置")
    inner class VoiceSettings {

        @Test
        @DisplayName("ttsSpeechRate 默认 1.0")
        fun testDefaultTtsSpeechRate() {
            assertEquals(1.0f, AppConfig.getTtsSpeechRate(context), 0.001f)
        }

        @Test
        @DisplayName("ttsPitch 默认 1.0")
        fun testDefaultTtsPitch() {
            assertEquals(1.0f, AppConfig.getTtsPitch(context), 0.001f)
        }

        @Test
        @DisplayName("autoReadAloud 默认 false")
        fun testDefaultAutoReadAloud() {
            assertFalse(AppConfig.getAutoReadAloud(context))
        }

        @Test
        @DisplayName("autoReadAloud 设置后读取")
        fun testSetAutoReadAloud() {
            AppConfig.setAutoReadAloud(context, true)
            assertTrue(AppConfig.getAutoReadAloud(context))
        }

        @Test
        @DisplayName("voiceRecognitionLang 默认 zh-CN")
        fun testDefaultVoiceRecognitionLang() {
            assertEquals("zh-CN", AppConfig.getVoiceRecognitionLang(context))
        }

        @Test
        @DisplayName("voiceRecognitionLang 设置后读取")
        fun testSetVoiceRecognitionLang() {
            AppConfig.setVoiceRecognitionLang(context, "en-US")
            assertEquals("en-US", AppConfig.getVoiceRecognitionLang(context))
        }

        @Test
        @DisplayName("ttsVoiceTimbre 默认 default")
        fun testDefaultVoiceTimbre() {
            assertEquals("default", AppConfig.getTtsVoiceTimbre(context))
        }

        @Test
        @DisplayName("ttsModelType 默认 auto")
        fun testDefaultTtsModelType() {
            assertEquals("auto", AppConfig.getTtsModelType(context))
        }

        @Test
        @DisplayName("ttsSpeakerId 默认 0")
        fun testDefaultSpeakerId() {
            assertEquals(0, AppConfig.getTtsSpeakerId(context))
        }

        @Test
        @DisplayName("ttsSpeakerId 设置后读取")
        fun testSetSpeakerId() {
            AppConfig.setTtsSpeakerId(context, 42)
            assertEquals(42, AppConfig.getTtsSpeakerId(context))
        }
    }

    // =====================================================================
    // VITS 参数
    // =====================================================================

    @Nested
    @DisplayName("VITS 参数")
    inner class VitsParams {

        @Test
        @DisplayName("noiseScale 默认 0.667")
        fun testDefaultNoiseScale() {
            assertEquals(0.667f, AppConfig.getTtsVitsNoiseScale(context), 0.001f)
        }

        @Test
        @DisplayName("noiseScaleW 默认 0.8")
        fun testDefaultNoiseScaleW() {
            assertEquals(0.8f, AppConfig.getTtsVitsNoiseScaleW(context), 0.001f)
        }

        @Test
        @DisplayName("lengthScale 默认 1.0")
        fun testDefaultLengthScale() {
            assertEquals(1.0f, AppConfig.getTtsVitsLengthScale(context), 0.001f)
        }
    }

    // =====================================================================
    // 主动消息
    // =====================================================================

    @Nested
    @DisplayName("主动消息")
    inner class Proactive {

        @Test
        @DisplayName("proactiveEnabled 默认 false")
        fun testDefaultProactiveEnabled() {
            assertFalse(AppConfig.getProactiveEnabled(context))
        }

        @Test
        @DisplayName("proactiveEnabled 设置后读取")
        fun testSetProactiveEnabled() {
            AppConfig.setProactiveEnabled(context, true)
            assertTrue(AppConfig.getProactiveEnabled(context))
        }

        @Test
        @DisplayName("proactiveInterval 默认 3 小时")
        fun testDefaultProactiveInterval() {
            assertEquals(10800000L, AppConfig.getProactiveInterval(context))
        }

        @Test
        @DisplayName("proactiveInterval 设置后读取")
        fun testSetProactiveInterval() {
            AppConfig.setProactiveInterval(context, 3600000L)
            assertEquals(3600000L, AppConfig.getProactiveInterval(context))
        }

        @Test
        @DisplayName("quietStart 默认空字符串")
        fun testDefaultQuietStart() {
            assertEquals("", AppConfig.getQuietStart(context))
        }

        @Test
        @DisplayName("quietEnd 默认空字符串")
        fun testDefaultQuietEnd() {
            assertEquals("", AppConfig.getQuietEnd(context))
        }

        @Test
        @DisplayName("setQuietHours 一次性设置")
        fun testSetQuietHours() {
            AppConfig.setQuietHours(context, "22:00", "08:00")
            assertEquals("22:00", AppConfig.getQuietStart(context))
            assertEquals("08:00", AppConfig.getQuietEnd(context))
        }

        @Test
        @DisplayName("clearQuietHours 清除免打扰时段")
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

    @Nested
    @DisplayName("引导页")
    inner class Onboarding {

        @Test
        @DisplayName("isOnboardingCompleted 默认 false")
        fun testDefaultOnboarding() {
            assertFalse(AppConfig.isOnboardingCompleted(context))
        }

        @Test
        @DisplayName("设置完成后返回 true")
        fun testSetOnboardingCompleted() {
            AppConfig.setOnboardingCompleted(context, true)
            assertTrue(AppConfig.isOnboardingCompleted(context))
        }
    }

    // =====================================================================
    // 主题模式
    // =====================================================================

    @Nested
    @DisplayName("主题模式")
    inner class Theme {

        @Test
        @DisplayName("themeMode 默认 light")
        fun testDefaultTheme() {
            assertEquals(AppConfig.THEME_LIGHT, AppConfig.getThemeMode(context))
        }

        @Test
        @DisplayName("设置 dark 模式")
        fun testSetDarkTheme() {
            AppConfig.setThemeMode(context, AppConfig.THEME_DARK)
            assertEquals(AppConfig.THEME_DARK, AppConfig.getThemeMode(context))
            assertTrue(AppConfig.isDarkMode(context))
            assertFalse(AppConfig.isFollowSystem(context))
        }

        @Test
        @DisplayName("设置 system 模式")
        fun testSetSystemTheme() {
            AppConfig.setThemeMode(context, AppConfig.THEME_SYSTEM)
            assertEquals(AppConfig.THEME_SYSTEM, AppConfig.getThemeMode(context))
            assertFalse(AppConfig.isDarkMode(context))
            assertTrue(AppConfig.isFollowSystem(context))
        }

        @Test
        @DisplayName("设置 light 模式")
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

    @Nested
    @DisplayName("显示设置")
    inner class Display {

        @Test
        @DisplayName("fontSize 默认 medium")
        fun testDefaultFontSize() {
            assertEquals("medium", AppConfig.getFontSize(context))
        }

        @Test
        @DisplayName("fontSize 设置后读取")
        fun testSetFontSize() {
            AppConfig.setFontSize(context, "large")
            assertEquals("large", AppConfig.getFontSize(context))
        }

        @Test
        @DisplayName("bubbleRadius 默认 16")
        fun testDefaultBubbleRadius() {
            assertEquals(16, AppConfig.getBubbleRadius(context))
        }

        @Test
        @DisplayName("bubbleRadius 设置后读取")
        fun testSetBubbleRadius() {
            AppConfig.setBubbleRadius(context, 24)
            assertEquals(24, AppConfig.getBubbleRadius(context))
        }

        @Test
        @DisplayName("bubbleRadius 边界值 0")
        fun testBubbleRadiusZero() {
            AppConfig.setBubbleRadius(context, 0)
            assertEquals(0, AppConfig.getBubbleRadius(context))
        }

        @Test
        @DisplayName("showTimestamp 默认 true")
        fun testDefaultShowTimestamp() {
            assertTrue(AppConfig.getShowTimestamp(context))
        }

        @Test
        @DisplayName("showTimestamp 设置 false")
        fun testSetShowTimestamp() {
            AppConfig.setShowTimestamp(context, false)
            assertFalse(AppConfig.getShowTimestamp(context))
        }
    }

    // =====================================================================
    // 联网搜索
    // =====================================================================

    @Nested
    @DisplayName("联网搜索")
    inner class WebSearch {

        @Test
        @DisplayName("webSearchEnabled 默认 false")
        fun testDefaultWebSearch() {
            assertFalse(AppConfig.getWebSearchEnabled(context))
        }

        @Test
        @DisplayName("webSearchEnabled 设置后读取")
        fun testSetWebSearch() {
            AppConfig.setWebSearchEnabled(context, true)
            assertTrue(AppConfig.getWebSearchEnabled(context))
        }
    }

    // =====================================================================
    // 应用语言
    // =====================================================================

    @Nested
    @DisplayName("应用语言")
    inner class AppLanguage {

        @Test
        @DisplayName("appLanguage 默认 zh")
        fun testDefaultLanguage() {
            assertEquals("zh", AppConfig.getAppLanguage(context))
        }

        @Test
        @DisplayName("appLanguage 设置后读取")
        fun testSetLanguage() {
            AppConfig.setAppLanguage(context, "en")
            assertEquals("en", AppConfig.getAppLanguage(context))
        }
    }

    // =====================================================================
    // 记忆参数
    // =====================================================================

    @Nested
    @DisplayName("记忆参数")
    inner class Memory {

        @Test
        @DisplayName("memoryMaxCount 默认 1000")
        fun testDefaultMemoryMaxCount() {
            assertEquals(1000, AppConfig.getMemoryMaxCount(context))
        }

        @Test
        @DisplayName("memoryMaxCount 设置后读取")
        fun testSetMemoryMaxCount() {
            AppConfig.setMemoryMaxCount(context, 500)
            assertEquals(500, AppConfig.getMemoryMaxCount(context))
        }

        @Test
        @DisplayName("memoryDedupThreshold 默认 0.7")
        fun testDefaultDedupThreshold() {
            assertEquals(0.7f, AppConfig.getMemoryDedupThreshold(context), 0.001f)
        }

        @Test
        @DisplayName("memoryDedupThreshold 设置后读取")
        fun testSetDedupThreshold() {
            AppConfig.setMemoryDedupThreshold(context, 0.85f)
            assertEquals(0.85f, AppConfig.getMemoryDedupThreshold(context), 0.001f)
        }

        @Test
        @DisplayName("memoryDecayHalfLife 默认 30 天")
        fun testDefaultDecayHalfLife() {
            assertEquals(30, AppConfig.getMemoryDecayHalfLife(context))
        }

        @Test
        @DisplayName("memoryDecayHalfLife 设置后读取")
        fun testSetDecayHalfLife() {
            AppConfig.setMemoryDecayHalfLife(context, 60)
            assertEquals(60, AppConfig.getMemoryDecayHalfLife(context))
        }
    }

    // =====================================================================
    // 常量值
    // =====================================================================

    @Nested
    @DisplayName("常量值")
    inner class Constants {

        @Test
        @DisplayName("MAX_MESSAGES = 50")
        fun testMaxMessagesConstant() {
            assertEquals(50, AppConfig.MAX_MESSAGES)
        }

        @Test
        @DisplayName("DEFAULT_TTS_SPEECH_RATE = 1.0")
        fun testDefaultTtsSpeechRateConstant() {
            assertEquals(1.0f, AppConfig.DEFAULT_TTS_SPEECH_RATE)
        }

        @Test
        @DisplayName("DEFAULT_MEMORY_MAX_COUNT = 1000")
        fun testDefaultMemoryMaxCountConstant() {
            assertEquals(1000, AppConfig.DEFAULT_MEMORY_MAX_COUNT)
        }

        @Test
        @DisplayName("MIN_INTERVAL_MS = 30 分钟")
        fun testMinIntervalMs() {
            assertEquals(1800000L, AppConfig.MIN_INTERVAL_MS)
        }

        @Test
        @DisplayName("TTS_MODEL_* 常量值")
        fun testTtsModelConstants() {
            assertEquals("auto", AppConfig.TTS_MODEL_AUTO)
            assertEquals("vits", AppConfig.TTS_MODEL_VITS)
            assertEquals("matcha", AppConfig.TTS_MODEL_MATCHA)
        }
    }
}