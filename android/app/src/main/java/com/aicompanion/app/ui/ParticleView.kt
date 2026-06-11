package com.aicompanion.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * 背景粒子视图 — 往世乐土主题粒子漂浮效果。
 * 在聊天主页面底层渲染缓慢飘浮的彩色粒子。
 */
class ParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 粒子数据类 */
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

    /** 粒子颜色池：粉红半透、紫色半透、白色微透 */
    private val colors = intArrayOf(
        Color.argb(128, 255, 183, 197),   // 粉色半透
        Color.argb(128, 155, 89, 182),     // 紫色半透
        Color.argb(96, 255, 255, 255)      // 白色微透
    )

    init {
        paint.style = Paint.Style.FILL
        generateParticles()
        startAnimation()
    }

    /** 生成 20 个随机粒子 */
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

    /** 启动无限循环动画 */
    private fun startAnimation() {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = java.lang.Long.MAX_VALUE
        animator.interpolator = LinearInterpolator()
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener {
            updateParticles()
            invalidate()
        }
        animator.start()
    }

    /** 更新粒子位置（约 60fps） */
    private fun updateParticles() {
        val density = 1f / resources.displayMetrics.density
        for (p in particles) {
            p.y += p.speedY * density * 0.016f
            p.x += p.speedX * density * 0.016f
            // 超出底部则回到顶部
            if (p.y > 1.2f) {
                p.y = -0.1f
                p.x = Random.nextFloat()
            }
            // 水平越界折回
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