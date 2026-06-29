package com.aicompanion.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * 背景粒子视图 — 主题粒子漂浮效果。
 * 支持深色/亮色模式自动适配粒子颜色。
 */
class ParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var radius: Float,
        var color: Int,
        var speedY: Float,
        var speedX: Float,
        var phase: Float
    )

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 判断当前是否为深色模式 */
    private val isDarkMode: Boolean
        get() {
            val nightMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            return nightMode == Configuration.UI_MODE_NIGHT_YES
        }

    /** 根据主题返回不同的颜色池（使用樱羽主题色系） */
    private fun getColors(): IntArray {
        return if (isDarkMode) {
            intArrayOf(
                Color.argb(128, 255, 183, 197),       // 粉色半透（对应 typing_dot #FFB7C5）
                Color.argb(128, 176, 196, 222),        // 淡天蓝半透（对应 sakura_sky #B0C4DE）
                Color.argb(96, 255, 255, 255)          // 白色微透
            )
        } else {
            intArrayOf(
                Color.argb(100, 176, 196, 222),        // 淡天蓝半透（对应 sakura_sky #B0C4DE）
                Color.argb(100, 255, 183, 197),        // 粉色半透（对应 typing_dot #FFB7C5）
                Color.argb(60, 253, 240, 240),         // 淡樱粉微透（对应 sakura_pink #FDF0F0）
                Color.argb(40, 176, 196, 222)          // 淡天蓝微透（对应 sakura_sky #B0C4DE）
            )
        }
    }

    private var colors = getColors()

    init {
        paint.style = Paint.Style.FILL
        generateParticles()
        startAnimation()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        colors = getColors()
        for (p in particles) {
            p.color = colors[Random.nextInt(colors.size)]
        }
    }

    private fun generateParticles() {
        particles.clear()
        val density = resources.displayMetrics.density
        for (i in 0 until 20) {
            particles.add(
                Particle(
                    x = Random.nextFloat(),
                    y = Random.nextFloat(),
                    radius = (Random.nextFloat() * 1.5f + 1f) * density,
                    color = colors[Random.nextInt(colors.size)],
                    speedY = Random.nextFloat() * 0.3f + 0.1f,
                    speedX = Random.nextFloat() * 0.1f - 0.05f,
                    phase = Random.nextFloat() * Math.PI.toFloat() * 2
                )
            )
        }
    }

    private var animator: ValueAnimator? = null

    private fun startAnimation() {
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = java.lang.Long.MAX_VALUE
        anim.interpolator = LinearInterpolator()
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener {
            updateParticles()
            invalidate()
        }
        anim.start()
        animator = anim
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null || animator?.isRunning == false) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private fun updateParticles() {
        val density = 1f / resources.displayMetrics.density
        for (p in particles) {
            p.y += p.speedY * density * 0.016f
            p.x += p.speedX * density * 0.016f
            if (p.y > 1.2f) {
                p.y = -0.1f
                p.x = Random.nextFloat()
            }
            if (p.x < -0.1f) p.x = 1.1f
            if (p.x > 1.1f) p.x = -0.1f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (p in particles) {
            paint.color = p.color
            canvas.drawCircle(p.x * w, p.y * h, p.radius, paint)
        }
    }
}