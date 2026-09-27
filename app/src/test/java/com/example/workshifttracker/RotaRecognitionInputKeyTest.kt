package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaRecognitionInputKeyTest {
    private fun sample(): ScheduleImporter.AssistData = ScheduleImporter.AssistData(
        imageWidth = 1601, imageHeight = 1140, ocrPasses = 3,
        tokens = listOf(
            ScheduleImporter.AssistToken("one", 100, 200, 140, 220),
            ScheduleImporter.AssistToken("two", 260, 700, 320, 729)
        ), rowBoundaries = List(7) { listOf(100f, 450f, 820f) },
        verticalRules = (1..6).map { it * 200f }
    )

    @Test fun shuffledTokensHaveSameCacheKeyAndGeometryFingerprint() {
        val original = sample()
        val shuffled = original.copy(tokens = original.tokens.reversed())
        assertEquals(RotaRecognitionInputKey.full(original), RotaRecognitionInputKey.full(shuffled))
        assertEquals(RotaRecognitionInputKey.geometry(original), RotaRecognitionInputKey.geometry(shuffled))
        assertEquals(RotaRecognitionInputKey.geometryByXBucket(original), RotaRecognitionInputKey.geometryByXBucket(shuffled))
    }

    @Test fun changingTextWithSameTokenCountInvalidatesInternalKeyNotExportedGeometry() {
        val original = sample()
        val edited = original.copy(tokens = original.tokens.mapIndexed { index, token ->
            if (index == 0) token.copy(text = "different") else token
        })
        assertFalse(RotaRecognitionInputKey.full(original) == RotaRecognitionInputKey.full(edited))
        assertEquals(RotaRecognitionInputKey.geometry(original), RotaRecognitionInputKey.geometry(edited))
    }

    @Test fun changingPositionOrRowsWithSameTokenCountInvalidatesCache() {
        val original = sample()
        val shifted = original.copy(tokens = original.tokens.mapIndexed { index, token ->
            if (index == 1) token.copy(top = 702) else token
        })
        val newRows = original.copy(rowBoundaries = List(7) { listOf(100f, 450f, 821f) })
        assertFalse(RotaRecognitionInputKey.full(original) == RotaRecognitionInputKey.full(shifted))
        assertFalse(RotaRecognitionInputKey.geometry(original) == RotaRecognitionInputKey.geometry(shifted))
        assertFalse(RotaRecognitionInputKey.full(original) == RotaRecognitionInputKey.full(newRows))
    }

    @Test fun viewerWaitsOnlyWhileBackgroundResultsAreNotFinal() {
        assertTrue(RotaRecognitionInputKey.viewerShouldWait(true, false)) // Immediate preview
        assertTrue(RotaRecognitionInputKey.viewerShouldWait(true, true)) // Late final data during scan
        assertTrue(RotaRecognitionInputKey.viewerShouldWait(false, false)) // Scan ended; review not synchronized
        assertFalse(RotaRecognitionInputKey.viewerShouldWait(false, true)) // Only safe start

    }
}
