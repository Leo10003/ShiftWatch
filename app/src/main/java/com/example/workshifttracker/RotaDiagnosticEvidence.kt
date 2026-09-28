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
        val recognitionRunId: String? = null,
        val recognitionLifecycle: String = "not_started",
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

    /** One row per candidate source, including losing candidates outside the exported top three.
     * These source summaries contain no OCR text or image coordinates.
     */
    data class CandidateSourceEvidence(
        val origin: String,
        val scoredCount: Int,
        val strongestAdjustedScore: Float,
        val strongestPositiveScore: Float,
        val strongestConfuserScore: Float,
        val strongestPhysicalBlockIndex: Int?
    ) {
        init {
            require(scoredCount > 0)
            require(strongestAdjustedScore in 0f..1f)
            require(strongestPositiveScore in 0f..1f)
            require(strongestConfuserScore in 0f..1f)
            require(strongestPhysicalBlockIndex == null || strongestPhysicalBlockIndex >= 0)
        }
    }

    /** Preserve both OCR and ink-probe alternatives even if only one reaches the top three. */
    fun sourceEvidence(candidates: List<Pair<String, RankedProfileCandidate>>): List<CandidateSourceEvidence> =
        candidates.groupBy { it.first }.toSortedMap().map { (origin, group) ->
            val winner = group.map { it.second }.sortedWith(
                compareByDescending<RankedProfileCandidate> { it.adjustedScore }
                    .thenByDescending { it.positiveScore }
                    .thenBy { it.physicalBlockIndex ?: Int.MAX_VALUE }
                    .thenBy { it.verticalDecile }
            ).first()
            CandidateSourceEvidence(origin, group.size, winner.adjustedScore, winner.positiveScore,
                winner.confuserScore, winner.physicalBlockIndex)
        }

    /** Instrumentation of candidate *generation*, not recognition confidence. Counts expose
     * where rows disappear without exporting OCR text or exact image coordinates.
     */
    data class CandidatePipeline(
        val strictCount: Int,
        val looseObserved: Int,
        val looseAdded: Int,
        val eligibleOcrTokens: Int,
        val ocrTokenDeciles: List<Int>,
        val ocrMerged: Int,
        val ocrAdded: Int,
        val probesAttempted: Int,
        val probesNearExisting: Int,
        val probesInkRejected: Int,
        val probesAdded: Int,
        val rejectedHeight: Int,
        val rejectedBody: Int,
        val finalCandidates: Int,
        val finalOcrCandidates: Int,
        val survivorsByBlock: Map<Int, Int>,
        val eligibleOcrTokensByBlock: Map<Int, Int> = emptyMap(),
        val ocrMergedByBlock: Map<Int, Int> = emptyMap(),
        val ocrAddedByBlock: Map<Int, Int> = emptyMap()
    ) {
        init {
            require(ocrTokenDeciles.size == 10 && ocrTokenDeciles.all { it >= 0 })
            require(listOf(strictCount, looseObserved, looseAdded, eligibleOcrTokens, ocrMerged,
                ocrAdded, probesAttempted, probesNearExisting, probesInkRejected, probesAdded,
                rejectedHeight, rejectedBody, finalCandidates, finalOcrCandidates).all { it >= 0 })
            require(looseAdded <= looseObserved && ocrMerged + ocrAdded == eligibleOcrTokens)
            require(ocrTokenDeciles.sum() == eligibleOcrTokens)
            require(probesNearExisting + probesInkRejected + probesAdded == probesAttempted)
            require(strictCount + looseAdded + ocrAdded + probesAdded ==
                finalCandidates + rejectedHeight + rejectedBody)
            require(finalOcrCandidates <= finalCandidates)
            require(survivorsByBlock.keys.all { it >= 0 } && survivorsByBlock.values.all { it >= 0 })
            require(survivorsByBlock.values.sum() <= finalCandidates)
            require(listOf(eligibleOcrTokensByBlock, ocrMergedByBlock, ocrAddedByBlock).all { map ->
                map.keys.all { it >= 0 } && map.values.all { it >= 0 }
            })
            require(eligibleOcrTokensByBlock.values.sum() <= eligibleOcrTokens)
            require(ocrMergedByBlock.values.sum() <= ocrMerged)
            require(ocrAddedByBlock.values.sum() <= ocrAdded)
            require((ocrMergedByBlock.keys + ocrAddedByBlock.keys).all { block ->
                (ocrMergedByBlock[block] ?: 0) + (ocrAddedByBlock[block] ?: 0) <=
                    (eligibleOcrTokensByBlock[block] ?: 0)
            })
        }
    }

    /** Shadow OCR crops are scored separately; never used in production matching. */
    data class ShadowScore(
        val physicalBlockIndex: Int?, val verticalDecile: Int,
        val adjustedScore: Float, val positiveScore: Float, val confuserScore: Float
    ) {
        init {
            require(physicalBlockIndex == null || physicalBlockIndex >= 0)
            require(verticalDecile in 0..9)
            require(listOf(adjustedScore, positiveScore, confuserScore).all { it in 0f..1f })
        }
    }

    data class ShadowOcrEvidence(
        val scoredCount: Int,
        val best: ShadowScore?,
        val scoredByBlock: Map<Int, Int>
    )

    fun shadowEvidence(rows: List<ShadowScore>): ShadowOcrEvidence {
        val best = rows.sortedWith(compareByDescending<ShadowScore> { it.adjustedScore }
            .thenByDescending { it.positiveScore }
            .thenBy { it.physicalBlockIndex ?: Int.MAX_VALUE }
            .thenBy { it.verticalDecile }).firstOrNull()
        return ShadowOcrEvidence(rows.size, best,
            rows.filter { it.physicalBlockIndex != null }
                .groupingBy { it.physicalBlockIndex!! }.eachCount().toSortedMap())
    }

    /** Summary of the best two scored crops inside each physical block, regardless of overall rank.
     * Diagnostic only: these rows cannot alter the matcher or generate shift suggestions.
     */
    data class BlockScoreEvidence(
        val physicalBlockIndex: Int,
        val candidateCount: Int,
        val best: RankedProfileCandidate,
        val runner: RankedProfileCandidate?,
        val scoreMargin: Float?
    )

    fun blockScoreEvidence(rows: List<RankedProfileCandidate>): List<BlockScoreEvidence> =
        rows.filter { it.physicalBlockIndex != null }
            .groupBy { it.physicalBlockIndex!! }.toSortedMap().map { (block, candidates) ->
                val ordered = candidates.sortedWith(
                    compareByDescending<RankedProfileCandidate> { it.adjustedScore }
                        .thenByDescending { it.positiveScore }
                        .thenBy { it.verticalDecile }.thenBy { it.candidateOrigin }
                        .thenBy { it.confuserScore }
                )
                val runner = ordered.getOrNull(1)
                BlockScoreEvidence(block, ordered.size, ordered[0].copy(rank = 1),
                    runner?.copy(rank = 2), runner?.let { ordered[0].adjustedScore - it.adjustedScore })
            }

    /** Anonymous candidate crop experiments: scores are never used for shift acceptance. */
    data class VerticalCrop(val label: String, val top: Int, val bottom: Int)
    data class CropVariantScore(
        val variant: String, val adjustedScore: Float, val positiveScore: Float,
        val confuserScore: Float, val rawSeparation: Float,
        val confuserPenalty: Float, val separationAdjustment: Float
    )
    data class CropExperiment(
        val physicalBlockIndex: Int, val candidateRankInBlock: Int,
        val candidateOrigin: String, val verticalDecile: Int,
        val variants: List<CropVariantScore>, val overlapWithOtherTop: Float?
    )

    /** A fixed set of symmetric crop variants. Boundaries are never exported.
     * Avoid introducing new pixels from outside the bitmap; deduplicate at edges.
     * These compare only vertical geometry, not OCR/preprocessing methods.
     */
    fun verticalCropVariants(top: Int, bottom: Int, imageHeight: Int): List<VerticalCrop> {
        require(imageHeight > 0 && top in 0 until imageHeight && bottom in top until imageHeight)
        val height = bottom - top + 1
        val widen28 = maxOf(3, (height * 0.28f).toInt())
        val widen52 = maxOf(5, (height * 0.52f).toInt())
        val trim = maxOf(1, (height * 0.12f).toInt())
        val candidates = listOf(
            VerticalCrop("original", top, bottom),
            VerticalCrop("widen_28", (top - widen28).coerceAtLeast(0),
                (bottom + widen28).coerceAtMost(imageHeight - 1)),
            VerticalCrop("widen_52", (top - widen52).coerceAtLeast(0),
                (bottom + widen52).coerceAtMost(imageHeight - 1)),
            VerticalCrop("trim_12", top + trim, bottom - trim)
        )
        return candidates.filter { it.bottom > it.top }
            .distinctBy { it.top to it.bottom }
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
        val runnerIsSamePhysicalBlock: Boolean? = null,
        val candidateSources: List<CandidateSourceEvidence> = emptyList(),
        val pipeline: CandidatePipeline? = null,
        val shadowOcr: ShadowOcrEvidence? = null,
        val productionByBlock: List<BlockScoreEvidence> = emptyList(),
        val shadowByBlock: List<BlockScoreEvidence> = emptyList(),
        val cropExperiments: List<CropExperiment> = emptyList()
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
