package com.aicompanion.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue

/**
 * 品牌设备 UI 适配工具。
 *
 * 小米 HyperOS 和荣耀 MagicOS 在系统更新后可能改变默认显示缩放，
 * 导致应用 UI 整体缩小/放大。本工具在 Application 初始化时统一校准。
 *
 * 适配策略：
 * - 小米（Xiaomi/Redmi）：检测 HyperOS 缩放，自动补偿
 * - 荣耀（Honor）：检测 MagicOS 缩放，自动补偿
 * - 其他品牌：保持系统默认
 */
object DeviceAdaptationHelper {

    /** 标准 density 基准（360dp 宽度屏幕对应 density=2.0） */
    private const val BASE_DENSITY = 2.0f

    /** 最小允许的 density，低于此值视为系统缩放异常 */
    private const val MIN_DENSITY = 1.5f

    /** 最大允许的 density */
    private const val MAX_DENSITY = 4.0f

    /** 标准字体缩放 */
    private const val BASE_FONT_SCALE = 1.0f

    /** 最大字体缩放 */
    private const val MAX_FONT_SCALE = 1.3f

    /**
     * 初始化设备适配。应在 Application.onCreate() 中调用。
     */
    fun init(context: Context) {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        when {
            isXiaomi(brand, manufacturer) -> adaptXiaomi(context)
            isHonor(brand, manufacturer) -> adaptHonor(context)
            else -> adaptDefault(context)
        }
    }

    // ── 品牌检测 ──

    private fun isXiaomi(brand: String, manufacturer: String): Boolean {
        return brand.contains("xiaomi") || brand.contains("redmi") ||
               manufacturer.contains("xiaomi")
    }

    private fun isHonor(brand: String, manufacturer: String): Boolean {
        return brand.contains("honor") || manufacturer.contains("honor") ||
               brand.contains("hihonor")
    }

    // ── 小米 HyperOS 适配 ──

    /**
     * 小米 HyperOS（澎湃OS 1→2→3）的显示缩放可能默认缩小 UI。
     * 检测当前 density 并在异常时补偿。
     */
    @Suppress("DEPRECATION")
    private fun adaptXiaomi(context: Context) {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration

        val currentDensity = metrics.density
        val currentFontScale = config.fontScale

        val needsDensityFix = currentDensity < MIN_DENSITY || currentDensity > MAX_DENSITY
        val needsFontFix = currentFontScale > MAX_FONT_SCALE

        if (needsDensityFix || needsFontFix) {
            val newConfig = Configuration(config)
            val newMetrics = DisplayMetrics().apply { setTo(metrics) }

            if (needsDensityFix) {
                val targetDensity = when {
                    currentDensity < MIN_DENSITY -> (metrics.widthPixels / 360f).coerceAtLeast(MIN_DENSITY)
                    currentDensity > MAX_DENSITY -> MAX_DENSITY
                    else -> currentDensity
                }
                newMetrics.density = targetDensity
                newMetrics.densityDpi = (targetDensity * DisplayMetrics.DENSITY_DEFAULT).toInt()
                newMetrics.scaledDensity = targetDensity * (if (needsFontFix) BASE_FONT_SCALE else currentFontScale)
                newConfig.densityDpi = newMetrics.densityDpi
            }

            if (needsFontFix) {
                newConfig.fontScale = BASE_FONT_SCALE
            }

            if (needsDensityFix) {
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(newConfig, newMetrics)
            } else {
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(newConfig, metrics)
            }
        }
    }

    // ── 荣耀 MagicOS 适配 ──

    /**
     * 荣耀 MagicOS 类似小米，也有独立的显示缩放机制。
     * 策略与小米相同：检测异常 density 并补偿。
     */
    private fun adaptHonor(context: Context) {
        // 荣耀适配策略与小米一致
        adaptXiaomi(context)
    }

    // ── 默认适配 ──

    /**
     * 其他品牌：仅限制字体缩放，避免系统"大字体"破坏布局。
     */
    private fun adaptDefault(context: Context) {
        val config = context.resources.configuration
        if (config.fontScale > MAX_FONT_SCALE) {
            val newConfig = Configuration(config)
            newConfig.fontScale = MAX_FONT_SCALE
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(newConfig, context.resources.displayMetrics)
        }
    }

    // ── 辅助 ──

    /** 获取状态栏高度（dp），用于手动适配 */
    fun getStatusBarHeightDp(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics
            ).toInt()
        }
        return (px / context.resources.displayMetrics.density).toInt()
    }

    /** 获取导航栏高度（dp），用于手动适配 */
    fun getNavBarHeightDp(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val px = if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics
            ).toInt()
        }
        return (px / context.resources.displayMetrics.density).toInt()
    }
}