package com.example.foodocr.offline

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OrtEnvironment
import com.example.foodocr.DateObservation
import com.example.foodocr.DateRole
import com.example.foodocr.FrameAnalysis
import com.example.foodocr.FrameQuality
import org.opencv.core.Mat
import kotlin.math.max

class OfflineDatePipeline private constructor(
    private val env: OrtEnvironment,
    private val detector: YoloDateDetector,
    private val recognizer: PpocrDateRecognizer,
) : AutoCloseable {

    @Synchronized
    fun analyze(bitmap: Bitmap): OfflineFrameResult {
        val image = OpenCvImageOps.bitmapToBgr(bitmap)
        return try {
            analyze(image)
        } finally {
            image.release()
        }
    }

    @Synchronized
    fun analyze(imageBgr: Mat): OfflineFrameResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val detections = detector.detect(imageBgr)
        val candidates = mutableListOf<CropCandidate>()

        for (detection in detections) {
            val (paddedBox, crop) = OpenCvImageOps.cropWithPadding(imageBgr, detection.bbox)
            val recognition = try {
                recognizer.recognizeDateCrop(crop)
            } finally {
                crop.release()
            }
            if (recognition.text.isNotBlank() || recognition.date != null) {
                candidates += CropCandidate(
                    detection = detection,
                    paddedBox = paddedBox,
                    crop = Mat(),
                    recognition = recognition,
                )
            }
        }

        val assignments = assignRoles(candidates)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        return OfflineFrameResult(
            imageWidth = imageBgr.cols(),
            imageHeight = imageBgr.rows(),
            detections = detections,
            candidates = candidates,
            assignments = assignments,
            elapsedMs = elapsed,
        )
    }

    @Synchronized
    fun analyzeForCamera(bitmap: Bitmap): FrameAnalysis {
        val result = analyze(bitmap)
        val now = android.os.SystemClock.elapsedRealtime()
        return FrameAnalysis(
            quality = defaultQuality(result.elapsedMs),
            manufactureObservation = result.manufacture?.toObservation(now),
            expiryObservation = result.expiry?.toObservation(now),
            statusMessage = when {
                result.manufacture != null && result.expiry != null ->
                    "ONNX OK det=${result.detections.size} rec=${result.candidates.size} MFG+EXP"
                result.manufacture != null ->
                    "ONNX OK det=${result.detections.size} rec=${result.candidates.size} MFG only"
                result.expiry != null ->
                    "ONNX OK det=${result.detections.size} rec=${result.candidates.size} EXP only"
                result.candidates.isNotEmpty() ->
                    "ONNX partial det=${result.detections.size} rec=${result.candidates.size} no role"
                else ->
                    "ONNX no date det=${result.detections.size} rec=0"
            },
            debugText = buildDebugText(result),
        )
    }

    override fun close() {
        recognizer.close()
        detector.close()
        env.close()
    }

    private fun assignRoles(candidates: List<CropCandidate>): List<RoleAssignment> {
        val dated = candidates.filter { it.date != null }
        if (dated.isEmpty()) return emptyList()

        val explicit = mutableListOf<RoleAssignment>()
        for (candidate in dated) {
            val text = candidate.recognition.text.uppercase()
            val date = OfflineDatePatterns.normalizeForDisplay(candidate.date!!)
            manufactureScore(text)?.let { score ->
                explicit += RoleAssignment(DateRole.MANUFACTURE, date, score, text, candidate)
            }
            expiryScore(text)?.let { score ->
                explicit += RoleAssignment(DateRole.EXPIRY, date, score, text, candidate)
            }
        }

        val assignments = mutableListOf<RoleAssignment>()
        explicit
            .groupBy { it.role }
            .mapValues { entry -> entry.value.maxBy { it.score } }
            .values
            .forEach { assignments += it }

        val missingManufacture = assignments.none { it.role == DateRole.MANUFACTURE }
        val missingExpiry = assignments.none { it.role == DateRole.EXPIRY }

        if (missingManufacture || missingExpiry) {
            val remaining = dated
                .filter { candidate -> assignments.none { it.candidate === candidate } }
                .sortedWith(
                    compareBy<CropCandidate> { OfflineDatePatterns.sortKey(it.date!!) }
                        .thenBy { it.detection.bbox.centerY },
                )

            if (missingManufacture && missingExpiry) {
                if (remaining.size >= 2) {
                    val earliest = remaining.first()
                    val latest = remaining.last()
                    if (earliest.date != latest.date) {
                        assignments += fallbackAssignment(DateRole.MANUFACTURE, earliest, 0.65)
                        assignments += fallbackAssignment(DateRole.EXPIRY, latest, 0.65)
                    }
                } else {
                    remaining.firstOrNull()?.let { assignments += fallbackAssignment(DateRole.EXPIRY, it, 0.55) }
                }
            } else {
                if (missingManufacture) {
                    remaining.firstOrNull()?.let { assignments += fallbackAssignment(DateRole.MANUFACTURE, it, 0.45) }
                }
                if (missingExpiry) {
                    remaining.lastOrNull()?.let { assignments += fallbackAssignment(DateRole.EXPIRY, it, 0.45) }
                }
            }
        }

        return assignments
    }

    private fun fallbackAssignment(role: DateRole, candidate: CropCandidate, baseScore: Double): RoleAssignment {
        val date = OfflineDatePatterns.normalizeForDisplay(candidate.date!!)
        val score = baseScore + candidate.detection.confidence.toDouble()
        return RoleAssignment(role, date, score, candidate.recognition.text, candidate)
    }

    private fun manufactureScore(text: String): Double? {
        val hits = MANUFACTURE_KEYWORDS.count { text.contains(it) }
        if (hits == 0) return null
        return 2.0 + hits * 0.25
    }

    private fun expiryScore(text: String): Double? {
        val hits = EXPIRY_KEYWORDS.count { text.contains(it) }
        if (hits == 0) return null
        return 2.1 + hits * 0.25
    }

    private fun RoleAssignment.toObservation(capturedAtMs: Long): DateObservation {
        val parsed = OfflineDatePatterns.parse(date)
        return DateObservation(
            role = role,
            normalizedDate = parsed?.normalized ?: date,
            year = parsed?.year ?: 0,
            month = parsed?.month ?: 0,
            day = parsed?.day ?: 0,
            score = score,
            sourceText = sourceText,
            capturedAtMs = capturedAtMs,
        )
    }

    private fun defaultQuality(elapsedMs: Long): FrameQuality {
        return FrameQuality(
            meanBrightness = 128.0,
            contrast = 32.0,
            glareRatio = 0.0,
            isTooDark = false,
            isTooBright = false,
            isLowContrast = false,
            hint = "Offline ONNX elapsed ${max(1, elapsedMs)} ms",
        )
    }

    private fun buildDebugText(result: OfflineFrameResult): String {
        val lines = mutableListOf<String>()
        lines += "DBG det=${result.detections.size} rec=${result.candidates.size} ${result.elapsedMs}ms"
        result.candidates.take(3).forEachIndexed { index, candidate ->
            val rec = candidate.recognition
            val text = rec.text.ifBlank { "<blank>" }.take(80)
            val date = rec.date ?: "none"
            lines += "#${index + 1} ${rec.variantName} conf=${candidate.detection.confidence.round3()} date=$date"
            lines += "REC: $text"
        }
        if (result.candidates.isEmpty() && result.detections.isNotEmpty()) {
            lines += "REC: <blank from all detected boxes>"
        }
        return lines.joinToString("\n")
    }

    companion object {
        private val MANUFACTURE_KEYWORDS = listOf(
            "MFG",
            "MFD",
            "MANUFACTURE",
            "PROD",
            "\\u88fd\\u9020",
            "\\u751f\\u7523",
            "\\u51fa\\u5ee0",
        ).map { decodeRegexUnicode(it).uppercase() }

        private val EXPIRY_KEYWORDS = listOf(
            "EXP",
            "EXPIRE",
            "EXPIRY",
            "BEST",
            "BEFORE",
            "BB",
            "BBF",
            "\\u6709\\u6548",
            "\\u5230\\u671f",
            "\\u8cde\\u5473",
            "\\u4fdd\\u8cea",
        ).map { decodeRegexUnicode(it).uppercase() }

        fun create(
            context: Context,
            assets: OfflineModelAssets = OfflineModelAssets(),
            yoloInputSize: Int = 640,
            runAllRecVariants: Boolean = false,
        ): OfflineDatePipeline {
            OpenCvImageOps.ensureLoaded()
            val env = OrtEnvironment.getEnvironment()
            val detector = YoloDateDetector(
                env = env,
                modelBytes = AssetModelRepository.readBytes(context, assets.yoloModel),
                inputSize = yoloInputSize,
            )
            val recognizer = PpocrDateRecognizer(
                env = env,
                modelBytes = AssetModelRepository.readBytes(context, assets.recModel),
                keys = AssetModelRepository.readLines(context, assets.recKeys),
                runAllVariants = runAllRecVariants,
            )
            return OfflineDatePipeline(env, detector, recognizer)
        }

        private fun decodeRegexUnicode(value: String): String {
            return value
                .replace("\\u88fd", "\u88fd")
                .replace("\\u9020", "\u9020")
                .replace("\\u751f", "\u751f")
                .replace("\\u7523", "\u7523")
                .replace("\\u51fa", "\u51fa")
                .replace("\\u5ee0", "\u5ee0")
                .replace("\\u6709", "\u6709")
                .replace("\\u6548", "\u6548")
                .replace("\\u5230", "\u5230")
                .replace("\\u671f", "\u671f")
                .replace("\\u8cde", "\u8cde")
                .replace("\\u5473", "\u5473")
                .replace("\\u4fdd", "\u4fdd")
                .replace("\\u8cea", "\u8cea")
        }
    }
}
