package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaViewerStatusTest {
    @Test fun noSuggestionsDoesNotLookBusy() {
        assertEquals("Recognition complete • no automatic matches • select shifts manually",
            RotaViewerStatus.completedSuggestions(0))
    }

    @Test fun restoredSuggestionsHaveCompletedStatus() {
        assertEquals("Recognition complete • 1 suggestion awaits confirmation",
            RotaViewerStatus.completedSuggestions(1))
        assertEquals("Recognition complete • 5 suggestions await confirmation",
            RotaViewerStatus.completedSuggestions(5))
    }
}
