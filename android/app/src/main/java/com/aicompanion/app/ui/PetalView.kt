package com.aicompanion.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PetalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Petal(
        var x: Float,
        var y: Float,
        val scale: Float,
        val color: Int,
        var rotation: Float,
        val phase: Float,
        val amplitude: Float,
        val speedY: Float
    )

    companion object {
        private const val PETAL_COUNT = 5
        private val PETAL_COLORS = intArrayOf(
            Color.parseColor("#FDF0F0"),
            Color.parseColor("#FCE4E0"),
            Color.parseColor("#F8D0D8"),
            Color.parseColor("#FDE8EC"),
            Color.parseColor("#F9D8DC")
        )
        private const val FALL_DURATION_MS = 3000L
        private const val FRAME_INTERVAL_MS = 16L
    }

    private val petals = mutableListOf<Petal>()
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val petalPath = Path()
    private var animator: ValueAnimator? = null
    private var isAnimating = false
    private var viewWidth = 0f
    private var viewHeight = 0f

    init {
        petalPaint.style = Paint.Style.FILL
        petalPaint.isAntiAlias = true
        generatePetals()
    }

    private fun generatePetals() {
        petals.clear()
        for (i in 0 until PETAL_COUNT) {
            petals.add(Petal(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                scale = Random.nextFloat() * 0.3f + 0.6f,
                color = PETAL_COLORS[Random.nextInt(PETAL_COLORS.size)],
                rotation = Random.nextFloat() * 360f,
                phase = Random.nextFloat() * 2f * PI.toFloat(),
                amplitude = Random.nextFloat() * 0.04f + 0.02f,
                speedY = Random.nextFloat() * 0.15f + 0.25f
            ))
        }
    }

    fun startPetalAnimation() {
        if (isAnimating) stopPetalAnimation()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = FALL_DURATION_MS
        anim.interpolator = LinearInterpolator()
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener { animation ->
            val elapsed = (animation.currentPlayTime % FALL_DURATION_MS).toFloat() / FALL_DURATION_MS
            updatePetals(elapsed)
            invalidate()
        }
        anim.start()
        animator = anim
        isAnimating = true
    }

    fun stopPetalAnimation() {
        animator?.cancel()
        animator = null
        isAnimating = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isAnimating) startPetalAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPetalAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
    }

    private fun updatePetals(progress: Float) {
        for (petal in petals) {
            petal.y = (petal.y + petal.speedY * 0.016f) % 1.2f
            if (petal.y > 1.1f) {
                petal.y = -0.1f
                petal.x = Random.nextFloat()
                petal.rotation = Random.nextFloat() * 360f
            }
            petal.x += sin(progress * 2f * PI.toFloat() + petal.phase) * petal.amplitude * 0.016f
            if (petal.x < -0.05f) petal.x = 1.05f
            if (petal.x > 1.05f) petal.x = -0.05f
            petal.rotation = (petal.rotation + 0.5f) % 360f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth == 0f || viewHeight == 0f) return
        for (petal in petals) { drawPetal(canvas, petal) }
    }

    private fun drawPetal(canvas: Canvas, petal: Petal) {
        val cx = petal.x * viewWidth
        val cy = petal.y * viewHeight
        val baseSize = 18f * petal.scale

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(petal.rotation)

        petalPath.reset()
        petalPath.moveTo(0f, -baseSize * 0.8f)
        petalPath.cubicTo(baseSize * 0.6f, -baseSize * 0.5f, baseSize * 0.7f, baseSize * 0.6f, 0f, baseSize * 0.3f)
        petalPath.cubicTo(-baseSize * 0.7f, baseSize * 0.6f, -baseSize * 0.6f, -baseSize * 0.5f, 0f, -baseSize * 0.8f)
        petalPath.close()

        petalPaint.color = petal.color
        petalPaint.alpha = 200
        canvas.drawPath(petalPath, petalPaint)

        petalPaint.color = Color.argb(60, 255, 255, 255)
        petalPaint.strokeWidth = 1f
        petalPaint.style = Paint.Style.STROKE
        canvas.drawLine(0f, -baseSize * 0.6f, 0f, baseSize * 0.2f, petalPaint)
        petalPaint.style = Paint.Style.FILL

        canvas.restore()
    }
}