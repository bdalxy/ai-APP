package com.aicompanion.app.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        setBackgroundColor(Color.argb(80, 0, 0, 0))
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
                setColor(Color.argb(220, 45, 27, 58))
                cornerRadius = 24f
            }
        }

        // 波形图标
        val waveIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_voice_wave)
            setColorFilter(Color.parseColor("#FFB7C5"))
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
            setTextColor(Color.parseColor("#FFB7C5"))
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
            setTextColor(Color.argb(180, 255, 183, 197))
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