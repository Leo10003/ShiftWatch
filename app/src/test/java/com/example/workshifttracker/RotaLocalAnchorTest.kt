package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RotaLocalAnchorTest {
    private fun token(text: String, column: Int, y: Int): ScheduleImporter.AssistToken {
        val left = column * 100 + 6
        return ScheduleImporter.AssistToken(text, left, y - 10, left + 44, y + 10)
    }

    private val rules = List(7) { listOf(100f, 390f, 690f, 900f) }

    @Test fun repeatedSecondShiftHourBeatsIsolatedWrongMorningOcr() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(
                token("16", 0, 410),
                token("16", 1, 412),
                token("11:00", 2, 414),
                token("16", 3, 409)
            ),
            rowBoundaries = rules
        )
        val draft = ScheduleImporter.draftFromTap(
            assist = assist,
            tapX = 250f,
            tapY = 510f,
            typicalShiftHours = 8,
            fallbackWeekStart = LocalDate.of(2026, 9, 21),
            today = LocalDate.of(2026, 9, 20),
            lockedWeekStart = LocalDate.of(2026, 9, 21)
        )
        assertEquals(LocalTime.of(16, 0), draft?.start?.toLocalTime())
    }

    @Test fun isolatedTwoDigitHourDoesNotAutoLock() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(token("11", 2, 410)),
            rowBoundaries = rules
        )
        val draft = ScheduleImporter.draftFromTap(
            assist = assist,
            tapX = 250f,
            tapY = 510f,
            typicalShiftHours = 8,
            fallbackWeekStart = LocalDate.of(2026, 9, 21),
            today = LocalDate.of(2026, 9, 20),
            lockedWeekStart = LocalDate.of(2026, 9, 21)
        )
        assertTrue(draft == null || draft.confidence < 0.92f || draft.start.toLocalTime() != LocalTime.of(11, 0))
    }
}
