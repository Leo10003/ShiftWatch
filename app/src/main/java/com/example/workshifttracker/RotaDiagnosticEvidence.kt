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
        val confirmedByColumn: List<Int> = List(7) { 0 }
    ) {
        init {
            require(suggested >= 0 && confirmed >= 0)
            require(suggestionsByColumn.size == 7 && confirmedByColumn.size == 7)
            require(suggestionsByColumn.all { it >= 0 } && confirmedByColumn.all { it >= 0 })
        }
    }

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
