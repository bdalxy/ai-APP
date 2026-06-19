package com.aicompanion.app.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.aicompanion.app.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

abstract class GlassDialog : BottomSheetDialogFragment() {

    protected var glassBlurRadius = BlurUtils.DEFAULT_RADIUS
        set(value) { field = value.coerceIn(1f, 25f) }
    protected var glassBlurEnabled = true
    protected var glassEdgeHighlightEnabled = true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isHideable = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val contentView = onCreateGlassView(inflater, container, savedInstanceState)
        return createGlassContainer(contentView)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (glassBlurEnabled) { view.post { applyGlassBackground(view) } }
    }

    abstract fun onCreateGlassView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View

    fun refreshGlassBackground() { view?.let { applyGlassBackground(it) } }

    private fun createGlassContainer(contentView: View): View {
        val container = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = createGlassEdgeDrawable()
            clipToPadding = false
            setPadding(dpToPx(0), dpToPx(12), dpToPx(0), dpToPx(24))
        }
        container.addView(contentView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dpToPx(16), 0, dpToPx(16), 0) })
        return container
    }

    private fun applyGlassBackground(glassContainer: View) {
        if (!glassBlurEnabled) return
        val activity = activity ?: return
        val decorView = activity.window?.decorView ?: return
        if (decorView.width <= 0 || decorView.height <= 0) return
        if (glassContainer.width <= 0 || glassContainer.height <= 0) { glassContainer.post { applyGlassBackground(glassContainer) }; return }

        val location = IntArray(2)
        glassContainer.getLocationInWindow(location)
        val x = location[0]
        val y = location[1]

        val croppedBitmap = BlurUtils.captureViewForBlur(decorView, x, y, glassContainer.width, glassContainer.height)
        if (croppedBitmap == null) { applyFallbackGlassBackground(glassContainer); return }

        val blurredBitmap = BlurUtils.blurBitmap(requireContext(), croppedBitmap, glassBlurRadius)
        croppedBitmap.recycle()

        if (blurredBitmap == null) { applyFallbackGlassBackground(glassContainer); return }

        val edgeDrawable = createGlassEdgeDrawable()
        val layers = arrayOf(BitmapDrawable(resources, blurredBitmap), edgeDrawable)
        val layerDrawable = LayerDrawable(layers)
        glassContainer.background = layerDrawable
    }

    private fun createGlassEdgeDrawable(): LayerDrawable {
        val gradientStroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(dpToPx(16).toFloat(), dpToPx(16).toFloat(), dpToPx(16).toFloat(), dpToPx(16).toFloat(), 0f, 0f, 0f, 0f)
            colors = intArrayOf(ContextCompat.getColor(requireContext(), R.color.glass_edge_white), ContextCompat.getColor(requireContext(), R.color.glass_edge_blue))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(dpToPx(15).toFloat(), dpToPx(15).toFloat(), dpToPx(15).toFloat(), dpToPx(15).toFloat(), 0f, 0f, 0f, 0f)
            setColor(ContextCompat.getColor(requireContext(), R.color.glass_fill_white))
        }
        return LayerDrawable(arrayOf(gradientStroke, fill)).apply { setLayerInset(1, dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1)) }
    }

    private fun applyFallbackGlassBackground(view: View) { view.background = createGlassEdgeDrawable() }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}