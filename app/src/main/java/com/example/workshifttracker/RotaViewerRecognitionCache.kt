package com.example.workshifttracker

/**
 * Session-only viewer recognition snapshot. Kept by the parent review composable while a
 * full-screen Dialog is dismissed/recreated. It is never persisted, exported or used to import
 * shifts. User confirmations remain authoritative in the review draft collection.
 */
internal data class RotaViewerRecognitionCache(
    val runId: String,
    val profileFingerprint: Int?,
    val ocrPasses: Int,
    val tokenCount: Int,
    val reviewOnly: Boolean,
    val suggestions: List<Suggestion>,
    val ocrHitsByColumn: List<Int>,
    val profileDecisions: List<RotaDiagnosticEvidence.ProfileDecision>,
    val profileStatus: String,
    val seededDecisions: List<RotaDiagnosticEvidence.ProfileDecision>,
    val seededStatus: String
) {
    data class Suggestion(val x: Float, val y: Float, val score: Float?, val origin: String)

    fun reusableFor(profile: String?, passes: Int, tokens: Int, isReviewOnly: Boolean): Boolean =
        profileStatus == "completed" && profileFingerprint == profile?.hashCode() &&
            ocrPasses == passes && tokenCount == tokens && reviewOnly == isReviewOnly

    /** Preserve manually selected/removed shifts instead of resurrecting overlapping suggestions. */
    fun availableSuggestions(blocked: (Suggestion) -> Boolean): List<Suggestion> = suggestions.filterNot(blocked)
}
