package com.aicompanion.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 公共 View 工具方法，消除各 Activity 间的代码重复。
 */
object ViewUtils {

    /** 设置 edge-to-edge 全屏显示 */
    fun setupEdgeToEdge(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    /**
     * 为 [root] 应用系统栏 insets 内边距。
     * @param includeBottom 是否包含底部导航栏高度（MainActivity 不需要，其他页面需要）
     */
    fun applyInsets(root: View, includeBottom: Boolean = true) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                if (includeBottom) v.paddingBottom + systemBars.bottom else 0
            )
            insets
        }
    }
}