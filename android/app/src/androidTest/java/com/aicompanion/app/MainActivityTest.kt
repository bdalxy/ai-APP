package com.aicompanion.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity 冒烟测试
 * 验证主界面（对话界面）的核心 UI 元素是否正确加载
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunchesSuccessfully() {
        // 验证 RecyclerView（消息列表）存在并可见
        onView(withId(R.id.rvMessages))
            .check(matches(isDisplayed()))
    }

    @Test
    fun inputFieldExists() {
        // 验证输入框存在
        onView(withId(R.id.etInput))
            .check(matches(isDisplayed()))
    }

    @Test
    fun sendButtonExists() {
        // 验证发送按钮存在
        onView(withId(R.id.btnSend))
            .check(matches(isDisplayed()))
    }

    @Test
    fun canTypeMessage() {
        // 验证可以在输入框中输入文字
        onView(withId(R.id.etInput))
            .perform(typeText("Hello"), closeSoftKeyboard())

        onView(withId(R.id.etInput))
            .check(matches(withText("Hello")))
    }
}