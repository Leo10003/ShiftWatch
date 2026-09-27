package com.example.workshifttracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class RotaEvaluationMetricsTest {
    @Test fun duplicateFalsePositivesReducePrecisionWithoutInflatingRecall() {
        val good = RotaEvaluationMetrics.ShiftLabel(0, 1, LocalTime.of(9,30))
        val wrong = good.copy(weekday = 2)
        val score = RotaEvaluationMetrics.evaluate(listOf(good), listOf(good, good, wrong))
        assertEquals(1, score.matched)
        assertEquals(1.0 / 3.0, score.precision, 0.001)
        assertEquals(1.0, score.recall, 0.001)
    }
}
