package com.example.workshifttracker

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class RotaTimeWordGuardTest {
    @Test fun employeeNamesDoNotBecomeTimes() {
        listOf("SANJUTA", "VESNA", "UROS", "DANICA", "VALENTINA", "PAULA", "IGOR").forEach { word ->
            assertNull("$word must not be time evidence", RotaTimeRecognitionEngine.analyze(word).best)
        }
    }

    @Test fun ocrConfusionRepairStillWorksForActualTimeTokens() {
        assertEquals(LocalTime.of(9, 30), RotaTimeRecognitionEngine.analyze("O9.3O").best?.time)
        assertEquals(LocalTime.of(16, 0), RotaTimeRecognitionEngine.analyze("I6:OO").best?.time)
        assertEquals(LocalTime.of(13, 0), RotaTimeRecognitionEngine.analyze("13OO").best?.time)
    }
}
