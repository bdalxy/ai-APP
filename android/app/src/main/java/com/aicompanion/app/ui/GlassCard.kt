package com.aicompanion.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.aicompanion.app.R
import com.google.android.material.card.MaterialCardView

class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.style.GlassCard
) : MaterialCardView(context, attrs, defStyleAttr) {

    var blurEnabled = false
        private set
    var blurRadius = BlurUtils.DEFAULT_RADIUS
    var pressAnimationEnabled = true

    private var rootView: View? = null
    private val pressScale = 0.96f
    private val animDuration = 150L
    private var isPressed = false
    private var pressAnimator: ValueAnimator? = null

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_glass_edge)
        cardElevation = 0f
        setCardBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (blurEnabled) { post { applyBlur() } }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (blurEnabled) { post { applyBlur() } }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && blurEnabled && width > 0 && height > 0) { post { applyBlur() } }
    }

    fun enableBlur(root: View, radius: Float = BlurUtils.DEFAULT_RADIUS) {
        this.rootView = root
        this.blurRadius = radius
        this.blurEnabled = true
        if (isAttachedToWindow && width > 0 && height > 0) { post { applyBlur() } }
    }

    fun disableBlur() {
        this.blurEnabled = false
        background = ContextCompat.getDrawable(context, R.drawable.bg_glass_edge)
    }

    fun refreshBlur() {
        if (blurEnabled && isAttachedToWindow && width > 0 && height > 0) { applyBlur() }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (pressAnimationEnabled && isEnabled && event != null) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (!isPressed) { isPressed = true; animatePress(true) } }
                MotionEvent.ACTION_UP -> { if (isPressed) { isPressed = false; animatePress(false) } }
                MotionEvent.ACTION_CANCEL -> { if (isPressed) { isPressed = false; animatePress(false) } }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyBlur() {
        val root = rootView ?: return
        if (width <= 0 || height <= 0) return

        val location = IntArray(2)
        getLocationInWindow(location)
        val x = location[0]
        val y = location[1]

        val rootBitmap = BlurUtils.captureViewForBlur(root, x, y, width, height)
        if (rootBitmap == null) return

        val blurredBitmap = BlurUtils.blurBitmap(context, rootBitmap, blurRadius)
        rootBitmap.recycle()

        if (blurredBitmap == null) return

        val edgeDrawable = ContextCompat.getDrawable(context, R.drawable.bg_glass_edge)
        val layers = arrayOf(BitmapDrawable(resources, blurredBitmap), edgeDrawable)
        val layerDrawable = LayerDrawable(layers)
        background = layerDrawable
    }

    private fun animatePress(pressed: Boolean) {
        pressAnimator?.cancel()
        val fromScale = if (pressed) 1f else pressScale
        val toScale = if (pressed) pressScale else 1f
        pressAnimator = ValueAnimator.ofFloat(fromScale, toScale).apply {
            duration = animDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                scaleX = value
                scaleY = value
            }
            start()
        }
    }
}