package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RotaDiagnosticEvidenceTest {
    @Test fun emptyCandidateListDoesNotAssertThereWereNoSuggestions() {
        val unseen = RotaDiagnosticEvidence.MarkerSummary()
        assertEquals("not_observed_viewer_not_opened", RotaDiagnosticEvidence.status(unseen))
        val viewed = unseen.copy(viewerOpened = true, suggested = 5,
            suggestionsByColumn = listOf(1, 1, 1, 0, 0, 1, 1))
        assertEquals("observed_in_viewer", RotaDiagnosticEvidence.status(viewed))
        assertEquals(5, viewed.suggestionsByColumn.sum())
    }

    @Test fun incorrectPlannerFallbackIsAnOffsetNotVerifiedDocumentDate() {
        val fallback = LocalDate.of(2026, 9, 21)
        val observed = RotaDateAuthorityEngine.Resolution(
            LocalDate.of(2026, 9, 7), 0.48f, 2, 0, false,
            "partial header; date unverified")
        val hypothesis = RotaDiagnosticEvidence.dateHypothesis("geometry", observed, fallback)
        assertEquals(-14L, hypothesis.offsetDaysFromPlannerFallback)
        assertFalse(hypothesis.authoritative)
    }

    @Test fun explicitSevenDaySeptemberSevenHeaderMustOverrideSeptemberTwentyOneFallback() {
        val observations = (0..6).map { day ->
            RotaDateAuthorityEngine.Token("${7 + day}.09", day, .10f)
        }
        val resolved = RotaDateAuthorityEngine.resolve(
            observations, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 21), .255f, .18f)
        assertEquals(LocalDate.of(2026, 9, 7), resolved.weekStart)
        assertTrue(resolved.authoritative)
    }

    @Test fun missingHeaderCannotTurnPlannerFallbackIntoVerifiedWeek() {
        val fallback = LocalDate.of(2026, 9, 21)
        val result = RotaDateAuthorityEngine.resolve(emptyList(), fallback, fallback, .25f, .18f)
        assertEquals(fallback, result.weekStart)
        assertFalse(result.authoritative)
    }
}
