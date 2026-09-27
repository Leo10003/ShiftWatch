package com.example.workshifttracker

/** Privacy-preserving stage durations. All timestamps use a monotonic elapsed clock. */
internal object RotaScanTrace {
    val stages = setOf("PREVIEW", "DECODE", "PAGE_OCR", "CONTRAST_OCR", "THRESHOLD_OCR", "HEADER", "TIMES", "VERIFY", "COMPLETE", "STOPPED", "ERROR")
    data class Snapshot(
        val current: String = "PREVIEW",
        val stageStartedMs: Long = 0L,
        val elapsed: Map<String, Long> = emptyMap()
    )
    fun advance(snapshot: Snapshot, stage: String, now: Long): Snapshot {
        if (stage !in stages || now < snapshot.stageStartedMs || stage == snapshot.current) return snapshot
        val order = listOf("PREVIEW", "DECODE", "PAGE_OCR", "CONTRAST_OCR", "THRESHOLD_OCR", "HEADER", "TIMES", "VERIFY")
        val oldRank = order.indexOf(snapshot.current)
        val newRank = order.indexOf(stage)
        if (snapshot.current in setOf("COMPLETE", "STOPPED", "ERROR") ||
            (newRank >= 0 && oldRank >= 0 && newRank < oldRank)) return snapshot
        // Only successfully completed stages are timed; an in-progress stage is reported
        // independently so an incomplete/cancelled OCR run does not resemble a success.
        val spent = now - snapshot.stageStartedMs
        return snapshot.copy(current = stage, stageStartedMs = now,
            elapsed = snapshot.elapsed + (snapshot.current to
                (snapshot.elapsed[snapshot.current] ?: 0L).plus(spent)))
    }
}
