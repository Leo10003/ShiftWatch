package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaViewerRecognitionCacheTest {
    private fun completedCache(): RotaViewerRecognitionCache = RotaViewerRecognitionCache(
        runId = "run-1", profileFingerprint = "training-set".hashCode(),
        ocrPasses = 3, tokenCount = 207, reviewOnly = false,
        suggestions = listOf(
            RotaViewerRecognitionCache.Suggestion(10f, 100f, .7f, "saved_profile"),
            RotaViewerRecognitionCache.Suggestion(30f, 200f, .8f, "saved_profile")
        ),
        ocrHitsByColumn = List(7) { 0 },
        profileDecisions = listOf(RotaDiagnosticEvidence.ProfileDecision(
            weekdayColumn = 1, candidateCount = 27, scoredCount = 25,
            bestScore = .447f, runnerScore = .40f, acceptanceFloor = .55f,
            confuserScore = .58f, status = "rejected_below_rescue_floor"
        )),
        profileStatus = "completed", seededDecisions = emptyList(), seededStatus = "not_attempted"
    )

    @Test fun reopenedViewerReusesCompletedRecognitionWithUnchangedInputs() {
        val cache = completedCache()
        assertTrue(cache.reusableFor("training-set", 3, 207, false))
        assertEquals("run-1", cache.runId)
        assertEquals(.447f, cache.profileDecisions.single().bestScore ?: 0f, .001f)
    }

    @Test fun changedProfileOrOcrInputsMustNotReuseStaleMarkers() {
        val cache = completedCache()
        assertFalse(cache.reusableFor("new-training", 3, 207, false))
        assertFalse(cache.reusableFor("training-set", 2, 207, false))
        assertFalse(cache.reusableFor("training-set", 3, 208, false))
        assertFalse(cache.reusableFor("training-set", 3, 207, true))
        assertFalse(cache.copy(profileStatus = "failed").reusableFor("training-set", 3, 207, false))
    }

    @Test fun manuallySelectedLocationIsNotReintroducedAsAnAutomaticSuggestion() {
        val cache = completedCache()
        val visible = cache.availableSuggestions { it.y == 100f }
        assertEquals(1, visible.size)
        assertEquals(200f, visible.single().y, .001f)
        // Cache remains immutable: reopening must not mutate the original evidence or run ID.
        assertEquals(2, cache.suggestions.size)
    }
}
