package com.example.foodocr.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class YoloDateDetector(
    private val env: OrtEnvironment,
    modelBytes: ByteArray,
    private val inputSize: Int = 640,
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()

    fun detect(imageBgr: Mat): List<YoloDetection> {
        val (input, letterbox) = buildInput(imageBgr)
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)

        val rawDetections = tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = OnnxTensorUtils.output(results[0].value)
                decodeOutput(outputTensor, letterbox, imageBgr.cols(), imageBgr.rows())
            }
        }

        return nms(rawDetections)
    }

    override fun close() {
        session.close()
    }

    private fun buildInput(imageBgr: Mat): Pair<FloatArray, LetterboxInfo> {
        val scale = min(
            inputSize.toFloat() / max(1, imageBgr.cols()).toFloat(),
            inputSize.toFloat() / max(1, imageBgr.rows()).toFloat(),
        )
        val resizedWidth = (imageBgr.cols() * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (imageBgr.rows() * scale).roundToInt().coerceAtLeast(1)
        val padX = (inputSize - resizedWidth) / 2f
        val padY = (inputSize - resizedHeight) / 2f

        val resized = Mat()
        Imgproc.resize(imageBgr, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val padded = Mat(inputSize, inputSize, CvType.CV_8UC3, Scalar(114.0, 114.0, 114.0))
        val roi = Rect(padX.roundToInt(), padY.roundToInt(), resizedWidth, resizedHeight)
        val targetRegion = padded.submat(roi)
        resized.copyTo(targetRegion)
        targetRegion.release()

        val rgb = Mat()
        Imgproc.cvtColor(padded, rgb, Imgproc.COLOR_BGR2RGB)

        val data = OpenCvImageOps.cv8uc3ToChwFloat(rgb) { value -> value / 255f }

        resized.release()
        padded.release()
        rgb.release()
        return data to LetterboxInfo(scale, padX, padY, inputSize, inputSize)
    }

    private fun decodeOutput(
        tensor: OnnxTensorUtils.TensorOutput,
        letterbox: LetterboxInfo,
        originalWidth: Int,
        originalHeight: Int,
    ): List<YoloDetection> {
        val shape = tensor.shape
        val data = tensor.data
        if (shape.size < 3 || data.isEmpty()) return emptyList()

        val dim1 = shape[1]
        val dim2 = shape[2]
        val channelsFirst = dim1 <= dim2 && dim1 <= 128
        val channels = if (channelsFirst) dim1 else dim2
        val anchors = if (channelsFirst) dim2 else dim1

        fun value(anchor: Int, channel: Int): Float {
            return if (channelsFirst) {
                data[channel * anchors + anchor]
            } else {
                data[anchor * channels + channel]
            }
        }

        val detections = mutableListOf<YoloDetection>()
        for (anchor in 0 until anchors) {
            if (channels < 5) continue

            var bestClass = 0
            var score = value(anchor, 4)
            if (channels > 5) {
                score = Float.NEGATIVE_INFINITY
                for (classIndex in 4 until channels) {
                    val classScore = value(anchor, classIndex)
                    if (classScore > score) {
                        score = classScore
                        bestClass = classIndex - 4
                    }
                }
            }
            if (score < confThreshold) continue

            val cx = value(anchor, 0)
            val cy = value(anchor, 1)
            val width = value(anchor, 2)
            val height = value(anchor, 3)

            val left = ((cx - width / 2f) - letterbox.padX) / letterbox.scale
            val top = ((cy - height / 2f) - letterbox.padY) / letterbox.scale
            val right = ((cx + width / 2f) - letterbox.padX) / letterbox.scale
            val bottom = ((cy + height / 2f) - letterbox.padY) / letterbox.scale

            val box = RectFBox(
                left = left.coerceIn(0f, originalWidth.toFloat()),
                top = top.coerceIn(0f, originalHeight.toFloat()),
                right = right.coerceIn(0f, originalWidth.toFloat()),
                bottom = bottom.coerceIn(0f, originalHeight.toFloat()),
            )
            if (box.width >= 2f && box.height >= 2f) {
                detections += YoloDetection(box, score, bestClass)
            }
        }

        return detections
    }

    private fun nms(detections: List<YoloDetection>): List<YoloDetection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<YoloDetection>()
        for (candidate in sorted) {
            val overlaps = kept.any { keptDetection ->
                keptDetection.classId == candidate.classId &&
                    OpenCvImageOps.iou(keptDetection.bbox, candidate.bbox) > iouThreshold
            }
            if (!overlaps) kept += candidate
        }
        return kept
    }
}
