package com.example.workshifttracker

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaBlockIndexedTimeTest {
    private fun rows() = List(7) { listOf(100f, 250f, 400f, 550f, 700f) }

    @Test
    fun samePhysicalBlockClustersAcrossVerticallyNoisyTokens() {
        val assist = ScheduleImporter.AssistData(
            imageWidth = 1400,
            imageHeight = 900,
            rowBoundaries = rows(),
            tokens = listOf(
                ScheduleImporter.AssistToken("09:30", 30, 270, 100, 300),
                ScheduleImporter.AssistToken("O9.3O", 230, 285, 310, 315),
                ScheduleImporter.AssistToken("930", 430, 300, 500, 330)
            )
        )
        val band = RotaSemanticTimeBands.infer(assist).firstOrNull { it.blockIndex == 1 }
        assertNotNull(band)
        assertEquals(LocalTime.of(9, 30), band!!.time)
        assertTrue(band.supportColumns >= 2)
    }

    @Test
    fun learnedBlockSurvivesLargeVerticalPhotoShift() {
        val prior = RotaSemanticTimeBands.Band(
            yRatio = .30f,
            time = LocalTime.of(13, 0),
            confidence = .91f,
            supportColumns = 5,
            learned = true,
            blockIndex = 2,
            confirmations = 9
        )
        val assist = ScheduleImporter.AssistData(
            imageWidth = 1400,
            imageHeight = 1100,
            rowBoundaries = List(7) { listOf(170f, 340f, 510f, 680f, 850f) },
            tokens = emptyList()
        )
        val result = RotaSemanticTimeBands.infer(assist, listOf(prior))
        assertEquals(LocalTime.of(13, 0), RotaSemanticTimeBands.forBlock(result, 2)?.time)
    }
}
