package com.example.foodocr

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class DateRole {
    MANUFACTURE,
    EXPIRY,
}

data class DateObservation(
    val role: DateRole,
    val normalizedDate: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val score: Double,
    val sourceText: String,
    val capturedAtMs: Long,
)

data class FrameQuality(
    val meanBrightness: Double,
    val contrast: Double,
    val glareRatio: Double,
    val isTooDark: Boolean,
    val isTooBright: Boolean,
    val isLowContrast: Boolean,
    val hint: String,
) {
    val shouldTryFallback: Boolean
        get() = isTooDark || isTooBright || isLowContrast || glareRatio >= 0.18
}

data class FrameAnalysis(
    val quality: FrameQuality,
    val manufactureObservation: DateObservation?,
    val expiryObservation: DateObservation?,
    val statusMessage: String,
    val debugText: String = "",
)

data class VoteState(
    val stableDate: String?,
    val pendingDate: String?,
    val count: Int,
    val totalScore: Double,
)

class WeightedVoteBuffer(
    private val capacity: Int = 12,
    private val windowMs: Long = 5_000L,
    private val stableMinCount: Int = 3,
    private val stableMinScore: Double = 6.0,
) {
    private val observations = ArrayDeque<DateObservation>()

    @Synchronized
    fun add(observation: DateObservation) {
        purgeExpired(observation.capturedAtMs)
        observations.addLast(observation)
        while (observations.size > capacity) {
            observations.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(now: Long = SystemClock.elapsedRealtime()): VoteState {
        purgeExpired(now)

        if (observations.isEmpty()) {
            return VoteState(
                stableDate = null,
                pendingDate = null,
                count = 0,
                totalScore = 0.0,
            )
        }

        val grouped = observations.groupBy { it.normalizedDate }
            .mapValues { entry ->
                val totalScore = entry.value.sumOf { it.score }
                val count = entry.value.size
                Pair(totalScore, count)
            }

        val top = grouped.maxByOrNull { it.value.first } ?: return VoteState(null, null, 0, 0.0)
        val runnerUpScore = grouped
            .filterKeys { it != top.key }
            .maxOfOrNull { it.value.first }
            ?: 0.0

        val topScore = top.value.first
        val topCount = top.value.second
        val isStable = topCount >= stableMinCount && topScore >= stableMinScore && topScore >= runnerUpScore * 1.25

        return VoteState(
            stableDate = if (isStable) top.key else null,
            pendingDate = if (isStable) null else top.key,
            count = topCount,
            totalScore = topScore,
        )
    }

    private fun purgeExpired(now: Long) {
        while (observations.isNotEmpty()) {
            val age = now - observations.first.capturedAtMs
            if (age <= windowMs) {
                break
            }
            observations.removeFirst()
        }
    }
}

class FoodDateAnalyzer(
    private val onFrameAnalyzed: (FrameAnalysis) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )
    private val isProcessing = AtomicBoolean(false)
    private var lastAnalyzedAt = 0L
    private var frameCounter = 0

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAnalyzedAt = now
        frameCounter += 1

        val quality = evaluateFrameQuality(imageProxy)
        val originalImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        recognizer.process(originalImage)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks
                    .flatMap { block ->
                        block.lines.map { line ->
                            val rawText = normalizeRawText(line.text)
                            OcrLine(
                                displayText = line.text.trim(),
                                rawText = rawText,
                                dateText = normalizeDateText(rawText),
                                boundingBox = line.boundingBox,
                            )
                        }
                    }
                    .filter { it.displayText.isNotBlank() }
                    .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }

                var detection = detectFrame(lines, quality, now)

                val shouldTryFallback = quality.shouldTryFallback &&
                    !detection.hasRoleHit &&
                    frameCounter % FALLBACK_FRAME_INTERVAL == 0

                if (shouldTryFallback) {
                    val enhancedImage = buildEnhancedImage(imageProxy)
                    if (enhancedImage != null) {
                        recognizer.process(enhancedImage)
                            .addOnSuccessListener { fallbackText ->
                                val fallbackLines = fallbackText.textBlocks
                                    .flatMap { block ->
                                        block.lines.map { line ->
                                            val rawText = normalizeRawText(line.text)
                                            OcrLine(
                                                displayText = line.text.trim(),
                                                rawText = rawText,
                                                dateText = normalizeDateText(rawText),
                                                boundingBox = line.boundingBox,
                                            )
                                        }
                                    }
                                    .filter { it.displayText.isNotBlank() }
                                    .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }

                                val fallbackDetection = detectFrame(fallbackLines, quality, now)
                                detection = if (fallbackDetection.totalScore >= detection.totalScore) {
                                    fallbackDetection.copy(usedFallback = true)
                                } else {
                                    detection
                                }
                            }
                            .addOnFailureListener { error ->
                                Log.w(TAG, "強化影像 OCR 失敗", error)
                            }
                            .addOnCompleteListener {
                                onFrameAnalyzed(buildFrameAnalysis(detection, quality))
                                finishFrame(imageProxy)
                            }
                    } else {
                        onFrameAnalyzed(buildFrameAnalysis(detection, quality))
                        finishFrame(imageProxy)
                    }
                } else {
                    onFrameAnalyzed(buildFrameAnalysis(detection, quality))
                    finishFrame(imageProxy)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "OCR 辨識失敗", error)
                onFrameAnalyzed(
                    FrameAnalysis(
                        quality = quality,
                        manufactureObservation = null,
                        expiryObservation = null,
                        statusMessage = "OCR 暫時失敗，請稍微停穩再試",
                    ),
                )
                finishFrame(imageProxy)
            }
    }

    override fun close() {
        recognizer.close()
    }

    private fun finishFrame(imageProxy: ImageProxy) {
        isProcessing.set(false)
        imageProxy.close()
    }

    private fun buildFrameAnalysis(detection: FrameDetection, quality: FrameQuality): FrameAnalysis {
        val statusMessage = when {
            detection.manufactureObservation != null && detection.expiryObservation != null ->
                if (detection.usedFallback) {
                    "已抓到製造與有效日期，正在跨幀確認"
                } else {
                    "已抓到兩種日期，正在跨幀投票"
                }
            detection.manufactureObservation != null ->
                "已抓到製造日期，正在補捉有效日期"
            detection.expiryObservation != null ->
                "已抓到有效日期，正在補捉製造日期"
            detection.hasUnlabeledDate ->
                "看到日期數字了，正在判斷 MFG / EXP"
            detection.lineCount == 0 ->
                "目前尚未讀到文字"
            quality.shouldTryFallback ->
                "畫面條件較差，已自動嘗試強化辨識"
            else ->
                "正在掃描日期..."
        }

        return FrameAnalysis(
            quality = quality,
            manufactureObservation = detection.manufactureObservation,
            expiryObservation = detection.expiryObservation,
            statusMessage = statusMessage,
        )
    }

    private fun detectFrame(
        lines: List<OcrLine>,
        quality: FrameQuality,
        capturedAtMs: Long,
    ): FrameDetection {
        val contexts = buildContexts(lines)
        val explicitCandidates = mutableListOf<DateObservation>()
        val unlabeledCandidates = mutableListOf<DateObservation>()

        for (context in contexts) {
            val parsedDates = parseDates(context.dateText, context.allowCompactFormats)
            if (parsedDates.isEmpty()) {
                continue
            }

            val segmentHits = findRoleSegments(context.rawText, context.dateText)
            val segmentCandidates = mutableListOf<DateObservation>()

            for (segment in segmentHits) {
                val segmentDates = parseDates(segment.dateText, true)
                for (parsedDate in segmentDates) {
                    val score = parsedDate.baseScore + segment.keywordWeight + context.contextBonus
                    segmentCandidates += parsedDate.toObservation(
                        role = segment.role,
                        score = score,
                        sourceText = segment.rawText.trim(),
                        capturedAtMs = capturedAtMs,
                    )
                }
            }

            explicitCandidates += segmentCandidates

            val manufactureKeywordScore = scoreKeywords(context.rawText, MANUFACTURE_KEYWORDS)
            val expiryKeywordScore = scoreKeywords(context.rawText, EXPIRY_KEYWORDS)

            for (parsedDate in parsedDates) {
                val sourceText = context.displayText
                if (manufactureKeywordScore > 0.0) {
                    explicitCandidates += parsedDate.toObservation(
                        role = DateRole.MANUFACTURE,
                        score = parsedDate.baseScore + manufactureKeywordScore + context.contextBonus,
                        sourceText = sourceText,
                        capturedAtMs = capturedAtMs,
                    )
                }
                if (expiryKeywordScore > 0.0) {
                    explicitCandidates += parsedDate.toObservation(
                        role = DateRole.EXPIRY,
                        score = parsedDate.baseScore + expiryKeywordScore + context.contextBonus,
                        sourceText = sourceText,
                        capturedAtMs = capturedAtMs,
                    )
                }
                if (manufactureKeywordScore == 0.0 && expiryKeywordScore == 0.0) {
                    unlabeledCandidates += parsedDate.toObservation(
                        role = DateRole.EXPIRY,
                        score = parsedDate.baseScore - 0.15,
                        sourceText = sourceText,
                        capturedAtMs = capturedAtMs,
                    )
                }
            }
        }

        val explicitByRole = explicitCandidates
            .groupBy { it.role }
            .mapValues { entry ->
                entry.value
                    .groupBy { it.normalizedDate }
                    .mapValues { sameDate -> sameDate.value.maxByOrNull { it.score }!! }
                    .values
                    .sortedByDescending { it.score }
            }

        var manufactureObservation = explicitByRole[DateRole.MANUFACTURE]?.firstOrNull()
        var expiryObservation = explicitByRole[DateRole.EXPIRY]?.firstOrNull()

        val unlabeledDistinct = unlabeledCandidates
            .groupBy { it.normalizedDate }
            .mapValues { sameDate -> sameDate.value.maxByOrNull { it.score }!! }
            .values
            .sortedWith(
                compareByDescending<DateObservation> { it.score }
                    .thenByDescending { it.year }
                    .thenByDescending { it.month }
                    .thenByDescending { it.day },
            )

        if (manufactureObservation == null || expiryObservation == null) {
            val sortedByDate = unlabeledDistinct.sortedBy { sortKey(it.year, it.month, it.day) }
            if (manufactureObservation == null && expiryObservation == null && sortedByDate.size >= 2) {
                val earliest = sortedByDate.first()
                val latest = sortedByDate.last()
                if (earliest.normalizedDate != latest.normalizedDate) {
                    manufactureObservation = earliest.copy(
                        role = DateRole.MANUFACTURE,
                        score = earliest.score + 0.7,
                    )
                    expiryObservation = latest.copy(
                        role = DateRole.EXPIRY,
                        score = latest.score + 0.7,
                    )
                }
            } else {
                if (manufactureObservation == null) {
                    val candidate = sortedByDate.firstOrNull {
                        expiryObservation == null || it.normalizedDate != expiryObservation.normalizedDate
                    }
                    if (candidate != null) {
                        manufactureObservation = candidate.copy(
                            role = DateRole.MANUFACTURE,
                            score = candidate.score + 0.35,
                        )
                    }
                }
                if (expiryObservation == null) {
                    val candidate = sortedByDate.lastOrNull {
                        manufactureObservation == null || it.normalizedDate != manufactureObservation.normalizedDate
                    }
                    if (candidate != null) {
                        expiryObservation = candidate.copy(
                            role = DateRole.EXPIRY,
                            score = candidate.score + 0.35,
                        )
                    }
                }
            }
        }

        val qualityPenalty = when {
            quality.isTooDark || quality.isTooBright -> 0.75
            quality.isLowContrast -> 0.9
            else -> 1.0
        }

        manufactureObservation = manufactureObservation?.copy(score = manufactureObservation.score * qualityPenalty)
        expiryObservation = expiryObservation?.copy(score = expiryObservation.score * qualityPenalty)

        return FrameDetection(
            manufactureObservation = manufactureObservation,
            expiryObservation = expiryObservation,
            hasUnlabeledDate = unlabeledDistinct.isNotEmpty(),
            hasRoleHit = explicitCandidates.isNotEmpty(),
            usedFallback = false,
            lineCount = lines.size,
            totalScore = (manufactureObservation?.score ?: 0.0) + (expiryObservation?.score ?: 0.0),
        )
    }

    private fun buildContexts(lines: List<OcrLine>): List<OcrContext> {
        val contexts = mutableListOf<OcrContext>()
        val seenKeys = mutableSetOf<String>()

        fun addContext(contextLines: List<OcrLine>, bonus: Double) {
            val sortedLines = contextLines.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
            val key = sortedLines.joinToString(separator = "|") { it.displayText }
            if (!seenKeys.add(key)) {
                return
            }
            contexts += OcrContext(
                rawText = sortedLines.joinToString(separator = "\n") { it.rawText },
                dateText = sortedLines.joinToString(separator = "\n") { it.dateText },
                displayText = sortedLines.joinToString(separator = " | ") { it.displayText },
                contextBonus = bonus,
                allowCompactFormats = sortedLines.any { hasRoleKeyword(it.rawText) } ||
                    sortedLines.joinToString(separator = "") { it.displayText }.count(Char::isDigit) >= 6,
            )
        }

        for ((index, line) in lines.withIndex()) {
            addContext(listOf(line), bonus = 0.0)

            for (nextIndex in index + 1 until lines.size) {
                val neighbor = lines[nextIndex]
                if (!areNearby(line.boundingBox, neighbor.boundingBox)) {
                    continue
                }
                addContext(listOf(line, neighbor), bonus = 0.45)
            }
        }

        return contexts
    }

    private fun areNearby(first: Rect?, second: Rect?): Boolean {
        if (first == null || second == null) {
            return false
        }

        val verticalGap = if (first.bottom < second.top) {
            second.top - first.bottom
        } else if (second.bottom < first.top) {
            first.top - second.bottom
        } else {
            0
        }
        val maxHeight = max(first.height(), second.height())
        if (verticalGap > maxHeight * 2) {
            return false
        }

        val overlapLeft = max(first.left, second.left)
        val overlapRight = min(first.right, second.right)
        val overlapWidth = max(0, overlapRight - overlapLeft)
        val minWidth = max(1, min(first.width(), second.width()))
        val overlapRatio = overlapWidth.toDouble() / minWidth.toDouble()

        return overlapRatio >= 0.2 || kotlin.math.abs(first.centerX() - second.centerX()) <= max(first.width(), second.width())
    }

    private fun findRoleSegments(rawText: String, dateText: String): List<RoleSegment> {
        val hits = mutableListOf<RoleKeywordHit>()
        addKeywordHits(hits, rawText, DateRole.MANUFACTURE, MANUFACTURE_KEYWORDS)
        addKeywordHits(hits, rawText, DateRole.EXPIRY, EXPIRY_KEYWORDS)

        if (hits.isEmpty()) {
            return emptyList()
        }

        val sortedHits = hits.sortedBy { it.start }
        val segments = mutableListOf<RoleSegment>()

        for ((index, hit) in sortedHits.withIndex()) {
            val segmentStart = hit.start
            val segmentEnd = if (index + 1 < sortedHits.size) sortedHits[index + 1].start else rawText.length
            val safeEnd = min(segmentEnd, min(rawText.length, dateText.length))
            val safeStart = min(segmentStart, safeEnd)
            val rawSegment = rawText.substring(safeStart, safeEnd)
            val dateSegment = dateText.substring(safeStart, safeEnd)
            segments += RoleSegment(
                role = hit.role,
                rawText = rawSegment,
                dateText = dateSegment,
                keywordWeight = hit.weight + if (sortedHits.size > 1) 0.2 else 0.0,
            )
        }

        return segments
    }

    private fun addKeywordHits(
        target: MutableList<RoleKeywordHit>,
        text: String,
        role: DateRole,
        keywords: List<String>,
    ) {
        for (keyword in keywords) {
            var startIndex = text.indexOf(keyword)
            while (startIndex >= 0) {
                target += RoleKeywordHit(
                    role = role,
                    start = startIndex,
                    weight = keywordWeight(keyword),
                )
                startIndex = text.indexOf(keyword, startIndex + keyword.length)
            }
        }
    }

    private fun hasRoleKeyword(text: String): Boolean {
        return scoreKeywords(text, MANUFACTURE_KEYWORDS) > 0.0 || scoreKeywords(text, EXPIRY_KEYWORDS) > 0.0
    }

    private fun scoreKeywords(text: String, keywords: List<String>): Double {
        return keywords.maxOfOrNull { keyword ->
            if (text.contains(keyword)) keywordWeight(keyword) else 0.0
        } ?: 0.0
    }

    private fun keywordWeight(keyword: String): Double {
        return when {
            keyword.length >= 4 -> 2.2
            keyword.length == 3 -> 1.8
            else -> 1.2
        }
    }

    private fun parseDates(text: String, allowCompactFormats: Boolean): List<ParsedDate> {
        val candidates = mutableMapOf<String, ParsedDate>()

        fun addCandidate(candidate: ParsedDate?) {
            candidate ?: return
            val key = candidate.normalizedDate
            val previous = candidates[key]
            if (previous == null || candidate.baseScore > previous.baseScore) {
                candidates[key] = candidate
            }
        }

        SEPARATED_YEAR_FIRST.findAll(text).forEach { match ->
            addCandidate(
                buildParsedDate(
                    year = match.groupValues[1].toIntOrNull(),
                    month = match.groupValues[2].toIntOrNull(),
                    day = match.groupValues[3].toIntOrNull(),
                    baseScore = 1.45,
                ),
            )
        }

        CHINESE_YEAR_FIRST.findAll(text).forEach { match ->
            val yearValue = match.groupValues[1].toIntOrNull()
            val parsedYear = if (match.groupValues[1].length == 3) {
                yearValue?.plus(1911)
            } else {
                yearValue
            }
            addCandidate(
                buildParsedDate(
                    year = parsedYear,
                    month = match.groupValues[2].toIntOrNull(),
                    day = match.groupValues[3].toIntOrNull(),
                    baseScore = if (match.groupValues[1].length == 3) 1.3 else 1.45,
                ),
            )
        }

        SEPARATED_YEAR_LAST.findAll(text).forEach { match ->
            val first = match.groupValues[1].toIntOrNull()
            val second = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()

            if (first != null && second != null && year != null) {
                addCandidate(buildParsedDate(year, first, second, 1.1))
                addCandidate(buildParsedDate(year, second, first, 1.0))
            }
        }

        SEPARATED_TWO_DIGIT_YEAR.findAll(text).forEach { match ->
            val first = match.groupValues[1].toIntOrNull()
            val second = match.groupValues[2].toIntOrNull()
            val third = match.groupValues[3].toIntOrNull()
            if (first == null || second == null || third == null) {
                return@forEach
            }
            addCandidate(buildParsedDate(expandTwoDigitYear(first), second, third, 0.85))
            addCandidate(buildParsedDate(expandTwoDigitYear(third), first, second, 0.65))
            addCandidate(buildParsedDate(expandTwoDigitYear(third), second, first, 0.6))
        }

        if (allowCompactFormats) {
            COMPACT_EIGHT_DIGITS.findAll(text).forEach { match ->
                addCompactCandidates(match.value, candidates)
            }
            COMPACT_SEVEN_DIGITS.findAll(text).forEach { match ->
                val digits = match.value
                val rocYear = digits.substring(0, 3).toIntOrNull()?.plus(1911)
                addCandidate(
                    buildParsedDate(
                        year = rocYear,
                        month = digits.substring(3, 5).toIntOrNull(),
                        day = digits.substring(5, 7).toIntOrNull(),
                        baseScore = 1.0,
                    ),
                )
            }
            COMPACT_SIX_DIGITS.findAll(text).forEach { match ->
                val digits = match.value
                addCandidate(
                    buildParsedDate(
                        year = expandTwoDigitYear(digits.substring(0, 2).toIntOrNull() ?: -1),
                        month = digits.substring(2, 4).toIntOrNull(),
                        day = digits.substring(4, 6).toIntOrNull(),
                        baseScore = 0.72,
                    ),
                )
                addCandidate(
                    buildParsedDate(
                        year = expandTwoDigitYear(digits.substring(4, 6).toIntOrNull() ?: -1),
                        month = digits.substring(0, 2).toIntOrNull(),
                        day = digits.substring(2, 4).toIntOrNull(),
                        baseScore = 0.65,
                    ),
                )
                addCandidate(
                    buildParsedDate(
                        year = expandTwoDigitYear(digits.substring(4, 6).toIntOrNull() ?: -1),
                        month = digits.substring(2, 4).toIntOrNull(),
                        day = digits.substring(0, 2).toIntOrNull(),
                        baseScore = 0.6,
                    ),
                )
            }
        }

        return candidates.values.sortedByDescending { it.baseScore }
    }

    private fun addCompactCandidates(token: String, target: MutableMap<String, ParsedDate>) {
        fun save(candidate: ParsedDate?) {
            candidate ?: return
            val previous = target[candidate.normalizedDate]
            if (previous == null || candidate.baseScore > previous.baseScore) {
                target[candidate.normalizedDate] = candidate
            }
        }

        val firstFour = token.substring(0, 4).toIntOrNull()
        if (firstFour != null && firstFour in 2000..2100) {
            save(
                buildParsedDate(
                    year = firstFour,
                    month = token.substring(4, 6).toIntOrNull(),
                    day = token.substring(6, 8).toIntOrNull(),
                    baseScore = 1.05,
                ),
            )
        }

        val lastFour = token.substring(4, 8).toIntOrNull()
        if (lastFour != null && lastFour in 2000..2100) {
            save(
                buildParsedDate(
                    year = lastFour,
                    month = token.substring(0, 2).toIntOrNull(),
                    day = token.substring(2, 4).toIntOrNull(),
                    baseScore = 0.92,
                ),
            )
            save(
                buildParsedDate(
                    year = lastFour,
                    month = token.substring(2, 4).toIntOrNull(),
                    day = token.substring(0, 2).toIntOrNull(),
                    baseScore = 0.84,
                ),
            )
        }
    }

    private fun buildParsedDate(
        year: Int?,
        month: Int?,
        day: Int?,
        baseScore: Double,
    ): ParsedDate? {
        if (year == null || month == null || day == null) {
            return null
        }
        if (!isValidDate(year, month, day)) {
            return null
        }
        return ParsedDate(
            year = year,
            month = month,
            day = day,
            baseScore = baseScore,
        )
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) {
            return false
        }
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> return false
        }
        return day <= maxDay
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private fun expandTwoDigitYear(year: Int): Int {
        return when {
            year < 0 -> -1
            year <= 69 -> 2000 + year
            else -> 1900 + year
        }
    }

    private fun normalizeRawText(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('：', ':')
            .replace('／', '/')
            .replace('－', '-')
            .replace('．', '.')
            .uppercase(Locale.ROOT)
    }

    private fun normalizeDateText(text: String): String {
        val builder = StringBuilder(text.length)
        for (character in text) {
            val normalizedCharacter = when (character) {
                'O', 'Q', 'D' -> '0'
                'S' -> '5'
                'B' -> '8'
                'I', 'L', '|', '!' -> '1'
                'Z' -> '2'
                ' ' -> ' '
                else -> character
            }
            builder.append(normalizedCharacter)
        }
        return builder.toString()
    }

    private fun evaluateFrameQuality(imageProxy: ImageProxy): FrameQuality {
        val plane = imageProxy.planes.firstOrNull() ?: return defaultQuality()
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) {
            return defaultQuality()
        }

        val rowStride = plane.rowStride
        val pixelStride = max(1, plane.pixelStride)
        val stepX = max(4, width / 48)
        val stepY = max(4, height / 48)

        var pixelCount = 0
        var sum = 0.0
        var sumSquares = 0.0
        var brightPixels = 0

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val index = y * rowStride + x * pixelStride
                if (index >= buffer.limit()) {
                    continue
                }
                val value = buffer.get(index).toInt() and 0xFF
                pixelCount += 1
                sum += value
                sumSquares += value.toDouble() * value.toDouble()
                if (value >= 245) {
                    brightPixels += 1
                }
            }
        }

        if (pixelCount == 0) {
            return defaultQuality()
        }

        val mean = sum / pixelCount
        val variance = max(0.0, sumSquares / pixelCount - mean * mean)
        val contrast = sqrt(variance)
        val glareRatio = brightPixels.toDouble() / pixelCount.toDouble()

        val tooDark = mean < 60.0
        val tooBright = mean > 205.0
        val lowContrast = contrast < 28.0

        val hint = when {
            glareRatio >= 0.18 -> "反光偏強，請把包裝微微傾斜，避免亮斑蓋住日期"
            tooDark -> "畫面偏暗，請靠近光源或打開手電筒"
            tooBright -> "畫面過亮，請稍微遠離光源或改變角度"
            lowContrast -> "字樣對比偏低，請再靠近一點並停穩"
            else -> "請讓日期區域位於中央，保持對焦並穩定 0.5 秒"
        }

        return FrameQuality(
            meanBrightness = mean,
            contrast = contrast,
            glareRatio = glareRatio,
            isTooDark = tooDark,
            isTooBright = tooBright,
            isLowContrast = lowContrast,
            hint = hint,
        )
    }

    private fun defaultQuality(): FrameQuality {
        return FrameQuality(
            meanBrightness = 128.0,
            contrast = 32.0,
            glareRatio = 0.0,
            isTooDark = false,
            isTooBright = false,
            isLowContrast = false,
            hint = "請讓日期區域位於中央，保持對焦並穩定 0.5 秒",
        )
    }

    private fun buildEnhancedImage(imageProxy: ImageProxy): InputImage? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = max(1, plane.pixelStride)
        val histogram = IntArray(256)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * rowStride + x * pixelStride
                if (index >= buffer.limit()) {
                    continue
                }
                histogram[buffer.get(index).toInt() and 0xFF] += 1
            }
        }

        val totalPixels = width * height
        val lowerBound = percentile(histogram, totalPixels, 0.03)
        val upperBound = percentile(histogram, totalPixels, 0.97)
        val inputRange = max(1, upperBound - lowerBound)

        val argbPixels = IntArray(width * height)
        var outputIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * rowStride + x * pixelStride
                if (index >= buffer.limit()) {
                    argbPixels[outputIndex] = Color.BLACK
                    outputIndex += 1
                    continue
                }
                val luminance = buffer.get(index).toInt() and 0xFF
                val stretched = (((luminance - lowerBound) * 255.0) / inputRange).toInt().coerceIn(0, 255)
                val boosted = when {
                    stretched > 210 -> 255
                    stretched < 45 -> 0
                    else -> stretched
                }
                argbPixels[outputIndex] = Color.rgb(boosted, boosted, boosted)
                outputIndex += 1
            }
        }

        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height)
        return InputImage.fromBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun percentile(histogram: IntArray, totalPixels: Int, percentile: Double): Int {
        val target = (totalPixels * percentile).toInt()
        var runningCount = 0
        for (index in histogram.indices) {
            runningCount += histogram[index]
            if (runningCount >= target) {
                return index
            }
        }
        return histogram.lastIndex
    }

    private fun sortKey(year: Int, month: Int, day: Int): Int {
        return year * 10_000 + month * 100 + day
    }

    private data class OcrLine(
        val displayText: String,
        val rawText: String,
        val dateText: String,
        val boundingBox: Rect?,
    )

    private data class OcrContext(
        val rawText: String,
        val dateText: String,
        val displayText: String,
        val contextBonus: Double,
        val allowCompactFormats: Boolean,
    )

    private data class ParsedDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val baseScore: Double,
    ) {
        val normalizedDate: String
            get() = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

        fun toObservation(
            role: DateRole,
            score: Double,
            sourceText: String,
            capturedAtMs: Long,
        ): DateObservation {
            return DateObservation(
                role = role,
                normalizedDate = normalizedDate,
                year = year,
                month = month,
                day = day,
                score = score,
                sourceText = sourceText,
                capturedAtMs = capturedAtMs,
            )
        }
    }

    private data class FrameDetection(
        val manufactureObservation: DateObservation?,
        val expiryObservation: DateObservation?,
        val hasUnlabeledDate: Boolean,
        val hasRoleHit: Boolean,
        val usedFallback: Boolean,
        val lineCount: Int,
        val totalScore: Double,
    )

    private data class RoleKeywordHit(
        val role: DateRole,
        val start: Int,
        val weight: Double,
    )

    private data class RoleSegment(
        val role: DateRole,
        val rawText: String,
        val dateText: String,
        val keywordWeight: Double,
    )

    companion object {
        private const val TAG = "FoodOCR"
        private const val ANALYSIS_INTERVAL_MS = 280L
        private const val FALLBACK_FRAME_INTERVAL = 3

        private val MANUFACTURE_KEYWORDS = listOf(
            "MFG",
            "MFD",
            "PROD",
            "PKD",
            "PACK",
            "MANUFACTURE",
            "MANUFACTURING",
            "PACKED ON",
            "製造日期",
            "製造",
            "生產日期",
            "生產",
            "出廠",
        )

        private val EXPIRY_KEYWORDS = listOf(
            "EXP",
            "EXPIRY",
            "EXPIRE",
            "USE BY",
            "USEBY",
            "BEST BEFORE",
            "BESTBEFORE",
            "BBE",
            "BBD",
            "有效日期",
            "有效期限",
            "到期",
            "賞味期限",
            "保存期限",
            "保存至",
        )

        private val SEPARATED_YEAR_FIRST = Regex("""(?<!\d)(\d{4})[./\-\s](\d{1,2})[./\-\s](\d{1,2})(?!\d)""")
        private val CHINESE_YEAR_FIRST = Regex("""(?<!\d)(\d{3,4})年(\d{1,2})月(\d{1,2})日?""")
        private val SEPARATED_YEAR_LAST = Regex("""(?<!\d)(\d{1,2})[./\-\s](\d{1,2})[./\-\s](\d{4})(?!\d)""")
        private val SEPARATED_TWO_DIGIT_YEAR = Regex("""(?<!\d)(\d{2})[./\-\s](\d{1,2})[./\-\s](\d{1,2})(?!\d)""")
        private val COMPACT_EIGHT_DIGITS = Regex("""(?<!\d)\d{8}(?!\d)""")
        private val COMPACT_SEVEN_DIGITS = Regex("""(?<!\d)\d{7}(?!\d)""")
        private val COMPACT_SIX_DIGITS = Regex("""(?<!\d)\d{6}(?!\d)""")
    }
}
