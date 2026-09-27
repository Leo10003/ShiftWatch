package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaTemplateSelectionTest {
    @Test
    fun choosesClosestKnownLayout() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 700,
            imageHeight = 1000,
            tokens = emptyList(),
            verticalRules = listOf(100f, 200f, 300f, 400f, 500f, 600f),
            rowBoundaries = List(7) { listOf(200f, 400f, 600f) },
            documentKind = ScheduleImporter.DocumentKind.HANDWRITTEN_GRID
        )
        val close = ScheduleImporter.TemplateSnapshot(
            columnCenters = listOf(0.07f, 0.21f, 0.36f, 0.50f, 0.64f, 0.79f, 0.93f),
            rowRatios = listOf(0.20f, 0.40f, 0.60f),
            canonicalTimes = emptyList()
        )
        val far = close.copy(columnCenters = listOf(0.02f, 0.08f, 0.14f, 0.20f, 0.26f, 0.32f, 0.38f))
        assertEquals(close, ScheduleImporter.chooseBestTemplate(assist, listOf(far, close)))
    }
}
