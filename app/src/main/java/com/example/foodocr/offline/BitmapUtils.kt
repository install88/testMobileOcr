package com.example.foodocr.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.example.foodocr.ScanGuide
import java.io.ByteArrayOutputStream

/** Decode a high-res JPEG capture from ImageCapture into a rotated Bitmap. */
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

/**
 * Decode a YUV_420_888 ImageAnalysis frame into a rotated Bitmap.
 * Used by live-preview YOLO analyzers (SnapActivity overlay, MainActivity auto-detect).
 * The YUV → NV21 → JPEG (quality 90) → Bitmap path is the cheapest universally
 * supported route on Android — no NDK or RenderScript required.
 */
fun ImageProxy.toBitmapFromYuv(jpegQuality: Int = 90): Bitmap? {
    val nv21 = yuv420ToNv21Bytes(this)
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
    val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun Bitmap.cropToScanGuideRoi(): Bitmap {
    val roi = ScanGuide.roi(width, height)
    return Bitmap.createBitmap(this, roi.left, roi.top, roi.width(), roi.height())
}

// ── YUV plane interleaving helpers ──────────────────────────────────────────

private fun yuv420ToNv21Bytes(image: ImageProxy): ByteArray {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val ySize = image.width * image.height
    val uvSize = image.width * image.height / 2
    val output = ByteArray(ySize + uvSize)

    copyPlane(yPlane, image.width, image.height, output, 0, 1)
    interleaveVU(image, vPlane, uPlane, output, ySize)
    return output
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    output: ByteArray,
    outputOffset: Int,
    outputPixelStride: Int,
) {
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    var outputIndex = outputOffset
    for (row in 0 until height) {
        for (col in 0 until width) {
            output[outputIndex] = buffer.get(row * rowStride + col * pixelStride)
            outputIndex += outputPixelStride
        }
    }
}

private fun interleaveVU(
    image: ImageProxy,
    vPlane: ImageProxy.PlaneProxy,
    uPlane: ImageProxy.PlaneProxy,
    output: ByteArray,
    outputOffset: Int,
) {
    val vBuffer = vPlane.buffer.duplicate()
    val uBuffer = uPlane.buffer.duplicate()
    val chromaHeight = image.height / 2
    val chromaWidth = image.width / 2
    var outputIndex = outputOffset

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            output[outputIndex++] = vBuffer.get(vIndex)
            output[outputIndex++] = uBuffer.get(uIndex)
        }
    }
}
