package com.example.foodocr.offline

import com.example.foodocr.DateRole
import org.opencv.core.Mat
import kotlin.math.max

data class RectFBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = max(0f, right - left)
    val height: Float
        get() = max(0f, bottom - top)
    val area: Float
        get() = width * height
    val centerY: Float
        get() = (top + bottom) / 2f

    fun padded(horizontalPadding: Int, verticalPadding: Int, imageWidth: Int, imageHeight: Int): RectFBox {
        return RectFBox(
            left = (left - horizontalPadding).coerceAtLeast(0f),
            top = (top - verticalPadding).coerceAtLeast(0f),
            right = (right + horizontalPadding).coerceAtMost(imageWidth.toFloat()),
            bottom = (bottom + verticalPadding).coerceAtMost(imageHeight.toFloat()),
        )
    }

    fun toJson(): String {
        return "[${left.round3()},${top.round3()},${right.round3()},${bottom.round3()}]"
    }
}

data class YoloDetection(
    val bbox: RectFBox,
    val confidence: Float,
    val classId: Int = 0,
) {
    fun toJson(): String {
        return """{"bbox":${bbox.toJson()},"conf":${confidence.round3()},"class_id":$classId}"""
    }
}

data class RecVariantResult(
    val variantName: String,
    val text: String,
    val confidence: Float,
    val date: String?,
    val inputShape: LongArray,
) {
    fun toJson(): String {
        return """{"variant":"${variantName.escapeJson()}","text":"${text.escapeJson()}","conf":${confidence.round3()},"date":${date?.let { "\"${it.escapeJson()}\"" } ?: "null"},"input_shape":[${inputShape.joinToString(",")}]}"""
    }
}

data class CropCandidate(
    val detection: YoloDetection,
    val paddedBox: RectFBox,
    val crop: Mat,
    val recognition: RecVariantResult,
) {
    val date: String?
        get() = recognition.date

    fun toJson(): String {
        return """{"det":${detection.toJson()},"padded_bbox":${paddedBox.toJson()},"rec":${recognition.toJson()}}"""
    }
}

data class RoleAssignment(
    val role: DateRole,
    val date: String,
    val score: Double,
    val sourceText: String,
    val candidate: CropCandidate,
) {
    fun toJson(): String {
        return """{"role":"$role","date":"${date.escapeJson()}","score":${score.round3()},"source_text":"${sourceText.escapeJson()}","bbox":${candidate.detection.bbox.toJson()}}"""
    }
}

data class OfflineFrameResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val detections: List<YoloDetection>,
    val candidates: List<CropCandidate>,
    val assignments: List<RoleAssignment>,
    val elapsedMs: Long,
) {
    val manufacture: RoleAssignment?
        get() = assignments.firstOrNull { it.role == DateRole.MANUFACTURE }
    val expiry: RoleAssignment?
        get() = assignments.firstOrNull { it.role == DateRole.EXPIRY }

    fun toDebugJson(imageName: String? = null): String {
        val prefix = imageName?.let { """"image":"${it.escapeJson()}",""" } ?: ""
        return """{$prefix"image_width":$imageWidth,"image_height":$imageHeight,"elapsed_ms":$elapsedMs,"detections":[${detections.joinToString(",") { it.toJson() }}],"candidates":[${candidates.joinToString(",") { it.toJson() }}],"assignments":[${assignments.joinToString(",") { it.toJson() }}]}"""
    }
}

data class LetterboxInfo(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val inputWidth: Int,
    val inputHeight: Int,
)

data class PreprocessVariant(
    val name: String,
    val image: Mat,
)

internal fun String.escapeJson(): String {
    val builder = StringBuilder(length + 16)
    for (char in this) {
        when (char) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> {
                if (char.code < 0x20) {
                    builder.append("\\u")
                    builder.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
    }
    return builder.toString()
}

internal fun Float.round3(): String {
    return String.format(java.util.Locale.US, "%.3f", this)
}

internal fun Double.round3(): String {
    return String.format(java.util.Locale.US, "%.3f", this)
}
