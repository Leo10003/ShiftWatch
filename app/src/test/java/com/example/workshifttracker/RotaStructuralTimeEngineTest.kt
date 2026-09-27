package com.example.workshifttracker

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaStructuralTimeEngineTest {
    @Test
    fun repeatedAtlasEvidenceWinsOverIsolatedWrongTime() {
        val obs = listOf(
            RotaStructuralTimeEngine.Observation(2, 1, LocalTime.of(16, 0), .91f, true, true),
            RotaStructuralTimeEngine.Observation(2, 2, LocalTime.of(16, 0), .88f, true, true),
            RotaStructuralTimeEngine.Observation(2, 4, LocalTime.of(16, 0), .79f, true, true),
            RotaStructuralTimeEngine.Observation(2, 3, LocalTime.of(9, 0), .95f, true, false)
        )
        val result = RotaStructuralTimeEngine.solve(obs).single()
        assertEquals(LocalTime.of(16, 0), result.time)
        assertFalse(result.ambiguous)
        assertTrue(result.supportColumns >= 3)
    }

    @Test
    fun ambiguousSingleColumnDoesNotBecomeTrustedTime() {
        val obs = listOf(
            RotaStructuralTimeEngine.Observation(1, 2, LocalTime.of(13, 0), .72f, false, false)
        )
        val result = RotaStructuralTimeEngine.solve(obs).single()
        assertTrue(result.ambiguous)
    }

    @Test
    fun repeatedlyConfirmedBlockCanResolveUnreadableFuturePhoto() {
        val prior = RotaStructuralTimeEngine.Prior(2, LocalTime.of(16, 0), .92f, confirmations = 8)
        val result = RotaStructuralTimeEngine.solve(emptyList(), listOf(prior)).single()
        assertEquals(LocalTime.of(16, 0), result.time)
        assertFalse(result.ambiguous)
    }

    @Test
    fun clearSingleAtlasObservationCanResolveItsOwnBlock() {
        val obs = listOf(
            RotaStructuralTimeEngine.Observation(2, 4, LocalTime.of(16, 0), .93f, true, true),
            RotaStructuralTimeEngine.Observation(2, 4, LocalTime.of(9, 0), .28f, false, false)
        )
        val result = RotaStructuralTimeEngine.solve(obs).single()
        assertEquals(LocalTime.of(16, 0), result.time)
        assertFalse(result.ambiguous)
    }

    @Test
    fun rowOrderingDowngradesUnexplainedBackwardTime() {
        val obs = listOf(
            RotaStructuralTimeEngine.Observation(0, 0, LocalTime.of(9, 30), .90f, true, true),
            RotaStructuralTimeEngine.Observation(0, 1, LocalTime.of(9, 30), .88f, true, true),
            RotaStructuralTimeEngine.Observation(1, 0, LocalTime.of(13, 0), .90f, true, true),
            RotaStructuralTimeEngine.Observation(1, 1, LocalTime.of(13, 0), .88f, true, true),
            RotaStructuralTimeEngine.Observation(2, 0, LocalTime.of(9, 0), .82f, true, false),
            RotaStructuralTimeEngine.Observation(2, 1, LocalTime.of(9, 0), .80f, false, false)
        )
        val result = RotaStructuralTimeEngine.solve(obs)
        assertTrue(result.first { it.blockIndex == 2 }.ambiguous)
    }
    @Test
    fun chronologyCannotPromoteUnsupportedLaterHour() {
        // A single 20:00 hallucination below a confirmed earlier row must not become the
        // chosen hour merely because 20:00 preserves increasing chronological order.
        val obs = listOf(
            RotaStructuralTimeEngine.Observation(0, 0, LocalTime.of(9, 0), .95f, true, true),
            RotaStructuralTimeEngine.Observation(1, 0, LocalTime.of(9, 0), .95f, true, true),
            RotaStructuralTimeEngine.Observation(1, 1, LocalTime.of(9, 0), .95f, true, true),
            RotaStructuralTimeEngine.Observation(1, 0, LocalTime.of(20, 0), .55f, false, false)
        )
        val result = RotaStructuralTimeEngine.solve(obs)
        assertTrue(result.first { it.blockIndex == 1 }.time != LocalTime.of(20, 0))
        assertTrue(result.first { it.blockIndex == 1 }.ambiguous)
    }
}
