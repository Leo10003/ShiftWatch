package com.example.workshifttracker

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaSemanticTimeBandsTest {
    @Test
    fun repeatedRowAcrossColumnsBecomesSemanticBand() {
        val tokens = listOf(
            ScheduleImporter.AssistToken("09:30", 40, 300, 110, 330),
            ScheduleImporter.AssistToken("9.30", 240, 302, 310, 332),
            ScheduleImporter.AssistToken("O9.3O", 440, 298, 520, 330)
        )
        val assist = ScheduleImporter.AssistData(
            imageWidth = 1400,
            imageHeight = 1000,
            tokens = tokens
        )
        val bands = RotaSemanticTimeBands.infer(assist)
        val morning = bands.firstOrNull { it.time == LocalTime.of(9, 30) }
        assertTrue(morning != null)
        assertTrue((morning?.supportColumns ?: 0) >= 2)
    }

    @Test
    fun strongLearnedBandSurvivesUnreadableFutureImage() {
        val prior = RotaSemanticTimeBands.Band(
            yRatio = 0.42f,
            time = LocalTime.of(13, 0),
            confidence = 0.90f,
            supportColumns = 5,
            learned = true
        )
        val assist = ScheduleImporter.AssistData(1400, 1000, emptyList())
        val bands = RotaSemanticTimeBands.infer(assist, listOf(prior))
        assertEquals(LocalTime.of(13, 0), bands.single().time)
        assertTrue(bands.single().confidence >= 0.70f)
    }
}
