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

    @Test fun bandOverlapDoesNotCountUnrelatedLinesAsTheSameOccurrence() {
        assertEquals(0f, RotaDiagnosticEvidence.bandOverlapFraction(10, 20, 30, 40), 0.001f)
        assertEquals(1f, RotaDiagnosticEvidence.bandOverlapFraction(10, 20, 10, 20), 0.001f)
        assertEquals(6f / 11f, RotaDiagnosticEvidence.bandOverlapFraction(10, 20, 15, 25), 0.001f)
        assertEquals(0f, RotaDiagnosticEvidence.bandOverlapFraction(10, 8, 10, 20), 0.001f)
    }

    @Test fun rankedDiagnosticsSeparateRawIdentityEvidenceFromPenalties() {
        val top = RotaDiagnosticEvidence.RankedProfileCandidate(1, 2, 7,
            0.543f, 0.753f, 0.60f, rawSeparation = 0.153f,
            confuserPenalty = 0.09f, separationAdjustment = -0.12f,
            candidateOrigin = "ocr_token_band")
        val decision = RotaDiagnosticEvidence.ProfileDecision(3, 25, 25,
            0.543f, 0.525f, 0.55f, 0.60f,
            "rejected_insufficient_runner_margin", listOf(top),
            runnerOverlapFraction = 0.82f, runnerIsSamePhysicalBlock = true)
        assertEquals("ocr_token_band", decision.rankedCandidates.first().candidateOrigin)
        assertEquals(0.153f, decision.rankedCandidates.first().rawSeparation, 0.001f)
        assertEquals(0.82f, decision.runnerOverlapFraction ?: 0f, 0.001f)
        assertTrue(decision.runnerIsSamePhysicalBlock == true)
    }

    @Test fun sourceEvidencePreservesLosingOcrAlternativeAndCounts() {
        fun row(origin: String, adjusted: Float, positive: Float, block: Int) =
            origin to RotaDiagnosticEvidence.RankedProfileCandidate(1, block, 7,
                adjusted, positive, 0.60f, candidateOrigin = origin)
        val evidence = RotaDiagnosticEvidence.sourceEvidence(listOf(
            row("ink_gap_probe", 0.543f, 0.753f, 2),
            row("ocr_token_band", 0.525f, 0.743f, 2),
            row("ink_gap_probe", 0.440f, 0.56f, 0),
            row("ocr_token_band", 0.401f, 0.52f, 1)
        ))
        assertEquals(listOf("ink_gap_probe", "ocr_token_band"), evidence.map { it.origin })
        assertEquals(listOf(2, 2), evidence.map { it.scoredCount })
        assertEquals(0.543f, evidence[0].strongestAdjustedScore, 0.001f)
        assertEquals(0.743f, evidence[1].strongestPositiveScore, 0.001f)
        assertEquals(2, evidence[1].strongestPhysicalBlockIndex)
    }

    @Test fun sourceEvidenceIsIndependentOfCandidateInputOrder() {
        val a = RotaDiagnosticEvidence.RankedProfileCandidate(1, 2, 7, 0.5f, 0.7f, 0.6f)
        val b = RotaDiagnosticEvidence.RankedProfileCandidate(1, 1, 4, 0.4f, 0.6f, 0.5f)
        val rows = listOf("ocr_token_band" to a, "ink_gap_probe" to b)
        assertEquals(RotaDiagnosticEvidence.sourceEvidence(rows),
            RotaDiagnosticEvidence.sourceEvidence(rows.reversed()))
    }

    @Test fun shadowEvidenceIsDeterministicAndDoesNotClaimAcceptance() {
        val first = RotaDiagnosticEvidence.ShadowScore(2, 7, 0.71f, 0.74f, 0.55f)
        val second = RotaDiagnosticEvidence.ShadowScore(0, 2, 0.41f, 0.53f, 0.62f)
        val a = RotaDiagnosticEvidence.shadowEvidence(listOf(first, second))
        assertEquals(a, RotaDiagnosticEvidence.shadowEvidence(listOf(second, first)))
        assertEquals(2, a.scoredCount)
        assertEquals(mapOf(0 to 1, 2 to 1), a.scoredByBlock)
        assertEquals(2, a.best?.physicalBlockIndex)
        val decision = RotaDiagnosticEvidence.ProfileDecision(1, 27, 25, 0.447f, 0.404f,
            0.55f, 0.58f, "rejected_below_rescue_floor", shadowOcr = a)
        assertEquals("rejected_below_rescue_floor", decision.status)
    }
    @Test fun shadowEvidenceIsEmptyWhenNoOriginalMergeIsEligible() {
        val result = RotaDiagnosticEvidence.shadowEvidence(emptyList())
        assertEquals(0, result.scoredCount)
        assertEquals(null, result.best)
        assertTrue(result.scoredByBlock.isEmpty())
    }

}
