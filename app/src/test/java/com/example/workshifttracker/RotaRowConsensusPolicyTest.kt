package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaRowConsensusPolicyTest {
    private fun c(
        column: Int, block: Int, rank: Int, row: Float,
        adjusted: Float, positive: Float, raw: Float,
        required: Float, penalty: Float = 0f, ocr: Boolean = false
    ) = RotaRowConsensusPolicy.Candidate(
        column, block, rank, row, adjusted, positive, raw, required, penalty, ocr
    )

    @Test
    fun never_quarantines_existing_matches() {
        val anchors = listOf(
            RotaRowConsensusPolicy.Anchor(0, 2, .20f),
            RotaRowConsensusPolicy.Anchor(1, 2, .52f),
            RotaRowConsensusPolicy.Anchor(3, 2, .78f)
        )
        val plan = RotaRowConsensusPolicy.select(anchors, emptyList(), setOf(0, 1, 3))
        assertTrue(plan.quarantinedColumns.isEmpty())
    }

    @Test
    fun selects_near_tied_same_block_runner_for_review() {
        val anchors = listOf(
            RotaRowConsensusPolicy.Anchor(1, 2, .70f),
            RotaRowConsensusPolicy.Anchor(3, 2, .68f),
            RotaRowConsensusPolicy.Anchor(5, 2, .72f),
            RotaRowConsensusPolicy.Anchor(6, 2, .71f)
        )
        val candidates = listOf(
            c(2, 2, 1, .38f, .541f, .750f, .019f, .061f, .088f),
            c(2, 2, 2, .70f, .539f, .721f, .030f, .054f, .062f, ocr = true)
        )
        val plan = RotaRowConsensusPolicy.select(
            anchors, candidates, setOf(1, 3, 5, 6)
        )
        assertEquals(1, plan.reviewCandidates.size)
        assertEquals(2, plan.reviewCandidates.single().rank)
    }

    @Test
    fun selects_lower_rank_ocr_candidate_when_close_to_column_best() {
        val anchors = listOf(
            RotaRowConsensusPolicy.Anchor(0, 0, .62f),
            RotaRowConsensusPolicy.Anchor(1, 0, .63f),
            RotaRowConsensusPolicy.Anchor(6, 0, .65f)
        )
        val candidates = listOf(
            c(4, 2, 1, .60f, .446f, .569f, -.035f, .037f),
            c(4, 0, 5, .64f, .423f, .572f, -.071f, .044f, .030f, ocr = true)
        )
        val plan = RotaRowConsensusPolicy.select(
            anchors, candidates, setOf(0, 1, 6)
        )
        assertEquals(1, plan.reviewCandidates.size)
        assertEquals(4, plan.reviewCandidates.single().column)
        assertEquals(5, plan.reviewCandidates.single().rank)
    }
}
