package com.example.foodocr.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

internal object OnnxTensorUtils {
    data class TensorOutput(
        val shape: List<Int>,
        val data: FloatArray,
    )

    fun tensorShape(tensor: OnnxTensor): LongArray {
        return tensor.info.shape
    }

    fun flatten(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        if (buffer != null) {
            val duplicate = buffer.duplicate()
            duplicate.rewind()
            val output = FloatArray(duplicate.remaining())
            duplicate.get(output)
            return output
        }

        val values = ArrayList<Float>()
        appendValues(tensor.value, values)
        return values.toFloatArray()
    }

    fun output(value: Any?): TensorOutput {
        return when (value) {
            is OnnxTensor -> TensorOutput(
                shape = tensorShape(value).map { it.toInt() },
                data = flatten(value),
            )
            else -> TensorOutput(
                shape = inferShape(value).toList(),
                data = flattenValue(value),
            )
        }
    }

    fun fixedInputHeight(info: TensorInfo, fallback: Int): Int {
        val shape = info.shape
        return shape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: fallback
    }

    fun fixedInputWidthOrNull(info: TensorInfo): Int? {
        val shape = info.shape
        return shape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
    }

    fun createFloatTensor(
        env: ai.onnxruntime.OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    private fun appendValues(value: Any?, output: MutableList<Float>) {
        when (value) {
            null -> Unit
            is Float -> output += value
            is FloatArray -> value.forEach { output += it }
            is Array<*> -> value.forEach { appendValues(it, output) }
            else -> error("Unsupported ONNX tensor value type: ${value::class.java.name}")
        }
    }

    private fun flattenValue(value: Any?): FloatArray {
        val values = ArrayList<Float>()
        appendValues(value, values)
        return values.toFloatArray()
    }

    private fun inferShape(value: Any?): IntArray {
        return when (value) {
            null -> intArrayOf()
            is Float -> intArrayOf()
            is FloatArray -> intArrayOf(value.size)
            is Array<*> -> {
                val childShape = inferShape(value.firstOrNull())
                intArrayOf(value.size) + childShape
            }
            else -> error("Unsupported ONNX tensor value type: ${value::class.java.name}")
        }
    }
}
