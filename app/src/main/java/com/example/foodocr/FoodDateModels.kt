package com.example.foodocr

import android.os.SystemClock
import java.util.ArrayDeque

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
