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
 * MainActivity Espresso UI 测试。
 *
 * 覆盖：
 *  - 页面启动与核心 UI 元素
 *  - 输入框与发送按钮
 *  - 抽屉（DrawerLayout）打开/关闭
 *  - 新对话按钮
 *  - 设置按钮
 *  - 搜索功能
 *  - 滚动到底部按钮
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // =====================================================================
    // 页面启动
    // =====================================================================

    @Test
    fun appLaunchesSuccessfully() {
        onView(withId(R.id.rvMessages))
            .check(matches(isDisplayed()))
    }

    @Test
    fun allCoreElementsDisplayed() {
        // 顶栏
        onView(withId(R.id.ivAvatar)).check(matches(isDisplayed()))
        onView(withId(R.id.tvTitle)).check(matches(isDisplayed()))
        onView(withId(R.id.tvStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.btnNewChat)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSettings)).check(matches(isDisplayed()))

        // 输入区
        onView(withId(R.id.etInput)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSend)).check(matches(isDisplayed()))
        onView(withId(R.id.btnVoice)).check(matches(isDisplayed()))
    }

    // =====================================================================
    // 输入框
    // =====================================================================

    @Test
    fun canTypeMessage() {
        onView(withId(R.id.etInput))
            .perform(typeText("Hello"), closeSoftKeyboard())

        onView(withId(R.id.etInput))
            .check(matches(withText("Hello")))
    }

    @Test
    fun canClearInput() {
        onView(withId(R.id.etInput))
            .perform(typeText("测试消息"), closeSoftKeyboard())

        onView(withId(R.id.etInput))
            .perform(clearText(), closeSoftKeyboard())

        onView(withId(R.id.etInput))
            .check(matches(withText("")))
    }

    @Test
    fun inputHintDisplayed() {
        onView(withId(R.id.etInput))
            .check(matches(withHint(not(isEmptyString()))))
    }

    // =====================================================================
    // 发送按钮
    // =====================================================================

    @Test
    fun sendButtonIsClickable() {
        onView(withId(R.id.btnSend))
            .check(matches(isClickable()))
    }

    @Test
    fun sendButtonHasContentDescription() {
        onView(withId(R.id.btnSend))
            .check(matches(withContentDescription(not(isEmptyString()))))
    }

    // =====================================================================
    // 抽屉（DrawerLayout）
    // =====================================================================

    @Test
    fun drawerLayoutExists() {
        onView(withId(R.id.drawerLayout))
            .check(matches(isDisplayed()))
    }

    @Test
    fun drawerSessionListExists() {
        // 先打开抽屉
        onView(withId(R.id.drawerLayout))
            .perform(swipeRight())

        onView(withId(R.id.drawerSessionList))
            .check(matches(isDisplayed()))
    }

    @Test
    fun drawerHasNewChatButton() {
        onView(withId(R.id.drawerLayout))
            .perform(swipeRight())

        onView(withId(R.id.btnDrawerNewChat))
            .check(matches(isDisplayed()))
    }

    @Test
    fun drawerHasSessionList() {
        onView(withId(R.id.drawerLayout))
            .perform(swipeRight())

        onView(withId(R.id.rvSessionList))
            .check(matches(isDisplayed()))
    }

    @Test
    fun drawerHasExportButton() {
        onView(withId(R.id.drawerLayout))
            .perform(swipeRight())

        onView(withId(R.id.btnDrawerExport))
            .check(matches(isDisplayed()))
    }

    @Test
    fun drawerHasSearchButton() {
        onView(withId(R.id.drawerLayout))
            .perform(swipeRight())

        onView(withId(R.id.btnDrawerSearch))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 顶栏按钮
    // =====================================================================

    @Test
    fun newChatButtonIsClickable() {
        onView(withId(R.id.btnNewChat))
            .check(matches(isClickable()))
    }

    @Test
    fun settingsButtonIsClickable() {
        onView(withId(R.id.btnSettings))
            .check(matches(isClickable()))
    }

    @Test
    fun avatarIsDisplayed() {
        onView(withId(R.id.ivAvatar))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 消息列表
    // =====================================================================

    @Test
    fun messageListExists() {
        onView(withId(R.id.rvMessages))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 滚动到底部按钮
    // =====================================================================

    @Test
    fun scrollBottomButtonExists() {
        onView(withId(R.id.btnScrollBottom))
            .check(matches(isDisplayed()))
    }
}