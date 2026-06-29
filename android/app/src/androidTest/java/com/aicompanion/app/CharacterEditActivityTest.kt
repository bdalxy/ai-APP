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
 * CharacterEditActivity Espresso UI 测试。
 *
 * 覆盖：
 *  - 角色编辑页面启动
 *  - 页面标题与保存按钮
 *  - 头像区域
 *  - 模式选择（基础/高级）
 *  - 返回按钮
 */
@RunWith(AndroidJUnit4::class)
class CharacterEditActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(CharacterEditActivity::class.java)

    // =====================================================================
    // 页面启动
    // =====================================================================

    @Test
    fun pageLaunchesSuccessfully() {
        onView(withId(R.id.character_edit_root))
            .check(matches(isDisplayed()))
    }

    @Test
    fun pageTitleIsDisplayed() {
        onView(withId(R.id.tvPageTitle))
            .check(matches(isDisplayed()))
    }

    @Test
    fun saveButtonIsDisplayed() {
        onView(withId(R.id.btnSave))
            .check(matches(isDisplayed()))
    }

    @Test
    fun backButtonIsDisplayed() {
        onView(withId(R.id.btnBack))
            .check(matches(isDisplayed()))
    }

    @Test
    fun backButtonIsClickable() {
        onView(withId(R.id.btnBack))
            .check(matches(isClickable()))
    }

    // =====================================================================
    // 头像区域
    // =====================================================================

    @Test
    fun avatarImageIsDisplayed() {
        onView(withId(R.id.ivAvatar))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 模式选择
    // =====================================================================

    @Test
    fun modeSelectionCardIsDisplayed() {
        onView(withId(R.id.cardModeSelection))
            .check(matches(isDisplayed()))
    }

    @Test
    fun basicModeOptionIsDisplayed() {
        onView(withId(R.id.optionBasic))
            .check(matches(isDisplayed()))
    }

    @Test
    fun proModeOptionIsDisplayed() {
        onView(withId(R.id.optionPro))
            .check(matches(isDisplayed()))
    }

    // =====================================================================
    // 保存按钮
    // =====================================================================

    @Test
    fun saveButtonIsClickable() {
        onView(withId(R.id.btnSave))
            .check(matches(isClickable()))
    }
}