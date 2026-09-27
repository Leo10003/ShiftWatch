package com.example.workshifttracker

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * RotaVision v11 verification layer.
 *
 * Detection and verification are deliberately separate. ScheduleImporter proposes hypotheses;
 * this object tries to disprove them using independent structural evidence before a draft is
 * allowed to look trustworthy in the UI.
 */
object RotaVerificationEngine {
    enum class State { CONFIRMED, HIGH_CONFIDENCE, REVIEW, UNRESOLVED, CONFLICT }

    data class Verification(
        val state: State,
        val score: Float,
        val supports: List<String>,
        val contradictions: List<String>,
        val contradictionPenalty: Float
    )

    data class DayNode(
        val column: Int,
        val boundaryCount: Int,
        val draftCount: Int
    )

    data class TimeBandNode(
        val time: LocalTime,
        val support: Int,
        val columns: Set<Int>
    )

    data class CandidateNode(
        val draftId: String,
        val column: Int?,
        val time: LocalTime,
        val confidence: Float,
        val origin: ScheduleImporter.DraftOrigin
    )

    data class DocumentGraph(
        val fingerprint: String,
        val days: List<DayNode>,
        val timeBands: List<TimeBandNode>,
        val candidates: List<CandidateNode>,
        val conflicts: Int
    )

    /** Stable enough to catch the same rota after a rescan without storing image pixels. */
    fun documentFingerprint(assist: ScheduleImporter.AssistData): String {
        val w = assist.imageWidth.coerceAtLeast(1).toFloat()
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val canonicalTokens = assist.tokens.asSequence()
            .filter { it.text.isNotBlank() }
            .map {
                val text = it.text.lowercase(Locale.ROOT).filter { ch -> ch.isLetterOrDigit() || ch == ':' || ch == '.' }
                val x = ((it.cx / w) * 32f).toInt().coerceIn(0, 32)
                val y = ((it.cy / h) * 48f).toInt().coerceIn(0, 48)
                "$text@$x,$y"
            }
            .distinct()
            .sorted()
            .take(120)
            .joinToString("|")
        val normalizedRules = assist.verticalRules.sorted().joinToString(",") { rule ->
            (((rule / w) * 64f).toInt()).toString()
        }
        val normalizedRows = assist.rowBoundaries.flatten().sorted().take(40).joinToString(",") { row ->
            (((row / h) * 96f).toInt()).toString()
        }
        // Duplicate identity must describe the document itself, not what the model happened to
        // know when it was analyzed. Learned vocabulary/template data can change between imports.
        val raw = listOf(
            assist.documentKind.name,
            canonicalTokens,
            normalizedRules,
            normalizedRows
        ).joinToString("#")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun buildGraph(
        assist: ScheduleImporter.AssistData,
        drafts: List<ScheduleImporter.Draft>
    ): DocumentGraph {
        val dayNodes = (0..6).map { col ->
            DayNode(
                column = col,
                boundaryCount = assist.rowBoundaries.getOrNull(col).orEmpty().size,
                draftCount = drafts.count { it.columnIndex == col }
            )
        }
        val timeBands = drafts.groupBy { it.start.toLocalTime() }.map { (time, group) ->
            TimeBandNode(time, group.size, group.mapNotNull { it.columnIndex }.toSet())
        }.sortedBy { it.time }
        val candidates = drafts.map {
            CandidateNode(it.id, it.columnIndex, it.start.toLocalTime(), it.confidence, it.origin)
        }
        val conflicts = drafts.groupBy { it.start.toLocalDate() }.values.sumOf { group ->
            max(0, group.map { it.start.toLocalTime() }.distinct().size - 1)
        }
        return DocumentGraph(
            fingerprint = documentFingerprint(assist),
            days = dayNodes,
            timeBands = timeBands,
            candidates = candidates,
            conflicts = conflicts
        )
    }

    fun verifyDraft(
        draft: ScheduleImporter.Draft,
        assist: ScheduleImporter.AssistData,
        allDrafts: List<ScheduleImporter.Draft>
    ): Verification {
        val supports = mutableListOf<String>()
        val contradictions = mutableListOf<String>()
        var penalty = 0f
        var independentSupport = 0

        val quality = assist.quality.score
        when {
            quality >= 0.72f -> { supports += "image quality strong"; independentSupport++ }
            quality < 0.32f -> { contradictions += "image quality too weak for automatic trust"; penalty += 0.12f }
            quality < 0.48f -> { contradictions += "image quality is marginal"; penalty += 0.05f }
        }

        val column = draft.columnIndex
        if (column != null) {
            val draftMonday = draft.start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val documentMonday = ScheduleImporter.detectedWeekStart(assist, draftMonday)
            val expected = documentMonday.plusDays(column.toLong())
            if (expected == draft.start.toLocalDate()) {
                supports += "weekday/date geometry agrees with rota header"
                independentSupport++
            } else {
                contradictions += "draft date conflicts with the rota header week"
                penalty += 0.30f
            }
            val boundaries = assist.rowBoundaries.getOrNull(column).orEmpty()
            if (boundaries.size >= 3) {
                supports += "schedule block geometry available"
                independentSupport++
            }
        } else if (draft.origin == ScheduleImporter.DraftOrigin.HANDWRITING || draft.origin == ScheduleImporter.DraftOrigin.ASSISTED) {
            contradictions += "no weekday-column ownership"
            penalty += 0.08f
        }

        val time = draft.start.toLocalTime()
        val perceptionSupport = RotaPerceptionEngine.supportForTime(time, assist.perception, assist.learnedTimeVocabulary)
        when {
            perceptionSupport >= 0.78f -> { supports += "specialized time perception agrees"; independentSupport++ }
            perceptionSupport < 0.38f && assist.perception.score >= 0.45f -> {
                contradictions += "specialized time perception does not support this time"
                penalty += 0.08f
            }
        }
        if (assist.perception.ambiguity > 0.55f) {
            contradictions += "time-label perception is ambiguous"
            penalty += 0.04f
        }
        val vocabulary = assist.learnedTimeVocabulary
        if (vocabulary.isNotEmpty()) {
            if (time in vocabulary) {
                supports += "time agrees with learned workplace vocabulary"
                independentSupport++
            } else {
                val nearestMinutes = vocabulary.minOf { candidate ->
                    abs(candidate.toSecondOfDay() - time.toSecondOfDay()) / 60
                }
                if (nearestMinutes >= 45) {
                    contradictions += "time is outside learned schedule vocabulary"
                    penalty += 0.08f
                }
            }
        }

        val sameDay = allDrafts.filter { it.id != draft.id && it.start.toLocalDate() == draft.start.toLocalDate() }
        if (sameDay.any { it.start.toLocalTime() != time }) {
            contradictions += "multiple incompatible shifts detected for the same day"
            penalty += 0.24f
        }

        val sameTimePeers = allDrafts.filter { it.id != draft.id && it.start.toLocalTime() == time }
        val peerColumns = sameTimePeers.mapNotNull { it.columnIndex }.distinct().size
        if (peerColumns >= 2) {
            supports += "same time band repeats across weekdays"
            independentSupport++
        }

        val timeCounts = allDrafts.groupingBy { it.start.toLocalTime() }.eachCount()
        val dominant = timeCounts.maxByOrNull { it.value }
        if (dominant != null && dominant.value >= 3 && dominant.key != time && draft.confidence < 0.90f) {
            val diffMinutes = abs(dominant.key.toSecondOfDay() - time.toSecondOfDay()) / 60
            if (diffMinutes >= 45) {
                contradictions += "weak outlier against the dominant weekly time band"
                penalty += 0.08f
            }
        }

        if (draft.requiresTimeConfirmation) {
            contradictions += "detector requested explicit time confirmation"
            penalty += 0.18f
        }

        if (draft.confidence >= 0.94f) {
            supports += "detector confidence strong"
        } else if (draft.confidence < 0.70f) {
            contradictions += "detector confidence weak"
            penalty += 0.10f
        }

        var score = (draft.confidence * 0.62f + min(1f, independentSupport / 4f) * 0.38f - penalty)
            .coerceIn(0f, 1f)

        // Verification is intentionally conservative: one subsystem must not make a result look
        // verified when independent structural evidence is missing.
        if (independentSupport < 2) score = min(score, 0.86f)
        if (contradictions.size >= 2) score = min(score, 0.74f)
        if (sameDay.isNotEmpty()) score = min(score, 0.69f)
        if (assist.quality.score < 0.28f) score = min(score, 0.64f)

        val confirmedByUser = draft.sourceLine.contains("confirmed", ignoreCase = true)
        val state = when {
            confirmedByUser && !draft.requiresTimeConfirmation -> State.CONFIRMED
            sameDay.isNotEmpty() || penalty >= 0.30f -> State.CONFLICT
            draft.requiresTimeConfirmation || score < 0.62f -> State.UNRESOLVED
            score >= 0.92f && independentSupport >= 2 -> State.HIGH_CONFIDENCE
            else -> State.REVIEW
        }
        if (confirmedByUser) score = max(score, 0.995f)
        return Verification(state, score.coerceIn(0f, 1f), supports, contradictions, penalty.coerceIn(0f, 1f))
    }

    /**
     * Runs a second pass over the complete week. It never changes a detected time silently;
     * contradictions are converted to review requirements instead.
     */
    fun verifyWeek(
        assist: ScheduleImporter.AssistData,
        drafts: List<ScheduleImporter.Draft>
    ): List<ScheduleImporter.Draft> = drafts.map { draft ->
        val report = verifyDraft(draft, assist, drafts)
        val requiresReview = report.state == State.UNRESOLVED || report.state == State.CONFLICT
        draft.copy(
            confidence = min(draft.confidence, report.score),
            requiresTimeConfirmation = draft.requiresTimeConfirmation || requiresReview,
            verificationState = report.state,
            verificationNotes = (report.supports.map { "+ $it" } + report.contradictions.map { "! $it" }).take(8)
        )
    }
}
