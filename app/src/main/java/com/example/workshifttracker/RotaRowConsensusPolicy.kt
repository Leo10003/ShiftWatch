package com.example.workshifttracker

import kotlin.math.abs

/**
 * Review-only week-level row geometry.
 *
 * Row geometry may vary materially across photographed weekday cells because horizontal
 * rules are skewed or locally detected at different heights. Therefore this policy NEVER
 * removes an already accepted identity match. It only nominates conservative review hints.
 */
internal object RotaRowConsensusPolicy {
    data class Anchor(
        val column: Int,
        val block: Int,
        val rowFraction: Float
    )

    data class Candidate(
        val column: Int,
        val block: Int,
        val rank: Int,
        val rowFraction: Float,
        val adjustedScore: Float,
        val positiveScore: Float,
        val rawSeparation: Float,
        val requiredSeparation: Float?,
        val confuserPenalty: Float,
        val ocrDerived: Boolean
    )

    data class Plan(
        val quarantinedColumns: Set<Int> = emptySet(),
        val reviewCandidates: List<Candidate> = emptyList()
    )

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    fun select(
        trustedAnchors: List<Anchor>,
        candidates: List<Candidate>,
        acceptedColumns: Set<Int>
    ): Plan {
        if (trustedAnchors.size < 2) return Plan()

        val counts = trustedAnchors.groupingBy { it.block }.eachCount().filterValues { it >= 2 }
        val maxCount = counts.values.maxOrNull() ?: return Plan()
        val dominant = counts.filterValues { it == maxCount }.keys
        if (dominant.size != 1) return Plan()
        val anchorBlock = dominant.single()

        val anchorRows = trustedAnchors.filter { it.block == anchorBlock }.map { it.rowFraction }
        val anchorMedian = median(anchorRows) ?: return Plan()
        val anchorSpread = (anchorRows.maxOrNull() ?: anchorMedian) -
            (anchorRows.minOrNull() ?: anchorMedian)

        // Photographed blocks can be skewed. A broad accepted-row spread must widen review
        // tolerance, never delete accepted matches.
        val reviewTolerance = (0.22f + anchorSpread * 0.55f).coerceIn(0.22f, 0.36f)

        val bestByColumn = candidates.groupBy { it.column }
            .mapValues { (_, values) ->
                values.maxOfOrNull { it.adjustedScore } ?: Float.NEGATIVE_INFINITY
            }

        val review = candidates
            .asSequence()
            .filter { it.column !in acceptedColumns }
            .filter { it.block == anchorBlock && it.rank <= 5 }
            .filter { abs(it.rowFraction - anchorMedian) <= reviewTolerance }
            .filter { it.adjustedScore >= 0.41f && it.positiveScore >= 0.56f }
            .filter { it.confuserPenalty.isFinite() && it.confuserPenalty <= 0.070f }
            .filter {
                val required = it.requiredSeparation
                required != null && required.isFinite() &&
                    it.rawSeparation > -0.080f &&
                    it.rawSeparation < required &&
                    required - it.rawSeparation <= 0.125f
            }
            .filter {
                it.rank == 1 ||
                    (it.ocrDerived &&
                        it.adjustedScore >=
                            (bestByColumn[it.column] ?: it.adjustedScore) - 0.035f)
            }
            .groupBy { it.column }
            .mapNotNull { (_, values) ->
                values.sortedWith(
                    compareBy<Candidate> { abs(it.rowFraction - anchorMedian) }
                        .thenBy { it.rank }
                        .thenByDescending { it.adjustedScore }
                ).firstOrNull()
            }
            .sortedBy { it.column }

        return Plan(emptySet(), review)
    }
}
