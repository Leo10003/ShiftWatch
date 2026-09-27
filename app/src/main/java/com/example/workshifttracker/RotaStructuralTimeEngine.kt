package com.example.workshifttracker

import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

/**
 * v18 structural start-time solver.
 *
 * The unit of reasoning is the physical rota block, not an employee name or a nearby OCR token.
 * Each OCR observation is already assigned to a weekday column and block index.  The solver
 * collapses duplicate OCR passes per column, combines fresh atlas evidence with learned priors,
 * keeps alternatives alive, and applies a soft chronological consistency check across blocks.
 */
object RotaStructuralTimeEngine {
    data class Observation(
        val blockIndex: Int,
        val column: Int,
        val time: LocalTime,
        val confidence: Float,
        val strong: Boolean,
        val atlas: Boolean
    )

    data class Prior(
        val blockIndex: Int,
        val time: LocalTime,
        val confidence: Float,
        val confirmations: Int = 0,
        val contradictions: Int = 0
    )

    data class Alternative(val time: LocalTime, val score: Float)

    data class Resolution(
        val blockIndex: Int,
        val time: LocalTime,
        val confidence: Float,
        val supportColumns: Int,
        val atlasColumns: Int,
        val ambiguous: Boolean,
        val reason: String,
        val alternatives: List<Alternative> = emptyList()
    )

    private data class Candidate(
        val time: LocalTime,
        val score: Float,
        val supportColumns: Int,
        val strongColumns: Int,
        val atlasColumns: Int,
        val evidence: Float,
        val priorStrength: Float
    )

    fun solve(observations: List<Observation>, priors: List<Prior> = emptyList()): List<Resolution> {
        if (observations.isEmpty() && priors.isEmpty()) return emptyList()

        val blockSet = linkedSetOf<Int>()
        observations.forEach { observation -> blockSet += observation.blockIndex }
        priors.forEach { prior -> blockSet += prior.blockIndex }
        val blocks: List<Int> = blockSet.toList().sorted()

        val ranked: MutableMap<Int, List<Candidate>> = linkedMapOf()
        blocks.forEach { block ->
            ranked[block] = rankBlock(block, observations, priors)
        }

        val chosen: MutableList<Resolution> = mutableListOf()

        for (block in blocks) {
            val options: List<Candidate> = ranked[block] ?: emptyList()
            if (options.isEmpty()) continue
            var selected: Candidate = options[0]
            val previous: Resolution? = chosen.lastOrNull()

            // A normal rota lists start-time groups chronologically from top to bottom.  Treat
            // ordering as a soft structural constraint, never as permission to invent a time.
            if (previous != null && selected.time.toSecondOfDay() <= previous.time.toSecondOfDay()) {
                val freshStrong = selected.supportColumns >= 3 && selected.atlasColumns >= 2
                val orderedAlternative = options.drop(1).firstOrNull {
                    it.time.toSecondOfDay() > previous.time.toSecondOfDay() &&
                        // Ordering is a tie-breaker, not evidence: alternatives must have their
                        // own independent weekday/atlas support before replacing the winner.
                        it.supportColumns >= 2 && it.atlasColumns >= 1 &&
                        selected.score - it.score <= 1.65f
                }
                if (!freshStrong && orderedAlternative != null) selected = orderedAlternative
            }

            val runner: Candidate? = options.firstOrNull { candidate -> candidate.time != selected.time }
            val margin: Float = selected.score - (runner?.score ?: 0f)
            val repeatedFresh = selected.supportColumns >= 2
            val atlasBacked = selected.atlasColumns >= 1 && selected.supportColumns >= 2
            val trustedPrior = selected.priorStrength >= 0.72f
            // v19.1: one isolated but very clear atlas observation is useful when it sits in a
            // structurally consistent block. Previously it was always left unresolved, which is
            // why clearly visible 13/16/18 labels could fail to propagate to employee matches.
            val strongSingleAtlas = selected.supportColumns == 1 && selected.atlasColumns == 1 &&
                selected.evidence >= 0.84f && margin >= 1.10f
            val wellLearnedPrior = trustedPrior && selected.priorStrength >= 0.86f &&
                blockPriorsForResolution(priors, block, selected.time)
            val enoughEvidence = repeatedFresh || (atlasBacked && selected.evidence >= 1.15f) ||
                (trustedPrior && selected.supportColumns >= 1) || strongSingleAtlas || wellLearnedPrior
            var ambiguous = !enoughEvidence || margin < if (strongSingleAtlas || wellLearnedPrior) 0.34f else 0.52f

            // If chronological order is still violated, fresh evidence must be exceptionally
            // strong; otherwise abstain instead of allowing one row to borrow another row's time.
            if (previous != null && selected.time.toSecondOfDay() <= previous.time.toSecondOfDay()) {
                if (!(selected.supportColumns >= 4 && selected.atlasColumns >= 2 && margin >= 1.5f)) {
                    ambiguous = true
                }
            }

            val rawBase = 0.52f + min(0.18f, selected.supportColumns * 0.045f) +
                min(0.12f, selected.atlasColumns * 0.04f) +
                min(0.10f, selected.evidence * 0.035f) +
                min(0.05f, max(0f, margin) * 0.015f) +
                min(0.05f, selected.priorStrength * 0.05f)
            val base = when {
                !ambiguous && strongSingleAtlas -> max(rawBase, 0.80f)
                !ambiguous && wellLearnedPrior -> max(rawBase, 0.82f)
                else -> rawBase
            }
            val confidence = (if (ambiguous) min(base, 0.77f) else base).coerceIn(0.35f, 0.985f)
            val reason = when {
                selected.atlasColumns >= 2 && selected.supportColumns >= 3 -> "block atlas consensus"
                selected.supportColumns >= 3 -> "cross-week block consensus"
                selected.atlasColumns >= 1 && selected.supportColumns >= 2 -> "block-local visual consensus"
                strongSingleAtlas -> "clear isolated block label"
                trustedPrior && selected.supportColumns >= 1 -> "fresh block evidence + learned mapping"
                wellLearnedPrior -> "confirmed learned block mapping"
                trustedPrior -> "learned block mapping"
                else -> "weak structural block evidence"
            }
            chosen += Resolution(
                blockIndex = block,
                time = selected.time,
                confidence = confidence,
                supportColumns = selected.supportColumns,
                atlasColumns = selected.atlasColumns,
                ambiguous = ambiguous,
                reason = reason,
                alternatives = options.take(4).map { candidate -> Alternative(candidate.time, candidate.score) }
            )
        }
        return chosen
    }

    private fun rankBlock(
        block: Int,
        observations: List<Observation>,
        priors: List<Prior>
    ): List<Candidate> {
        val blockObs: List<Observation> = observations.filter { observation -> observation.blockIndex == block }
        val blockPriors: List<Prior> = priors.filter { prior -> prior.blockIndex == block }

        val timeSet = linkedSetOf<LocalTime>()
        blockObs.forEach { observation -> timeSet += observation.time }
        blockPriors.forEach { prior -> timeSet += prior.time }

        val result: MutableList<Candidate> = mutableListOf()
        for (time in timeSet) {
            val matchingObservations: List<Observation> = blockObs.filter { observation -> observation.time == time }
            val grouped: Map<Int, List<Observation>> = matchingObservations.groupBy { observation -> observation.column }
            val perColumn: MutableMap<Int, Observation> = linkedMapOf()
            grouped.forEach { (column, values) ->
                val best: Observation? = values.maxByOrNull { observation -> observationScore(observation) }
                if (best != null) perColumn[column] = best
            }

            val support: Int = perColumn.size
            val strong: Int = perColumn.values.count { observation -> observation.strong }
            val atlas: Int = perColumn.values.count { observation -> observation.atlas }
            val evidence: Float = perColumn.values.sumOf { observation -> observation.confidence.toDouble() }.toFloat()

            val matchingPriors: List<Prior> = blockPriors.filter { prior -> prior.time == time }
            val prior: Prior? = matchingPriors.maxByOrNull { item ->
                item.confidence + min(12, item.confirmations) * 0.018f - min(6, item.contradictions) * 0.10f
            }
            val priorStrength: Float = prior?.let { item ->
                (item.confidence + min(12, item.confirmations) * 0.018f - min(6, item.contradictions) * 0.10f)
                    .coerceIn(0f, 0.94f)
            } ?: 0f

            val minuteSpecific: Float = if (time.minute != 0) 0.18f else 0f
            val score: Float = evidence * 2.65f + support * 1.85f + strong * 0.82f + atlas * 1.05f +
                priorStrength * 1.20f + minuteSpecific
            result += Candidate(time, score, support, strong, atlas, evidence, priorStrength)
        }

        return result.sortedByDescending { candidate -> candidate.score }
    }

    private fun blockPriorsForResolution(priors: List<Prior>, block: Int, time: LocalTime): Boolean {
        val prior = priors.filter { it.blockIndex == block && it.time == time }
            .maxByOrNull { it.confidence + min(12, it.confirmations) * 0.018f - min(6, it.contradictions) * 0.10f }
            ?: return false
        // An explicit user confirmation is stronger than OCR.  The prior only reaches this
        // solver after template matching, so a single high-confidence, contradiction-free
        // calibration may safely recover the same physical block on later imports.
        return prior.contradictions == 0 && (
            (prior.confidence >= 0.95f && prior.confirmations >= 1) ||
                (prior.confidence >= 0.90f && prior.confirmations >= 6)
        )
    }

    private fun observationScore(o: Observation): Float =
        o.confidence + (if (o.strong) 0.10f else 0f) + (if (o.atlas) 0.13f else 0f)
}
