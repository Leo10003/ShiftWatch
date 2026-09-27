package com.example.workshifttracker

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Aggregate, privacy-preserving evidence for development diagnostics. No OCR text,
 * employee names, exact document dates, photograph or image coordinates are exported.
 * Numbers describe *hypotheses*, never verified truth.
 */
internal object RotaDiagnosticEvidence {
    data class MarkerSummary(
        val viewerOpened: Boolean = false,
        val suggested: Int = 0,
        val confirmed: Int = 0,
        val suggestionsByColumn: List<Int> = List(7) { 0 },
        val confirmedByColumn: List<Int> = List(7) { 0 },
        val ocrHitsByColumn: List<Int> = List(7) { 0 },
        val profileDecisions: List<ProfileDecision> = emptyList(),
        val profileStatus: String = "not_attempted",
        val seededSearchDecisions: List<ProfileDecision> = emptyList(),
        val seededSearchStatus: String = "not_attempted",
        val markerDetails: List<MarkerDetail> = emptyList()
    ) {
        init {
            require(suggested >= 0 && confirmed >= 0)
            require(suggestionsByColumn.size == 7 && confirmedByColumn.size == 7)
            require(ocrHitsByColumn.size == 7)
            require(suggestionsByColumn.all { it >= 0 } && confirmedByColumn.all { it >= 0 })
            require(ocrHitsByColumn.all { it >= 0 })
        }
    }

    /** Compare two proposed weeks against the same parsed OCR header without exposing dates. */
    data class HeaderWeekFit(
        val observationMatches: Int,
        val explicitMatches: Int,
        val matchingColumns: Int,
        val totalObservations: Int
    )

    fun headerWeekFit(
        observations: List<RotaDateAuthorityEngine.Observation>,
        proposedMonday: LocalDate
    ): HeaderWeekFit {
        val matched = observations.filter { observation ->
            if (observation.column !in 0..6) false else {
                val day = proposedMonday.plusDays(observation.column.toLong())
                observation.day == day.dayOfMonth && (observation.month == null || observation.month == day.monthValue)
            }
        }
        return HeaderWeekFit(matched.size, matched.count { it.explicit },
            matched.map { it.column }.distinct().size, observations.size)
    }

    data class TimeInput(
        val physicalBlockIndex: Int?,
        val weekdayColumn: Int,
        val proposedTime: String,
        val tokenSource: String,
        val strong: Boolean,
        val alternate: Boolean,
        val confidenceDecile: Int,
        val geometryDecision: String
    )

    /** Only the top three anonymous candidates per day. No OCR, crops or precise coordinates. */
    data class RankedProfileCandidate(
        val rank: Int,
        val physicalBlockIndex: Int?,
        val verticalDecile: Int,
        val adjustedScore: Float,
        val positiveScore: Float,
        val confuserScore: Float,
        val rawSeparation: Float = 0f,
        val confuserPenalty: Float = 0f,
        val separationAdjustment: Float = 0f,
        val candidateOrigin: String = "unknown"
    ) {
        init {
            require(rank in 1..3 && verticalDecile in 0..9)
            require(physicalBlockIndex == null || physicalBlockIndex >= 0)
            require(adjustedScore in 0f..1f && positiveScore in 0f..1f && confuserScore in 0f..1f)
            require(rawSeparation in -1f..1f && confuserPenalty in 0f..1f)
            require(separationAdjustment in -1f..1f)
        }
    }

    data class ProfileDecision(
        val weekdayColumn: Int,
        val candidateCount: Int,
        val scoredCount: Int,
        val bestScore: Float?,
        val runnerScore: Float?,
        val acceptanceFloor: Float?,
        val confuserScore: Float?,
        val status: String,
        val rankedCandidates: List<RankedProfileCandidate> = emptyList(),
        val runnerOverlapFraction: Float? = null,
        val runnerIsSamePhysicalBlock: Boolean? = null
    )

    /** Candidate overlap is computed locally; exact image coordinates are never exported. */
    fun bandOverlapFraction(aTop: Int, aBottom: Int, bTop: Int, bBottom: Int): Float {
        val intersection = (minOf(aBottom, bBottom) - maxOf(aTop, bTop) + 1).coerceAtLeast(0)
        val shortest = minOf(aBottom - aTop + 1, bBottom - bTop + 1)
        return if (shortest <= 0) 0f else (intersection.toFloat() / shortest).coerceIn(0f, 1f)
    }

    /** Derive stable, coarse image-position buckets without revealing exact image coordinates. */
    fun verticalDecile(imageY: Float, imageHeight: Float): Int =
        if (imageHeight <= 0f) 0 else ((imageY / imageHeight).coerceIn(0f, 0.999f) * 10).toInt()

    /** Scores are rounded for troubleshooting, never raw image coordinates or OCR text. */
    data class MarkerDetail(
        val weekdayColumn: Int,
        val physicalBlockIndex: Int?,
        val origin: String,
        val scoreBucket: Int?,
        val confirmed: Boolean
    )

    data class DateHypothesis(
        val source: String,
        val confidence: Float,
        val authoritative: Boolean,
        val evidenceCount: Int,
        val explicitCount: Int,
        val reason: String,
        val offsetDaysFromPlannerFallback: Long
    )

    fun dateHypothesis(
        source: String,
        result: RotaDateAuthorityEngine.Resolution,
        plannerFallbackMonday: LocalDate
    ): DateHypothesis = DateHypothesis(
        source = source,
        confidence = result.confidence,
        authoritative = result.authoritative,
        evidenceCount = result.evidenceCount,
        explicitCount = result.explicitCount,
        reason = result.reason,
        offsetDaysFromPlannerFallback = ChronoUnit.DAYS.between(plannerFallbackMonday, result.weekStart)
    )

    /** A viewer that was never opened is *unknown*, not zero model suggestions. */
    fun status(summary: MarkerSummary): String = when {
        !summary.viewerOpened -> "not_observed_viewer_not_opened"
        else -> "observed_in_viewer"
    }
}
