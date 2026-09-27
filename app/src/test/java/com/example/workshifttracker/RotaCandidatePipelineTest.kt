package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaCandidatePipelineTest {
    private fun example() = RotaDiagnosticEvidence.CandidatePipeline(
        strictCount = 3, looseObserved = 4, looseAdded = 1,
        eligibleOcrTokens = 5, ocrTokenDeciles = listOf(0, 1, 0, 1, 1, 0, 2, 0, 0, 0),
        ocrMerged = 4, ocrAdded = 1, probesAttempted = 10,
        probesNearExisting = 3, probesInkRejected = 4, probesAdded = 3,
        rejectedHeight = 1, rejectedBody = 1, finalCandidates = 6,
        finalOcrCandidates = 3, survivorsByBlock = mapOf(0 to 1, 2 to 4)
    )

    @Test fun funnelPreservesPreMergeEvidenceAndDoesNotAssumeAllRowsBelongToBlocks() {
        val trace = example()
        assertEquals(5, trace.ocrTokenDeciles.sum())
        assertEquals(8, trace.strictCount + trace.looseAdded + trace.ocrAdded + trace.probesAdded)
        assertEquals(6, trace.finalCandidates)
        assertEquals(5, trace.survivorsByBlock.values.sum())
        val decision = RotaDiagnosticEvidence.ProfileDecision(3, 6, 5, 0.54f, 0.52f,
            0.55f, 0.73f, "rejected_insufficient_runner_margin", pipeline = trace)
        assertEquals(1, decision.pipeline?.ocrAdded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidProbeAccountingIsRejected() {
        example().copy(probesNearExisting = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleTokenMergingIsRejected() {
        example().copy(ocrMerged = 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun preFilterCountsCannotInventSurvivors() {
        example().copy(finalCandidates = 7)
    }

    @Test fun decileBucketsDoNotExposeTextOrPreciseCoordinates() {
        val names = RotaDiagnosticEvidence.CandidatePipeline::class.java.declaredFields.map { it.name }
        assertTrue(names.none { it.contains("text", ignoreCase = true) || it.contains("coordinate", ignoreCase = true) })
    }
}
