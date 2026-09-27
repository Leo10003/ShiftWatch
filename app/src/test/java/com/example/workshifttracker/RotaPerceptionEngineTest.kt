package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RotaPerceptionEngineTest {
    @Test fun repairsCommonTimeOcrConfusions() {
        val result = RotaPerceptionEngine.timeHypotheses("O9.3O")
        assertTrue(result.isNotEmpty())
        assertEquals(LocalTime.of(9, 30), result.first().time)
    }

    @Test fun learnedTimeActsAsPriorNotHardRule() {
        val learned = listOf(LocalTime.of(13, 0))
        val known = RotaPerceptionEngine.timeHypotheses("13:00", learned).first()
        val novel = RotaPerceptionEngine.timeHypotheses("16:00", learned).first()
        assertTrue(known.score > novel.score)
        assertEquals(LocalTime.of(16, 0), novel.time)
    }

    @Test fun supportsHalfHourCompactNotation() {
        val result = RotaPerceptionEngine.timeHypotheses("930")
        assertTrue(result.any { it.time == LocalTime.of(9, 30) })
    }
}
