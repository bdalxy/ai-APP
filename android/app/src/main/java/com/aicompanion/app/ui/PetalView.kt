package com.aicompanion.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aicompanion.app.R
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

    /** 花瓣颜色池（从主题资源中获取） */
    private val petalColors: IntArray

    init {
        // 从资源中获取主题色作为花瓣颜色（匹配樱羽粉系）
        petalColors = intArrayOf(
            ContextCompat.getColor(context, R.color.sakura_pink),           // #FDF0F0 淡樱粉
            ContextCompat.getColor(context, R.color.sakura_gradient_start), // #FCE4E0 樱羽渐变起
            ContextCompat.getColor(context, R.color.sakura_header_end),     // #F8D8D8 樱羽标题栏尾（最接近 #F8D0D8）
            ContextCompat.getColor(context, R.color.sakura_pink),           // #FDF0F0 淡樱粉（最接近 #FDE8EC）
            ContextCompat.getColor(context, R.color.sakura_header_end)      // #F8D8D8 樱羽标题栏尾（最接近 #F9D8DC）
        )
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
                color = petalColors[Random.nextInt(petalColors.size)],
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

        // 花瓣中线高光（白色微透，对应 R.color.sakura_white）
        petalPaint.color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.sakura_white), 60
        )
        petalPaint.strokeWidth = 1f
        petalPaint.style = Paint.Style.STROKE
        canvas.drawLine(0f, -baseSize * 0.6f, 0f, baseSize * 0.2f, petalPaint)
        petalPaint.style = Paint.Style.FILL

        canvas.restore()
    }
}