package com.example.workshifttracker

import android.graphics.Bitmap
import java.text.Normalizer
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RotaVision 8: a fully-offline handwriting matcher specialized for weekly work rotas.
 *
 * The model deliberately does not pretend to be a general handwriting recognizer. It combines:
 *  - the physical seven-column rota geometry,
 *  - OCR hints when ML Kit manages to read part of a name,
 *  - two or more user-confirmed handwriting examples,
 *  - translation-tolerant 2D ink-shape matching,
 *  - horizontal/vertical projection profiles,
 *  - stroke-transition profiles, elastic word-shape alignment, density, aspect and centre-of-mass features.
 *
 * The result is an ensemble classifier tuned for the exact job ShiftWatch needs: find the same
 * handwritten employee name again in the other day columns. Suggestions are never persisted as
 * shifts until the user confirms them.
 */
internal object OfflineRotaVision {
    data class OcrNameHit(
        val x: Float,
        val y: Float,
        val column: Int,
        val score: Float,
        val rawText: String,
        val exact: Boolean,
        val top: Int,
        val bottom: Int
    )

    /**
     * Text-first employee detector. Printed/Excel rotas should never require handwriting training:
     * when OCR can read the requested employee, use that evidence directly.  The detector scores
     * individual words as well as full OCR tokens, deduplicates repeated OCR passes, and keeps the
     * strongest occurrence in each weekday column.
     */
    fun ocrNameHits(assist: ScheduleImporter.AssistData, employeeName: String): List<OcrNameHit> {
        val target = normalizeWord(employeeName)
        if (target.length < 2 || assist.imageWidth <= 0 || assist.imageHeight <= 0) return emptyList()
        data class Candidate(val hit: OcrNameHit, val top: Int, val bottom: Int)
        val candidates = assist.tokens.mapNotNull { token ->
            if (token.cy !in (assist.imageHeight * 0.105f)..(assist.imageHeight * 0.925f)) return@mapNotNull null
            // OCR lines in printed tables may contain a name plus punctuation or a time. Score the
            // alphabetic chunks independently so "Leonardo 09:30" still resolves to LEONARDO.
            val chunks = Regex("[\\p{L}0-9]+", RegexOption.IGNORE_CASE).findAll(token.text)
                .map { normalizeWord(it.value) }.filter { it.length >= 2 }.toList()
            if (chunks.isEmpty()) return@mapNotNull null
            var bestText = ""
            var best = 0f
            for (chunk in chunks + normalizeWord(token.text)) {
                if (chunk.isBlank()) continue
                val score = wordSimilarity(target, chunk)
                if (score > best) { best = score; bestText = chunk }
            }
            val exact = bestText == target
            val threshold = when {
                exact -> 0.0f
                target.length <= 4 -> 0.82f
                else -> 0.78f
            }
            if (best < threshold) return@mapNotNull null
            val column = ScheduleImporter.columnIndexForX(assist, token.cx)
            Candidate(OcrNameHit(token.cx, token.cy, column, best, token.text, exact, token.top, token.bottom), token.top, token.bottom)
        }
        // The same word is normally emitted by line + element and by several OCR passes. Cluster
        // those copies before choosing one occurrence per day.
        val merged = mutableListOf<OcrNameHit>()
        candidates.sortedByDescending { it.hit.score }.forEach { candidate ->
            val h = candidate.hit
            val duplicate = merged.any { old ->
                old.column == h.column && kotlin.math.abs(old.y - h.y) <= assist.imageHeight * 0.018f
            }
            if (!duplicate) merged += h
        }
        return merged.groupBy { it.column }.mapNotNull { (_, group) ->
            group.maxWithOrNull(compareBy<OcrNameHit> { it.exact }.thenBy { it.score })
        }.sortedBy { it.column }
    }

    data class Match(
        val x: Float,
        val y: Float,
        val column: Int,
        val score: Float
    )

    data class Report(
        val seedColumn: Int,
        val seedY: Float,
        val matches: List<Match>,
        val scannedLines: Int,
        val columnDecisions: List<RotaDiagnosticEvidence.ProfileDecision> = emptyList()
    )

    /** v13 local identity-model health. Counts are prototype clusters, not raw observations. */
    data class ProfileDiagnostics(
        val stylePrototypes: Int,
        val confuserPrototypes: Int,
        val closestConfuserSimilarity: Float,
        val separationHealth: Float
    )

    private data class Band(val top: Int, val bottom: Int) {
        val center: Float get() = (top + bottom) / 2f
        val height: Int get() = bottom - top + 1
    }

    private data class Candidate(val band: Band, val ocrText: String?)

    private data class Signature(
        val pixels: FloatArray,
        val horizontal: FloatArray,
        val vertical: FloatArray,
        val rowTransitions: FloatArray,
        val colTransitions: FloatArray,
        val density: Float,
        val aspect: Float,
        val centerX: Float,
        val centerY: Float
    )

    private data class ProfileModel(
        val positives: List<Signature>,
        val negatives: List<Signature> = emptyList()
    )

    private const val GRID_W = 48
    private const val GRID_H = 18

    // Signature extraction is the expensive part of the visual matcher. During one review session
    // the same bitmap/candidate rows are scored repeatedly as the user confirms examples. Cache the
    // normalized signatures by bitmap + crop rectangle so subsequent learning passes are mostly math.
    private val signatureCache = WeakHashMap<Bitmap, MutableMap<String, Signature?>>()
    private val candidateCache = WeakHashMap<Bitmap, MutableMap<String, List<Candidate>>>()
    private val candidateTraceCache = WeakHashMap<Bitmap, MutableMap<String, RotaDiagnosticEvidence.CandidatePipeline>>()

    private fun candidateKey(assist: ScheduleImporter.AssistData, column: Int, left: Int, right: Int): String =
        "${System.identityHashCode(assist)}:$column:$left:$right:${assist.tokens.size}"

    @Synchronized
    private fun cachedCandidateTrace(bitmap: Bitmap, assist: ScheduleImporter.AssistData,
                                     column: Int, left: Int, right: Int): RotaDiagnosticEvidence.CandidatePipeline? =
        candidateTraceCache[bitmap]?.get(candidateKey(assist, column, left, right))

    @Synchronized
    private fun cachedCandidates(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        column: Int,
        left: Int,
        right: Int,
        sx: Float,
        sy: Float
    ): List<Candidate> {
        val perBitmap = candidateCache.getOrPut(bitmap) { mutableMapOf() }
        val key = candidateKey(assist, column, left, right)
        return perBitmap.getOrPut(key) {
            val (candidates, trace) = buildCandidates(bitmap, assist, column, left, right, sx, sy)
            candidateTraceCache.getOrPut(bitmap) { mutableMapOf() }[key] = trace
            candidates
        }
    }

    @Synchronized
    private fun cachedSignature(bitmap: Bitmap, left: Int, right: Int, band: Band): Signature? {
        val perBitmap = signatureCache.getOrPut(bitmap) { mutableMapOf() }
        val key = "$left:$right:${band.top}:${band.bottom}"
        if (perBitmap.containsKey(key)) return perBitmap[key]
        val value = signature(bitmap, left, right, band)
        if (perBitmap.size > 700) perBitmap.clear()
        perBitmap[key] = value
        return value
    }

    /**
     * Fully-offline bootstrap from OCR when the employee name happens to be readable in at least
     * two weekday columns. These are only seed hints for the visual matcher; they never create
     * planner shifts by themselves.
     */
    fun bootstrapSeedsFromOcr(
        assist: ScheduleImporter.AssistData,
        employeeName: String
    ): List<Pair<Float, Float>> = ocrNameHits(assist, employeeName)
        .filter { it.exact || it.score >= 0.84f }
        .take(7)
        .map { it.x to it.y }

    fun createProfile(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        seedX: Float,
        seedY: Float
    ): String? {
        if (!usable(bitmap, assist)) return null
        val sx = bitmap.width.toFloat() / assist.imageWidth.toFloat()
        val sy = bitmap.height.toFloat() / assist.imageHeight.toFloat()
        val column = ScheduleImporter.columnIndexForX(assist, seedX)
        val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
        val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
        val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
        val seedYBitmap = seedY * sy
        val candidate = cachedCandidates(bitmap, assist, column, left, right, sx, sy)
            .minByOrNull { abs(it.band.center - seedYBitmap) }
            ?.takeIf { abs(it.band.center - seedYBitmap) <= max(38f, bitmap.height * 0.055f) }
            ?: return null
        return cachedSignature(bitmap, left, right, candidate.band)?.let(::encode)
    }

    /** Stores several confirmed examples as one local employee handwriting model. */
    fun createProfileSet(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        seeds: List<Pair<Float, Float>>
    ): String? {
        if (!usable(bitmap, assist)) return null
        val sx = bitmap.width.toFloat() / assist.imageWidth.toFloat()
        val sy = bitmap.height.toFloat() / assist.imageHeight.toFloat()
        val encoded = seeds.distinctBy { ScheduleImporter.columnIndexForX(assist, it.first) to (it.second / 16f).toInt() }
            .mapNotNull { (seedX, seedY) ->
                val column = ScheduleImporter.columnIndexForX(assist, seedX)
                val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
                val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
                val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
                val targetY = seedY * sy
                val candidate = cachedCandidates(bitmap, assist, column, left, right, sx, sy)
                    .minByOrNull { abs(it.band.center - targetY) }
                    ?.takeIf { abs(it.band.center - targetY) <= max(40f, bitmap.height * 0.058f) }
                    ?: return@mapNotNull null
                cachedSignature(bitmap, left, right, candidate.band)?.let(::encode)
            }
            .distinct()
        if (encoded.isEmpty()) return null
        if (encoded.size < 2) return encoded.first()
        // Keep style diversity immediately instead of persisting six near-identical samples from
        // the same rota. Later merges can grow this bank to sixteen representative prototypes.
        val encodedDiverse = selectDiversePrototypes(encoded.mapNotNull(::decode), 8).map(::encode)

        // Persist a small set of hard negatives as well. Future rotas then remember not only what
        // the employee name looks like, but also which same-writer word shapes were confusing.
        // This dramatically reduces false positives when the same person writes the whole rota.
        val positiveSigs = encodedDiverse.mapNotNull(::decode)
        val negativeCandidates = mutableListOf<Pair<Signature, Float>>()
        seeds.distinctBy { ScheduleImporter.columnIndexForX(assist, it.first) }.forEach { (seedX, seedY) ->
            val column = ScheduleImporter.columnIndexForX(assist, seedX)
            val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
            val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
            val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
            val targetY = seedY * sy
            cachedCandidates(bitmap, assist, column, left, right, sx, sy)
                .filter { abs(it.band.center - targetY) > max(44f, bitmap.height * 0.050f) }
                .forEach { candidate ->
                    val sig = cachedSignature(bitmap, left, right, candidate.band) ?: return@forEach
                    val confusion = positiveSigs.maxOfOrNull { similarity(it, sig) } ?: 0f
                    if (confusion >= 0.42f) negativeCandidates += sig to confusion
                }
        }
        val negativePool = negativeCandidates.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { sig -> listOf((sig.aspect * 16).toInt(), (sig.density * 150).toInt(), (sig.centerX * 22).toInt(), (sig.centerY * 22).toInt()) }
        val negatives = selectDiversePrototypes(negativePool, 14).map(::encode)
        return "rvset3;P=" + encodedDiverse.joinToString(";") + "#N=" + negatives.joinToString(";")
    }

    /**
     * Merge newly confirmed handwriting into the existing local model instead of replacing it.
     * This lets ShiftWatch accumulate several natural writing styles for one employee over time.
     */
    fun mergeProfiles(existing: String?, incoming: String?): String? {
        if (incoming.isNullOrBlank()) return existing
        if (existing.isNullOrBlank()) return incoming
        val a = decodeProfileModel(existing)
        val b = decodeProfileModel(incoming)
        val positivePool = (a.positives + b.positives)
            .distinctBy { sig -> listOf((sig.aspect * 22).toInt(), (sig.density * 190).toInt(), (sig.centerX * 28).toInt(), (sig.centerY * 28).toInt()) }
        val positives = selectDiversePrototypes(positivePool, 16)
        val negativePool = (a.negatives + b.negatives)
            .distinctBy { sig -> listOf((sig.aspect * 16).toInt(), (sig.density * 150).toInt(), (sig.centerX * 22).toInt(), (sig.centerY * 22).toInt()) }
        // Keep hard negatives that are closest to at least one positive. Randomly different words
        // add little value; near-misses are what teach the local model useful decision boundaries.
        val negatives = negativePool
            .map { sig -> sig to (positives.maxOfOrNull { similarity(it, sig) } ?: 0f) }
            .filter { it.second >= 0.34f }
            .sortedByDescending { it.second }
            .take(28)
            .map { it.first }
        if (positives.isEmpty()) return incoming
        return "rvset3;P=" + positives.joinToString(";") { encode(it) } + "#N=" + negatives.joinToString(";") { encode(it) }
    }


    /**
     * Farthest-point prototype selection keeps genuinely different handwriting styles instead of
     * filling the profile with near-identical samples from one week. It is a lightweight local
     * approximation of prototype clustering and requires no network/model download.
     */
    private fun selectDiversePrototypes(pool: List<Signature>, limit: Int): List<Signature> {
        if (pool.size <= limit) return pool
        val selected = mutableListOf<Signature>()
        // Start with the sample most representative of the pool.
        val first = pool.maxByOrNull { candidate ->
            pool.asSequence().filter { it !== candidate }.map { similarity(candidate, it) }.average()
        } ?: return pool.take(limit)
        selected += first
        val remaining = pool.toMutableList().apply { remove(first) }
        while (selected.size < limit && remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { candidate ->
                selected.maxOf { similarity(candidate, it) }
            } ?: break
            selected += next
            remaining.remove(next)
        }
        return selected
    }
    /** Summarizes how well the local employee model is separated from known confusers. */
    fun profileDiagnostics(encodedProfile: String?): ProfileDiagnostics {
        if (encodedProfile.isNullOrBlank()) return ProfileDiagnostics(0, 0, 0f, 0f)
        val model = decodeProfileModel(encodedProfile)
        val closest = model.negatives.maxOfOrNull { negative ->
            model.positives.maxOfOrNull { positive -> similarity(positive, negative) } ?: 0f
        } ?: 0f
        val styleBreadth = (model.positives.size / 6f).coerceIn(0f, 1f)
        val confuserCoverage = (model.negatives.size / 8f).coerceIn(0f, 1f)
        // High confuser similarity means the boundary is difficult, but having explicit confuser
        // prototypes is still healthier than being unaware of them. Keep this diagnostic descriptive.
        val boundary = (1f - (closest - 0.45f).coerceAtLeast(0f) / 0.45f).coerceIn(0f, 1f)
        val health = (styleBreadth * 0.46f + confuserCoverage * 0.24f + boundary * 0.30f).coerceIn(0f, 1f)
        return ProfileDiagnostics(model.positives.size, model.negatives.size, closest, health)
    }

    /**
     * Learns a user-rejected name occurrence as a targeted hard negative. This is intentionally
     * correction-specific: changing a time must not alter identity, while removing a false employee
     * match should strengthen the employee-vs-confuser boundary for later rotas.
     */
    fun learnHardNegative(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        encodedProfile: String?,
        sourceX: Float,
        sourceY: Float
    ): String? {
        if (encodedProfile.isNullOrBlank() || !usable(bitmap, assist)) return encodedProfile
        val model = decodeProfileModel(encodedProfile)
        if (model.positives.isEmpty()) return encodedProfile
        val sx = bitmap.width.toFloat() / assist.imageWidth.toFloat()
        val sy = bitmap.height.toFloat() / assist.imageHeight.toFloat()
        val column = ScheduleImporter.columnIndexForX(assist, sourceX)
        val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
        val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
        val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
        val targetY = sourceY * sy
        val candidate = cachedCandidates(bitmap, assist, column, left, right, sx, sy)
            .minByOrNull { abs(it.band.center - targetY) }
            ?.takeIf { abs(it.band.center - targetY) <= max(44f, bitmap.height * 0.060f) }
            ?: return encodedProfile
        val negative = cachedSignature(bitmap, left, right, candidate.band) ?: return encodedProfile
        val resemblesTarget = model.positives.maxOfOrNull { similarity(it, negative) } ?: 0f
        // Extremely unrelated shapes teach nothing; preserve only genuine near-miss confusers.
        if (resemblesTarget < 0.30f) return encodedProfile
        val negatives = selectDiversePrototypes(model.negatives + negative, 28)
        return "rvset3;P=" + model.positives.joinToString(";") { encode(it) } +
            "#N=" + negatives.joinToString(";") { encode(it) }
    }

    fun findMatchesFromProfile(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        encodedProfile: String
    ): Report {
        val model = decodeProfileModel(encodedProfile)
        val profiles = model.positives
        if (profiles.isEmpty()) return Report(-1, 0f, emptyList(), 0)
        if (!usable(bitmap, assist)) return Report(-1, 0f, emptyList(), 0)
        val sx = bitmap.width.toFloat() / assist.imageWidth.toFloat()
        val sy = bitmap.height.toFloat() / assist.imageHeight.toFloat()
        var scanned = 0
        val matches = mutableListOf<Match>()
        data class Ranked(val candidate: Candidate, val score: Float, val positive: Float,
                          val negative: Float, val separation: Float,
                          val rawSeparation: Float, val confuserPenalty: Float,
                          val separationAdjustment: Float)
        data class Deferred(
            val column: Int,
            val x: Float,
            val y: Float,
            val score: Float,
            val runner: Float,
            val negative: Float,
            val separation: Float,
            val floor: Float
        )
        val deferred = mutableListOf<Deferred>()
        val decisions = linkedMapOf<Int, RotaDiagnosticEvidence.ProfileDecision>()
        for (column in 0..6) {
            val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
            val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
            val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
            val candidates = cachedCandidates(bitmap, assist, column, left, right, sx, sy)
            scanned += candidates.size
            val ranked = candidates.mapNotNull { candidate ->
                val sig = cachedSignature(bitmap, left, right, candidate.band) ?: return@mapNotNull null
                val scores = profiles.map { similarity(it, sig) }.sorted()
                val median = scores[scores.size / 2]
                val weakest = scores.first()
                val average = scores.average().toFloat()
                val strongest = scores.last()
                var score = (strongest * 0.46f + median * 0.29f + average * 0.21f + weakest * 0.04f).coerceIn(0f, 1f)
                val positive = score
                var negative = 0f
                var confuserPenalty = 0f
                var separationAdjustment = 0f
                var rawSeparation = 0f
                if (model.negatives.isNotEmpty()) {
                    val negativeScores = model.negatives.map { similarity(it, sig) }.sortedDescending()
                    negative = negativeScores.take(3).average().toFloat()
                    val separation = score - negative
                    rawSeparation = separation
                    val boundary = RotaIdentityPolicy.boundary(profiles.size, model.negatives.size, negative)
                    confuserPenalty = boundary.confuserPenalty
                    score -= confuserPenalty
                    if (separation < boundary.requiredSeparation) {
                        separationAdjustment = -0.12f
                        score += separationAdjustment
                    } else if (separation > boundary.requiredSeparation + 0.13f) {
                        separationAdjustment = 0.025f
                        score += separationAdjustment
                    }
                }
                Ranked(candidate, score.coerceIn(0f, 1f), positive, negative, score - negative,
                    rawSeparation, confuserPenalty, separationAdjustment)
            }.sortedWith(compareByDescending<Ranked> { it.score }
                .thenBy { it.candidate.band.top }.thenBy { it.candidate.band.bottom })
            val best = ranked.firstOrNull()
            if (best == null) {
                decisions[column] = RotaDiagnosticEvidence.ProfileDecision(column, candidates.size, 0, null, null, null, null,
                    if (candidates.isEmpty()) "no_candidate_lines" else "no_usable_signatures",
                    pipeline = cachedCandidateTrace(bitmap, assist, column, left, right))
                continue
            }
            val runner = ranked.getOrNull(1)?.score ?: 0f
            val runnerCandidate = ranked.getOrNull(1)
            val runnerOverlap = runnerCandidate?.let {
                RotaDiagnosticEvidence.bandOverlapFraction(best.candidate.band.top, best.candidate.band.bottom,
                    it.candidate.band.top, it.candidate.band.bottom)
            }
            val runnerSameBlock = runnerCandidate?.let {
                RotaGridModel.blockIndexForY(assist, column, best.candidate.band.center / sy) ==
                    RotaGridModel.blockIndexForY(assist, column, it.candidate.band.center / sy)
            }
            val profilePairScores = mutableListOf<Float>()
            for (i in profiles.indices) for (j in i + 1 until profiles.size) profilePairScores += similarity(profiles[i], profiles[j])
            val consistency = profilePairScores.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.78f
            val floor = (consistency - 0.17f).coerceIn(0.55f, 0.69f)
            val normalAccept = best.score >= floor && (best.score - runner >= 0.025f || best.score >= floor + 0.11f)

            // v18.1 missed-day rescue: a genuine handwriting occurrence can be slightly weaker on
            // one weekday because of pen pressure, camera blur or a grid crossing. Rescue only a
            // *single* best candidate that sits just below the normal floor and is clearly separated
            // from both the runner-up and learned confusers. This improves recall without returning
            // to the old permissive multi-candidate behaviour.
            val rescueFloor = (floor - 0.055f).coerceAtLeast(0.50f)
            val rescueAccept = !normalAccept && best.score >= rescueFloor &&
                best.score - runner >= 0.060f &&
                (model.negatives.isEmpty() || best.separation >= 0.075f) &&
                best.negative < 0.78f

            decisions[column] = RotaDiagnosticEvidence.ProfileDecision(column, candidates.size, ranked.size,
                best.score, runner, floor, best.negative,
                when {
                    normalAccept -> "accepted_normal"
                    rescueAccept -> "accepted_near_floor"
                    best.score < rescueFloor -> "rejected_below_rescue_floor"
                    best.score - runner < 0.060f -> "rejected_insufficient_runner_margin"
                    model.negatives.isNotEmpty() && best.separation < 0.075f -> "rejected_confuser_separation"
                    best.negative >= 0.78f -> "rejected_high_confuser_similarity"
                    else -> "rejected_combined_policy"
                },
                rankedCandidates = ranked.take(3).mapIndexed { index, item ->
                    val documentY = item.candidate.band.center / sy
                    RotaDiagnosticEvidence.RankedProfileCandidate(
                        rank = index + 1,
                        physicalBlockIndex = RotaGridModel.blockIndexForY(assist, column, documentY),
                        verticalDecile = RotaDiagnosticEvidence.verticalDecile(documentY, assist.imageHeight.toFloat()),
                        adjustedScore = item.score,
                        positiveScore = item.positive,
                        confuserScore = item.negative,
                        rawSeparation = item.rawSeparation,
                        confuserPenalty = item.confuserPenalty,
                        separationAdjustment = item.separationAdjustment,
                        candidateOrigin = if (item.candidate.ocrText == null) "ink_gap_probe" else "ocr_token_band"
                    )
                }, runnerOverlapFraction = runnerOverlap, runnerIsSamePhysicalBlock = runnerSameBlock,
                pipeline = cachedCandidateTrace(bitmap, assist, column, left, right),
                candidateSources = RotaDiagnosticEvidence.sourceEvidence(ranked.map { item ->
                    val documentY = item.candidate.band.center / sy
                    val origin = if (item.candidate.ocrText == null) "ink_gap_probe" else "ocr_token_band"
                    origin to RotaDiagnosticEvidence.RankedProfileCandidate(
                        rank = 1,
                        physicalBlockIndex = RotaGridModel.blockIndexForY(assist, column, documentY),
                        verticalDecile = RotaDiagnosticEvidence.verticalDecile(documentY, assist.imageHeight.toFloat()),
                        adjustedScore = item.score, positiveScore = item.positive,
                        confuserScore = item.negative
                    )
                }))
            if (normalAccept || rescueAccept) {
                matches += Match(
                    x = (sourceLeft + sourceRight) / 2f,
                    y = (best.candidate.band.center / sy).coerceIn(0f, assist.imageHeight.toFloat()),
                    column = column,
                    score = (if (rescueAccept) best.score * 0.94f else best.score).coerceIn(0f, 1f)
                )
            } else {
                deferred += Deferred(
                    column = column,
                    x = (sourceLeft + sourceRight) / 2f,
                    y = (best.candidate.band.center / sy).coerceIn(0f, assist.imageHeight.toFloat()),
                    score = best.score,
                    runner = runner,
                    negative = best.negative,
                    separation = best.separation,
                    floor = floor
                )
            }
        }

        // v19 sequence-level recall rescue. Once the same identity is already strong on several
        // weekdays, one genuinely weaker day may be rescued using a stricter confuser margin. This
        // is intentionally applied only after the first pass, and at most two missing columns can
        // be added, so a generally poor profile never becomes permissive across the whole week.
        if (matches.size >= 3 && deferred.isNotEmpty()) {
            val acceptedMedian = matches.map { it.score }.sorted().let { values -> values[values.size / 2] }
            val candidates = deferred
                .filter { item ->
                    val weeklyFloor = (item.floor - 0.085f).coerceAtLeast(0.49f)
                    item.score >= weeklyFloor &&
                        item.score >= acceptedMedian - 0.20f &&
                        item.score - item.runner >= 0.070f &&
                        (model.negatives.isEmpty() || item.separation >= 0.105f) &&
                        item.negative < 0.72f
                }
                .sortedWith(compareByDescending<Deferred> { it.separation }.thenByDescending { it.score })
                .take(2)
            for (item in candidates) {
                if (matches.none { it.column == item.column }) {
                    matches += Match(item.x, item.y, item.column, (item.score * 0.90f).coerceIn(0f, 0.86f))
                    decisions[item.column] = decisions.getValue(item.column).copy(status = "accepted_weekly_rescue")
                }
            }
        }

        // v20.2 mature-profile recovery. A profile with many diverse confirmed styles should not
        // collapse from a nearly complete week to one match just because one new photo is softer or
        // lower contrast. When the local identity model is mature, recover additional weekdays only
        // when the candidate is clearly separated from both its same-column runner-up and the
        // learned confuser set. These remain review-grade suggestions rather than verified matches.
        if (profiles.size >= 8 && matches.size in 1..2 && deferred.isNotEmpty()) {
            val strongestAccepted = matches.maxOfOrNull { it.score } ?: 0f
            val recovery = deferred
                .filter { item ->
                    // v20.3: prioritize recall for a mature, user-trained identity model. These
                    // matches are review suggestions, not planner selections, so we can safely
                    // recover softer handwriting while still requiring clear runner-up/confuser
                    // separation.
                    val matureFloor = (item.floor - 0.15f).coerceAtLeast(0.42f)
                    item.score >= matureFloor &&
                        item.score >= strongestAccepted - 0.34f &&
                        item.score - item.runner >= 0.045f &&
                        (model.negatives.isEmpty() || item.separation >= 0.080f) &&
                        item.negative < 0.76f
                }
                .sortedWith(compareByDescending<Deferred> { it.separation }.thenByDescending { it.score })
                .take(5)
            for (item in recovery) {
                if (matches.none { it.column == item.column }) {
                    matches += Match(item.x, item.y, item.column, (item.score * 0.86f).coerceIn(0f, 0.82f))
                    decisions[item.column] = decisions.getValue(item.column).copy(status = "accepted_mature_profile_recovery")
                }
            }
        }
        return Report(-1, 0f, matches.sortedBy { it.column }, scanned,
            (0..6).map { decisions.getValue(it) })
    }

    fun findSimilarNames(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        seedX: Float,
        seedY: Float
    ): Report = findSimilarNames(bitmap, assist, listOf(seedX to seedY), null)

    fun findSimilarNames(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        seeds: List<Pair<Float, Float>>
    ): Report = findSimilarNames(bitmap, assist, seeds, null)

    /** Hybrid visual + OCR ensemble. employeeName is optional so old callers remain compatible. */
    fun findSimilarNames(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        seeds: List<Pair<Float, Float>>,
        employeeName: String?
    ): Report {
        if (!usable(bitmap, assist)) {
            val first = seeds.firstOrNull()
            return Report(first?.let { ScheduleImporter.columnIndexForX(assist, it.first) } ?: 0, first?.second ?: 0f, emptyList(), 0)
        }

        val sx = bitmap.width.toFloat() / assist.imageWidth.toFloat()
        val sy = bitmap.height.toFloat() / assist.imageHeight.toFloat()
        data class ColumnData(val left: Int, val right: Int, val candidates: List<Candidate>)
        val columns = (0..6).associateWith { column ->
            val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
            val left = (sourceLeft * sx).toInt().coerceIn(0, bitmap.width - 2)
            val right = (sourceRight * sx).toInt().coerceIn(left + 2, bitmap.width)
            ColumnData(left, right, cachedCandidates(bitmap, assist, column, left, right, sx, sy))
        }

        data class SeedSig(val column: Int, val y: Float, val signature: Signature)
        val seedSigs = seeds
            .distinctBy { ScheduleImporter.columnIndexForX(assist, it.first) to (it.second / 16f).toInt() }
            .mapNotNull { (seedX, seedY) ->
                val column = ScheduleImporter.columnIndexForX(assist, seedX)
                val data = columns.getValue(column)
                val targetY = seedY * sy
                val candidate = data.candidates.minByOrNull { abs(it.band.center - targetY) }
                    ?.takeIf { abs(it.band.center - targetY) <= max(40f, bitmap.height * 0.058f) }
                    ?: return@mapNotNull null
                val sig = cachedSignature(bitmap, data.left, data.right, candidate.band) ?: return@mapNotNull null
                SeedSig(column, seedY, sig)
            }

        if (seedSigs.isEmpty()) {
            return Report(0, seeds.firstOrNull()?.second ?: 0f, emptyList(), columns.values.sumOf { it.candidates.size })
        }

        val normalizedTarget = employeeName?.let(::normalizeWord).orEmpty()
        val seedColumns = seedSigs.map { it.column }.toSet()

        // Hard-negative mining: once the user has confirmed a name in a day column, every other
        // handwriting row in that same column is overwhelmingly likely to be a different employee.
        // Using those rows as local negative examples is much more discriminative than comparing
        // only positive handwriting style, especially when one person wrote the whole rota.
        val negativeSignatures = buildList {
            seedSigs.forEach { seed ->
                val data = columns.getValue(seed.column)
                val seedYBitmap = seed.y * sy
                data.candidates
                    .filter { abs(it.band.center - seedYBitmap) > max(42f, bitmap.height * 0.045f) }
                    .flatMap { candidateBandVariants(it.band, bitmap.height) }
                    .mapNotNull { cachedSignature(bitmap, data.left, data.right, it) }
                    .forEach { add(it) }
            }
        }.distinctBy { sig ->
            listOf((sig.aspect * 10).toInt(), (sig.density * 100).toInt(), (sig.centerX * 20).toInt(), (sig.centerY * 20).toInt())
        }.take(80)

        val matches = mutableListOf<Match>()
        val seededDecisions = linkedMapOf<Int, RotaDiagnosticEvidence.ProfileDecision>()

        for (column in 0..6) {
            if (column in seedColumns) {
                seededDecisions[column] = RotaDiagnosticEvidence.ProfileDecision(column,
                    columns.getValue(column).candidates.size, 0, null, null, null, null, "seed_column_not_evaluated")
                continue
            }
            val data = columns.getValue(column)
            data class Ranked(
                val candidate: Candidate,
                val score: Float,
                val visual: Float,
                val lexical: Float,
                val negative: Float,
                val clusterSupport: Int,
                val clusterMean: Float
            )
            val ranked = data.candidates.mapNotNull { candidate ->
                // Evaluate several vertical crops around the same handwritten row. Camera perspective,
                // faint ascenders/descenders and grid lines often make the strict band clip part of a
                // name. Keeping the best crop materially improves recall without lowering the classifier
                // threshold for unrelated names.
                val variants = candidateBandVariants(candidate.band, bitmap.height)
                    .mapNotNull { cachedSignature(bitmap, data.left, data.right, it) }
                if (variants.isEmpty()) return@mapNotNull null
                val seedScores = seedSigs.map { seed ->
                    variants.maxOf { variant -> similarity(seed.signature, variant) }
                }.sorted()
                val weakest = seedScores.firstOrNull() ?: 0f
                val strongest = seedScores.lastOrNull() ?: 0f
                val median = seedScores[seedScores.size / 2]
                val average = seedScores.average().toFloat()

                // Treat confirmed examples as a *mixture of writing styles*, not one rigid template.
                // Human handwriting legitimately changes width, slant and letter joins from day to
                // day. A candidate may strongly match one learned style and only moderately match
                // another. The strongest prototype therefore carries most weight; the weakest is
                // retained only as a small sanity signal instead of acting as a veto.
                val visual = (strongest * 0.45f + median * 0.25f + average * 0.25f + weakest * 0.05f).coerceIn(0f, 1f)
                val lexical = if (normalizedTarget.isNotBlank() && !candidate.ocrText.isNullOrBlank()) {
                    wordSimilarity(normalizedTarget, normalizeWord(candidate.ocrText))
                } else 0f

                // OCR is only supporting evidence. A good visual match can survive bad handwriting
                // OCR, while a clean textual hit can rescue a borderline visual match.
                var combined = visual
                if (lexical >= 0.88f) combined = max(combined, visual * 0.72f + lexical * 0.28f + 0.05f)
                else if (lexical >= 0.64f) combined = visual * 0.82f + lexical * 0.18f
                else if (candidate.ocrText != null && lexical < 0.28f) combined *= 0.96f

                val negative = if (negativeSignatures.isEmpty()) 0f else {
                    negativeSignatures.maxOf { neg -> variants.maxOf { variant -> similarity(neg, variant) } }
                }

                // Pattern-recognition layer: the target employee normally appears on several days.
                // A genuine occurrence should therefore resemble at least one plausible row in other
                // weekday columns as well as the two seeds. This recovers valid variations that are
                // slightly below the seed threshold while suppressing one-off shapes.
                val crossColumnScores = columns
                    .filterKeys { otherColumn -> otherColumn != column && otherColumn !in seedColumns }
                    .mapNotNull { (_, otherData) ->
                        otherData.candidates.asSequence().mapNotNull { otherCandidate ->
                            val otherVariants = candidateBandVariants(otherCandidate.band, bitmap.height)
                                .mapNotNull { cachedSignature(bitmap, otherData.left, otherData.right, it) }
                            if (otherVariants.isEmpty()) null else variants.maxOf { a -> otherVariants.maxOf { b -> fastClusterSimilarity(a, b) } }
                        }.maxOrNull()
                    }
                val clusterSupport = crossColumnScores.count { it >= 0.64f }
                val clusterMean = crossColumnScores.sortedDescending().take(3).takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
                if (clusterSupport >= 2 && clusterMean >= 0.66f) combined += min(0.075f, 0.018f * clusterSupport + (clusterMean - 0.66f) * 0.12f)
                else if (clusterSupport == 0 && lexical < 0.80f) combined -= 0.025f

                // A candidate that resembles known non-target names nearly as much as the target
                // should not be suggested. Preserve genuinely strong positives while applying a
                // progressively larger penalty to same-writer false positives.
                val discrimination = (visual - negative).coerceIn(-1f, 1f)
                if (negative > 0.66f) combined -= ((negative - 0.66f) * 0.55f)
                if (discrimination < 0.045f) combined -= 0.10f
                else if (discrimination > 0.16f) combined += 0.035f

                Ranked(candidate, combined.coerceIn(0f, 1f), visual, lexical, negative, clusterSupport, clusterMean)
            }.sortedByDescending { it.score }

            val best = ranked.firstOrNull()
            if (best == null) {
                seededDecisions[column] = RotaDiagnosticEvidence.ProfileDecision(column,
                    data.candidates.size, 0, null, null, null, null,
                    if (data.candidates.isEmpty()) "no_candidate_lines" else "no_usable_signatures")
                continue
            }
            val runner = ranked.getOrNull(1)?.score ?: 0f
            val margin = (best.score - runner).coerceAtLeast(0f)
            val enoughExamples = seedSigs.size >= 2
            val strongLexical = best.lexical >= 0.88f
            // Calibrate the threshold from how similar the confirmed examples are to each other.
            // Different days often contain visibly different versions of the same handwritten name;
            // a fixed 0.685 threshold was discarding genuine matches.
            val pairwiseSeedScores = mutableListOf<Float>()
            for (i in seedSigs.indices) for (j in i + 1 until seedSigs.size) {
                pairwiseSeedScores += similarity(seedSigs[i].signature, seedSigs[j].signature)
            }
            val seedConsistency = pairwiseSeedScores.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.80f
            val adaptiveFloor = (seedConsistency - 0.16f).coerceIn(0.55f, 0.68f)
            val minScore = if (enoughExamples) (adaptiveFloor + 0.025f).coerceAtMost(0.72f) else 0.80f
            val minMargin = if (enoughExamples) 0.050f else 0.10f

            // Multi-prototype acceptance. Requiring a candidate to match every confirmed sample
            // rejects the same person's natural writing variation. Instead, require a strong match
            // to at least one learned style, plus either a second moderate style match, repeated
            // cross-column pattern evidence, strong OCR, or a healthy margin over hard negatives.
            val bestVariants = candidateBandVariants(best.candidate.band, bitmap.height)
                .mapNotNull { cachedSignature(bitmap, data.left, data.right, it) }
            val perSeedBest = seedSigs.map { seed -> bestVariants.maxOfOrNull { similarity(seed.signature, it) } ?: 0f }
                .sortedDescending()
            val styleBest = perSeedBest.firstOrNull() ?: 0f
            val styleSecond = perSeedBest.getOrNull(1) ?: styleBest
            val styleFloor = (seedConsistency - 0.12f).coerceIn(0.57f, 0.72f)
            val moderateFloor = (styleFloor - 0.11f).coerceAtLeast(0.48f)
            val discrimination = best.visual - best.negative
            val discriminativeEnough = negativeSignatures.isEmpty() || discrimination >= 0.045f || strongLexical
            val repeatedPattern = best.clusterSupport >= 2 && best.clusterMean >= 0.63f
            val styleAgreement = styleBest >= styleFloor && (styleSecond >= moderateFloor || repeatedPattern || strongLexical || discrimination >= 0.14f)
            val accept = (
                best.score >= minScore && styleAgreement && discriminativeEnough &&
                    (margin >= minMargin || best.score >= minScore + 0.10f || repeatedPattern)
                ) ||
                (repeatedPattern && styleBest >= styleFloor - 0.045f && best.score >= minScore - 0.045f && discrimination >= 0.025f) ||
                (strongLexical && styleBest >= 0.54f && discrimination >= -0.015f)

            seededDecisions[column] = RotaDiagnosticEvidence.ProfileDecision(column,
                data.candidates.size, ranked.size, best.score, runner, minScore, best.negative,
                when {
                    accept -> "accepted"
                    best.score < minScore && !repeatedPattern && !strongLexical -> "rejected_low_score"
                    !styleAgreement && !repeatedPattern && !strongLexical -> "rejected_style_disagreement"
                    !discriminativeEnough && !strongLexical -> "rejected_confuser_similarity"
                    margin < minMargin && best.score < minScore + 0.10f && !repeatedPattern -> "rejected_low_runner_margin"
                    else -> "rejected_combined_policy"
                })
            if (accept) {
                val (sourceLeft, sourceRight) = ScheduleImporter.columnBounds(assist, column)
                // One employee can only occupy one row per day in this rota format. Surface only the
                // strongest candidate in each day column. Borderline alternatives are intentionally not
                // painted over the rota; the user can still tap them manually. This restores precision
                // without sacrificing manual recall.
                val calibrated = (best.score * 0.92f + min(0.08f, margin * 1.25f) + if (best.lexical >= 0.88f) 0.02f else 0f)
                    .coerceIn(0f, 1f)
                matches += Match(
                    x = (sourceLeft + sourceRight) / 2f,
                    y = (best.candidate.band.center / sy).coerceIn(0f, assist.imageHeight.toFloat()),
                    column = column,
                    score = calibrated
                )
            }
        }

        return Report(
            seedColumn = seedSigs.first().column,
            seedY = seedSigs.first().y,
            matches = matches.sortedBy { it.column },
            scannedLines = columns.values.sumOf { it.candidates.size },
            columnDecisions = (0..6).map { seededDecisions.getValue(it) }
        )
    }

    /**
     * Candidate rows are the union of pixel-detected handwriting bands and useful OCR word boxes.
     * This avoids losing a name merely because one of the two detectors failed on that row.
     */
    private fun buildCandidates(
        bitmap: Bitmap,
        assist: ScheduleImporter.AssistData,
        column: Int,
        left: Int,
        right: Int,
        sx: Float,
        sy: Float
    ): Pair<List<Candidate>, RotaDiagnosticEvidence.CandidatePipeline> {
        val strict = detectTextBands(bitmap, left, right)
        val bands = strict.map { Candidate(it, null) }.toMutableList()
        var looseObserved = 0
        var looseAdded = 0
        // RotaVision 3 adds a second, recall-oriented row detector. The strict detector keeps
        // precision high, while the loose detector rescues faint/thin handwriting that used to
        // be missed completely (the main reason valid names disappeared from whole day columns).
        detectLooseTextBands(bitmap, left, right).forEach { band ->
            looseObserved++
            val nearest = bands.indices.minByOrNull { abs(bands[it].band.center - band.center) }
            if (nearest == null || abs(bands[nearest].band.center - band.center) > max(10f, bitmap.height * 0.009f)) {
                bands += Candidate(band, null)
                looseAdded++
            }
        }
        val sourceBounds = ScheduleImporter.columnBounds(assist, column)
        val columnTokens = assist.tokens.filter { token ->
            token.cx >= sourceBounds.first && token.cx <= sourceBounds.second &&
                token.cy in (assist.imageHeight * 0.13f)..(assist.imageHeight * 0.95f) &&
                normalizeWord(token.text).count(Char::isLetter) >= 2 &&
                token.text.count(Char::isDigit) <= 1 &&
                !looksLikeScheduleMetadata(token.text)
        }
        // OCR passes may return their word boxes in different orders. Since each token merges
        // into the nearest band, canonical geometric ordering prevents input-order-dependent
        // candidate crops when a photograph is scanned repeatedly.
        var tokensMerged = 0
        var tokensAdded = 0
        val tokenDeciles = MutableList(10) { 0 }
        columnTokens.sortedWith(compareBy<ScheduleImporter.AssistToken> { it.top }
            .thenBy { it.left }.thenBy { it.bottom }.thenBy { it.right }
            .thenBy { it.source.ordinal }.thenBy { it.text }).forEach { token ->
            tokenDeciles[RotaDiagnosticEvidence.verticalDecile(token.cy, assist.imageHeight.toFloat())]++
            val top = (token.top * sy).toInt().coerceIn(0, bitmap.height - 2)
            val bottom = (token.bottom * sy).toInt().coerceIn(top + 1, bitmap.height - 1)
            val candidate = Candidate(Band(top, bottom), token.text)
            val nearestIndex = bands.indices.minByOrNull { abs(bands[it].band.center - candidate.band.center) }
            if (nearestIndex != null && abs(bands[nearestIndex].band.center - candidate.band.center) <= max(10f, bitmap.height * 0.010f)) {
                val old = bands[nearestIndex]
                tokensMerged++
                bands[nearestIndex] = Candidate(
                    Band(min(old.band.top, top), max(old.band.bottom, bottom)),
                    betterOcrText(old.ocrText, token.text)
                )
            } else {
                bands += candidate
                tokensAdded++
            }
        }
        val sourceBodyTop = assist.imageHeight * 0.125f
        val footerRule = assist.rowBoundaries.getOrNull(column).orEmpty()
            .filter { it in (assist.imageHeight * 0.82f)..(assist.imageHeight * 0.97f) }
            .maxOrNull()
        val sourceBodyBottom = footerRule ?: assist.imageHeight * 0.945f
        val bitmapBodyTop = sourceBodyTop * sy
        val bitmapBodyBottom = sourceBodyBottom * sy

        // Recall safety net: row segmentation can miss faint handwriting entirely. Probe large
        // vertical gaps using the median observed text height and keep only windows containing a
        // plausible amount of ink. These probes are still filtered by the full handwriting model,
        // so this improves recall without directly creating extra matches.
        val sortedBands = bands.map { it.band }.sortedBy { it.top }
        val medianHeight = sortedBands.map { it.height }.sorted().let { hs ->
            if (hs.isEmpty()) max(12, (bitmap.height * 0.018f).toInt()) else hs[hs.size / 2].coerceAtLeast(8)
        }
        val probeStep = max(6, (medianHeight * 0.65f).toInt())
        var probesAttempted = 0
        var probesNearExisting = 0
        var probesInkRejected = 0
        var probesAdded = 0
        var probeY = bitmapBodyTop.toInt().coerceAtLeast(0)
        while (probeY + medianHeight < bitmapBodyBottom.toInt().coerceAtMost(bitmap.height - 1)) {
            val probe = Band(probeY, (probeY + medianHeight).coerceAtMost(bitmap.height - 1))
            val nearExisting = sortedBands.any { abs(it.center - probe.center) <= max(7f, medianHeight * 0.55f) }
            probesAttempted++
            if (!nearExisting) {
                val ink = quickInkFraction(bitmap, left, right, probe)
                if (ink in 0.012f..0.34f) {
                    bands += Candidate(probe, null)
                    probesAdded++
                } else probesInkRejected++
            } else probesNearExisting++
            probeY += probeStep
        }

        val validHeights = bands.filter {
            it.band.height in max(5, (bitmap.height * 0.0035f).toInt())..max(42, (bitmap.height * 0.075f).toInt())
        }
        val survivors = validHeights.filter { it.band.center in bitmapBodyTop..bitmapBodyBottom }
            .sortedWith(compareBy<Candidate> { it.band.top }.thenBy { it.band.bottom }
                .thenBy { if (it.ocrText == null) 1 else 0 }.thenBy { it.ocrText ?: "" })
        val byBlock = survivors.groupingBy {
            RotaGridModel.blockIndexForY(assist, column, it.band.center / sy) ?: -1
        }.eachCount().toSortedMap().filterKeys { it >= 0 }
        val trace = RotaDiagnosticEvidence.CandidatePipeline(
            strictCount = strict.size, looseObserved = looseObserved, looseAdded = looseAdded,
            eligibleOcrTokens = columnTokens.size, ocrTokenDeciles = tokenDeciles,
            ocrMerged = tokensMerged, ocrAdded = tokensAdded,
            probesAttempted = probesAttempted, probesNearExisting = probesNearExisting,
            probesInkRejected = probesInkRejected, probesAdded = probesAdded,
            rejectedHeight = bands.size - validHeights.size,
            rejectedBody = validHeights.size - survivors.size,
            finalCandidates = survivors.size,
            finalOcrCandidates = survivors.count { it.ocrText != null },
            survivorsByBlock = byBlock
        )
        return survivors to trace
    }


    private fun quickInkFraction(bitmap: Bitmap, leftRaw: Int, rightRaw: Int, band: Band): Float {
        val left = leftRaw.coerceIn(0, bitmap.width - 1)
        val right = rightRaw.coerceIn(left + 1, bitmap.width)
        val top = band.top.coerceIn(0, bitmap.height - 1)
        val bottom = band.bottom.coerceIn(top + 1, bitmap.height)
        val stepX = max(3, (right - left) / 42)
        val stepY = max(2, (bottom - top) / 12)
        var dark = 0
        var total = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (luminance(bitmap.getPixel(x, y)) < 185) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun candidateBandVariants(band: Band, imageHeight: Int): List<Band> {
        val p1 = max(3, (band.height * 0.28f).toInt())
        val p2 = max(5, (band.height * 0.52f).toInt())
        return listOf(
            band,
            Band((band.top - p1).coerceAtLeast(0), (band.bottom + p1).coerceAtMost(imageHeight - 1)),
            Band((band.top - p2).coerceAtLeast(0), (band.bottom + p2).coerceAtMost(imageHeight - 1))
        ).distinct()
    }


    private fun looksLikeScheduleMetadata(text: String): Boolean {
        val n = normalizeWord(text)
        if (n.isBlank()) return true
        val weekdays = listOf(
            "ponedjeljak", "ponediljak", "poned", "utorak", "utor", "srijeda", "srida", "srij",
            "cetvrtak", "cetv", "petak", "subota", "nedjelja", "nedilja",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        )
        if (weekdays.any { n == it || n.startsWith(it) }) return true
        val digits = text.count(Char::isDigit)
        if (digits >= 2 && text.count(Char::isLetter) <= 2) return true
        return false
    }

    private fun betterOcrText(a: String?, b: String?): String? = when {
        a.isNullOrBlank() -> b
        b.isNullOrBlank() -> a
        normalizeWord(b).length > normalizeWord(a).length -> b
        else -> a
    }

    /** Detect handwritten text rows while suppressing long horizontal grid rules. */
    private fun detectTextBands(bitmap: Bitmap, leftRaw: Int, rightRaw: Int): List<Band> {
        val width = (rightRaw - leftRaw).coerceAtLeast(2)
        val leftInset = max(3, (width * 0.09f).toInt())
        val rightInset = max(3, (width * 0.025f).toInt())
        val left = (leftRaw + leftInset).coerceAtMost(rightRaw - 2)
        val right = (rightRaw - rightInset).coerceAtLeast(left + 2)
        val top = (bitmap.height * 0.105f).toInt()
        val bottom = (bitmap.height * 0.955f).toInt().coerceAtMost(bitmap.height - 1)
        val sampleStep = if (right - left > 360) 3 else 2

        var lumSum = 0L
        var lumCount = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x < right) {
                lumSum += luminance(bitmap.getPixel(x, y)); lumCount++; x += 11
            }
            y += 17
        }
        val paper = if (lumCount > 0) lumSum.toFloat() / lumCount else 210f
        val darkThreshold = (paper - 36f).coerceIn(78f, 210f)

        val rowScores = FloatArray((bottom - top + 1).coerceAtLeast(1))
        for (py in top..bottom) {
            var dark = 0; var total = 0; var px = left
            while (px < right) {
                if (luminance(bitmap.getPixel(px, py)) < darkThreshold) dark++
                total++; px += sampleStep
            }
            rowScores[py - top] = if (total == 0) 0f else dark.toFloat() / total
        }

        val smoothed = FloatArray(rowScores.size)
        for (i in rowScores.indices) {
            var sum = 0f; var n = 0
            for (j in max(0, i - 2)..min(rowScores.lastIndex, i + 2)) { sum += rowScores[j]; n++ }
            smoothed[i] = if (n == 0) 0f else sum / n
        }

        val active = BooleanArray(smoothed.size) { i -> smoothed[i] in 0.010f..0.32f }
        val raw = mutableListOf<Band>()
        var start = -1; var gap = 0
        for (i in active.indices) {
            if (active[i]) { if (start < 0) start = i; gap = 0 }
            else if (start >= 0) {
                gap++
                if (gap > 3) {
                    val end = i - gap
                    if (end - start >= 3) raw += Band(start + top, end + top)
                    start = -1; gap = 0
                }
            }
        }
        if (start >= 0 && active.lastIndex - start >= 3) raw += Band(start + top, active.lastIndex + top)

        val merged = mutableListOf<Band>()
        raw.forEach { band ->
            val previous = merged.lastOrNull()
            val maxGap = max(4, (bitmap.height * 0.0045f).toInt())
            if (previous != null && band.top - previous.bottom <= maxGap) {
                // Only merge when the union still resembles one handwriting row. This prevents two
                // tightly spaced employee names from becoming one huge candidate band.
                val unionHeight = band.bottom - previous.top + 1
                val typicalMax = max(28, (bitmap.height * 0.034f).toInt())
                if (unionHeight <= typicalMax) merged[merged.lastIndex] = Band(previous.top, band.bottom)
                else merged += band
            } else merged += band
        }

        val minHeight = max(5, (bitmap.height * 0.0035f).toInt())
        val maxHeight = max(34, (bitmap.height * 0.060f).toInt())
        return merged.filter { it.height in minHeight..maxHeight }
    }

    /** A permissive companion to [detectTextBands] used only to generate candidates. */
    private fun detectLooseTextBands(bitmap: Bitmap, leftRaw: Int, rightRaw: Int): List<Band> {
        val width = (rightRaw - leftRaw).coerceAtLeast(2)
        val left = (leftRaw + max(2, (width * 0.07f).toInt())).coerceAtMost(rightRaw - 2)
        val right = (rightRaw - max(2, (width * 0.02f).toInt())).coerceAtLeast(left + 2)
        val top = (bitmap.height * 0.105f).toInt()
        val bottom = (bitmap.height * 0.955f).toInt().coerceAtMost(bitmap.height - 1)
        var lumSum = 0L; var lumCount = 0
        for (y in top..bottom step 19) for (x in left until right step 13) { lumSum += luminance(bitmap.getPixel(x,y)); lumCount++ }
        val paper = if (lumCount > 0) lumSum.toFloat()/lumCount else 210f
        val threshold = (paper - 28f).coerceIn(82f, 220f)
        val scores = FloatArray((bottom-top+1).coerceAtLeast(1))
        for (y in top..bottom) {
            var dark=0; var total=0
            var x=left
            while (x<right) { if (luminance(bitmap.getPixel(x,y)) < threshold) dark++; total++; x+=3 }
            scores[y-top] = if (total==0) 0f else dark.toFloat()/total
        }
        val smooth = FloatArray(scores.size)
        for (i in scores.indices) {
            var sum=0f; var n=0
            for (j in max(0,i-1)..min(scores.lastIndex,i+1)) { sum+=scores[j]; n++ }
            smooth[i]=sum/n.coerceAtLeast(1)
        }
        val active = BooleanArray(smooth.size) { i -> smooth[i] in 0.006f..0.38f }
        val out=mutableListOf<Band>(); var start=-1; var gap=0
        for (i in active.indices) {
            if (active[i]) { if (start<0) start=i; gap=0 }
            else if (start>=0) {
                gap++
                if (gap>2) { val end=i-gap; if (end-start>=2) out += Band(start+top,end+top); start=-1; gap=0 }
            }
        }
        if (start>=0 && active.lastIndex-start>=2) out += Band(start+top,active.lastIndex+top)
        val minH=max(4,(bitmap.height*0.003f).toInt())
        val maxH=max(36,(bitmap.height*0.070f).toInt())
        return out.filter { it.height in minH..maxH }
    }

    private fun signature(bitmap: Bitmap, leftRaw: Int, rightRaw: Int, band: Band): Signature? {
        val padY = min(7, max(2, band.height / 5))
        val top = (band.top - padY).coerceAtLeast(0)
        val bottom = (band.bottom + padY).coerceAtMost(bitmap.height - 1)
        val width = rightRaw - leftRaw
        val leftInset = max(2, (width * 0.09f).toInt())
        val rightInset = max(2, (width * 0.025f).toInt())
        val left = (leftRaw + leftInset).coerceAtMost(rightRaw - 2)
        val right = (rightRaw - rightInset).coerceAtLeast(left + 2)

        var lumSum = 0L; var count = 0
        for (y in top..bottom step 2) for (x in left until right step 3) { lumSum += luminance(bitmap.getPixel(x, y)); count++ }
        if (count == 0) return null
        val mean = lumSum.toFloat() / count
        val threshold = (mean - 30f).coerceIn(68f, 210f)

        // Remove table rules before building a word signature. A single vertical divider or
        // horizontal block line can otherwise dominate the bounding box and make two unrelated
        // names appear similar simply because they touch the same grid.
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top + 1).coerceAtLeast(1)
        val verticalRule = BooleanArray(cropW)
        val horizontalRule = BooleanArray(cropH)
        for (ix in 0 until cropW) {
            var dark = 0
            val x = left + ix
            for (y in top..bottom) if (luminance(bitmap.getPixel(x, y)) < threshold) dark++
            verticalRule[ix] = dark.toFloat() / cropH >= 0.72f
        }
        for (iy in 0 until cropH) {
            var dark = 0
            val y = top + iy
            for (x in left until right) if (luminance(bitmap.getPixel(x, y)) < threshold) dark++
            horizontalRule[iy] = dark.toFloat() / cropW >= 0.72f
        }
        fun isInk(x: Int, y: Int): Boolean {
            if (verticalRule[(x - left).coerceIn(0, cropW - 1)]) return false
            if (horizontalRule[(y - top).coerceIn(0, cropH - 1)]) return false
            return luminance(bitmap.getPixel(x, y)) < threshold
        }

        var minX = right; var maxX = left; var minY = bottom; var maxY = top; var ink = 0
        for (y in top..bottom) for (x in left until right) {
            if (isInk(x, y)) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); ink++
            }
        }
        if (ink < 12 || maxX <= minX || maxY <= minY) return null
        val sourceWidth = maxX - minX + 1
        val sourceHeight = maxY - minY + 1
        if (sourceWidth > (right - left) * 0.97f && sourceHeight < band.height * 0.43f) return null
        // Employee names are word-like horizontal shapes. Very narrow glyphs are almost always
        // time digits, punctuation, a table artefact, or a cropped fragment rather than a name.
        if (sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1) < 1.15f) return null

        val pixels = FloatArray(GRID_W * GRID_H)
        val horizontal = FloatArray(GRID_W)
        val vertical = FloatArray(GRID_H)
        var activeCount = 0f; var weightedX = 0f; var weightedY = 0f
        for (gy in 0 until GRID_H) {
            val y0 = minY + gy * sourceHeight / GRID_H
            val y1 = minY + (gy + 1) * sourceHeight / GRID_H
            for (gx in 0 until GRID_W) {
                val x0 = minX + gx * sourceWidth / GRID_W
                val x1 = minX + (gx + 1) * sourceWidth / GRID_W
                var dark = 0; var total = 0; var py = y0
                while (py < max(y0 + 1, y1)) {
                    var px = x0
                    while (px < max(x0 + 1, x1)) {
                        if (isInk(px.coerceAtMost(bitmap.width - 1), py.coerceAtMost(bitmap.height - 1))) dark++
                        total++; px++
                    }
                    py++
                }
                val v = if (total == 0) 0f else dark.toFloat() / total
                pixels[gy * GRID_W + gx] = v
                horizontal[gx] += v; vertical[gy] += v; activeCount += v
                weightedX += v * gx; weightedY += v * gy
            }
        }

        val rowTransitions = FloatArray(GRID_H)
        val colTransitions = FloatArray(GRID_W)
        for (gy in 0 until GRID_H) {
            var transitions = 0; var previous = pixels[gy * GRID_W] > 0.16f
            for (gx in 1 until GRID_W) {
                val current = pixels[gy * GRID_W + gx] > 0.16f
                if (current != previous) transitions++
                previous = current
            }
            rowTransitions[gy] = transitions.toFloat() / GRID_W
        }
        for (gx in 0 until GRID_W) {
            var transitions = 0; var previous = pixels[gx] > 0.16f
            for (gy in 1 until GRID_H) {
                val current = pixels[gy * GRID_W + gx] > 0.16f
                if (current != previous) transitions++
                previous = current
            }
            colTransitions[gx] = transitions.toFloat() / GRID_H
        }

        val centerDenom = activeCount.coerceAtLeast(1e-5f)
        val centerX = (weightedX / centerDenom / (GRID_W - 1)).coerceIn(0f, 1f)
        val centerY = (weightedY / centerDenom / (GRID_H - 1)).coerceIn(0f, 1f)
        normalize(horizontal); normalize(vertical); normalize(rowTransitions); normalize(colTransitions); normalize(pixels)
        return Signature(
            pixels, horizontal, vertical, rowTransitions, colTransitions,
            density = activeCount / (GRID_W * GRID_H),
            aspect = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1),
            centerX = centerX,
            centerY = centerY
        )
    }

    /** Cheap pre-clustering similarity used across all candidate rows. */
    private fun fastClusterSimilarity(a: Signature, b: Signature): Float {
        val pixel = shiftedCosine(a.pixels, b.pixels, GRID_W, GRID_H, maxDx = 2, maxDy = 1)
        val horizontal = shifted1DCosine(a.horizontal, b.horizontal, 2)
        val vertical = shifted1DCosine(a.vertical, b.vertical, 1)
        val aspect = relativeSimilarity(a.aspect, b.aspect, 0.65f)
        val density = relativeSimilarity(a.density, b.density, 0.018f)
        return (pixel * 0.52f + horizontal * 0.18f + vertical * 0.10f + aspect * 0.13f + density * 0.07f).coerceIn(0f, 1f)
    }

    private fun similarity(a: Signature, b: Signature): Float {
        val pixel = shiftedCosine(a.pixels, b.pixels, GRID_W, GRID_H, maxDx = 3, maxDy = 2)
        val horizontal = shifted1DCosine(a.horizontal, b.horizontal, 3)
        val vertical = shifted1DCosine(a.vertical, b.vertical, 2)
        val rowTransitions = cosine(a.rowTransitions, b.rowTransitions)
        val colTransitions = cosine(a.colTransitions, b.colTransitions)
        val density = relativeSimilarity(a.density, b.density, 0.015f)
        val aspect = relativeSimilarity(a.aspect, b.aspect, 0.6f)
        val center = (1f - (abs(a.centerX - b.centerX) * 0.65f + abs(a.centerY - b.centerY) * 0.35f)).coerceIn(0f, 1f)

        // RotaVision 5 adds two structure descriptors that are much harder for unrelated
        // handwritten names to spoof than simple pixel correlation. HOG captures the dominant
        // stroke directions inside a coarse spatial grid, while binary overlap measures whether
        // the actual ink occupies the same parts of the word after small translations.
        val hog = cosine(hogDescriptor(a.pixels, GRID_W, GRID_H), hogDescriptor(b.pixels, GRID_W, GRID_H))
        val binaryOverlap = shiftedBinaryIoU(a.pixels, b.pixels, GRID_W, GRID_H, maxDx = 3, maxDy = 2)
        // Elastic sequence matching tolerates letter spacing and width changes. This is important
        // for the same handwritten word written quickly on different days: the letters are often
        // stretched/compressed even though their left-to-right stroke sequence is preserved.
        val elastic = elasticWordSimilarity(a.pixels, b.pixels, GRID_W, GRID_H)
        // Small affine changes are normal in handwriting: the same word may be wider, compressed
        // or slightly slanted on another day.  A limited local registration makes the matcher
        // compare letter structure rather than exact pen placement.  It is intentionally bounded
        // so unrelated words cannot win by being distorted arbitrarily.
        val roughAgreement = (pixel * 0.45f + elastic * 0.35f + hog * 0.20f)
        val affine = if (roughAgreement >= 0.40f)
            affineWordSimilarity(a.pixels, b.pixels, GRID_W, GRID_H)
        else roughAgreement

        // Grossly different word proportions are usually different names, even when the writer's
        // overall stroke style is similar. Keep this as a soft gate so perspective does not break
        // legitimate matches.
        val shapeGate = (aspect * 0.65f + density * 0.35f).coerceIn(0f, 1f)
        val base = (
            pixel * 0.16f + hog * 0.17f + elastic * 0.17f + affine * 0.17f + binaryOverlap * 0.10f +
                horizontal * 0.065f + vertical * 0.045f +
                rowTransitions * 0.035f + colTransitions * 0.030f +
                density * 0.02f + aspect * 0.03f + center * 0.025f
            ).coerceIn(0f, 1f)
        return (base * (0.78f + shapeGate * 0.22f)).coerceIn(0f, 1f)
    }

    /**
     * Best bounded affine registration between two normalized word images.  Human handwriting
     * changes width and slant constantly; exact pixel correlation treats those natural changes as
     * different words.  We search only a small family of plausible transforms so this improves
     * invariance without allowing arbitrary warping.
     */
    private fun affineWordSimilarity(a: FloatArray, b: FloatArray, width: Int, height: Int): Float {
        fun transformed(scaleX: Float, shear: Float): FloatArray {
            val out = FloatArray(width * height)
            val cx = (width - 1) / 2f
            val cy = (height - 1) / 2f
            for (y in 0 until height) {
                val dy = y - cy
                for (x in 0 until width) {
                    val sx = ((x - cx) / scaleX + cx - shear * dy)
                    if (sx < 0f || sx > width - 1f) continue
                    val x0 = sx.toInt().coerceIn(0, width - 1)
                    val x1 = (x0 + 1).coerceAtMost(width - 1)
                    val t = sx - x0
                    val v0 = b[y * width + x0]
                    val v1 = b[y * width + x1]
                    out[y * width + x] = v0 * (1f - t) + v1 * t
                }
            }
            return out
        }
        var best = shiftedCosine(a, b, width, height, maxDx = 2, maxDy = 1)
        val scales = floatArrayOf(0.88f, 1.0f, 1.12f)
        val shears = floatArrayOf(-0.11f, 0f, 0.11f)
        for (scale in scales) for (shear in shears) {
            if (scale == 1f && shear == 0f) continue
            val warped = transformed(scale, shear)
            best = max(best, shiftedCosine(a, warped, width, height, maxDx = 2, maxDy = 1))
        }
        return best.coerceIn(0f, 1f)
    }

    private fun elasticWordSimilarity(a: FloatArray, b: FloatArray, width: Int, height: Int): Float {
        // Represent each x-position with density + vertical centroid + top/bottom envelope.
        fun sequence(p: FloatArray): Array<FloatArray> = Array(width) { x ->
            var density = 0f
            var weighted = 0f
            var top = height
            var bottom = -1
            var transitions = 0
            var prev = false
            for (y in 0 until height) {
                val v = p[y * width + x]
                val ink = v > 0.10f
                density += v
                weighted += v * y
                if (ink) { top = min(top, y); bottom = max(bottom, y) }
                if (y > 0 && ink != prev) transitions++
                prev = ink
            }
            val d = density / height
            val centroid = if (density > 1e-4f) weighted / density / max(1, height - 1) else 0.5f
            val t = if (top < height) top.toFloat() / max(1, height - 1) else 0.5f
            val bot = if (bottom >= 0) bottom.toFloat() / max(1, height - 1) else 0.5f
            floatArrayOf(d, centroid, t, bot, transitions.toFloat() / height)
        }
        val sa = sequence(a); val sb = sequence(b)
        val n = sa.size; val m = sb.size
        val inf = 1e9f
        val prev = FloatArray(m + 1) { inf }; prev[0] = 0f
        val curr = FloatArray(m + 1) { inf }
        val window = max(8, kotlin.math.abs(n - m) + 6)
        fun dist(x: FloatArray, y: FloatArray): Float {
            var d = 0f
            val weights = floatArrayOf(2.2f, 1.1f, 0.8f, 0.8f, 0.7f)
            for (i in x.indices) d += kotlin.math.abs(x[i] - y[i]) * weights[i]
            return d / weights.sum()
        }
        for (i in 1..n) {
            java.util.Arrays.fill(curr, inf)
            val lo = max(1, i - window); val hi = min(m, i + window)
            for (j in lo..hi) {
                val cost = dist(sa[i - 1], sb[j - 1])
                curr[j] = cost + min(prev[j], min(curr[j - 1], prev[j - 1]))
            }
            for (j in 0..m) prev[j] = curr[j]
        }
        val normalized = prev[m] / max(1, n + m) * 2f
        return kotlin.math.exp(-normalized * 5.2f).coerceIn(0f, 1f)
    }

    private fun hogDescriptor(pixels: FloatArray, width: Int, height: Int): FloatArray {
        val cellsX = 4
        val cellsY = 3
        val bins = 8
        val out = FloatArray(cellsX * cellsY * bins)
        fun value(x: Int, y: Int): Float = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val gx = value(x + 1, y) - value(x - 1, y)
                val gy = value(x, y + 1) - value(x, y - 1)
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 0.015f) continue
                var angle = kotlin.math.atan2(gy, gx)
                if (angle < 0f) angle += Math.PI.toFloat()
                if (angle >= Math.PI.toFloat()) angle -= Math.PI.toFloat()
                val bin = ((angle / Math.PI.toFloat()) * bins).toInt().coerceIn(0, bins - 1)
                val cx = (x * cellsX / width).coerceIn(0, cellsX - 1)
                val cy = (y * cellsY / height).coerceIn(0, cellsY - 1)
                out[(cy * cellsX + cx) * bins + bin] += mag
            }
        }
        normalize(out)
        return out
    }

    private fun shiftedBinaryIoU(a: FloatArray, b: FloatArray, width: Int, height: Int, maxDx: Int, maxDy: Int): Float {
        var best = 0f
        for (dy in -maxDy..maxDy) for (dx in -maxDx..maxDx) {
            var intersection = 0
            var union = 0
            for (y in 0 until height) {
                val by = y + dy
                if (by !in 0 until height) continue
                for (x in 0 until width) {
                    val bx = x + dx
                    if (bx !in 0 until width) continue
                    val av = a[y * width + x] > 0.12f
                    val bv = b[by * width + bx] > 0.12f
                    if (av || bv) union++
                    if (av && bv) intersection++
                }
            }
            if (union > 0) best = max(best, intersection.toFloat() / union.toFloat())
        }
        return best.coerceIn(0f, 1f)
    }

    private fun shiftedCosine(a: FloatArray, b: FloatArray, width: Int, height: Int, maxDx: Int, maxDy: Int): Float {
        var best = 0f
        for (dy in -maxDy..maxDy) for (dx in -maxDx..maxDx) {
            var dot = 0f; var aa = 0f; var bb = 0f
            for (y in 0 until height) {
                val by = y + dy
                if (by !in 0 until height) continue
                for (x in 0 until width) {
                    val bx = x + dx
                    if (bx !in 0 until width) continue
                    val av = a[y * width + x]; val bv = b[by * width + bx]
                    dot += av * bv; aa += av * av; bb += bv * bv
                }
            }
            val denom = sqrt(aa * bb).coerceAtLeast(1e-6f)
            best = max(best, (dot / denom).coerceIn(0f, 1f))
        }
        return best
    }

    private fun shifted1DCosine(a: FloatArray, b: FloatArray, maxShift: Int): Float {
        var best = 0f
        for (shift in -maxShift..maxShift) {
            var dot = 0f; var aa = 0f; var bb = 0f
            for (i in a.indices) {
                val j = i + shift
                if (j !in b.indices) continue
                dot += a[i] * b[j]; aa += a[i] * a[i]; bb += b[j] * b[j]
            }
            best = max(best, dot / sqrt(aa * bb).coerceAtLeast(1e-6f))
        }
        return best.coerceIn(0f, 1f)
    }

    private fun relativeSimilarity(a: Float, b: Float, floor: Float): Float =
        (1f - abs(a - b) / max(floor, max(abs(a), abs(b)))).coerceIn(0f, 1f)

    private fun encode(signature: Signature): String = buildString {
        append("rv2|")
        append(signature.density); append('|'); append(signature.aspect); append('|')
        append(signature.centerX); append('|'); append(signature.centerY); append('|')
        append(signature.pixels.joinToString(",")); append('|')
        append(signature.horizontal.joinToString(",")); append('|')
        append(signature.vertical.joinToString(",")); append('|')
        append(signature.rowTransitions.joinToString(",")); append('|')
        append(signature.colTransitions.joinToString(","))
    }

    private fun decodeProfileModel(raw: String): ProfileModel {
        if (raw.startsWith("rvset3;P=")) {
            val body = raw.removePrefix("rvset3;P=")
            val parts = body.split("#N=", limit = 2)
            val positives = parts.getOrNull(0).orEmpty().split(';').mapNotNull(::decode).take(16)
            val negatives = parts.getOrNull(1).orEmpty().split(';').mapNotNull(::decode).take(28)
            return ProfileModel(positives, negatives)
        }
        if (raw.startsWith("rvset2;")) {
            return ProfileModel(raw.removePrefix("rvset2;").split(';').mapNotNull(::decode).take(6))
        }
        return ProfileModel(listOfNotNull(decode(raw)))
    }

    private fun decodeProfileSet(raw: String): List<Signature> = decodeProfileModel(raw).positives

    private fun decode(raw: String): Signature? = runCatching {
        val parts = raw.split('|')
        fun floats(value: String, expected: Int): FloatArray {
            val parsed = value.split(',').map { it.toFloat() }.toFloatArray()
            require(parsed.size == expected); return parsed
        }
        when (parts.firstOrNull()) {
            "rv2" -> {
                require(parts.size == 10)
                Signature(
                    pixels = floats(parts[5], GRID_W * GRID_H),
                    horizontal = floats(parts[6], GRID_W),
                    vertical = floats(parts[7], GRID_H),
                    rowTransitions = floats(parts[8], GRID_H),
                    colTransitions = floats(parts[9], GRID_W),
                    density = parts[1].toFloat(), aspect = parts[2].toFloat(),
                    centerX = parts[3].toFloat(), centerY = parts[4].toFloat()
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun normalizeWord(raw: String): String = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('5', 's')
        .filter { it.isLetterOrDigit() }

    private fun wordSimilarity(aRaw: String, bRaw: String): Float {
        if (aRaw.isBlank() || bRaw.isBlank()) return 0f
        val a = aRaw; val b = bRaw
        if (a == b) return 1f
        if (a.length >= 4 && (a.contains(b) || b.contains(a))) {
            return (min(a.length, b.length).toFloat() / max(a.length, b.length)).coerceAtLeast(0.72f)
        }
        val distance = levenshtein(a, b)
        return (1f - distance.toFloat() / max(a.length, b.length).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1); current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun normalize(values: FloatArray) {
        var sumSq = 0f
        for (v in values) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
        for (i in values.indices) values[i] /= norm
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var aa = 0f; var bb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return (dot / sqrt(aa * bb).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    }

    private fun usable(bitmap: Bitmap, assist: ScheduleImporter.AssistData): Boolean =
        !bitmap.isRecycled && bitmap.width > 8 && bitmap.height > 8 && assist.imageWidth > 0 && assist.imageHeight > 0 &&
            !(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE)

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
