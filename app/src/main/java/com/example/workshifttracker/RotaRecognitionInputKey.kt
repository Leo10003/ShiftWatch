package com.example.workshifttracker

import java.security.MessageDigest

/**
 * Recognition caches must depend on token contents and geometry, not simply token count.
 * The full key is session-local and must never be included in sanitized diagnostic exports.
 * Geometry-only digests contain no OCR text and help distinguish input drift between runs.
 */
internal object RotaRecognitionInputKey {
    private fun digest(parts: List<String>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            sha.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            sha.update(bytes)
        }
        return sha.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun geometry(token: ScheduleImporter.AssistToken) =
        "${token.source.name}:${token.left}:${token.top}:${token.right}:${token.bottom}:${token.blockHint ?: -1}"

    private fun structure(assist: ScheduleImporter.AssistData, includeTemplate: Boolean): List<String> = listOf(
        "size:${assist.imageWidth}:${assist.imageHeight}",
        "passes:${assist.ocrPasses}",
        "kind:${assist.documentKind.name}",
        "rules:${assist.verticalRules.joinToString(",") { it.toRawBits().toString() }}",
        "rows:${assist.rowBoundaries.joinToString("|") { row -> row.joinToString(",") { it.toRawBits().toString() } }}"
    ) + if (includeTemplate) listOf("template:${assist.templateFingerprint}") else emptyList()

    fun full(assist: ScheduleImporter.AssistData): String = digest(
        structure(assist, includeTemplate = true) + assist.tokens.map { token -> geometry(token) + ":" + token.text }
            .sorted()
    )

    fun geometry(assist: ScheduleImporter.AssistData): String = digest(
        structure(assist, includeTemplate = false) + assist.tokens.map(::geometry).sorted()
    )

    /** Seven approximate x-buckets for investigation, not authoritative weekday assignments. */
    fun geometryByXBucket(assist: ScheduleImporter.AssistData): List<String> = (0..6).map { bucket ->
        val selected = assist.tokens.filter { token ->
            ((token.left.toLong() + token.right) * 7L / (2L * assist.imageWidth.coerceAtLeast(1)))
                .toInt().coerceIn(0, 6) == bucket
        }
        digest(listOf("size:${assist.imageWidth}:${assist.imageHeight}", "bucket:$bucket") +
            selected.map(::geometry).sorted())
    }

    fun viewerShouldWait(backgroundScanRunning: Boolean, finalAssistSynchronized: Boolean): Boolean =
        backgroundScanRunning || !finalAssistSynchronized
}
