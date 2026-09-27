package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleWeekResolverTest {
    private fun token(text: String, column: Int): ScheduleImporter.AssistToken {
        val columnWidth = 100
        val left = column * columnWidth + 20
        return ScheduleImporter.AssistToken(text, left, 20, left + 55, 60)
    }

    @Test fun resolvesUpcomingSeptemberWeekFromSevenHeaders() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(
                token("21.09", 0), token("22.09", 1), token("23.09", 2),
                token("24.09", 3), token("25.09", 4), token("26.09", 5), token("27.09", 6)
            )
        )
        val result = ScheduleImporter.detectedWeekStart(
            assist,
            fallbackWeekStart = LocalDate.of(2026, 9, 14),
            today = LocalDate.of(2026, 9, 20)
        )
        assertEquals(LocalDate.of(2026, 9, 21), result)
    }

    @Test fun oneBadMonthDoesNotMoveWholeRota() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(
                token("21.09", 0), token("22.09", 1), token("23.09", 2),
                token("24.09", 3), token("25.09", 4), token("26.06", 5), token("27.09", 6)
            )
        )
        val result = ScheduleImporter.detectedWeekStart(
            assist,
            fallbackWeekStart = LocalDate.of(2026, 9, 14),
            today = LocalDate.of(2026, 9, 20)
        )
        assertEquals(LocalDate.of(2026, 9, 21), result)
    }

    @Test fun lockedWeekAlwaysWinsForTapDate() {
        val assist = ScheduleImporter.AssistData(imageWidth = 700, imageHeight = 1000, tokens = emptyList())
        val date = ScheduleImporter.dateFromTap(
            assist = assist,
            tapX = 550f,
            fallbackWeekStart = LocalDate.of(2026, 6, 1),
            today = LocalDate.of(2026, 9, 20),
            lockedWeekStart = LocalDate.of(2026, 9, 21)
        )
        assertEquals(LocalDate.of(2026, 9, 26), date)
    }
}
