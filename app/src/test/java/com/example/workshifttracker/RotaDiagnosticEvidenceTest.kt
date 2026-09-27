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
    @Test fun comparisonsExposeEvidenceForCorrectWeekWithoutExportingCalendarDates() {
        val observations = (0..6).map { column ->
            RotaDateAuthorityEngine.Observation(7 + column, 9, column, true, "private")
        }
        val fallback = RotaDiagnosticEvidence.headerWeekFit(observations, LocalDate.of(2026, 9, 21))
        val actual = RotaDiagnosticEvidence.headerWeekFit(observations, LocalDate.of(2026, 9, 7))
        assertEquals(0, fallback.matchingColumns)
        assertEquals(7, actual.matchingColumns)
        assertEquals(7, actual.explicitMatches)
    }

    @Test fun diagnosticColumnTraceCanExplainMissingThursdayWithoutAssumingNoName() {
        val trace = RotaDiagnosticEvidence.ProfileDecision(3, 4, 4, 0.56f, 0.54f,
            0.60f, 0.18f, "rejected_insufficient_runner_margin")
        assertEquals(3, trace.weekdayColumn)
        assertEquals(4, trace.scoredCount)
        assertEquals("rejected_insufficient_runner_margin", trace.status)
        val summary = RotaDiagnosticEvidence.MarkerSummary(viewerOpened = true,
            suggested = 5, suggestionsByColumn = listOf(1, 1, 1, 0, 0, 1, 1),
            profileDecisions = listOf(trace), profileStatus = "completed")
        assertEquals(0, summary.suggestionsByColumn[3])
        assertEquals(1, summary.profileDecisions.size)
    }

    @Test fun coarseRankedCandidatesKeepBlockIdentityWithoutCoordinates() {
        assertEquals(0, RotaDiagnosticEvidence.verticalDecile(-20f, 100f))
        assertEquals(4, RotaDiagnosticEvidence.verticalDecile(49f, 100f))
        assertEquals(9, RotaDiagnosticEvidence.verticalDecile(200f, 100f))
        val alternatives = listOf(
            RotaDiagnosticEvidence.RankedProfileCandidate(1, 2, 7, 0.447f, 0.61f, 0.58f),
            RotaDiagnosticEvidence.RankedProfileCandidate(2, 1, 4, 0.404f, 0.42f, 0.10f)
        )
        val decision = RotaDiagnosticEvidence.ProfileDecision(1, 27, 25,
            0.447f, 0.404f, 0.55f, 0.58f, "rejected_below_rescue_floor", alternatives)
        assertEquals(2, decision.rankedCandidates[0].physicalBlockIndex)
        assertEquals(1, decision.rankedCandidates[1].physicalBlockIndex)
        assertEquals("rejected_below_rescue_floor", decision.status)
    }

}
