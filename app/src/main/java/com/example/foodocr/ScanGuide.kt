package com.example.foodocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

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
        return Rect(left.coerceIn(0, width - 1), top, right.coerceIn(left + 1, width), bottom)
    }
}

class ScanGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 100, 255, 218)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val roi = ScanGuide.roi(width, height)
        val frame = RectF(roi)

        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)

        canvas.drawRoundRect(frame, 8f, 8f, borderPaint)
        drawCorners(canvas, frame)
    }

    private fun drawCorners(canvas: Canvas, frame: RectF) {
        val length = (frame.width() * 0.08f).coerceAtMost(54f)
        canvas.drawLine(frame.left, frame.top, frame.left + length, frame.top, cornerPaint)
        canvas.drawLine(frame.left, frame.top, frame.left, frame.top + length, cornerPaint)

        canvas.drawLine(frame.right, frame.top, frame.right - length, frame.top, cornerPaint)
        canvas.drawLine(frame.right, frame.top, frame.right, frame.top + length, cornerPaint)

        canvas.drawLine(frame.left, frame.bottom, frame.left + length, frame.bottom, cornerPaint)
        canvas.drawLine(frame.left, frame.bottom, frame.left, frame.bottom - length, cornerPaint)

        canvas.drawLine(frame.right, frame.bottom, frame.right - length, frame.bottom, cornerPaint)
        canvas.drawLine(frame.right, frame.bottom, frame.right, frame.bottom - length, cornerPaint)
    }
}
