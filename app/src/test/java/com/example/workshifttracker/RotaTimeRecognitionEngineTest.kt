package com.example.workshifttracker

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class RotaTimeRecognitionEngineTest {
    @Test fun repairsCommonHandwritingOcrNoise() {
        assertEquals(LocalTime.of(9,30), RotaTimeRecognitionEngine.best("O9.3O")?.time)
        assertEquals(LocalTime.of(9,30), RotaTimeRecognitionEngine.best("930")?.time)
        assertEquals(LocalTime.of(16,0), RotaTimeRecognitionEngine.best("I6:OO")?.time)
        assertEquals(LocalTime.of(13,0), RotaTimeRecognitionEngine.best("13OO")?.time)
    }

    @Test fun exposesAmbiguousBareMorningCompactTime() {
        val d = RotaTimeRecognitionEngine.analyze("900")
        assertTrue(d.ambiguous)
        assertNotNull(d.best)
        assertNotNull(d.runnerUp)
    }

    @Test fun learnedVocabularyIsOnlyASoftPrior() {
        val learned = listOf(LocalTime.of(9,30), LocalTime.of(16,0))
        val novel = RotaTimeRecognitionEngine.best("14:30", learned)
        assertEquals(LocalTime.of(14,30), novel?.time)
    }

    @Test fun understandsSuperscriptRotaMinutes() {
        assertEquals(LocalTime.of(9,30), RotaTimeRecognitionEngine.best("9³⁰")?.time)
        assertEquals(LocalTime.of(16,0), RotaTimeRecognitionEngine.best("16⁰⁰")?.time)
    }
}
