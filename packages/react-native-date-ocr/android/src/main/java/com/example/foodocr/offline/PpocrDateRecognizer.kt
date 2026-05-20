package com.example.foodocr.offline

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.core.Mat
import kotlin.math.max

class PpocrDateRecognizer(
    private val env: OrtEnvironment,
    modelBytes: ByteArray,
    keys: List<String>,
    private val runAllVariants: Boolean = false,
) : AutoCloseable {

    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputInfo: TensorInfo = session.inputInfo[inputName]?.info as TensorInfo
    private val targetHeight: Int = OnnxTensorUtils.fixedInputHeight(inputInfo, OpenCvImageOps.MIN_REC_HEIGHT)
    private val fixedWidth: Int? = OnnxTensorUtils.fixedInputWidthOrNull(inputInfo)
    private val dictionary: List<String> = keys.map { it.trimEnd('\r', '\n') }.filter { it.isNotEmpty() }

    fun recognizeDateCrop(crop: Mat): RecVariantResult {
        val variants = OpenCvImageOps.preprocessVariants(crop)
        var best = RecVariantResult(
            variantName = "empty",
            text = "",
            confidence = 0f,
            date = null,
            inputShape = longArrayOf(1, 3, targetHeight.toLong(), (fixedWidth ?: 0).toLong()),
        )

        for (variant in variants) {
            val result = try {
                recognizeVariant(variant)
            } finally {
                variant.image.release()
            }

            if (result.date != null && !runAllVariants) {
                variants.dropWhile { it !== variant }.drop(1).forEach { it.image.release() }
                return result
            }

            if (best.text.isBlank() || result.confidence > best.confidence || result.date != null && best.date == null) {
                best = result
            }
        }

        return best
    }

    override fun close() {
        session.close()
    }

    private fun recognizeVariant(variant: PreprocessVariant): RecVariantResult {
        val (input, shape) = OpenCvImageOps.preparePpocrRecTensor(variant.image, targetHeight, fixedWidth)
        val tensor = OnnxTensorUtils.createFloatTensor(env, input, shape)

        val decoded = tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = OnnxTensorUtils.output(results[0].value)
                decode(outputTensor)
            }
        }

        val date = OfflineDatePatterns.extractDate(decoded.text)
        return RecVariantResult(
            variantName = variant.name,
            text = decoded.text,
            confidence = decoded.confidence,
            date = date,
            inputShape = shape,
        )
    }

    private fun decode(tensor: OnnxTensorUtils.TensorOutput): DecodedText {
        val shape = tensor.shape
        val data = tensor.data
        if (shape.size < 2 || data.isEmpty()) return DecodedText("", 0f)

        val charCount = dictionary.size + 1
        val timeSteps: Int
        val classes: Int
        val classesFirst: Boolean

        when {
            shape.size >= 3 && shape.last() == charCount -> {
                timeSteps = shape[shape.size - 2]
                classes = shape.last()
                classesFirst = false
            }
            shape.size >= 3 && shape[shape.size - 2] == charCount -> {
                timeSteps = shape.last()
                classes = shape[shape.size - 2]
                classesFirst = true
            }
            shape.size >= 3 -> {
                timeSteps = shape[1]
                classes = shape[2]
                classesFirst = false
            }
            else -> {
                timeSteps = shape[0]
                classes = shape[1]
                classesFirst = false
            }
        }

        fun value(t: Int, c: Int): Float {
            return if (classesFirst) {
                data[c * timeSteps + t]
            } else {
                data[t * classes + c]
            }
        }

        val builder = StringBuilder()
        var previousIndex = -1
        var scoreSum = 0f
        var scoreCount = 0

        for (t in 0 until timeSteps) {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val score = value(t, c)
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = c
                }
            }

            if (bestIndex != 0 && bestIndex != previousIndex) {
                val dictIndex = bestIndex - 1
                if (dictIndex in dictionary.indices) {
                    builder.append(dictionary[dictIndex])
                    scoreSum += bestScore
                    scoreCount += 1
                }
            }
            previousIndex = bestIndex
        }

        return DecodedText(
            text = builder.toString(),
            confidence = if (scoreCount == 0) 0f else scoreSum / max(1, scoreCount),
        )
    }

    private data class DecodedText(
        val text: String,
        val confidence: Float,
    )
}
