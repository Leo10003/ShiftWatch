package com.example.workshifttracker

/** Wording shared by restored and newly completed viewer recognition. */
internal object RotaViewerStatus {
    fun completedSuggestions(count: Int): String = when (count) {
        0 -> "Recognition complete • no automatic matches • select shifts manually"
        1 -> "Recognition complete • 1 suggestion awaits confirmation"
        else -> "Recognition complete • $count suggestions await confirmation"
    }
}
