package com.example.workshifttracker

import org.junit.Assert.assertTrue
import org.junit.Test

class RotaEvidenceFusionTest {
    @Test
    fun strongIndependentSignalsCanVerify() {
        val result = ScheduleImporter.fuseEvidence(
            identity = 0.96f,
            geometry = 0.94f,
            time = 0.95f,
            template = 0.75f,
            consistency = 0.90f
        )
        assertTrue(result.fused >= 0.90f)
    }

    @Test
    fun badGeometryCapsConfidenceEvenWithStrongIdentity() {
        val result = ScheduleImporter.fuseEvidence(
            identity = 0.99f,
            geometry = 0.30f,
            time = 0.92f,
            template = 0.90f,
            consistency = 0.90f
        )
        assertTrue(result.fused <= 0.79f)
    }

    @Test
    fun contradictionsReduceConfidence() {
        val clean = ScheduleImporter.fuseEvidence(0.9f, 0.9f, 0.9f, 0.7f, 0.9f)
        val conflicted = ScheduleImporter.fuseEvidence(0.9f, 0.9f, 0.9f, 0.7f, 0.9f, contradictionPenalty = 0.18f)
        assertTrue(conflicted.fused < clean.fused)
    }
}
