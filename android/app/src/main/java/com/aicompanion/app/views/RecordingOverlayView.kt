package com.aicompanion.app.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aicompanion.app.R

/**
 * 录音覆盖层 View。
 * 封装录音指示器 UI（波形图标、时长、提示文字），
 * 通过 show()/hide()/updateDuration() 控制显示和状态。
 */
class RecordingOverlayView(context: Context) : FrameLayout(context) {

    private val durationText: TextView
    private val card: LinearLayout

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 半透明黑色遮罩层（标准 Android scrim，非主题色）
        setBackgroundColor(ContextCompat.getColor(context, R.color.recording_scrim))
        isClickable = true

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            setPadding(48, 36, 48, 36)
            background = GradientDrawable().apply {
                // 卡片背景：text_primary #4A3B3A + alpha 220（深色暖调）
                setColor(ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(context, R.color.text_primary), 220
                ))
                cornerRadius = 24f
            }
        }

        // 波形图标
        val waveIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_voice_wave)
            // 粉色波形图标（对应 R.color.typing_dot #FFB7C5）
            setColorFilter(ContextCompat.getColor(context, R.color.typing_dot))
        }
        card.addView(waveIcon)

        // 时长文本
        durationText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                gravity = Gravity.CENTER
            }
            text = "0:00"
            textSize = 28f
            // 粉色时长文字（对应 R.color.typing_dot #FFB7C5）
            setTextColor(ContextCompat.getColor(context, R.color.typing_dot))
        }
        card.addView(durationText)

        // 提示文字
        val hintText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                gravity = Gravity.CENTER
            }
            text = context.getString(R.string.voice_recording)
            textSize = 14f
            // 粉色提示文字（对应 R.color.typing_dot #FFB7C5 + alpha 180）
            setTextColor(ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.typing_dot), 180
            ))
        }
        card.addView(hintText)

        addView(card)
        visibility = GONE
    }

    /** 显示录音覆盖层 */
    fun show() {
        visibility = VISIBLE
        durationText.text = "0:00"
    }

    /** 隐藏录音覆盖层 */
    fun hide() {
        visibility = GONE
    }

    /** 更新录音时长显示 */
    fun updateDuration(durationStr: String) {
        durationText.text = durationStr
    }

    /** 检查是否正在显示 */
    fun isShowing(): Boolean = visibility == VISIBLE
}