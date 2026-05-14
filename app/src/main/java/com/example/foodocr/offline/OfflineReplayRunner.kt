package com.example.foodocr.offline

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File

class OfflineReplayRunner(
    private val context: Context,
    private val pipeline: OfflineDatePipeline,
) {
    fun runFromAssets(
        imageFolder: String = "replay/val",
        labelAsset: String = "replay/val_label.txt",
        outputFile: File = File(context.filesDir, "offline_replay_report.jsonl"),
    ): ReplaySummary {
        val lines = AssetModelRepository.readLines(context, labelAsset).filter { it.isNotBlank() }
        var total = 0
        var matched = 0
        val records = StringBuilder()

        for (line in lines) {
            val tabIndex = line.indexOf('\t')
            if (tabIndex <= 0) continue
            val imageName = line.substring(0, tabIndex)
            val annotations = JSONArray(line.substring(tabIndex + 1))
            val gtDates = extractGroundTruthDates(annotations)
            if (gtDates.isEmpty()) continue

            val bitmap = context.assets.open("$imageFolder/$imageName").use { input ->
                BitmapFactory.decodeStream(input)
            } ?: continue

            total += 1
            val result = pipeline.analyze(bitmap)
            val predDates = result.assignments.map { OfflineDatePatterns.digits(it.date) }.filter { it.isNotBlank() }
            val gtDigitDates = gtDates.map { OfflineDatePatterns.digits(it) }.filter { it.isNotBlank() }
            val isMatched = predDates.any { pred -> gtDigitDates.any { gt -> gt == pred } }
            if (isMatched) matched += 1

            records.append(
                """{"image":"${imageName.escapeJson()}","matched":$isMatched,"gt":[${gtDates.joinToString(",") { "\"${it.escapeJson()}\"" }}],"pred":[${result.assignments.joinToString(",") { "\"${it.date.escapeJson()}\"" }}],"debug":${result.toDebugJson()}}""",
            )
            records.append('\n')
        }

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(records.toString(), Charsets.UTF_8)
        return ReplaySummary(
            total = total,
            matched = matched,
            accuracy = if (total == 0) 0.0 else matched.toDouble() / total.toDouble(),
            outputPath = outputFile.absolutePath,
        )
    }

    private fun extractGroundTruthDates(annotations: JSONArray): List<String> {
        val dates = mutableListOf<String>()
        for (index in 0 until annotations.length()) {
            val item = annotations.optJSONObject(index) ?: continue
            val transcription = item.optString("transcription")
            if (OfflineDatePatterns.isDateText(transcription)) {
                dates += OfflineDatePatterns.extractDate(transcription) ?: transcription
            }
        }
        return dates
    }
}

data class ReplaySummary(
    val total: Int,
    val matched: Int,
    val accuracy: Double,
    val outputPath: String,
)
