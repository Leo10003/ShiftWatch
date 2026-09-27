package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RoundingTest {
    @Test fun rounds0940To0930() {
        val input = LocalDateTime.of(2026, 9, 20, 9, 40)
        assertEquals(LocalDateTime.of(2026, 9, 20, 9, 30), ShiftStore.roundToHalfHour(input))
    }

    @Test fun rounds0946To1000() {
        val input = LocalDateTime.of(2026, 9, 20, 9, 46)
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0), ShiftStore.roundToHalfHour(input))
    }

    @Test fun roundsExactHalfHourWithoutChangingIt() {
        val input = LocalDateTime.of(2026, 9, 20, 13, 30, 42)
        assertEquals(LocalDateTime.of(2026, 9, 20, 13, 30), ShiftStore.roundToHalfHour(input))
    }

    @Test fun roundingAcrossMidnightMovesToNextDay() {
        val input = LocalDateTime.of(2026, 9, 20, 23, 50)
        assertEquals(LocalDateTime.of(2026, 9, 21, 0, 0), ShiftStore.roundToHalfHour(input))
    }

    @Test fun rounds0010ToMidnightSameDay() {
        val input = LocalDateTime.of(2026, 9, 20, 0, 10)
        assertEquals(LocalDateTime.of(2026, 9, 20, 0, 0), ShiftStore.roundToHalfHour(input))
    }
}
