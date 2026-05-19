package com.example.foodocr.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.example.foodocr.ScanGuide

fun ImageProxy.toBitmapFromJpeg(): Bitmap? {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun Bitmap.cropToScanGuideRoi(): Bitmap {
    val roi = ScanGuide.roi(width, height)
    return Bitmap.createBitmap(this, roi.left, roi.top, roi.width(), roi.height())
}
