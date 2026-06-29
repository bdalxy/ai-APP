package com.aicompanion.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SettingsActivity Espresso UI 测试。
 *
 * 覆盖：
 *  - 设置页面启动与核心 UI 元素
 *  - 各设置项可见性
 *  - 返回按钮
 *  - 滚动行为
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    // =====================================================================
    // 页面启动
    // =====================================================================

    @Test
    fun pageLaunchesSuccessfully() {
        // 验证设置页面有一个可滚动的容器
        onView(isAssignableFrom(android.widget.ScrollView::class.java))
            .check(matches(isDisplayed()))
    }

    @Test
    fun backButtonExists() {
        // SettingsActivity 应该有返回按钮
        onView(withContentDescription(containsString("返回")))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 功能区域
    // =====================================================================

    @Test
    fun characterCardSectionExists() {
        // 角色卡部分应该有相关文本
        onView(withText(containsString("角色")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun apiKeySectionExists() {
        // API Key 设置区域
        onView(withText(containsString("API")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun conversationParamsSectionExists() {
        // 对话参数区域
        onView(withText(containsString("对话")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun voiceSettingsSectionExists() {
        // 语音设置区域
        onView(withText(containsString("语音")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun memorySectionExists() {
        // 记忆参数区域
        onView(withText(containsString("记忆")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun dataManagementSectionExists() {
        // 数据管理区域
        onView(withText(containsString("数据")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun displaySectionExists() {
        // 显示设置区域
        onView(withText(containsString("显示")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun aboutSectionExists() {
        // 关于区域
        onView(withText(containsString("关于")))
            .check(matches(isDisplayed()))
    }
}