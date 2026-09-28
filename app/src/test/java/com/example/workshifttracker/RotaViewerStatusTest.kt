package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaViewerStatusTest {
    @Test fun freshInstallNoNameHitsTerminatesInManualTraining() {
        val result = RotaViewerStatus.noDirectHits(hasSavedProfile = false, reviewOnly = false)
        assertEquals("no_saved_profile", result.status)
        assertEquals("No saved handwriting examples yet • select shifts manually to teach recognition", result.message)
    }

    @Test fun restoredProfileWithNoNameHitsIsNotFirstRun() {
        val result = RotaViewerStatus.noDirectHits(hasSavedProfile = true, reviewOnly = false)
        assertEquals("skipped_no_name_hits", result.status)
    }

    @Test fun reviewOnlyCannotBeMistakenForStalledRecognition() {
        val result = RotaViewerStatus.noDirectHits(hasSavedProfile = false, reviewOnly = true)
        assertEquals("skipped_review_only", result.status)
    }

    @Test fun emptyOcrIsTerminal() {
        assertEquals("No OCR regions found • select shifts manually", RotaViewerStatus.noOcrRegions())
    }

    @Test fun noSuggestionsDoesNotLookBusy() {
        assertEquals("Recognition complete • no automatic matches • select shifts manually",
            RotaViewerStatus.completedSuggestions(0))
    }

    @Test fun nameLookupExceptionIsTerminalAndDistinctFromNoHits() {
        val result = RotaViewerStatus.nameLookupFailed()
        assertEquals("failed_ocr_name_lookup", result.status)
        assertEquals("Name recognition could not finish • select shifts manually or retry", result.message)
    }

    @Test fun restoredSuggestionsHaveCompletedStatus() {
        assertEquals("Recognition complete • 1 suggestion awaits confirmation",
            RotaViewerStatus.completedSuggestions(1))
        assertEquals("Recognition complete • 5 suggestions await confirmation",
            RotaViewerStatus.completedSuggestions(5))
    }
}
