package com.example.workshifttracker

import org.junit.Assert.*
import org.junit.Test

class RotaScanTraceTest {
    @Test fun stageTimesAreMonotonicAndNeverIncludeUntrustedStageStrings() {
        val start = RotaScanTrace.Snapshot(stageStartedMs = 100)
        val first = RotaScanTrace.advance(start, "PAGE_OCR", 150)
        val second = RotaScanTrace.advance(first, "HEADER", 270)
        assertEquals(50L, second.elapsed["PREVIEW"])
        assertEquals(120L, second.elapsed["PAGE_OCR"])
        assertEquals(second, RotaScanTrace.advance(second, "employee_name_or_raw_text", 500))
        assertEquals(second, RotaScanTrace.advance(second, "COMPLETE", 200))
    }
}
