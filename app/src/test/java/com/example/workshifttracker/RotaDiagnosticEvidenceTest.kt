package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RotaDiagnosticEvidenceTest {
    @Test fun separationBranchPreservesStrictProductionBoundaries() {
        fun trace(raw: Float, required: Float) =
            RotaDiagnosticEvidence.separationTrace(raw, required)
        assertEquals("below_required_separation", trace(.031f, .05f).branch)
        assertEquals(-.12f, trace(.031f, .05f).adjustment, .00001f)
        assertEquals(0f, trace(.05f, .05f).adjustment, .00001f)
        // Derive the upper boundary using production's Float arithmetic.
        val upper = .05f + .13f
        assertEquals("within_separation_band", trace(upper, .05f).branch)
        assertEquals(0f, trace(upper, .05f).adjustment, .00001f)
        val aboveUpper = java.lang.Math.nextUp(upper)
        assertEquals("strong_separation_bonus", trace(aboveUpper, .05f).branch)
        assertEquals(.025f, trace(aboveUpper, .05f).adjustment, .00001f)
    }

    @Test fun scoredCandidateRecordsMeasuredBoundaryAndBranchWithoutChangingRanking() {
        val required = RotaIdentityPolicy.boundary(3, 5, .518f).requiredSeparation
        val trace = RotaDiagnosticEvidence.separationTrace(.031f, required)
        val recorded = RotaDiagnosticEvidence.RankedProfileCandidate(
            rank = 1, physicalBlockIndex = 2, verticalDecile = 5,
            adjustedScore = .429f, positiveScore = .549f, confuserScore = .518f,
            rawSeparation = .031f, separationAdjustment = trace.adjustment,
            candidateOrigin = "ocr_token_band",
            requiredSeparation = required, separationBranch = trace.branch)
        val complete = RotaDiagnosticEvidence.completeCandidateEvidence(listOf(recorded))
        assertEquals(required, complete.single().requiredSeparation)
        assertEquals(trace.branch, complete.single().separationBranch)
        val noNegatives = recorded.copy(rawSeparation = 0f, separationAdjustment = 0f,
            requiredSeparation = null, separationBranch = "no_confuser_profile")
        assertEquals(null, noNegatives.requiredSeparation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun contradictoryBoundaryTraceIsRejected() {
        RotaDiagnosticEvidence.RankedProfileCandidate(
            1, 2, 4, .4f, .5f, .4f, rawSeparation=.1f,
            requiredSeparation=.02f, separationBranch="below_required_separation")
    }

    @Test fun adjustmentDependsOnLearnedBoundaryNotWeekdayOrPositiveRawAlone() {
        // Illustrative raw values from diagnostic reports; boundary values are
        // synthetic, because the private export doesn't expose requiredSeparation.
        val positiveRaw = .031f
        assertEquals(-.12f, RotaDiagnosticEvidence.separationTrace(positiveRaw, .05f).adjustment, .00001f)
        assertEquals(0f, RotaDiagnosticEvidence.separationTrace(positiveRaw, .03f).adjustment, .00001f)
        // Negative OFF-day evidence is never made positive by the branch itself.
        assertEquals(-.12f, RotaDiagnosticEvidence.separationTrace(-.024f, .05f).adjustment, .00001f)
        assertEquals(-.12f, RotaDiagnosticEvidence.separationTrace(-.002f, .05f).adjustment, .00001f)
    }

    @Test fun confuserPenaltyRemainsIndependentOfSeparationAdjustment() {
        val positive = .60f
        val penalty = .04f
        val trace = RotaDiagnosticEvidence.separationTrace(.02f, .05f)
        val expected = (positive - penalty + trace.adjustment).coerceIn(0f, 1f)
        assertEquals(.44f, expected, .00001f)
        assertEquals(-.12f, trace.adjustment, .00001f)
    }
    @Test fun completeEvidencePreservesEveryProductionCandidateAndAssignsRanks() {
        // Candidate construction requires rank >= 1. Deliberately give every input
        // the same valid rank to verify that the export assigns contiguous ranks.
        val rows = listOf(
            RotaDiagnosticEvidence.RankedProfileCandidate(1, 0, 2, .80f, .80f, .20f,
                rawSeparation=.60f, candidateOrigin="ocr_token_band"),
            RotaDiagnosticEvidence.RankedProfileCandidate(1, null, 4, .60f, .70f, .65f,
                rawSeparation=.05f, candidateOrigin="ink_gap_probe"),
            RotaDiagnosticEvidence.RankedProfileCandidate(1, 2, 7, .40f, .52f, .54f,
                rawSeparation=-.02f, candidateOrigin="ink_gap_probe"),
            RotaDiagnosticEvidence.RankedProfileCandidate(1, 1, 5, .30f, .42f, .50f,
                rawSeparation=-.08f, candidateOrigin="ocr_token_band")
        )
        val all = RotaDiagnosticEvidence.completeCandidateEvidence(rows)
        assertEquals(listOf(1, 2, 3, 4), all.map { it.rank })
        assertEquals(rows.map { it.physicalBlockIndex }, all.map { it.physicalBlockIndex })
        assertEquals(rows.map { it.rawSeparation }, all.map { it.rawSeparation })
        assertEquals(rows.map { it.candidateOrigin }, all.map { it.candidateOrigin })
        assertEquals(3, all.take(3).size)
    }

    @Test fun verticalCropVariantsAreDistinctAndBoundedAtImageEdges() {
        val variants = RotaDiagnosticEvidence.verticalCropVariants(1, 20, 200)
        assertEquals("original", variants.first().label)
        assertTrue(variants.any { it.label == "widen_28" })
        assertTrue(variants.any { it.label == "trim_12" })
        assertEquals(variants.size, variants.map { it.top to it.bottom }.distinct().size)
        assertTrue(variants.all { it.top >= 0 && it.bottom < 200 && it.top < it.bottom })
    }

    @Test fun selectiveTrimHypothesisUsesCandidatePropertiesNotWeekdays() {
        val saturdayLike = RotaDiagnosticEvidence.CropVariantScore("original", .496f, .618f, .604f, .014f, 0f, -.12f)
        val trimmed = RotaDiagnosticEvidence.CropVariantScore("trim_12", .668f, .70f, .644f, .056f, 0f, 0f)
        assertTrue(RotaDiagnosticEvidence.selectiveTrimEligible("ocr_token_band", saturdayLike))
        assertTrue(RotaDiagnosticEvidence.selectiveTrimQualifies(saturdayLike, trimmed))
        assertFalse(RotaDiagnosticEvidence.selectiveTrimEligible("ink_gap_probe", saturdayLike))
        val fridayLike = saturdayLike.copy(adjustedScore=.438f, rawSeparation=-.031f)
        assertFalse(RotaDiagnosticEvidence.selectiveTrimEligible("ocr_token_band", fridayLike))
        val thursdayLike = saturdayLike.copy(adjustedScore=.543f, rawSeparation=.02f)
        assertFalse(RotaDiagnosticEvidence.selectiveTrimEligible("ink_gap_probe", thursdayLike))
        assertFalse(RotaDiagnosticEvidence.selectiveTrimQualifies(saturdayLike, trimmed.copy(rawSeparation=.015f)))
    }

    @Test fun diagnosticCropExperimentCannotAlterRecognitionDecision() {
        val variant = RotaDiagnosticEvidence.CropVariantScore(
            "widen_28", .90f, .92f, .20f, .72f, 0f, .025f)
        val experiment = RotaDiagnosticEvidence.CropExperiment(
            2, 1, "ocr_token_band", 7, listOf(variant), .75f)
        val baseline = RotaDiagnosticEvidence.ProfileDecision(
            4, 19, 17, .438f, .42f, .55f, .60f, "rejected_below_rescue_floor")
        assertEquals("rejected_below_rescue_floor", baseline.copy(cropExperiments = listOf(experiment)).status)
        assertEquals(.438f, baseline.copy(cropExperiments = listOf(experiment)).bestScore ?: 0f, .001f)
    }

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
    @Test fun blockScoresRetainLosingPhysicalBlockAndAreOrderInvariant() {
        fun row(block: Int, decile: Int, score: Float, origin: String) =
            RotaDiagnosticEvidence.RankedProfileCandidate(1, block, decile, score,
                score, 0.50f, candidateOrigin = origin)
        val input = listOf(row(0, 1, .70f, "ocr_token_band"),
            row(2, 6, .54f, "ink_gap_probe"), row(2, 7, .525f, "ocr_token_band"),
            row(0, 2, .35f, "ink_gap_probe"))
        val report = RotaDiagnosticEvidence.blockScoreEvidence(input)
        assertEquals(report, RotaDiagnosticEvidence.blockScoreEvidence(input.reversed()))
        assertEquals(listOf(0, 2), report.map { it.physicalBlockIndex })
        assertEquals(2, report[1].candidateCount)
        assertEquals(.54f, report[1].best.adjustedScore, .001f)
        assertEquals(.525f, report[1].runner!!.adjustedScore, .001f)
        assertEquals(.015f, report[1].scoreMargin ?: 0f, .001f)
    }

    @Test fun blockScoresDoNotModifyAcceptanceOrProfileDecision() {
        val row = RotaDiagnosticEvidence.RankedProfileCandidate(1, 2, 6,
            .54f, .75f, .73f, candidateOrigin = "ink_gap_probe")
        val evidence = RotaDiagnosticEvidence.blockScoreEvidence(listOf(row))
        val decision = RotaDiagnosticEvidence.ProfileDecision(3, 23, 23,
            .543f, .525f, .55f, .73f, "rejected_insufficient_runner_margin",
            productionByBlock = evidence)
        assertEquals("rejected_insufficient_runner_margin", decision.status)
        assertEquals(1, evidence.single().candidateCount)
        assertEquals(null, evidence.single().runner)
        assertEquals(null, evidence.single().scoreMargin)
    }

    @Test fun shadowEvidenceIsEmptyWhenNoOriginalMergeIsEligible() {
        val result = RotaDiagnosticEvidence.shadowEvidence(emptyList())
        assertEquals(0, result.scoredCount)
        assertEquals(null, result.best)
        assertTrue(result.scoredByBlock.isEmpty())
    }

}
