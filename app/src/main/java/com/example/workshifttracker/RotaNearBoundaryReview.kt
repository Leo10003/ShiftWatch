package com.example.workshifttracker

/**
 * Review-only path for the narrow gap where real handwriting is visible but the
 * learned confuser boundary prevents an automatic match. Never promote these
 * candidates into the normal acceptance or weekly-rescue paths.
 */
internal object RotaNearBoundaryReview {
    data class Candidate(
        val column: Int,
        val block: Int?,
        val rank: Int,
        val positiveScore: Float,
        val rawSeparation: Float,
        val requiredSeparation: Float?,
        val confuserPenalty: Float,
        val primaryOcr: Boolean = false,
        val x: Float,
        val y: Float,
        val corroboratingPositiveScore: Float? = null,
        val corroboratingRawSeparation: Float? = null,
        val corroboratingRequiredSeparation: Float? = null,
        val corroboratingConfuserPenalty: Float? = null,
        val corroboratingOcr: Boolean = false
    )

    fun select(acceptedBlocks: List<Int>, candidates: List<Candidate>, acceptedColumns: Set<Int>): List<Candidate> {
        // A consensus of TWO separately accepted weekdays is necessary; one
        // OCR guess is never enough to turn visual noise into a review marker.
        val counts = acceptedBlocks.groupingBy { it }.eachCount()
        val anchors = counts.filter { it.value >= 2 }
        val highest = anchors.values.maxOrNull() ?: return emptyList()
        val winners = anchors.filterValues { it == highest }
        if (winners.size != 1) return emptyList()
        val anchor = winners.keys.single()
        return candidates.filter { candidate ->
            val required = candidate.requiredSeparation ?: return@filter false
            val ordinaryNearBoundary = candidate.positiveScore >= 0.54f &&
                candidate.confuserPenalty.isFinite() && candidate.confuserPenalty <= 0.020f &&
                candidate.rawSeparation > -0.030f && candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.065f
            val corroboratingRequired = candidate.corroboratingRequiredSeparation
            val corroboratingPenalty = candidate.corroboratingConfuserPenalty
            // Stabilize OCR-backed review candidates that jitter a few thousandths around
            // the v20.8.36 raw/shortfall edge. Probe-led candidates keep the tighter gate.
            val ocrBackedEdge = candidate.primaryOcr &&
                candidate.positiveScore >= 0.54f &&
                candidate.confuserPenalty.isFinite() && candidate.confuserPenalty <= 0.020f &&
                candidate.rawSeparation > -0.035f && candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.070f
            val sameBlockCorroboration = candidate.positiveScore >= 0.52f &&
                candidate.confuserPenalty.isFinite() && candidate.confuserPenalty <= 0.020f &&
                candidate.rawSeparation > -0.065f && candidate.rawSeparation < required &&
                candidate.corroboratingOcr &&
                candidate.corroboratingPositiveScore?.let { it >= 0.50f } == true &&
                candidate.corroboratingRawSeparation?.let { it > -0.010f } == true &&
                corroboratingRequired?.isFinite() == true &&
                candidate.corroboratingRawSeparation != null &&
                corroboratingRequired != null &&
                candidate.corroboratingRawSeparation < corroboratingRequired &&
                corroboratingPenalty?.isFinite() == true && corroboratingPenalty <= 0.020f
            candidate.column !in acceptedColumns && candidate.block == anchor &&
                candidate.rank == 1 && required.isFinite() &&
                // Both branches remain review-only. The corroboration branch exists for the
                // narrow case where rank 1 and rank 2 independently land in the anchored block,
                // and the OCR-derived runner has near-neutral confuser separation.
                (ordinaryNearBoundary || ocrBackedEdge || sameBlockCorroboration)
        }.distinctBy { it.column }.sortedBy { it.column }
    }
}
