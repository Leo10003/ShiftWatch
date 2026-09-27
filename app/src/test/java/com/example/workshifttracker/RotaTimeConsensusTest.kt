package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RotaTimeConsensusTest {
    private fun token(text: String, column: Int, y: Int): ScheduleImporter.AssistToken {
        val left = column * 100 + 4
        return ScheduleImporter.AssistToken(text, left, y - 12, left + 42, y + 12)
    }

    private val rules = List(7) { listOf(100f, 390f, 690f, 900f) }

    @Test fun repeated0930BeatsIsolatedFormatted1100Hallucination() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(
                token("09:30", 0, 125),
                token("9:30", 1, 128),
                token("11:00", 2, 126)
            ),
            rowBoundaries = rules
        )
        val draft = ScheduleImporter.draftFromTap(
            assist = assist,
            tapX = 250f,
            tapY = 205f,
            typicalShiftHours = 8,
            fallbackWeekStart = LocalDate.of(2026, 9, 21),
            today = LocalDate.of(2026, 9, 20),
            lockedWeekStart = LocalDate.of(2026, 9, 21)
        )
        assertEquals(LocalTime.of(9, 30), draft?.start?.toLocalTime())
    }

    @Test fun isolatedWeakHourIsNotPromotedToAutomaticSuggestion() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = listOf(token("11", 2, 125)),
            rowBoundaries = rules
        )
        val suggestions = ScheduleImporter.timeSuggestionsForTap(assist, 250f, 205f)
        assertTrue(suggestions.none { it.time == LocalTime.of(11, 0) })
    }
}
