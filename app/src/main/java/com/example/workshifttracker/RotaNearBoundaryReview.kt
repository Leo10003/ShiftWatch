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
        val x: Float,
        val y: Float,
        val corroboratingPositiveScore: Float? = null,
        val corroboratingRawSeparation: Float? = null,
        val corroboratingRequiredSeparation: Float? = null,
        val corroboratingConfuserPenalty: Float? = null,
        val corroboratingOcr: Boolean = false,
        val verticalDecile: Int? = null,
        val adjustedScore: Float? = null,
        val runnerBlock: Int? = null,
        val runnerAdjustedScore: Float? = null,
        val runnerPositiveScore: Float? = null,
        val runnerRawSeparation: Float? = null,
        val runnerRequiredSeparation: Float? = null,
        val runnerConfuserPenalty: Float? = null,
        val runnerVerticalDecile: Int? = null
    )

    fun select(
        acceptedBlocks: List<Int>,
        candidates: List<Candidate>,
        acceptedColumns: Set<Int>,
        acceptedAnchorDeciles: Map<Int, List<Int>> = emptyMap()
    ): List<Candidate> {
        // A consensus of TWO separately accepted weekdays is necessary; one
        // OCR guess is never enough to turn visual noise into a review marker.
        val counts = acceptedBlocks.groupingBy { it }.eachCount()
        val anchors = counts.filter { it.value >= 2 }
        val highest = anchors.values.maxOrNull() ?: return emptyList()
        val winners = anchors.filterValues { it == highest }
        if (winners.size != 1) return emptyList()
        val anchor = winners.keys.single()
        val anchorDeciles = acceptedAnchorDeciles[anchor].orEmpty()
        val minAnchorDecile = anchorDeciles.minOrNull()
        val maxAnchorDecile = anchorDeciles.maxOrNull()
        return candidates.filter { candidate ->
            val required = candidate.requiredSeparation ?: return@filter false
            val insideAnchorEnvelope = if (minAnchorDecile == null || maxAnchorDecile == null) {
                true
            } else {
                candidate.verticalDecile?.let { it in minAnchorDecile..maxAnchorDecile } == true
            }
            val ordinaryNearBoundary =
                candidate.positiveScore >= 0.54f &&
                candidate.confuserPenalty.isFinite() && candidate.confuserPenalty <= 0.020f &&
                candidate.rawSeparation > -0.030f && candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.065f

            val adjusted = candidate.adjustedScore
            val runnerAdjusted = candidate.runnerAdjustedScore
            val runnerOutsideAnchorEnvelope = if (
                minAnchorDecile == null || maxAnchorDecile == null
            ) {
                false
            } else {
                candidate.runnerVerticalDecile?.let {
                    it !in minAnchorDecile..maxAnchorDecile
                } == true
            }
            val anchoredStrongNearTie =
                insideAnchorEnvelope &&
                candidate.positiveScore >= 0.70f &&
                candidate.rawSeparation >= 0f &&
                candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.030f &&
                adjusted?.let { it >= 0.53f } == true &&
                candidate.runnerBlock == anchor &&
                runnerAdjusted != null && adjusted != null &&
                kotlin.math.abs(adjusted - runnerAdjusted) <= 0.010f &&
                runnerOutsideAnchorEnvelope

            val adjacentStrongNearBoundary =
                !insideAnchorEnvelope &&
                minAnchorDecile != null && maxAnchorDecile != null &&
                candidate.verticalDecile?.let {
                    it in (minAnchorDecile - 1)..(maxAnchorDecile + 1)
                } == true &&
                candidate.positiveScore >= 0.58f &&
                candidate.confuserPenalty.isFinite() && candidate.confuserPenalty <= 0.015f &&
                candidate.rawSeparation > -0.030f && candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.070f
            val corroboratingRequired = candidate.corroboratingRequiredSeparation
            val corroboratingPenalty = candidate.corroboratingConfuserPenalty
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
            val runnerRequired = candidate.runnerRequiredSeparation
            val ambiguousDominantRunner =
                candidate.block != anchor &&
                candidate.runnerBlock == anchor &&
                candidate.adjustedScore?.isFinite() == true &&
                candidate.runnerAdjustedScore?.isFinite() == true &&
                candidate.adjustedScore - candidate.runnerAdjustedScore <= 0.005f &&
                candidate.runnerVerticalDecile?.let {
                    minAnchorDecile != null && maxAnchorDecile != null &&
                        it in minAnchorDecile..maxAnchorDecile
                } == true &&
                candidate.runnerPositiveScore?.let { it >= 0.56f } == true &&
                candidate.runnerConfuserPenalty?.let {
                    it.isFinite() && it <= 0.005f
                } == true &&
                candidate.runnerRawSeparation?.let { it > -0.040f } == true &&
                runnerRequired?.isFinite() == true &&
                candidate.runnerRawSeparation != null &&
                runnerRequired != null &&
                candidate.runnerRawSeparation < runnerRequired &&
                runnerRequired - candidate.runnerRawSeparation <= 0.075f

            candidate.column !in acceptedColumns &&
                candidate.rank == 1 && required.isFinite() &&
                (
                    (candidate.block == anchor &&
                        ((ordinaryNearBoundary && insideAnchorEnvelope) ||
                            anchoredStrongNearTie ||
                            adjacentStrongNearBoundary ||
                            sameBlockCorroboration)) ||
                    ambiguousDominantRunner
                )
        }.distinctBy { it.column }.sortedBy { it.column }
    }
}
