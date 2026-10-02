package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RotaVerificationEngineTest {
    private fun assist() = ScheduleImporter.AssistData(
        imageWidth = 1400,
        imageHeight = 1000,
        tokens = listOf(
            // Make the test week structurally authoritative. A weekday label by itself is
            // intentionally insufficient after the date-authority hardening.
            ScheduleImporter.AssistToken("Monday", 10, 20, 150, 60),
            ScheduleImporter.AssistToken("21/09", 40, 65, 120, 95),
            ScheduleImporter.AssistToken("22/09", 240, 65, 320, 95),
            ScheduleImporter.AssistToken("23/09", 440, 65, 520, 95),
            ScheduleImporter.AssistToken("24/09", 640, 65, 720, 95),
            ScheduleImporter.AssistToken("25/09", 840, 65, 920, 95),
            ScheduleImporter.AssistToken("26/09", 1040, 65, 1120, 95),
            ScheduleImporter.AssistToken("27/09", 1240, 65, 1320, 95),
            ScheduleImporter.AssistToken("13:00", 40, 300, 120, 340)
        ),
        rowBoundaries = List(7) { listOf(200f, 400f, 600f, 800f) },
        verticalRules = listOf(200f, 400f, 600f, 800f, 1000f, 1200f),
        documentKind = ScheduleImporter.DocumentKind.HANDWRITTEN_GRID,
        quality = ScheduleImporter.QualityAssessment(0.8f, 0.8f, 1f, 1f, 0.85f),
        templateFingerprint = "test-template",
        learnedTimeVocabulary = listOf(LocalTime.of(13, 0), LocalTime.of(16, 0))
    )

    @Test
    fun contradictorySameDayDraftsBecomeConflict() {
        val monday = LocalDate.of(2026, 9, 21)
        val a = ScheduleImporter.Draft(
            start = LocalDateTime.of(monday, LocalTime.of(13, 0)),
            end = LocalDateTime.of(monday, LocalTime.of(21, 0)),
            sourceLine = "detected",
            confidence = 0.96f,
            columnIndex = 0,
            origin = ScheduleImporter.DraftOrigin.HANDWRITING
        )
        val b = a.copy(
            id = "other",
            start = LocalDateTime.of(monday, LocalTime.of(16, 0)),
            end = LocalDateTime.of(monday, LocalTime.of(23, 59))
        )
        val verified = RotaVerificationEngine.verifyWeek(assist(), listOf(a, b))
        assertTrue(verified.all { it.verificationState == RotaVerificationEngine.State.CONFLICT })
        assertTrue(verified.all { it.requiresTimeConfirmation })
    }

    @Test
    fun structurallySupportedDraftCanReachHighConfidence() {
        val monday = LocalDate.of(2026, 9, 21)
        val drafts = (0..2).map { col ->
            val date = monday.plusDays(col.toLong())
            ScheduleImporter.Draft(
                start = LocalDateTime.of(date, LocalTime.of(13, 0)),
                end = LocalDateTime.of(date, LocalTime.of(21, 0)),
                sourceLine = "detected",
                confidence = 0.97f,
                columnIndex = col,
                origin = ScheduleImporter.DraftOrigin.HANDWRITING
            )
        }
        val result = RotaVerificationEngine.verifyWeek(assist(), drafts)
        assertTrue(result.all { it.verificationState == RotaVerificationEngine.State.HIGH_CONFIDENCE })
        assertTrue(result.all { it.confidence >= 0.92f })
    }

    @Test
    fun fingerprintIsStableForSameDocumentModel() {
        val a = RotaVerificationEngine.documentFingerprint(assist())
        val b = RotaVerificationEngine.documentFingerprint(assist())
        assertEquals(a, b)
        assertEquals(24, a.length)
    }
}
