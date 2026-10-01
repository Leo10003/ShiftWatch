package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaNearBoundaryReviewTest {
    private fun candidate(column: Int, block: Int?, raw: Float, required: Float?,
                          rank: Int = 1, positive: Float = 0.56f,
                          confuserPenalty: Float = 0f,
                          corroboratingPositive: Float? = null,
                          corroboratingRaw: Float? = null,
                          corroboratingRequired: Float? = null,
                          corroboratingPenalty: Float? = null,
                          corroboratingOcr: Boolean = false,
                          verticalDecile: Int? = null) =
        RotaNearBoundaryReview.Candidate(column, block, rank, positive, raw, required,
            confuserPenalty, 0.5f, 0.5f, corroboratingPositive, corroboratingRaw,
            corroboratingRequired, corroboratingPenalty, corroboratingOcr, verticalDecile)

    @Test fun nearBoundaryNeedsTwoAcceptedAnchors() {
        val near = candidate(2, 2, 0.0309f, 0.0406f)
        assertTrue(RotaNearBoundaryReview.select(listOf(2), listOf(near), setOf(1)).isEmpty())
        assertEquals(listOf(near), RotaNearBoundaryReview.select(listOf(2, 2), listOf(near), setOf(1, 3)))
    }

    @Test fun secondRotaOffersSaturdayButNotOffDays() {
        // Fresh 20.8.34 evidence: the anchored B1 candidate jitters slightly
        // negative on Saturday and Sunday while the two OFF days stay farther away.
        val input = listOf(candidate(2, 0, -0.0488f, 0.0368f, positive = 0.5569f), // Wed OFF
            candidate(3, 0, -0.0364f, 0.0398f, positive = 0.5846f), // Thu OFF
            candidate(4, 0, -0.0279f, 0.0347f, positive = 0.5669f), // Fri working
            candidate(5, 0, -0.0122f, 0.0316f, positive = 0.5671f), // Sat working
            candidate(6, 0, -0.0136f, 0.0360f, positive = 0.5882f)) // Sun working
        assertEquals(listOf(4, 5, 6), RotaNearBoundaryReview.select(listOf(0, 0), input, setOf(0, 1)).map { it.column })
    }


    @Test fun ordinaryReviewMustStayInsideAcceptedAnchorVerticalEnvelope() {
        // 0.9.1 paired second-rota evidence: accepted B1 anchors sit in deciles 2..3;
        // Wednesday OFF produced a plausible B1 candidate in decile 1, while Sunday is in 3.
        val wednesdayOff = candidate(2, 0, 0.00246f, 0.02939f,
            positive = 0.57056f, verticalDecile = 1)
        val sundayWorking = candidate(6, 0, 0.01202f, 0.03645f,
            positive = 0.61592f, confuserPenalty = 0.00266f, verticalDecile = 3)

        val selected = RotaNearBoundaryReview.select(
            listOf(0, 0, 0),
            listOf(wednesdayOff, sundayWorking),
            setOf(0, 1, 5),
            mapOf(0 to listOf(2, 2, 3))
        )

        assertEquals(listOf(6), selected.map { it.column })
    }

    @Test fun sameBlockOcrRunnerCanCorroborateOriginalWednesday() {
        // Fresh 20.8.36 original-rota Wednesday: rank 1 and rank 2 both land in B3.
        // The OCR-derived runner has near-neutral raw separation even though the leading
        // probe is too weak for the ordinary near-boundary gate.
        val wednesday = candidate(2, 2, -0.0590f, 0.0338f, positive = 0.5312f,
            corroboratingPositive = 0.5212f, corroboratingRaw = -0.0051f,
            corroboratingRequired = 0.0250f, corroboratingPenalty = 0f,
            corroboratingOcr = true)
        assertEquals(listOf(2),
            RotaNearBoundaryReview.select(listOf(2, 2, 2), listOf(wednesday), setOf(0, 1, 3, 6))
                .map { it.column })
    }

    @Test fun sameBlockCorroborationNeedsOcrRunnerAndNearNeutralSeparation() {
        val probeOnly = candidate(2, 2, -0.0590f, 0.0338f, positive = 0.5312f,
            corroboratingPositive = 0.5212f, corroboratingRaw = -0.0051f,
            corroboratingRequired = 0.0250f, corroboratingPenalty = 0f,
            corroboratingOcr = false)
        val weakRunner = candidate(3, 2, -0.0590f, 0.0338f, positive = 0.5312f,
            corroboratingPositive = 0.5212f, corroboratingRaw = -0.0110f,
            corroboratingRequired = 0.0250f, corroboratingPenalty = 0f,
            corroboratingOcr = true)
        assertTrue(RotaNearBoundaryReview.select(listOf(2, 2),
            listOf(probeOnly, weakRunner), emptySet()).isEmpty())
    }

    @Test fun originalRotaRejectsFridayConfuserButOffersSaturday() {
        val input = listOf(
            candidate(4, 2, 0.0034f, 0.0574f, positive = 0.7136f, confuserPenalty = 0.0749f), // Fri OFF
            candidate(5, 2, -0.0140f, 0.0327f, positive = 0.5707f) // Sat working
        )
        assertEquals(listOf(5), RotaNearBoundaryReview.select(listOf(0, 2, 2, 2), input, setOf(0, 1, 3, 6)).map { it.column })
    }

    @Test fun neverSurfacesNegativeUnrankedOrAlreadyAcceptedCrops() {
        val items = listOf(candidate(2, 2, -0.031f, 0.04f),
            candidate(3, 2, 0.01f, 0.04f, rank = 2),
            candidate(4, 2, 0.001f, 0.07f),
            candidate(5, 2, 0.01f, 0.04f, positive = 0.53f),
            candidate(6, 2, 0.01f, null),
            candidate(1, 2, 0.02f, 0.04f))
        assertTrue(RotaNearBoundaryReview.select(listOf(2, 2), items, setOf(0, 1)).isEmpty())
    }

    @Test fun excessiveBoundaryShortfallStillCannotEnterReviewQueue() {
        val tooFar = candidate(2, 2, -0.029f, 0.040f, positive = 0.58f)
        assertTrue(RotaNearBoundaryReview.select(listOf(2, 2), listOf(tooFar), emptySet()).isEmpty())
    }

    @Test fun highConfuserPenaltyCannotEnterReviewQueue() {
        val suspicious = candidate(4, 2, 0.004f, 0.055f,
            positive = 0.71f, confuserPenalty = 0.075f)
        assertTrue(RotaNearBoundaryReview.select(listOf(2, 2), listOf(suspicious), emptySet()).isEmpty())
    }

    @Test fun tiesAcrossAnchorBlocksProduceNoImplicitDaySpecificExceptions() {
        val hit = candidate(4, 0, 0.01f, 0.035f)
        assertTrue(RotaNearBoundaryReview.select(listOf(0, 0, 2, 2), listOf(hit), emptySet()).isEmpty())
    }

}
