package com.example.foodocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws YOLO detection boxes on top of the camera preview
 * during snap mode targeting. Box coordinates are supplied normalized to the
 * ScanGuide ROI (0..1 in each dimension), so this view can recompute view-space
 * pixels per its own measured size — no camera transform math required.
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Box coordinates normalized to the ScanGuide ROI (0..1, top-left origin). */
    data class NormalizedBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )

    @Volatile
    private var boxes: List<NormalizedBox> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 80, 80) // warm red — pops against green ScanGuide
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 80, 80)
        style = Paint.Style.FILL
    }
    private val labelTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Update the boxes to render and trigger redraw. Safe to call from any thread. */
    fun updateBoxes(newBoxes: List<NormalizedBox>) {
        boxes = newBoxes
        postInvalidateOnAnimation()
    }

    /** Clear all boxes (e.g. when capture starts). */
    fun clear() {
        if (boxes.isNotEmpty()) {
            boxes = emptyList()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val current = boxes
        if (current.isEmpty()) return

        // ROI is what the analyzer sees — boxes were normalized against this.
        // Drawing inside the same ScanGuide rectangle ensures visual alignment
        // with the user's reference frame (the green box).
        val roi = ScanGuide.roi(width, height)
        val roiW = roi.width().toFloat()
        val roiH = roi.height().toFloat()
        val roiLeft = roi.left.toFloat()
        val roiTop = roi.top.toFloat()

        for (box in current) {
            val left = roiLeft + box.left * roiW
            val top = roiTop + box.top * roiH
            val right = roiLeft + box.right * roiW
            val bottom = roiTop + box.bottom * roiH
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Confidence label above the box (or below if there's no room above)
            val label = "%.0f%%".format(box.confidence * 100f)
            val txtWidth = labelTxt.measureText(label)
            val padX = 8f
            val padY = 4f
            val txtH = labelTxt.textSize
            val labelTop = if (top - txtH - padY * 2 >= roiTop) top - txtH - padY * 2 else bottom
            canvas.drawRect(
                left, labelTop,
                left + txtWidth + padX * 2, labelTop + txtH + padY * 2,
                labelBg,
            )
            canvas.drawText(label, left + padX, labelTop + txtH + padY - 4f, labelTxt)
        }
    }
}
