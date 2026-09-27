package com.example.workshifttracker

import java.time.LocalTime
import kotlin.math.abs

/**
 * v17 block-indexed semantic time model.
 *
 * Normalized Y is retained for backwards compatibility and weak-layout fallback, but the primary
 * identity of a learned time row is now its physical block index.  This makes learned start times
 * robust to camera framing, mild perspective/crop changes and vertical translation between photos.
 */
object RotaSemanticTimeBands {
    data class Band(
        val yRatio: Float,
        val time: LocalTime,
        val confidence: Float,
        val supportColumns: Int,
        val learned: Boolean = false,
        val blockIndex: Int? = null,
        val confirmations: Int = 0,
        val contradictions: Int = 0
    )

    private data class Vote(
        val y: Float,
        val column: Int,
        val blockIndex: Int?,
        val time: LocalTime,
        val score: Float
    )

    fun blockIndexForY(assist: ScheduleImporter.AssistData, column: Int, y: Float): Int? =
        RotaGridModel.blockIndexForY(assist, column, y)

    fun infer(
        assist: ScheduleImporter.AssistData,
        priors: List<Band> = emptyList()
    ): List<Band> {
        if (assist.imageHeight <= 0 || assist.imageWidth <= 0) return priors

        val votes = mutableListOf<Vote>()
        assist.tokens.forEach { token ->
            if (token.cy < assist.imageHeight * 0.07f || token.cy > assist.imageHeight * 0.94f) return@forEach
            val column = ScheduleImporter.columnIndexForX(assist, token.cx)
            val block = token.blockHint ?: blockIndexForY(assist, column, token.cy)

            // v17.1: semantic time learning may only consume text from the label zone near the
            // top of a physical block.  Names and notes deeper in the row must never become time
            // evidence even if OCR happens to produce digit-like characters.
            if (block != null) {
                val bounds = RotaGridModel.boundsForBlock(assist, column, block)
                if (bounds != null) {
                    val top = bounds.first
                    val bottom = bounds.second
                    val fraction = ((token.cy - top) / (bottom - top).coerceAtLeast(1f))
                    if (fraction > 0.42f || fraction < -0.08f) return@forEach
                }
            }

            val decision = RotaTimeRecognitionEngine.analyze(token.text, assist.learnedTimeVocabulary)
            listOfNotNull(decision.best, decision.runnerUp).take(2).forEachIndexed { index, h ->
                if (h.time.hour !in 5..23 || h.time.minute !in setOf(0, 15, 30, 45)) return@forEachIndexed
                val runnerPenalty = if (index == 1) 0.16f else 0f
                val ambiguityPenalty = if (decision.ambiguous) 0.08f else 0f
                val sourceAdjustment = if (token.source == ScheduleImporter.TokenSource.TIME_ATLAS) 0.07f else -0.02f
                votes += Vote(
                    y = RotaGridModel.canonicalYForPoint(assist, column, token.cy) / assist.imageHeight.toFloat(),
                    column = column,
                    blockIndex = block,
                    time = h.time,
                    score = (h.score - runnerPenalty - ambiguityPenalty + sourceAdjustment).coerceIn(0.10f, 0.99f)
                )
            }
        }

        // Multiple OCR renderings often produce the same time several times in one weekday.
        // Collapse those duplicates before voting so adding another preprocessing pass cannot
        // artificially overpower evidence from other columns.
        val blockedVotes = votes.filter { it.blockIndex != null }
            .groupBy { Triple(it.blockIndex!!, it.column, it.time) }
            .mapNotNull { (_, sameEvidence) -> sameEvidence.maxByOrNull { it.score } }
        val unblockedVotes = votes.filter { it.blockIndex == null }
        val normalizedVotes = blockedVotes + unblockedVotes

        // Prefer physical block identity. Tokens without usable row geometry fall back to Y clusters.
        val blockGroups = normalizedVotes.filter { it.blockIndex != null }.groupBy { it.blockIndex!! }
        val unblocked = normalizedVotes.filter { it.blockIndex == null }.sortedBy { it.y }
            .fold(mutableListOf<MutableList<Vote>>()) { acc, vote ->
                val near = acc.lastOrNull()?.takeIf { g -> abs(g.map { it.y }.average().toFloat() - vote.y) <= 0.026f }
                if (near != null) near += vote else acc += mutableListOf(vote)
                acc
            }

        val groups = mutableListOf<Pair<Int?, List<Vote>>>()
        blockGroups.toSortedMap().forEach { (idx, group) -> groups += idx to group }
        unblocked.forEach { groups += null to it }

        val observed = groups.mapNotNull { (blockIndex, group) ->
            val byTime = group.groupBy { it.time }.mapValues { (_, vs) ->
                val columns = vs.map { it.column }.distinct().size
                val weighted = vs.sumOf { it.score.toDouble() }.toFloat()
                Triple(columns, weighted, vs.maxOf { it.score })
            }
            val winner = byTime.maxByOrNull { (_, s) -> s.first * 1.35f + s.second } ?: return@mapNotNull null
            val winningVotes = group.filter { it.time == winner.key }
            val support = winningVotes.map { it.column }.distinct().size
            val confidence = (0.34f + support * 0.115f + winningVotes.map { it.score }.average().toFloat() * 0.39f)
                .coerceIn(0f, 0.99f)
            Band(
                yRatio = winningVotes.map { it.y }.average().toFloat(),
                time = winner.key,
                confidence = confidence,
                supportColumns = support,
                learned = false,
                blockIndex = blockIndex,
                confirmations = support
            )
        }.toMutableList()

        // Merge priors by block index whenever possible. Y is only a compatibility fallback.
        priors.forEach { prior ->
            val matchingIndex = if (prior.blockIndex != null) {
                observed.indices.firstOrNull { observed[it].blockIndex == prior.blockIndex }
            } else null
            val nearIndex = matchingIndex ?: observed.indices.minByOrNull { abs(observed[it].yRatio - prior.yRatio) }
                ?.takeIf { abs(observed[it].yRatio - prior.yRatio) <= 0.040f }

            if (nearIndex == null) {
                observed += prior.copy(
                    confidence = (prior.confidence * 0.84f).coerceAtMost(0.90f),
                    learned = true
                )
            } else {
                val current = observed[nearIndex]
                if (current.time == prior.time) {
                    observed[nearIndex] = current.copy(
                        confidence = (current.confidence + 0.09f + (prior.confirmations.coerceAtMost(12) * 0.004f)).coerceAtMost(0.99f),
                        supportColumns = maxOf(current.supportColumns, prior.supportColumns),
                        confirmations = current.confirmations + maxOf(1, prior.confirmations),
                        contradictions = prior.contradictions,
                        blockIndex = current.blockIndex ?: prior.blockIndex
                    )
                } else {
                    val freshStrong = current.supportColumns >= 2 || current.confidence >= 0.78f
                    val priorTrust = (prior.confidence + prior.confirmations.coerceAtMost(10) * 0.015f - prior.contradictions * 0.08f)
                    if (!freshStrong && priorTrust >= 0.82f) {
                        observed[nearIndex] = prior.copy(
                            confidence = 0.66f,
                            learned = true,
                            contradictions = prior.contradictions + 1
                        )
                    }
                }
            }
        }
        return observed.sortedWith(compareBy<Band> { it.blockIndex ?: Int.MAX_VALUE }.thenBy { it.yRatio }).take(12)
    }

    fun forBlock(bands: List<Band>, blockIndex: Int): Band? =
        bands.filter { it.blockIndex == blockIndex }
            .maxByOrNull { it.confidence + it.supportColumns * 0.03f + it.confirmations.coerceAtMost(10) * 0.01f }

    fun nearest(bands: List<Band>, yRatio: Float, maxDistance: Float = 0.055f): Band? =
        bands.minByOrNull { abs(it.yRatio - yRatio) }?.takeIf { abs(it.yRatio - yRatio) <= maxDistance }
}
