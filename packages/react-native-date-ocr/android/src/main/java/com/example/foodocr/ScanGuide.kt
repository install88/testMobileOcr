package com.example.foodocr

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Centralized definition of the on-screen target rectangle (the green frame
 * the standalone testMobileOcr app calls "scan guide"). Mirrored in the
 * Carrefour DateOverlay.jsx ratios — keep both in sync if either changes.
 *
 * The standalone app also ships a ScanGuideView (a custom View that draws
 * the green frame). The module intentionally omits ScanGuideView because
 * RN host apps draw their own guide via JSX (Carrefour uses ScanMask).
 */
object ScanGuide {
    private const val LEFT_RATIO = 0.06f
    private const val RIGHT_RATIO = 0.94f
    private const val CENTER_Y_RATIO = 0.42f
    private const val HEIGHT_RATIO = 0.24f

    fun roi(width: Int, height: Int): Rect {
        val left = (width * LEFT_RATIO).roundToInt()
        val right = (width * RIGHT_RATIO).roundToInt()
        val roiHeight = (height * HEIGHT_RATIO).roundToInt().coerceAtLeast(1)
        val centerY = (height * CENTER_Y_RATIO).roundToInt()
        val top = (centerY - roiHeight / 2).coerceIn(0, height - 1)
        val bottom = (top + roiHeight).coerceIn(top + 1, height)
        return Rect(
            left.coerceIn(0, width - 1),
            top,
            right.coerceIn(left + 1, width),
            bottom,
        )
    }
}
