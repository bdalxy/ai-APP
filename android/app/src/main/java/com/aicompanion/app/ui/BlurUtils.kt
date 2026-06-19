package com.aicompanion.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import androidx.annotation.RequiresApi
import android.graphics.drawable.BitmapDrawable

object BlurUtils {

    const val DEFAULT_RADIUS = 15f
    private const val MAX_RADIUS = 25f
    private const val SCALE_FACTOR = 4

    fun blurViewBackground(target: View, radius: Float = DEFAULT_RADIUS, rootView: View) {
        val clampedRadius = radius.coerceIn(1f, MAX_RADIUS)
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val x = location[0]
        val y = location[1]
        val width = target.width
        val height = target.height
        if (width <= 0 || height <= 0) return

        val rootBitmap = captureView(rootView)
        if (rootBitmap == null) { applyFallbackBackground(target); return }

        val scaledWidth = width / SCALE_FACTOR
        val scaledHeight = height / SCALE_FACTOR
        val scaledX = x / SCALE_FACTOR
        val scaledY = y / SCALE_FACTOR

        val croppedBitmap = try {
            Bitmap.createBitmap(rootBitmap, scaledX.coerceIn(0, (rootBitmap.width - scaledWidth).coerceAtLeast(0)), scaledY.coerceIn(0, (rootBitmap.height - scaledHeight).coerceAtLeast(0)), scaledWidth.coerceAtMost(rootBitmap.width - scaledX.coerceAtLeast(0)), scaledHeight.coerceAtMost(rootBitmap.height - scaledY.coerceAtLeast(0)))
        } catch (e: Exception) { rootBitmap.recycle(); applyFallbackBackground(target); return }
        rootBitmap.recycle()

        val blurredBitmap = blurBitmap(target.context, croppedBitmap, clampedRadius)
        croppedBitmap.recycle()

        if (blurredBitmap != null) {
            val finalBitmap = Bitmap.createScaledBitmap(blurredBitmap, width, height, true)
            blurredBitmap.recycle()
            target.background = BitmapDrawable(target.resources, finalBitmap)
        } else { applyFallbackBackground(target) }
    }

    fun applyBlurToView(view: View, radius: Float = DEFAULT_RADIUS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { applyRenderEffectBlur(view, radius) }
    }

    fun removeBlurFromView(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { view.setRenderEffect(null) }
    }

    fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float = DEFAULT_RADIUS): Bitmap? {
        val clampedRadius = radius.coerceIn(1f, MAX_RADIUS)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurWithRenderEffect(context, bitmap, clampedRadius)
            else blurWithRenderScript(context, bitmap, clampedRadius)
        } catch (e: Exception) { createFallbackBlurredBitmap(bitmap) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun blurWithRenderEffect(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        return blurWithRenderScriptInternal(context, bitmap, radius)
    }

    private fun blurWithRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        return blurWithRenderScriptInternal(context, bitmap, radius)
    }

    private fun blurWithRenderScriptInternal(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val rs = RenderScript.create(context)
        val input = Allocation.createFromBitmap(rs, bitmap)
        val outputAlloc = Allocation.createFromBitmap(rs, output)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius)
        script.setInput(input)
        script.forEach(outputAlloc)
        outputAlloc.copyTo(output)
        input.destroy()
        outputAlloc.destroy()
        script.destroy()
        rs.destroy()
        return output
    }

    fun captureViewForBlur(root: View, x: Int, y: Int, width: Int, height: Int): Bitmap? {
        if (root.width <= 0 || root.height <= 0 || width <= 0 || height <= 0) return null
        val scaledWidth = width / SCALE_FACTOR
        val scaledHeight = height / SCALE_FACTOR
        if (scaledWidth <= 0 || scaledHeight <= 0) return null
        val rootBitmap = captureView(root) ?: return null
        val scaledX = (x / SCALE_FACTOR).coerceIn(0, (rootBitmap.width - scaledWidth).coerceAtLeast(0))
        val scaledY = (y / SCALE_FACTOR).coerceIn(0, (rootBitmap.height - scaledHeight).coerceAtLeast(0))
        val cropW = scaledWidth.coerceAtMost(rootBitmap.width - scaledX)
        val cropH = scaledHeight.coerceAtMost(rootBitmap.height - scaledY)
        val croppedBitmap = try { Bitmap.createBitmap(rootBitmap, scaledX, scaledY, cropW, cropH) } catch (e: Exception) { null }
        rootBitmap.recycle()
        return croppedBitmap
    }

    private fun captureView(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val scaledWidth = view.width / SCALE_FACTOR
        val scaledHeight = view.height / SCALE_FACTOR
        if (scaledWidth <= 0 || scaledHeight <= 0) return null
        val bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(1f / SCALE_FACTOR, 1f / SCALE_FACTOR)
        view.draw(canvas)
        return bitmap
    }

    private fun createFallbackBlurredBitmap(original: Bitmap): Bitmap? {
        val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(original, 0f, 0f, null)
        val overlayPaint = Paint().apply { color = 0x30FFFFFF.toInt() }
        canvas.drawRect(0f, 0f, original.width.toFloat(), original.height.toFloat(), overlayPaint)
        return output
    }

    private fun applyFallbackBackground(view: View) {
        view.setBackgroundColor(0xCCFFFFFF.toInt())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRenderEffectBlur(view: View, radius: Float) {
        val clampedRadius = radius.coerceIn(1f, MAX_RADIUS)
        view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(clampedRadius, clampedRadius, android.graphics.Shader.TileMode.CLAMP))
    }
}