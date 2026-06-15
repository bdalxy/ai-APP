package com.aicompanion.app.ui

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.aicompanion.app.R
import com.google.android.material.card.MaterialCardView

/**
 * 统一玻璃态卡片组件。
 * 主题切换时自动更新颜色，确保深色/亮色模式一致。
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.style.GlassCard
) : MaterialCardView(context, attrs, defStyleAttr) {

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateColors()
    }

    private fun updateColors() {
        setCardBackgroundColor(ContextCompat.getColorStateList(context, R.color.glass_bg))
        strokeColor = ContextCompat.getColor(context, R.color.glass_border)
    }
}