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
 * MemoryManageActivity 冒烟测试
 * 验证记忆管理页面的核心 UI 元素是否正确加载
 */
@RunWith(AndroidJUnit4::class)
class MemoryManageActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MemoryManageActivity::class.java)

    @Test
    fun activityLaunchesSuccessfully() {
        // 验证记忆列表 RecyclerView 存在并可见
        onView(withId(R.id.rvMemories))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchBarExists() {
        // 验证搜索栏存在并可见
        onView(withId(R.id.etSearch))
            .check(matches(isDisplayed()))
    }

    @Test
    fun canTypeSearchQuery() {
        // 验证可以在搜索框中输入文字
        onView(withId(R.id.etSearch))
            .perform(typeText("test"), closeSoftKeyboard())

        onView(withId(R.id.etSearch))
            .check(matches(withText("test")))
    }

    @Test
    fun backButtonExists() {
        // 验证返回按钮存在
        onView(withId(R.id.btnBack))
            .check(matches(isDisplayed()))
    }

    @Test
    fun clearAllButtonExists() {
        // 验证清除全部按钮存在
        onView(withId(R.id.btnClearAll))
            .check(matches(isDisplayed()))
    }
}