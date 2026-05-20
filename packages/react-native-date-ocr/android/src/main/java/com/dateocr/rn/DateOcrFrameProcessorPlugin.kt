package com.dateocr.rn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.example.foodocr.offline.cropToScanGuideRoi
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin
import com.mrousavy.camera.frameprocessors.VisionCameraProxy
import java.io.ByteArrayOutputStream

/**
 * vision-camera Frame Processor Plugin that runs the same offline YOLO + PPOCR
 * pipeline used by `Ocr.detect()` on each live camera frame.
 *
 * Registered name: `detectDateFrame` (see [DateOcrPackage]).
 *
 * Inputs:
 *   - vision-camera [Frame] (YUV_420_888, with rotation info)
 *
 * Output (Map<String, Any?> consumable from a JS worklet):
 *   - `width`, `height`: ROI bitmap dimensions used as the YOLO input space.
 *     Detection box coordinates are relative to this size.
 *   - `detections`: List of `{ left, top, right, bottom, conf }`.
 *   - `candidates`: List of `{ text, date }` (rec output + regex-parsed date).
 *     `text` is raw recognition output (with prefixes / noise); JS callers should
 *     run their own date regex on it (Carrefour does this in `getRecognizedData`).
 *   - `elapsedMs`: pipeline runtime for this frame.
 *
 * The function returns null if:
 *   - The pipeline isn't initialized yet (caller hasn't run `Ocr.create()`).
 *   - The frame couldn't be decoded.
 *
 * Performance: callers should throttle invocation rate via vision-camera's
 * `frameProcessorFps` prop. 2-3 FPS is the recommended sweet spot — matches the
 * testMobileOcr auto-detect mode's 350 ms cadence.
 */
class DateOcrFrameProcessorPlugin(
    @Suppress("UNUSED_PARAMETER") proxy: VisionCameraProxy,
    @Suppress("UNUSED_PARAMETER") options: Map<String, Any>?,
) : FrameProcessorPlugin() {

    override fun callback(frame: Frame, arguments: Map<String, Any>?): Any? {
        val pipeline = SharedPipelineHolder.current ?: return null

        val bitmap = try {
            frameToBitmap(frame)
        } catch (error: Throwable) {
            Log.w(TAG, "Frame -> Bitmap failed", error)
            null
        } ?: return null

        val roi = try {
            bitmap.cropToScanGuideRoi()
        } finally {
            // The ROI is a new bitmap; the parent can be recycled.
            // We don't recycle ROI here because pipeline consumes it.
        }

        val result = pipeline.analyze(roi)

        val roiW = result.imageWidth
        val roiH = result.imageHeight

        val detections = result.detections.map { d ->
            mapOf(
                "left" to d.bbox.left.toDouble(),
                "top" to d.bbox.top.toDouble(),
                "right" to d.bbox.right.toDouble(),
                "bottom" to d.bbox.bottom.toDouble(),
                "conf" to d.confidence.toDouble(),
            )
        }

        val candidates = result.candidates.map { c ->
            mapOf(
                "text" to c.recognition.text,
                "date" to (c.recognition.date ?: ""),
                "conf" to c.recognition.confidence.toDouble(),
            )
        }

        return mapOf(
            "width" to roiW,
            "height" to roiH,
            "detections" to detections,
            "candidates" to candidates,
            "elapsedMs" to result.elapsedMs,
        )
    }

    // ── Frame -> Bitmap (YUV_420_888 -> NV21 -> JPEG -> Bitmap) ──────────────
    //
    // vision-camera frames are YUV_420_888 by default. Converting via JPEG is
    // the cheapest universally-portable path on Android (no NDK / RenderScript
    // required). Quality 90 keeps file artifacts low while staying fast (~10 ms
    // on a Snapdragon 8 Gen 2 for 1080p input).

    private fun frameToBitmap(frame: Frame): Bitmap? {
        val image: Image = frame.image ?: return null

        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        val raw = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null

        val rotation = frame.orientation.toDegrees()
        return if (rotation == 0) {
            raw
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val planes = image.planes
        val ySize = image.width * image.height
        val uvSize = image.width * image.height / 2
        val output = ByteArray(ySize + uvSize)

        // Y plane (straight copy honoring row stride)
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outIdx = 0
        for (row in 0 until image.height) {
            for (col in 0 until image.width) {
                output[outIdx++] = yBuffer.get(row * yRowStride + col * yPixelStride)
            }
        }

        // VU interleaved (NV21 = V then U)
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val chromaW = image.width / 2
        val chromaH = image.height / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                output[outIdx++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                output[outIdx++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }

        return output
    }

    /** vision-camera 4.x exposes [Frame.orientation] as an [Orientation] enum. */
    private fun com.mrousavy.camera.core.types.Orientation.toDegrees(): Int = when (this.name) {
        "PORTRAIT" -> 0
        "LANDSCAPE_LEFT" -> 270
        "PORTRAIT_UPSIDE_DOWN" -> 180
        "LANDSCAPE_RIGHT" -> 90
        else -> 0
    }

    companion object {
        private const val TAG = "DateOcrFrameProcessorPlugin"
        private const val JPEG_QUALITY = 90
    }
}
