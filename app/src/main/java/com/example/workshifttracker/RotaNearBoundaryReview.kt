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
        val y: Float
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
            val required = candidate.requiredSeparation
            candidate.column !in acceptedColumns && candidate.block == anchor &&
                candidate.rank == 1 && required != null && required.isFinite() &&
                candidate.positiveScore >= 0.54f &&
                candidate.confuserPenalty.isFinite() &&
                candidate.confuserPenalty <= 0.020f &&
                // Review-only tolerance for moderate run-to-run score jitter in an anchored block.
                // Automatic acceptance still requires the production boundary; this path
                // never enters accepted matches or profile training.
                candidate.rawSeparation > -0.030f &&
                candidate.rawSeparation < required &&
                required - candidate.rawSeparation <= 0.065f
        }.distinctBy { it.column }.sortedBy { it.column }
    }
}
