package com.example.foodocr.offline

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.foodocr.FrameAnalysis
import com.example.foodocr.FrameQuality
import com.example.foodocr.ScanGuide
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class OfflineOnnxDateAnalyzer(
    private val pipeline: OfflineDatePipeline,
    private val onFrameAnalyzed: (FrameAnalysis) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val isProcessing = AtomicBoolean(false)
    private var lastAnalyzedAt = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAnalyzedAt = now

        try {
            val bitmap = imageProxy.toBitmapViaJpeg()
            if (bitmap == null) {
                imageProxy.close()
                isProcessing.set(false)
                return
            }
            val roiBitmap = bitmap.cropToScanGuide()
            val result = pipeline.analyzeForCamera(roiBitmap)
            onFrameAnalyzed(result)
        } catch (error: Exception) {
            Log.w(TAG, "Offline ONNX frame failed", error)
            onFrameAnalyzed(
                FrameAnalysis(
                    quality = FrameQuality(
                        meanBrightness = 128.0,
                        contrast = 0.0,
                        glareRatio = 0.0,
                        isTooDark = false,
                        isTooBright = false,
                        isLowContrast = false,
                        hint = error.message?.take(160) ?: error::class.java.simpleName,
                    ),
                    manufactureObservation = null,
                    expiryObservation = null,
                    statusMessage = "ONNX ERROR: ${error::class.java.simpleName}",
                    debugText = "DBG error=${error::class.java.simpleName}\n${error.message?.take(220).orEmpty()}",
                ),
            )
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    override fun close() {
        // Pipeline is owned by OfflineDatePipelineProvider (process-scoped singleton); do not dispose it here.
    }

    private fun ImageProxy.toBitmapViaJpeg(): android.graphics.Bitmap? {
        val nv21 = yuv420ToNv21(this)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun android.graphics.Bitmap.cropToScanGuide(): android.graphics.Bitmap {
        val roi = ScanGuide.roi(width, height)
        return android.graphics.Bitmap.createBitmap(this, roi.left, roi.top, roi.width(), roi.height())
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
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

    companion object {
        private const val TAG = "OfflineOnnxDateAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 350L
        private const val JPEG_QUALITY = 90
    }
}
