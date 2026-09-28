package com.example.workshifttracker

/** Wording shared by restored and newly completed viewer recognition. */
internal object RotaViewerStatus {
    data class TerminalState(val status: String, val message: String)

    /** No saved examples or OCR name matches: finish bootstrap and invite manual training. */
    fun noDirectHits(hasSavedProfile: Boolean, reviewOnly: Boolean): TerminalState = when {
        reviewOnly -> TerminalState("skipped_review_only", "No matching printed names found • review table or select shifts manually")
        !hasSavedProfile -> TerminalState("no_saved_profile", "No saved handwriting examples yet • select shifts manually to teach recognition")
        else -> TerminalState("skipped_no_name_hits", "No automatic matches found • select shifts manually")
    }

    fun noOcrRegions(): String = "No OCR regions found • select shifts manually"

    /** A failed name lookup is not an empty result and must always end bootstrap visibly. */
    fun nameLookupFailed(): TerminalState = TerminalState(
        "failed_ocr_name_lookup", "Name recognition could not finish • select shifts manually or retry"
    )

    fun completedSuggestions(count: Int): String = when (count) {
        0 -> "Recognition complete • no automatic matches • select shifts manually"
        1 -> "Recognition complete • 1 suggestion awaits confirmation"
        else -> "Recognition complete • $count suggestions await confirmation"
    }
}
