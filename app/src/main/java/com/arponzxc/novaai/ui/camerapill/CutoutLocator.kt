package com.arponzxc.novaai.ui.camerapill

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager

data class CutoutPlacement(
    val alignment: CutoutAlignment,
    val cutoutRect: Rect?,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val statusBarTopPx: Int,
    val rotation: Int
)

/**
 * Computes front camera cutout geometry and safe floating pill coordinates
 * across different screen sizes, notch/punch-hole positions, and rotations.
 */
object CutoutLocator {

    fun inspectPlacement(context: Context, windowManager: WindowManager): CutoutPlacement {
        val metrics = DisplayMetrics()
        val rotation: Int
        val cutoutRect: Rect?
        val screenWidth: Int
        val screenHeight: Int
        var statusBarTopPx = dpToPx(context, 24)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            val display = try {
                context.display
            } catch (_: Exception) {
                null
            }
            rotation = display?.rotation ?: Surface.ROTATION_0
            val insets = windowMetrics.windowInsets
            val cutout = insets.displayCutout
            val topInsets = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
            if (topInsets.top > 0) statusBarTopPx = topInsets.top
            cutoutRect = selectTopOrPrimaryCutoutRect(cutout?.boundingRects, screenWidth, screenHeight)
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(context.resources.displayMetrics.widthPixels)
            screenHeight = metrics.heightPixels.coerceAtLeast(context.resources.displayMetrics.heightPixels)
            @Suppress("DEPRECATION")
            rotation = display?.rotation ?: Surface.ROTATION_0
            cutoutRect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                selectTopOrPrimaryCutoutRect(display?.cutout?.boundingRects, screenWidth, screenHeight)
            } else {
                null
            }
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) {
                statusBarTopPx = context.resources.getDimensionPixelSize(resId).coerceAtLeast(statusBarTopPx)
            }
        }

        val alignment = resolveAlignment(cutoutRect, screenWidth)
        return CutoutPlacement(
            alignment = alignment,
            cutoutRect = cutoutRect,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            statusBarTopPx = statusBarTopPx,
            rotation = rotation
        )
    }

    /**
     * Pure coordinate calculation helper (also directly unit-testable).
     */
    fun computeOverlayWindowPosition(
        placement: CutoutPlacement,
        pillWidthPx: Int,
        pillHeightPx: Int,
        edgePaddingPx: Int,
        topFallbackOffsetPx: Int
    ): Pair<Int, Int> {
        val screenW = placement.screenWidthPx.coerceAtLeast(pillWidthPx + edgePaddingPx * 2)
        val screenH = placement.screenHeightPx.coerceAtLeast(pillHeightPx + edgePaddingPx * 2)
        val rect = placement.cutoutRect

        val rawX: Int
        val rawY: Int

        val rectW = if (rect != null) rect.right - rect.left else 0
        val rectH = if (rect != null) rect.bottom - rect.top else 0

        if (rect != null && rectW > 0 && rectH > 0 && rect.top <= placement.statusBarTopPx + edgePaddingPx * 4) {
            // Center horizontally on the cutout when near center, or hug the cutout cleanly when corner-aligned.
            val centerX = ((rect.left + rect.right) / 2) - (pillWidthPx / 2)
            rawX = when (placement.alignment) {
                CutoutAlignment.CENTER -> centerX
                CutoutAlignment.LEFT -> (rect.left - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                CutoutAlignment.RIGHT -> (rect.right - pillWidthPx + edgePaddingPx)
            }
            // Vertically wrap/anchor neatly around the top cutout bar.
            val cutoutVerticalCenterY = ((rect.top + rect.bottom) / 2) - (pillHeightPx / 2)
            rawY = maxOf(edgePaddingPx / 2, cutoutVerticalCenterY)
        } else {
            rawX = (screenW - pillWidthPx) / 2
            rawY = topFallbackOffsetPx
        }

        val (clampedX, clampedY) = clampOverlayPosition(
            rawX = rawX,
            rawY = rawY,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            edgePaddingPx = edgePaddingPx
        )
        return clampedX to clampedY
    }

    fun clampOverlayPosition(
        rawX: Int,
        rawY: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        pillWidthPx: Int,
        pillHeightPx: Int,
        edgePaddingPx: Int
    ): Pair<Int, Int> {
        val maxX = (screenWidthPx - pillWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        val minY = edgePaddingPx / 2
        val maxY = (screenHeightPx - pillHeightPx - edgePaddingPx).coerceAtLeast(minY)
        return rawX.coerceIn(edgePaddingPx, maxX) to rawY.coerceIn(minY, maxY)
    }

    fun selectTopOrPrimaryCutoutRect(
        rects: List<Rect>?,
        screenWidth: Int,
        screenHeight: Int
    ): Rect? {
        if (rects.isNullOrEmpty()) return null
        // Prefer a cutout hugging the top edge (normal front camera / Dynamic Island zone).
        val topRect = rects
            .filter { (it.right - it.left) > 0 && (it.bottom - it.top) > 0 && it.top <= screenHeight / 5 }
            .minByOrNull { it.top }
        return topRect ?: rects.firstOrNull { (it.right - it.left) > 0 && (it.bottom - it.top) > 0 && it.left < screenWidth }
    }

    fun resolveAlignment(cutoutRect: Rect?, screenWidth: Int): CutoutAlignment {
        if (cutoutRect == null || screenWidth <= 0) return CutoutAlignment.CENTER
        val centerX = (cutoutRect.left + cutoutRect.right) / 2
        val ratio = centerX.toFloat() / screenWidth.toFloat()
        return when {
            ratio < 0.36f -> CutoutAlignment.LEFT
            ratio > 0.64f -> CutoutAlignment.RIGHT
            else -> CutoutAlignment.CENTER
        }
    }

    fun applyCutoutParams(params: WindowManager.LayoutParams) {
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
