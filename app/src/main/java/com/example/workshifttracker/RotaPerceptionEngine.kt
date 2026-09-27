package com.example.workshifttracker

import java.time.LocalTime
import kotlin.math.min

/**
 * RotaVision v12 perception layer.
 *
 * This layer deliberately sits below the v11 verifier. It improves what the app can see without
 * being allowed to bypass verification. It specializes in the tiny rota vocabulary (times,
 * weekday structure and recurring employee evidence) and reports ambiguity explicitly.
 */
object RotaPerceptionEngine {
    data class Assessment(
        val score: Float = 0f,
        val timeReadability: Float = 0f,
        val structuralReadability: Float = 0f,
        val ambiguity: Float = 1f,
        val recognizedTimes: List<LocalTime> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class TimeHypothesis(
        val time: LocalTime,
        val score: Float,
        val normalizedText: String,
        val sourceText: String
    )

    /**
     * Tiny-vocabulary recognizer for rota time labels. It is intentionally independent from the
     * general OCR parser and repairs common handwriting/OCR confusions before scoring candidates.
     */
    fun timeHypotheses(raw: String, learned: List<LocalTime> = emptyList()): List<TimeHypothesis> {
        val decision = RotaTimeRecognitionEngine.analyze(raw, learned)
        return listOfNotNull(decision.best, decision.runnerUp)
            .distinctBy { it.time }
            .map { h -> TimeHypothesis(h.time, h.score, h.normalized, raw) }
            .sortedByDescending { it.score }
    }

    fun assess(assist: ScheduleImporter.AssistData): Assessment {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return Assessment()
        val timeVotes = mutableMapOf<LocalTime, MutableSet<Int>>()
        data class EvidenceKey(val column: Int, val block: Int, val time: LocalTime)
        val bestEvidence = mutableMapOf<EvidenceKey, Float>()
        val ambiguousEvidence = mutableSetOf<Pair<Int, Int>>()

        assist.tokens.forEach { token ->
            val column = runCatching { ScheduleImporter.columnIndexForX(assist, token.cx) }.getOrNull() ?: return@forEach
            val block = token.blockHint ?: RotaGridModel.blockIndexForY(assist, column, token.cy) ?: return@forEach
            val bounds = RotaGridModel.boundsForBlock(assist, column, block) ?: return@forEach
            val fraction = ((token.cy - bounds.first) / (bounds.second - bounds.first).coerceAtLeast(1f))
            // Page OCR may contain dates, names and notes. Only the small label corridor near the
            // top of a schedule block is legitimate time evidence. Atlas tokens are already
            // block-cropped, but still keep them within a generous structural range.
            val maxFraction = if (token.source == ScheduleImporter.TokenSource.TIME_ATLAS) 0.62f else 0.42f
            if (fraction < -0.08f || fraction > maxFraction) return@forEach

            val hypotheses = timeHypotheses(token.text, assist.learnedTimeVocabulary)
            if (hypotheses.isEmpty()) return@forEach
            val best = hypotheses.first()
            val key = EvidenceKey(column, block, best.time)
            val adjusted = (best.score + if (token.source == ScheduleImporter.TokenSource.TIME_ATLAS) 0.06f else 0f).coerceAtMost(0.99f)
            if (adjusted > (bestEvidence[key] ?: -1f)) bestEvidence[key] = adjusted
            if (hypotheses.size > 1 && hypotheses[0].score - hypotheses[1].score < 0.12f) {
                ambiguousEvidence.add(column to block)
            }
        }

        bestEvidence.forEach { (key, _) ->
            timeVotes.getOrPut(key.time) { mutableSetOf() }.add(key.column)
        }
        val readableTimeTokens = bestEvidence.size
        val ambiguousTimeTokens = ambiguousEvidence.size.coerceAtMost(readableTimeTokens)
        val consensus = timeVotes.entries
            .filter { it.value.size >= 2 }
            .sortedWith(compareByDescending<Map.Entry<LocalTime, MutableSet<Int>>> { it.value.size }.thenBy { it.key })
            .map { it.key }
        val gridScore = ((assist.verticalRules.size / 6f) * 0.45f +
            (assist.rowBoundaries.count { it.size >= 3 } / 7f) * 0.55f).coerceIn(0f, 1f)
        val timeReadability = if (readableTimeTokens == 0) 0f else
            (min(1f, readableTimeTokens / 6f) * 0.55f + min(1f, consensus.size / 3f) * 0.45f).coerceIn(0f, 1f)
        val ambiguity = if (readableTimeTokens == 0) 1f else
            (ambiguousTimeTokens.toFloat() / readableTimeTokens.toFloat()).coerceIn(0f, 1f)
        val score = (assist.quality.score * 0.34f + gridScore * 0.36f + timeReadability * 0.30f - ambiguity * 0.12f)
            .coerceIn(0f, 1f)
        val warnings = buildList {
            if (gridScore < 0.35f) add("weak schedule structure")
            if (timeReadability < 0.30f) add("shift labels are difficult to read")
            if (ambiguity > 0.35f) add("several time labels are ambiguous")
            if (assist.quality.score < 0.38f) add("image quality limits recognition")
        }
        return Assessment(score, timeReadability, gridScore, ambiguity, consensus, warnings)
    }

    /** Independent score used by the verifier; it never overrides a detected time. */
    fun supportForTime(time: LocalTime, assessment: Assessment, learned: List<LocalTime>): Float {
        var support = 0.35f
        if (time in assessment.recognizedTimes) support += 0.34f
        if (time in learned) support += 0.22f
        if (assessment.structuralReadability >= 0.65f) support += 0.06f
        if (assessment.ambiguity > 0.45f) support -= 0.12f
        return support.coerceIn(0f, 1f)
    }
}
