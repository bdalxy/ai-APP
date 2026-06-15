package com.aicompanion.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent

/**
 * 统一 Activity 转场动画辅助类。
 * 所有页面跳转统一使用此方法，确保动画一致。
 */
object ActivityTransitionHelper {

    /** 从右侧滑入（进入子页面） */
    fun startWithSlideIn(activity: Activity, intent: Intent) {
        val options = ActivityOptions.makeCustomAnimation(
            activity,
            R.anim.slide_in_right,
            R.anim.slide_out_left
        )
        activity.startActivity(intent, options.toBundle())
    }

    /** 从左侧滑入（返回上一页，通常不需要） */
    fun startWithSlideBack(activity: Activity, intent: Intent) {
        val options = ActivityOptions.makeCustomAnimation(
            activity,
            R.anim.slide_in_left,
            R.anim.slide_out_right
        )
        activity.startActivity(intent, options.toBundle())
    }

    /** 页面退出动画（finish 后调用） */
    @Suppress("DEPRECATION")
    fun finishWithSlideOut(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(
            R.anim.slide_in_left,
            R.anim.slide_out_right
        )
    }
}