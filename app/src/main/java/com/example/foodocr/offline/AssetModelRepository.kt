package com.example.foodocr.offline

import android.content.Context

data class OfflineModelAssets(
    val yoloModel: String = "models/yolo_date.onnx",
    val recModel: String = "models/rec_finetuned.onnx",
    val recKeys: String = "models/ppocr_keys_v1.txt",
)

object AssetModelRepository {
    fun hasRequiredAssets(context: Context, assets: OfflineModelAssets = OfflineModelAssets()): Boolean {
        return listOf(assets.yoloModel, assets.recModel, assets.recKeys).all { exists(context, it) }
    }

    fun readBytes(context: Context, assetPath: String): ByteArray {
        return context.assets.open(assetPath).use { stream -> stream.readBytes() }
    }

    fun readLines(context: Context, assetPath: String): List<String> {
        return context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines()
        }
    }

    private fun exists(context: Context, assetPath: String): Boolean {
        val folder = assetPath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = assetPath.substringAfterLast('/')
        return try {
            context.assets.list(folder)?.contains(name) == true
        } catch (_: Exception) {
            false
        }
    }
}
