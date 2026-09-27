package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RotaDateAuthorityEngineTest {
    @Test
    fun explicitHeaderSequenceOverridesWrongPlannerWeek() {
        val tokens = (0..6).map { column ->
            RotaDateAuthorityEngine.Token(
                text = "${14 + column}.09",
                column = column,
                yRatio = 0.10f
            )
        }
        val result = RotaDateAuthorityEngine.resolve(
            tokens = tokens,
            today = LocalDate.of(2026, 9, 21),
            fallbackWeekStart = LocalDate.of(2026, 6, 29),
            explicitDateBottomRatio = 0.26f,
            weakDateBottomRatio = 0.17f
        )
        assertEquals(LocalDate.of(2026, 9, 14), result.weekStart)
        assertTrue(result.authoritative)
        assertTrue(result.confidence >= 0.98f)
    }

    @Test
    fun fourBareHeaderDaysCanRecoverWeekWithoutMonth() {
        val tokens = listOf(14, 15, 16, 17).mapIndexed { column, day ->
            RotaDateAuthorityEngine.Token(day.toString(), column, 0.11f)
        }
        val result = RotaDateAuthorityEngine.resolve(
            tokens = tokens,
            today = LocalDate.of(2026, 9, 21),
            fallbackWeekStart = LocalDate.of(2026, 6, 29),
            explicitDateBottomRatio = 0.26f,
            weakDateBottomRatio = 0.17f
        )
        assertEquals(LocalDate.of(2026, 9, 14), result.weekStart)
        assertTrue(result.confidence >= 0.84f)
    }

    @Test
    fun orderedTextHeaderOverridesPoisonedFallbackWeek() {
        val result = RotaDateAuthorityEngine.resolveTextSequence(
            rawText = "21.09 22.09 23.09 24.09 25.09 26.09 27.09",
            today = LocalDate.of(2026, 9, 21),
            fallbackWeekStart = LocalDate.of(2024, 12, 30)
        )
        assertEquals(LocalDate.of(2026, 9, 21), result.weekStart)
        assertTrue(result.authoritative)
        assertTrue(result.confidence >= 0.99f)
    }

    @Test
    fun unrelatedExplicitDatesCannotBeatOrderedHeaderRun() {
        val result = RotaDateAuthorityEngine.resolveTextSequence(
            rawText = "21.09 22.09 23.09 24.09 25.09 26.09 27.09 notes 03.01 17.02",
            today = LocalDate.of(2026, 9, 21),
            fallbackWeekStart = LocalDate.of(2025, 1, 1)
        )
        assertEquals(LocalDate.of(2026, 9, 21), result.weekStart)
    }
}
