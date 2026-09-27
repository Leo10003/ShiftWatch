package com.example.workshifttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.os.Build
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Imports both conventional printed rotas and photographed handwritten weekly grids.
 *
 * Handwritten-grid mode deliberately uses OCR geometry instead of flattening the page to text:
 * dates establish the day columns, time labels establish vertical bands, and the employee name is
 * fuzzy-matched inside each column. This is much more tolerant of the common rota format where a
 * start time is written once followed by several names and no end time is printed.
 */
object ScheduleImporter {
    enum class DraftOrigin { PRINTED_TABLE, OCR_TEXT, HANDWRITING, ASSISTED, UNKNOWN }
    enum class ConfidenceTier { VERIFIED, REVIEW, UNRESOLVED }

    data class Draft(
        val id: String = UUID.randomUUID().toString(),
        val start: LocalDateTime,
        val end: LocalDateTime,
        val sourceLine: String,
        val confidence: Float = 1f,
        val estimatedEnd: Boolean = false,
        val columnIndex: Int? = null,
        val origin: DraftOrigin = DraftOrigin.UNKNOWN,
        val requiresTimeConfirmation: Boolean = false,
        val verificationState: RotaVerificationEngine.State = RotaVerificationEngine.State.REVIEW,
        val verificationNotes: List<String> = emptyList(),
        // Explicit, typed human evidence; old sessions without these fields remain compatible.
        val userConfirmedDate: Boolean = false,
        val userConfirmedTime: Boolean = false,
        val userConfirmedIdentity: Boolean = false,
        val physicalBlockId: Int? = null
    ) {
        val tier: ConfidenceTier
            get() = when {
                verificationState == RotaVerificationEngine.State.CONFLICT || verificationState == RotaVerificationEngine.State.UNRESOLVED -> ConfidenceTier.UNRESOLVED
                requiresTimeConfirmation || confidence < 0.72f -> ConfidenceTier.UNRESOLVED
                verificationState == RotaVerificationEngine.State.CONFIRMED || verificationState == RotaVerificationEngine.State.HIGH_CONFIDENCE -> ConfidenceTier.VERIFIED
                confidence < 0.92f -> ConfidenceTier.REVIEW
                else -> ConfidenceTier.VERIFIED
            }
    }

    enum class TokenSource { PAGE_OCR, HEADER_FOCUS, TIME_ATLAS }

    data class AssistToken(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val source: TokenSource = TokenSource.PAGE_OCR,
        val blockHint: Int? = null
    ) {
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
    }

    enum class DocumentKind { PRINTED_TABLE, HANDWRITTEN_GRID, MIXED, UNKNOWN }

    data class AssistData(
        val imageWidth: Int,
        val imageHeight: Int,
        val tokens: List<AssistToken>,
        // Strong horizontal rules detected directly from the rota image, grouped per day column.
        // These let the tap resolver understand schedule blocks even when handwriting OCR misses
        // the small time label at the start of a block.
        val rowBoundaries: List<List<Float>> = List(7) { emptyList() },
        // Six internal vertical grid separators detected directly from the photo.
        // When available these are more reliable than OCR for deciding the weekday column.
        val verticalRules: List<Float> = emptyList(),
        val ocrPasses: Int = 1,
        val documentKind: DocumentKind = DocumentKind.UNKNOWN,
        val printedConfidence: Float = 0f,
        val quality: QualityAssessment = QualityAssessment(),
        val templateFingerprint: String = "",
        val learnedTimeVocabulary: List<LocalTime> = emptyList(),
        val perception: RotaPerceptionEngine.Assessment = RotaPerceptionEngine.Assessment(),
        val semanticTimeBands: List<RotaSemanticTimeBands.Band> = emptyList()
    )

    data class DocumentAssessment(
        val kind: DocumentKind,
        val confidence: Float,
        val exactEmployeeHits: Int,
        val alignedRows: Int,
        val gridEvidence: Int
    )

    data class QualityAssessment(
        val contrast: Float = 0f,
        val sharpness: Float = 0f,
        val gridCompleteness: Float = 0f,
        val cropCoverage: Float = 1f,
        val score: Float = 0f,
        val warnings: List<String> = emptyList()
    )

    data class EvidenceBreakdown(
        val identity: Float,
        val geometry: Float,
        val time: Float,
        val template: Float,
        val consistency: Float,
        val contradictionPenalty: Float,
        val fused: Float
    )

    data class TemplateSnapshot(
        val columnCenters: List<Float>,
        val rowRatios: List<Float>,
        val canonicalTimes: List<String>,
        val fingerprint: String = "",
        val documentKind: String = DocumentKind.UNKNOWN.name,
        val qualityScore: Float = 0f,
        val timeVocabulary: List<String> = emptyList(),
        val observations: Int = 1
    )

    private data class OcrLine(val text: String, val box: Rect) {
        val cx: Float get() = (box.left + box.right) / 2f
        val cy: Float get() = (box.top + box.bottom) / 2f
        val height: Int get() = max(1, box.height())
    }

    private data class Header(val date: LocalDate, val cx: Float, val line: OcrLine)
    private data class TimeMarker(val time: LocalTime, val y: Float, val source: String)
    private data class AssistTimeCandidate(
        val time: LocalTime,
        val column: Int,
        val x: Float,
        val y: Float,
        val source: String,
        val strong: Boolean,
        val confidence: Float = if (strong) 0.86f else 0.48f,
        val alternate: Boolean = false,
        val tokenSource: TokenSource = TokenSource.PAGE_OCR,
        val blockHint: Int? = null
    )

    /**
     * Public preview model used by the Compose review overlay.
     *
     * v18.0 accidentally dropped this declaration while keeping timeSuggestionsForTap() and
     * PlannerActivity references to it. That made the return type unresolved and triggered a
     * cascade of misleading generic-inference / firstOrNull / score / time compiler errors.
     */
    data class TimeSuggestion(
        val time: LocalTime,
        val score: Float,
        val reason: String
    )

    /** A schedule-row time reconstructed from evidence across several weekday columns. */
    private data class CanonicalTimeRow(
        val y: Float,
        val time: LocalTime,
        val supportColumns: Int,
        val strongVotes: Int,
        val confidence: Float
    )

    private data class CanonicalVote(
        val time: LocalTime,
        val score: Float,
        val columns: Int,
        val strong: Int,
        val evidence: Float
    )

    private data class ColumnGeometry(val centers: List<Float>, val bounds: List<Pair<Float, Float>>)

    /**
     * Weekly rotas often have a left/right paper margin, so splitting the raw bitmap into
     * seven equal slices can put a handwritten name in the neighbouring day.  Prefer the
     * weekday header positions (PONEDJELJAK…NEDJELJA) and fit an evenly-spaced seven-column
     * grid through those observed centers.  Fall back to equal columns only when the header
     * OCR provides no useful geometry.
     */
    private fun columnGeometry(assist: AssistData): ColumnGeometry {
        val width = assist.imageWidth.coerceAtLeast(1).toFloat()

        val gridRules = assist.verticalRules.sorted().filter { it > width * 0.04f && it < width * 0.96f }
        if (gridRules.size == 6) {
            val edges = listOf(0f) + gridRules + listOf(width)
            val bounds = (0..6).map { i -> edges[i] to edges[i + 1] }
            val centers = bounds.map { (left, right) -> (left + right) / 2f }
            return ColumnGeometry(centers, bounds)
        }
        fun weekdayIndex(raw: String): Int? {
            val t = raw.lowercase(Locale.ROOT)
                .replace('č', 'c').replace('ć', 'c').replace('š', 's').replace('ž', 'z').replace('đ', 'd')
                .filter { it.isLetter() }
            return when {
                t.startsWith("poned") -> 0
                t.startsWith("utor") -> 1
                t.startsWith("srij") || t.startsWith("sred") -> 2
                t.startsWith("cetv") -> 3
                t.startsWith("pet") -> 4
                t.startsWith("sub") -> 5
                t.startsWith("ned") -> 6
                else -> null
            }
        }

        val observed = assist.tokens.mapNotNull { token ->
            if (token.cy > assist.imageHeight * 0.18f) return@mapNotNull null
            val i = weekdayIndex(token.text) ?: return@mapNotNull null
            i to token.cx
        }.groupBy({ it.first }, { it.second }).mapValues { (_, xs) -> xs.average().toFloat() }

        val defaultSpacing = width / 7f
        val spacings = mutableListOf<Float>()
        val keys = observed.keys.sorted()
        for (a in keys.indices) for (b in a + 1 until keys.size) {
            val ia = keys[a]; val ib = keys[b]
            val d = ib - ia
            if (d > 0) spacings += (observed.getValue(ib) - observed.getValue(ia)) / d
        }
        val spacing = spacings.sorted().let { vals ->
            if (vals.isEmpty()) defaultSpacing else vals[vals.size / 2].coerceIn(defaultSpacing * 0.70f, defaultSpacing * 1.30f)
        }
        val intercepts = observed.map { (i, x) -> x - i * spacing }.sorted()
        val firstCenter = if (intercepts.isNotEmpty()) intercepts[intercepts.size / 2] else spacing / 2f
        val centers = (0..6).map { i -> (firstCenter + i * spacing).coerceIn(0f, width) }
        val bounds = (0..6).map { i ->
            val left = if (i == 0) (centers[0] - spacing / 2f).coerceAtLeast(0f) else (centers[i - 1] + centers[i]) / 2f
            val right = if (i == 6) (centers[6] + spacing / 2f).coerceAtMost(width) else (centers[i] + centers[i + 1]) / 2f
            left to right
        }
        return ColumnGeometry(centers, bounds)
    }

    fun columnIndexForX(assist: AssistData, x: Float): Int {
        val g = columnGeometry(assist)
        val clamped = x.coerceIn(0f, assist.imageWidth.coerceAtLeast(1).toFloat())
        return g.bounds.indexOfFirst { clamped >= it.first && clamped < it.second }.takeIf { it >= 0 }
            ?: g.centers.indices.minByOrNull { kotlin.math.abs(g.centers[it] - clamped) } ?: 0
    }

    fun columnBounds(assist: AssistData, column: Int): Pair<Float, Float> =
        columnGeometry(assist).bounds[column.coerceIn(0, 6)]

    /**
     * Classifies the input before choosing a recognition strategy.  Printed spreadsheets and
     * handwritten rotas are different document types and should not share the same thresholds.
     */
    fun assessDocument(assist: AssistData, employeeName: String): DocumentAssessment {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) {
            return DocumentAssessment(DocumentKind.UNKNOWN, 0f, 0, 0, 0)
        }
        val exactHits = OfflineRotaVision.ocrNameHits(assist, employeeName).count { it.exact || it.score >= 0.94f }
        val useful = assist.tokens.filter { token ->
            val text = token.text.trim()
            text.length >= 2 && text.any { it.isLetterOrDigit() }
        }
        val heights = useful.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
        val medianHeight = heights.takeIf { it.isNotEmpty() }?.get(heights.size / 2)?.toFloat() ?: 0f
        val heightStable = if (medianHeight <= 0f) 0f else useful.count {
            val h = (it.bottom - it.top).coerceAtLeast(1)
            h in (medianHeight * 0.70f).toInt().coerceAtLeast(1)..(medianHeight * 1.35f).toInt().coerceAtLeast(2)
        }.toFloat() / useful.size.coerceAtLeast(1)

        // Printed tables tend to have many OCR boxes sharing nearly identical baselines.
        val rowBin = max(5f, assist.imageHeight * 0.010f)
        val alignedRows = useful.groupBy { (it.cy / rowBin).toInt() }.count { (_, row) -> row.size >= 3 }
        val gridEvidence = assist.verticalRules.size + assist.rowBoundaries.count { it.size >= 3 }
        val tokenDensity = useful.size.toFloat() / 35f
        val printedScore = (
            exactHits.coerceAtMost(3) * 0.16f +
                heightStable * 0.28f +
                alignedRows.coerceAtMost(8) * 0.035f +
                gridEvidence.coerceAtMost(12) * 0.022f +
                tokenDensity.coerceIn(0f, 1f) * 0.12f
            ).coerceIn(0f, 1f)

        val kind = when {
            printedScore >= 0.72f && exactHits >= 1 -> DocumentKind.PRINTED_TABLE
            printedScore >= 0.55f -> DocumentKind.MIXED
            useful.size >= 8 && gridEvidence >= 2 -> DocumentKind.HANDWRITTEN_GRID
            else -> DocumentKind.UNKNOWN
        }
        return DocumentAssessment(kind, printedScore, exactHits, alignedRows, gridEvidence)
    }

    fun withAssessment(assist: AssistData, employeeName: String): AssistData {
        val a = assessDocument(assist, employeeName)
        val assessed = assist.copy(documentKind = a.kind, printedConfidence = a.confidence)
        val geometry = columnGeometry(assessed)
        val h = assessed.imageHeight.coerceAtLeast(1).toFloat()
        val w = assessed.imageWidth.coerceAtLeast(1).toFloat()
        val centers = geometry.centers.map { (it / w).coerceIn(0f, 1f) }
        val rows = RotaGridModel.globalAnchors(assessed)
            .map { (it / h).coerceIn(0f, 1f) }
            .sorted()
        val vocabulary = canonicalTimeRows(assessed, geometry)
            .filter { it.confidence >= 0.60f || it.supportColumns >= 2 }
            .map { it.time }
            .distinct()
            .sorted()
        val enrichedBase = assessed.copy(
            templateFingerprint = buildTemplateFingerprint(assessed, centers, rows),
            learnedTimeVocabulary = (assessed.learnedTimeVocabulary + vocabulary).distinct().sorted()
        )
        val semantic = RotaSemanticTimeBands.infer(enrichedBase, assessed.semanticTimeBands)
        val enriched = enrichedBase.copy(semanticTimeBands = semantic)
        return enriched.copy(perception = RotaPerceptionEngine.assess(enriched))
    }

    /** Snapshot reusable geometry and semantics. Ratios make the template resolution-independent. */
    fun templateSnapshot(assist: AssistData): TemplateSnapshot {
        val geometry = columnGeometry(assist)
        val w = assist.imageWidth.coerceAtLeast(1).toFloat()
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val centers = geometry.centers.map { (it / w).coerceIn(0f, 1f) }
        val rows = RotaGridModel.globalAnchors(assist)
            .map { (it / h).coerceIn(0f, 1f) }
            .sorted()
        val canonicalRows = canonicalTimeRows(assist, geometry)
        // v19 stores block identity and learning health when available. Older y|time snapshots are
        // still accepted by applyTemplate(), so existing users keep all previously learned data.
        val structuralBands = assist.semanticTimeBands
            .filter { it.blockIndex != null && (it.confidence >= 0.62f || it.confirmations > 0 || it.supportColumns >= 2) }
            .sortedBy { it.blockIndex }
        val canonical = if (structuralBands.isNotEmpty()) {
            structuralBands.map { band ->
                "B${band.blockIndex}|${band.yRatio}|${band.time}|${band.confidence}|${band.confirmations}|${band.contradictions}"
            }
        } else {
            canonicalRows.map { "${it.y / h}|${it.time}" }
        }
        val vocabulary = ((canonicalRows
            .filter { it.confidence >= 0.62f || it.supportColumns >= 2 }
            .map { it.time }) + structuralBands.map { it.time } + assist.learnedTimeVocabulary)
            .distinct()
            .sorted()
            .map { it.toString() }
        return TemplateSnapshot(
            columnCenters = centers,
            rowRatios = rows,
            canonicalTimes = canonical,
            fingerprint = buildTemplateFingerprint(assist, centers, rows),
            documentKind = assist.documentKind.name,
            qualityScore = assist.quality.score,
            timeVocabulary = vocabulary,
            observations = 1
        )
    }

    fun templateSimilarity(assist: AssistData, template: TemplateSnapshot): Float {
        if (template.columnCenters.size != 7 || assist.imageWidth <= 0 || assist.imageHeight <= 0) return 0f
        val geometry = columnGeometry(assist)
        val w = assist.imageWidth.toFloat()
        val currentCenters = geometry.centers.map { (it / w).coerceIn(0f, 1f) }
        val centerError = currentCenters.zip(template.columnCenters).map { (a, b) -> abs(a - b) }.average().toFloat()
        val centerScore = (1f - centerError / 0.10f).coerceIn(0f, 1f)

        val currentRows = RotaGridModel.globalAnchors(assist)
            .map { (it / assist.imageHeight.toFloat()).coerceIn(0f, 1f) }
        val rowScore = if (currentRows.isEmpty() || template.rowRatios.isEmpty()) 0.45f else {
            val distances = currentRows.map { row -> template.rowRatios.minOf { abs(it - row) } }
            (1f - distances.average().toFloat() / 0.075f).coerceIn(0f, 1f)
        }
        val kindScore = if (template.documentKind == assist.documentKind.name || assist.documentKind == DocumentKind.UNKNOWN) 1f else 0.62f
        val fingerprintScore = if (template.fingerprint.isNotBlank() && template.fingerprint == assist.templateFingerprint) 1f else 0.72f
        return (centerScore * 0.48f + rowScore * 0.30f + kindScore * 0.12f + fingerprintScore * 0.10f).coerceIn(0f, 1f)
    }

    fun chooseBestTemplate(assist: AssistData, templates: List<TemplateSnapshot>): TemplateSnapshot? =
        templates.asSequence()
            .filter { it.columnCenters.size == 7 }
            .map { it to templateSimilarity(assist, it) }
            .filter { it.second >= 0.56f }
            .maxByOrNull { it.second }
            ?.first

    /** Apply a learned workplace template only where current geometry is weak or ambiguous. */
    fun applyTemplate(assist: AssistData, template: TemplateSnapshot?): AssistData {
        if (template == null || template.columnCenters.size != 7) return assist
        val similarity = templateSimilarity(assist, template)
        if (similarity < 0.50f) return assist
        val w = assist.imageWidth.coerceAtLeast(1).toFloat()
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val currentStrong = assist.verticalRules.size == 6 && assist.rowBoundaries.count { it.size >= 3 } >= 4
        val centers = template.columnCenters.map { it.coerceIn(0f, 1f) * w }
        val rules = (0 until 6).map { i -> (centers[i] + centers[i + 1]) / 2f }
        val learnedRows = template.rowRatios.map { it.coerceIn(0f, 1f) * h }
        val byColumn = List(7) { col ->
            val own = assist.rowBoundaries.getOrNull(col).orEmpty()
            if (own.size >= 3 || similarity < 0.64f) own else learnedRows
        }
        val learnedVocabulary = template.timeVocabulary.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        val learnedBands = template.canonicalTimes.mapNotNull { encoded ->
            val parts = encoded.split("|")
            if (parts.firstOrNull()?.startsWith("B") == true && parts.size >= 3) {
                val block = parts[0].removePrefix("B").toIntOrNull() ?: return@mapNotNull null
                val ratio = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
                val time = parts.getOrNull(2)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val confidence = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0.55f, 0.995f) ?: 0.90f
                val confirmations = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val contradictions = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                RotaSemanticTimeBands.Band(
                    yRatio = ratio.coerceIn(0f, 1f),
                    time = time,
                    confidence = confidence,
                    supportColumns = 1,
                    learned = true,
                    blockIndex = block,
                    confirmations = confirmations,
                    contradictions = contradictions
                )
            } else {
                // Backward compatibility with v10-v18 yRatio|time snapshots.
                val ratio = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
                val time = parts.getOrNull(1)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                RotaSemanticTimeBands.Band(ratio.coerceIn(0f, 1f), time, 0.84f, 2, learned = true)
            }
        }
        return assist.copy(
            verticalRules = if (assist.verticalRules.size == 6 || currentStrong || similarity < 0.64f) assist.verticalRules else rules,
            rowBoundaries = byColumn,
            learnedTimeVocabulary = (assist.learnedTimeVocabulary + learnedVocabulary).distinct().sorted(),
            templateFingerprint = if (assist.templateFingerprint.isNotBlank()) assist.templateFingerprint else template.fingerprint,
            semanticTimeBands = RotaSemanticTimeBands.infer(assist, assist.semanticTimeBands + learnedBands)
        )
    }

    /**
     * v19 durable correction learning. A manually confirmed start time is attached to the
     * employee's physical block, not merely a raw Y coordinate. The mapping is then persisted by
     * templateSnapshot(), so future imports can use it as strong-but-reversible structural prior.
     */
    /** Return an explicitly user-confirmed block time for this point, if one exists. */
    fun confirmedBlockTimeForPoint(assist: AssistData, tapX: Float, tapY: Float): LocalTime? {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return null
        val column = columnIndexForX(assist, tapX)
        val block = RotaGridModel.blockIndexForY(assist, column, tapY) ?: return null
        val band = RotaSemanticTimeBands.forBlock(assist.semanticTimeBands, block) ?: return null
        return band.time.takeIf {
            band.learned && band.confirmations >= 1 && band.contradictions == 0 && band.confidence >= 0.95f
        }
    }

    fun withConfirmedBlockTime(assist: AssistData, tapX: Float, tapY: Float, time: LocalTime): AssistData {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return assist
        val column = columnIndexForX(assist, tapX)
        val block = RotaGridModel.blockIndexForY(assist, column, tapY) ?: return assist
        val yRatio = RotaGridModel.canonicalRatioForBlock(assist, block)
            ?: (tapY / assist.imageHeight.toFloat()).coerceIn(0f, 1f)
        val bands = assist.semanticTimeBands.toMutableList()
        val index = bands.indexOfFirst { it.blockIndex == block }
        if (index >= 0) {
            val old = bands[index]
            bands[index] = if (old.time == time) {
                old.copy(
                    yRatio = yRatio,
                    confidence = (old.confidence + 0.08f).coerceAtMost(0.995f),
                    supportColumns = maxOf(old.supportColumns, 1),
                    learned = true,
                    confirmations = old.confirmations + 1,
                    contradictions = (old.contradictions - 1).coerceAtLeast(0)
                )
            } else {
                RotaSemanticTimeBands.Band(
                    yRatio = yRatio,
                    time = time,
                    confidence = 0.97f,
                    supportColumns = 1,
                    learned = true,
                    blockIndex = block,
                    confirmations = 1,
                    // A direct user correction is authoritative for the replacement value. Do not
                    // transfer the old mapping's contradiction debt onto the newly confirmed time.
                    contradictions = 0
                )
            }
        } else {
            bands += RotaSemanticTimeBands.Band(
                yRatio = yRatio,
                time = time,
                confidence = 0.97f,
                supportColumns = 1,
                learned = true,
                blockIndex = block,
                confirmations = 1,
                contradictions = 0
            )
        }
        return assist.copy(
            learnedTimeVocabulary = (assist.learnedTimeVocabulary + time).distinct().sorted(),
            semanticTimeBands = bands.sortedWith(compareBy<RotaSemanticTimeBands.Band> { it.blockIndex ?: Int.MAX_VALUE }.thenBy { it.yRatio })
        )
    }

    private fun buildTemplateFingerprint(
        assist: AssistData,
        centers: List<Float>,
        rows: List<Float>
    ): String {
        // Template identity must describe document structure, not what the model happened to
        // learn from previous imports. Including the learned time vocabulary fragmented one
        // physical rota into several templates as confirmations accumulated.
        val c = centers.joinToString(",") { ((it * 40f).toInt()).toString() }
        val r = rows.take(18).joinToString(",") { ((it * 40f).toInt()).toString() }
        val kind = assist.documentKind.name.take(3)
        return "$kind|$c|$r"
    }

    fun recognize(
        context: Context,
        uri: Uri,
        employeeName: String,
        typicalShiftHours: Int = 8,
        onSuccess: (String, List<Draft>, AssistData) -> Unit,
        onError: (Throwable) -> Unit,
        isCancelled: () -> Boolean = { false },
        onStage: (String) -> Unit = {},
        onProvisional: (String, AssistData) -> Unit = { _, _ -> }
    ) {
        fun stage(name: String) { if (!isCancelled()) runCatching { onStage(name) } }
        stage("DECODE")
        val analysisBitmap = runCatching { loadAnalysisBitmap(context, uri) }.getOrNull()
        val image = runCatching {
            analysisBitmap?.let { InputImage.fromBitmap(it, 0) } ?: InputImage.fromFilePath(context, uri)
        }.getOrElse { error ->
            if (analysisBitmap != null && !analysisBitmap.isRecycled) analysisBitmap.recycle()
            onError(error)
            return
        }
        val dimensions = analysisBitmap?.let { it.width to it.height } ?: readImageDimensions(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ShiftWatch-RotaImport").apply { isDaemon = true }
        }
        val completed = AtomicBoolean(false)

        fun cleanup() {
            runCatching { recognizer.close() }
            if (analysisBitmap != null && !analysisBitmap.isRecycled) analysisBitmap.recycle()
            callbackExecutor.shutdown()
        }

        fun finishSuccess(raw: String, drafts: List<Draft>, assist: AssistData) {
            if (!completed.compareAndSet(false, true)) return
            cleanup()
            onSuccess(raw, drafts, assist)
        }

        fun finishError(error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            stage("ERROR")
            cleanup()
            onError(error)
        }

        fun abortIfCancelled(): Boolean {
            if (!isCancelled()) return false
            if (completed.compareAndSet(false, true)) cleanup()
            return true
        }

        stage("PAGE_OCR")
        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { firstResult ->
                if (abortIfCancelled()) return@addOnSuccessListener
                // Fast path for clean printed/Excel tables. If the first OCR pass reads the
                // employee and an explicit start/end pair on the same row, there is no reason to
                // run the expensive handwriting pipeline or ask the user to teach examples.
                val firstAssist = withAssessment(createAssistData(listOf(firstResult), dimensions.first, dimensions.second, analysisBitmap), employeeName)
                // Emit early header/grid information while the deeper OCR passes continue.
                // Provisional data is analysis evidence, never an automatic shift approval.
                if (!isCancelled()) runCatching { onProvisional(firstResult.text.orEmpty(), firstAssist) }
                val firstTextHits = OfflineRotaVision.ocrNameHits(firstAssist, employeeName).filter { it.exact }
                val firstTableDrafts = extractAssistDrafts(firstAssist, employeeName, typicalShiftHours)
                if (firstAssist.documentKind == DocumentKind.PRINTED_TABLE && firstTextHits.isNotEmpty() && firstTableDrafts.isNotEmpty() &&
                    firstTableDrafts.all { it.confidence >= 0.93f }) {
                    // Printed tables are deterministic enough to skip handwriting analysis. The
                    // review UI still opens with all detected shifts pre-selected for visual
                    // confirmation, but the user never has to point at employee names manually.
                    finishSuccess(firstResult.text.orEmpty(), firstTableDrafts, firstAssist)
                    return@addOnSuccessListener
                }

                // Do not stop after one OCR pass on a photographed rota. A single pass can
                // confidently hallucinate a handwritten time/name; the fusion stage below needs
                // agreement from multiple renderings before an automatic result is trusted.

                // Handwritten rotas get two additional local OCR passes.  The first boosts
                // contrast; the second uses a clean black/white rendition.  We MERGE the
                // geometry from all passes rather than choosing only one result.  This is
                // important for small superscript-style times such as 9:30 and 13:00: one
                // pass may read the employee names best while another reads the tiny digits.
                val enhancedBitmap = runCatching { analysisBitmap?.let { createEnhancedBitmap(it) } }.getOrNull()
                if (enhancedBitmap == null) {
                    val assist = withAssessment(createAssistData(listOf(firstResult), dimensions.first, dimensions.second, analysisBitmap), employeeName)
                    finishSuccess(
                        firstResult.text.orEmpty(),
                        extractAssistDrafts(assist, employeeName, typicalShiftHours),
                        assist
                    )
                    return@addOnSuccessListener
                }

                stage("CONTRAST_OCR")
                recognizer.process(InputImage.fromBitmap(enhancedBitmap, 0))
                    .addOnSuccessListener(callbackExecutor) { secondResult ->
                        if (!enhancedBitmap.isRecycled) enhancedBitmap.recycle()
                        if (abortIfCancelled()) return@addOnSuccessListener
                        val thresholdBitmap = runCatching { analysisBitmap?.let { createThresholdBitmap(it) } }.getOrNull()
                        if (thresholdBitmap == null) {
                            val secondDrafts = extractSpatialDrafts(secondResult, employeeName, typicalShiftHours.coerceIn(1, 16), LocalDate.now())
                            val combined = (secondDrafts + extractDrafts(secondResult.text.orEmpty(), employeeName))
                                .distinctBy { it.start to it.end }.sortedBy { it.start }
                            val mergedText = mergeOcrPreview(listOf(firstResult, secondResult))
                            val assist = withAssessment(createAssistData(listOf(firstResult, secondResult), dimensions.first, dimensions.second, analysisBitmap), employeeName)
                            val autonomous = extractAssistDrafts(assist, employeeName, typicalShiftHours)
                            finishSuccess(
                                mergedText,
                                (combined + autonomous).distinctBy { it.start to it.end }.sortedBy { it.start },
                                assist
                            )
                            return@addOnSuccessListener
                        }

                        stage("THRESHOLD_OCR")
                        recognizer.process(InputImage.fromBitmap(thresholdBitmap, 0))
                            .addOnSuccessListener(callbackExecutor) { thirdResult ->
                                if (!thresholdBitmap.isRecycled) thresholdBitmap.recycle()
                                if (abortIfCancelled()) return@addOnSuccessListener
                                val allResults = listOf(firstResult, secondResult, thirdResult)
                                val drafts = allResults.flatMap { result ->
                                    val spatial = extractSpatialDrafts(
                                        result = result,
                                        employeeName = employeeName,
                                        typicalShiftHours = typicalShiftHours.coerceIn(1, 16),
                                        today = LocalDate.now()
                                    )
                                    if (spatial.isEmpty()) extractDrafts(result.text.orEmpty(), employeeName) else spatial
                                }.distinctBy { it.start to it.end }.sortedBy { it.start }

                                val baseAssist = withAssessment(createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap), employeeName)
                                if (analysisBitmap != null) {
                                    // v20.4: dates and shift times get their own focused high-resolution passes.
                                    // Header OCR runs first so its geometry can improve the canonical grid/date model
                                    // that the time atlas consumes.
                                    stage("HEADER")
                                    runHeaderFocusOcr(recognizer, callbackExecutor, analysisBitmap, isCancelled) { headerTokens ->
                                        if (abortIfCancelled()) return@runHeaderFocusOcr
                                        val headerAssist = withAssessment(
                                            createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap, headerTokens),
                                            employeeName
                                        )
                                        stage("TIMES")
                                        runTimeAtlasOcr(recognizer, callbackExecutor, analysisBitmap, headerAssist, isCancelled) { timeTokens ->
                                            if (abortIfCancelled()) return@runTimeAtlasOcr
                                            val extras = headerTokens + timeTokens
                                            val assist = withAssessment(createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap, extras), employeeName)
                                            val autonomous = extractAssistDrafts(assist, employeeName, typicalShiftHours)
                                            finishSuccess(
                                                mergeOcrPreview(allResults) + "\n\n--- header focus pass ---\n" +
                                                    headerTokens.joinToString(" · ") { it.text }.take(1200) +
                                                    "\n\n--- time atlas pass ---\n" +
                                                    timeTokens.joinToString(" · ") { it.text }.take(1600),
                                                (drafts + autonomous).distinctBy { it.start to it.end }.sortedBy { it.start },
                                                assist
                                            )
                                        }
                                    }
                                } else {
                                    val autonomous = extractAssistDrafts(baseAssist, employeeName, typicalShiftHours)
                                    finishSuccess(mergeOcrPreview(allResults), (drafts + autonomous).distinctBy { it.start to it.end }.sortedBy { it.start }, baseAssist)
                                }
                            }
                            .addOnFailureListener(callbackExecutor) {
                                if (!thresholdBitmap.isRecycled) thresholdBitmap.recycle()
                                val allResults = listOf(firstResult, secondResult)
                                val baseAssist = withAssessment(createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap), employeeName)
                                if (analysisBitmap != null) {
                                    stage("HEADER")
                                    runHeaderFocusOcr(recognizer, callbackExecutor, analysisBitmap, isCancelled) { headerTokens ->
                                        if (abortIfCancelled()) return@runHeaderFocusOcr
                                        val headerAssist = withAssessment(
                                            createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap, headerTokens),
                                            employeeName
                                        )
                                        stage("TIMES")
                                        runTimeAtlasOcr(recognizer, callbackExecutor, analysisBitmap, headerAssist, isCancelled) { timeTokens ->
                                            if (abortIfCancelled()) return@runTimeAtlasOcr
                                            val extras = headerTokens + timeTokens
                                            val assist = withAssessment(createAssistData(allResults, dimensions.first, dimensions.second, analysisBitmap, extras), employeeName)
                                            finishSuccess(
                                                mergeOcrPreview(allResults) + "\n\n--- header focus pass ---\n" +
                                                    headerTokens.joinToString(" · ") { it.text }.take(1200) +
                                                    "\n\n--- time atlas pass ---\n" +
                                                    timeTokens.joinToString(" · ") { it.text }.take(1600),
                                                extractAssistDrafts(assist, employeeName, typicalShiftHours),
                                                assist
                                            )
                                        }
                                    }
                                } else {
                                    finishSuccess(mergeOcrPreview(allResults), extractAssistDrafts(baseAssist, employeeName, typicalShiftHours), baseAssist)
                                }
                            }
                    }
                    .addOnFailureListener(callbackExecutor) {
                        if (!enhancedBitmap.isRecycled) enhancedBitmap.recycle()
                        val assist = withAssessment(createAssistData(listOf(firstResult), dimensions.first, dimensions.second, analysisBitmap), employeeName)
                        finishSuccess(
                            firstResult.text.orEmpty(),
                            extractAssistDrafts(assist, employeeName, typicalShiftHours),
                            assist
                        )
                    }
            }
            .addOnFailureListener(callbackExecutor) { finishError(it) }
    }

    /**
     * Autonomous text-first extraction for printed / Excel-generated rotas.  Unlike the visual
     * handwriting learner, this path does not require two seed taps: if OCR can read the employee
     * name, every strong occurrence is resolved immediately against the detected day column and
     * schedule-time geometry.  This is intentionally run after all OCR passes and the time atlas.
     */
    private fun parsePrintedDate(text: String, today: LocalDate): LocalDate? {
        val m = Regex("""\b([0-3]?\d)[./-]([01]?\d)(?:[./-](\d{2,4}))?\b""").find(text) ?: return null
        val day = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val rawYear = m.groupValues[3].toIntOrNull()
        val year = when {
            rawYear == null -> today.year
            rawYear < 100 -> 2000 + rawYear
            else -> rawYear
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun extractAssistDrafts(
        assist: AssistData,
        employeeName: String,
        typicalShiftHours: Int,
        today: LocalDate = LocalDate.now()
    ): List<Draft> {
        if (employeeName.isBlank()) return emptyList()
        val hits = OfflineRotaVision.ocrNameHits(assist, employeeName)
            .filter { it.exact || it.score >= 0.88f }
        if (hits.isEmpty()) return emptyList()
        val fallbackWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lockedWeek = detectedWeekStart(assist, fallbackWeek)
        return hits.mapNotNull { hit ->
            val column = hit.column.coerceIn(0, 6)
            val rowPad = max(6f, (hit.bottom - hit.top).coerceAtLeast(1) * 0.45f)
            val rowTokens = assist.tokens.filter { token ->
                token.bottom >= hit.top - rowPad && token.top <= hit.bottom + rowPad
            }
            val sameRowDate = rowTokens.asSequence().mapNotNull { parsePrintedDate(it.text, today) }.firstOrNull()
            val headerDate = assist.tokens.asSequence()
                .filter { token -> columnIndexForX(assist, token.cx) == column && token.cy < hit.y }
                .mapNotNull { parsePrintedDate(it.text, today) }
                .lastOrNull()
            val date = sameRowDate ?: headerDate ?: lockedWeek.plusDays(column.toLong())
            // Printed spreadsheets often put start/end times on the same visual row as the
            // employee. Recover that exact pair before falling back to block-style rota logic.
            val rowTimes = rowTokens.asSequence()
                .mapNotNull { token -> parseTimeLenient(token.text)?.let { token.cx to it } }
                .sortedBy { it.first }
                .distinctBy { it.second }
                .toList()
            val exactRowDraft = when {
                rowTimes.size >= 2 -> {
                    // Preserve left-to-right table order instead of sorting by clock value.
                    // Sorting times numerically breaks overnight rows such as 22:00 → 06:00.
                    val startTime = rowTimes[0].second
                    val endTime = rowTimes[1].second
                    val start = LocalDateTime.of(date, startTime)
                    var end = LocalDateTime.of(date, endTime)
                    if (!end.isAfter(start)) end = end.plusDays(1)
                    Draft(
                        start = start,
                        end = end,
                        sourceLine = "Automatic table import · “${hit.rawText}” · exact start/end from same row",
                        confidence = if (hit.exact) 0.998f else 0.97f,
                        estimatedEnd = false,
                        columnIndex = column,
                        origin = DraftOrigin.PRINTED_TABLE
                    )
                }
                rowTimes.size == 1 && hit.exact -> {
                    val start = LocalDateTime.of(date, rowTimes[0].second)
                    Draft(
                        start = start,
                        end = start.plusHours(typicalShiftHours.coerceIn(1, 16).toLong()),
                        sourceLine = "Automatic table import · “${hit.rawText}” · exact start from same row · end estimated +${typicalShiftHours}h",
                        confidence = 0.95f,
                        estimatedEnd = true,
                        columnIndex = column,
                        origin = DraftOrigin.PRINTED_TABLE
                    )
                }
                else -> null
            }
            exactRowDraft ?: draftFromTap(
                assist = assist,
                tapX = hit.x,
                tapY = hit.y,
                typicalShiftHours = typicalShiftHours.coerceIn(1, 16),
                fallbackWeekStart = fallbackWeek,
                today = today,
                lockedWeekStart = lockedWeek
            )?.let { draft ->
                draft.copy(
                    confidence = min(draft.confidence, if (hit.exact) 0.995f else (0.86f + hit.score * 0.12f).coerceAtMost(0.98f)),
                    sourceLine = "Automatic OCR import · “${hit.rawText}” · ${draft.sourceLine}",
                    origin = DraftOrigin.OCR_TEXT
                )
            }
        }.distinctBy { it.columnIndex to it.start }.sortedBy { it.start }
    }

    private fun mergeOcrPreview(results: List<Text>): String = buildString {
        results.forEachIndexed { index, result ->
            if (index > 0 && result.text.isNotBlank()) append("\n\n--- handwriting OCR pass ${index + 1} ---\n")
            if (result.text.isNotBlank()) append(result.text)
        }
    }

    private fun readImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
        return options.outWidth.coerceAtLeast(1) to options.outHeight.coerceAtLeast(1)
    }

    private fun loadAnalysisBitmap(context: Context, uri: Uri): Bitmap? {
        val maxSide = 3000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                val sample = max(1, (max(w, h) + maxSide - 1) / maxSide)
                decoder.setTargetSampleSize(sample)
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }

    private fun assessImageQuality(bitmap: Bitmap?, verticalRules: List<Float>, rowBoundaries: List<List<Float>>): QualityAssessment {
        if (bitmap == null || bitmap.width < 8 || bitmap.height < 8) return QualityAssessment()
        val stepX = max(1, bitmap.width / 180)
        val stepY = max(1, bitmap.height / 180)
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var edgeSum = 0.0
        var edgeCount = 0
        for (y in stepY until bitmap.height - stepY step stepY) {
            for (x in stepX until bitmap.width - stepX step stepX) {
                val px = bitmap.getPixel(x, y)
                val gray = (android.graphics.Color.red(px) * 0.299 + android.graphics.Color.green(px) * 0.587 + android.graphics.Color.blue(px) * 0.114)
                count++
                sum += gray
                sumSq += gray * gray
                val right = bitmap.getPixel((x + stepX).coerceAtMost(bitmap.width - 1), y)
                val down = bitmap.getPixel(x, (y + stepY).coerceAtMost(bitmap.height - 1))
                fun g(c: Int) = android.graphics.Color.red(c) * 0.299 + android.graphics.Color.green(c) * 0.587 + android.graphics.Color.blue(c) * 0.114
                edgeSum += abs(g(right) - gray) + abs(g(down) - gray)
                edgeCount += 2
            }
        }
        if (count == 0) return QualityAssessment()
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val contrast = (kotlin.math.sqrt(variance) / 64.0).toFloat().coerceIn(0f, 1f)
        val sharpness = ((edgeSum / edgeCount.coerceAtLeast(1)) / 26.0).toFloat().coerceIn(0f, 1f)
        val grid = ((verticalRules.size / 6f) * 0.48f + (rowBoundaries.count { it.size >= 3 } / 7f) * 0.52f).coerceIn(0f, 1f)
        val score = (contrast * 0.34f + sharpness * 0.36f + grid * 0.30f).coerceIn(0f, 1f)
        val warnings = buildList {
            if (contrast < 0.24f) add("Low contrast / glare may hide handwriting")
            if (sharpness < 0.20f) add("Image appears soft or blurred")
            if (grid < 0.35f) add("Rota grid is only partially visible")
        }
        return QualityAssessment(contrast, sharpness, grid, 1f, score, warnings)
    }

    private fun createAssistData(
        results: List<Text>,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
        extraTokens: List<AssistToken> = emptyList()
    ): AssistData {
        val tokens = results.flatMap { result ->
            result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    buildList {
                        line.boundingBox?.let { add(AssistToken(line.text.trim(), it.left, it.top, it.right, it.bottom)) }
                        line.elements.forEach { element ->
                            element.boundingBox?.let { add(AssistToken(element.text.trim(), it.left, it.top, it.right, it.bottom)) }
                        }
                    }
                }
            }
        }.plus(extraTokens)
            .filter { it.text.isNotBlank() }
            .distinctBy { listOf(it.text.lowercase(Locale.ROOT), it.left, it.top, it.right, it.bottom, it.source.name, it.blockHint ?: -1) }

        val inferredWidth = max(width, tokens.maxOfOrNull { it.right } ?: 1)
        val inferredHeight = max(height, tokens.maxOfOrNull { it.bottom } ?: 1)
        val base = AssistData(inferredWidth, inferredHeight, tokens, ocrPasses = results.size)
        val verticalRules = if (bitmap != null) detectVerticalRules(bitmap, base) else emptyList()
        val withColumns = base.copy(verticalRules = verticalRules)
        val boundaries = if (bitmap != null) detectHorizontalRules(bitmap, withColumns) else List(7) { emptyList() }
        val quality = assessImageQuality(bitmap, verticalRules, boundaries)
        return withColumns.copy(rowBoundaries = boundaries, quality = quality)
    }

    /**
     * v20.4 focused header OCR. Full-page OCR can read the employee grid while missing the
     * small handwritten DD.MM header. Re-scan only the top document strip at higher resolution
     * and map its tokens back to source coordinates so date authority has geometry it can trust.
     * Two sequential views are used to keep peak memory bounded.
     */
    private fun runHeaderFocusOcr(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        executor: java.util.concurrent.Executor,
        bitmap: Bitmap,
        isCancelled: () -> Boolean,
        onComplete: (List<AssistToken>) -> Unit
    ) {
        if (bitmap.width < 20 || bitmap.height < 20) {
            onComplete(emptyList())
            return
        }
        val cropHeight = (bitmap.height * 0.22f).toInt().coerceIn(20, bitmap.height)
        val source = runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight) }.getOrNull()
        if (source == null) {
            onComplete(emptyList())
            return
        }
        val scale = minOf(3.2f, 2800f / source.width.coerceAtLeast(1).toFloat()).coerceAtLeast(1.35f)
        val enlarged = runCatching {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }.getOrNull()
        source.recycle()
        if (enlarged == null) {
            onComplete(emptyList())
            return
        }

        val collected = mutableListOf<AssistToken>()
        fun collect(result: Text) {
            fun add(text: String, box: Rect?) {
                if (box == null || text.isBlank()) return
                collected += AssistToken(
                    text = text.trim(),
                    left = (box.left / scale).toInt().coerceAtLeast(0),
                    top = (box.top / scale).toInt().coerceAtLeast(0),
                    right = (box.right / scale).toInt().coerceAtMost(bitmap.width),
                    bottom = (box.bottom / scale).toInt().coerceAtMost(cropHeight),
                    source = TokenSource.HEADER_FOCUS
                )
            }
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    add(line.text, line.boundingBox)
                    line.elements.forEach { element -> add(element.text, element.boundingBox) }
                }
            }
        }

        fun finish() {
            if (!enlarged.isRecycled) enlarged.recycle()
            onComplete(collected.distinctBy {
                listOf(it.text.lowercase(Locale.ROOT), it.left, it.top, it.right, it.bottom, it.source.name)
            })
        }

        fun runThresholdPass() {
            if (isCancelled()) { finish(); return }
            val threshold = runCatching { createThresholdBitmap(enlarged) }.getOrNull()
            if (threshold == null) { finish(); return }
            recognizer.process(InputImage.fromBitmap(threshold, 0))
                .addOnSuccessListener(executor) { result ->
                    collect(result)
                    if (!threshold.isRecycled) threshold.recycle()
                    finish()
                }
                .addOnFailureListener(executor) {
                    if (!threshold.isRecycled) threshold.recycle()
                    finish()
                }
        }

        recognizer.process(InputImage.fromBitmap(enlarged, 0))
            .addOnSuccessListener(executor) { result ->
                collect(result)
                runThresholdPass()
            }
            .addOnFailureListener(executor) { runThresholdPass() }
    }

    /**
     * RotaVision 8 time atlas.
     *
     * Instead of OCRing seven complete columns, extract only the small top-left corner of every
     * detected schedule block, enlarge those regions into a single contact sheet and recognize the
     * sheet twice. The time label is therefore large while employee names and most grid noise are
     * absent. One atlas replaces many slow OCR calls and gives superscript minutes much more pixel
     * area (for example handwritten 9³⁰).
     */
    private fun runTimeAtlasOcr(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        executor: java.util.concurrent.Executor,
        bitmap: Bitmap,
        baseAssist: AssistData,
        isCancelled: () -> Boolean,
        onComplete: (List<AssistToken>) -> Unit
    ) {
        data class Cell(
            val sourceLeft: Float,
            val sourceTop: Float,
            val sourceRight: Float,
            val sourceBottom: Float,
            val atlasLeft: Int,
            val atlasTop: Int,
            val atlasRight: Int,
            val atlasBottom: Int,
            val column: Int,
            val blockIndex: Int
        )

        val geometry = columnGeometry(baseAssist)
        val sx = bitmap.width.toFloat() / baseAssist.imageWidth.coerceAtLeast(1)
        val sy = bitmap.height.toFloat() / baseAssist.imageHeight.coerceAtLeast(1)

        // Block starts are the header/body separator plus strong horizontal rules in each day.
        // Merge nearby rules because pencil lines are often detected as two parallel strokes.
        // v19: generate atlas cells from the reconstructed canonical block grid. Even when one
        // photographed column is missing a detectable horizontal rule, the expected block start is
        // interpolated from neighbouring columns and still receives its own time-label crop.
        val startsPerColumn = (0..6).map { column -> RotaGridModel.expectedStartsForColumn(baseAssist, column).take(7) }
        val maxRows = startsPerColumn.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val cellW = 420
        val cellH = 170
        val gap = 10
        val atlasW = 7 * cellW + 8 * gap
        val atlasH = maxRows * cellH + (maxRows + 1) * gap
        val atlas = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(atlas)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cells = mutableListOf<Cell>()

        for (column in 0..6) {
            val (sourceLeft, sourceRight) = geometry.bounds[column]
            val columnWidth = (sourceRight - sourceLeft).coerceAtLeast(1f)
            startsPerColumn[column].forEachIndexed { row, startY ->
                // Time is written immediately below the block's top rule and near the left edge.
                val sl = (sourceLeft + columnWidth * 0.005f).coerceAtLeast(0f)
                val sr = (sourceLeft + columnWidth * 0.40f).coerceAtMost(baseAssist.imageWidth.toFloat())
                val st = (startY - baseAssist.imageHeight * 0.010f).coerceAtLeast(0f)
                val sb = (startY + baseAssist.imageHeight * 0.060f).coerceAtMost(baseAssist.imageHeight.toFloat())
                val src = Rect(
                    (sl * sx).toInt().coerceIn(0, bitmap.width - 1),
                    (st * sy).toInt().coerceIn(0, bitmap.height - 1),
                    (sr * sx).toInt().coerceIn(1, bitmap.width),
                    (sb * sy).toInt().coerceIn(1, bitmap.height)
                )
                if (src.width() < 3 || src.height() < 3) return@forEachIndexed
                val al = gap + column * (cellW + gap)
                val at = gap + row * (cellH + gap)
                val ar = al + cellW
                val ab = at + cellH
                canvas.drawBitmap(bitmap, src, Rect(al, at, ar, ab), paint)
                // The top horizontal rule is known geometry, not handwriting. Remove only a very
                // thin band at its mapped position so tiny 00/30 superscripts are not crossed by
                // the grid line during OCR.
                val ruleFraction = ((startY - st) / (sb - st).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val ruleY = at + (ruleFraction * cellH).toInt()
                val erasePaint = Paint().apply { color = android.graphics.Color.WHITE }
                canvas.drawRect(al.toFloat(), (ruleY - 2).toFloat(), ar.toFloat(), (ruleY + 2).toFloat(), erasePaint)
                // Suppress vertical table-rule remnants at the crop edges. These lines frequently
                // merge with handwritten 1/0 glyphs and create false 10/11/20 readings.
                canvas.drawRect(al.toFloat(), at.toFloat(), (al + 4).toFloat(), ab.toFloat(), erasePaint)
                canvas.drawRect((ar - 4).toFloat(), at.toFloat(), ar.toFloat(), ab.toFloat(), erasePaint)
                val canonicalBlock = row
                cells += Cell(sl, st, sr, sb, al, at, ar, ab, column, canonicalBlock)
            }
        }
        if (cells.isEmpty()) {
            onComplete(emptyList())
            return
        }

        fun renderVariant(source: Bitmap, threshold: Boolean): Bitmap {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            if (!threshold) {
                val c = Canvas(out)
                val cm = ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 2.25f
                    val translate = (-0.5f * contrast + 0.5f) * 255f + 28f
                    postConcat(ColorMatrix(floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                c.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                })
                return out
            }
            // Threshold each block cell independently. A single global cutoff is fragile when
            // one side of the photographed sheet is shadowed or brighter than the other.
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            cells.forEach { cell ->
                val w = (cell.atlasRight - cell.atlasLeft).coerceAtLeast(1)
                val hCell = (cell.atlasBottom - cell.atlasTop).coerceAtLeast(1)
                val px = IntArray(w * hCell)
                source.getPixels(px, 0, w, cell.atlasLeft, cell.atlasTop, w, hCell)
                val hist = IntArray(256)
                px.forEachIndexed { index, color ->
                    if (index % 2 == 0) {
                        val l = (android.graphics.Color.red(color) * 30 + android.graphics.Color.green(color) * 59 + android.graphics.Color.blue(color) * 11) / 100
                        hist[l]++
                    }
                }
                val total = hist.sum().coerceAtLeast(1)
                var sum = 0L
                hist.indices.forEach { i -> sum += i.toLong() * hist[i] }
                var sumB = 0L; var wB = 0; var best = 175; var bestVar = -1.0
                for (t in 60..225) {
                    wB += hist[t]; if (wB == 0) continue
                    val wF = total - wB; if (wF == 0) break
                    sumB += t.toLong() * hist[t]
                    val mB = sumB.toDouble() / wB
                    val mF = (sum - sumB).toDouble() / wF
                    val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
                    if (between > bestVar) { bestVar = between; best = t }
                }
                val cutoff = best.coerceIn(110, 210)
                px.indices.forEach { i ->
                    val color = px[i]
                    val l = (android.graphics.Color.red(color) * 30 + android.graphics.Color.green(color) * 59 + android.graphics.Color.blue(color) * 11) / 100
                    px[i] = if (l < cutoff) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                out.setPixels(px, 0, w, cell.atlasLeft, cell.atlasTop, w, hCell)
            }
            return out
        }

        /**
         * v19.2 pen-sensitive atlas.  Handwritten times are frequently blue/purple while the
         * printed/grid background is neutral.  This view keeps both dark ink and chromatic pen
         * strokes, then suppresses pale paper.  It complements (rather than replaces) grayscale
         * OCR, so black-ink rotas continue to work.
         */
        fun renderPenInkVariant(source: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            cells.forEach { cell ->
                val w = (cell.atlasRight - cell.atlasLeft).coerceAtLeast(1)
                val hCell = (cell.atlasBottom - cell.atlasTop).coerceAtLeast(1)
                val px = IntArray(w * hCell)
                source.getPixels(px, 0, w, cell.atlasLeft, cell.atlasTop, w, hCell)
                px.indices.forEach { i ->
                    val c = px[i]
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    val chroma = maxOf(r, g, b) - minOf(r, g, b)
                    val darkInk = 255 - luma
                    val penStrength = maxOf(darkInk, chroma * 2)
                    px[i] = if (penStrength >= 42) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                out.setPixels(px, 0, w, cell.atlasLeft, cell.atlasTop, w, hCell)
            }
            return out
        }

        val collected = mutableListOf<AssistToken>()

        // v19.1: besides the full block label, create two structure-aware views. The hour view
        // enlarges the left part where 9/13/16/18 is normally written; the minute view enlarges
        // the small elevated 00/30 area. ML Kit can then recognize the two pieces independently
        // and collectAssistTimeCandidates() recombines them only inside the same physical block.
        fun renderFocusedVariant(
            source: Bitmap,
            xStart: Float,
            xSpan: Float,
            yStart: Float,
            ySpan: Float,
            threshold: Boolean
        ): Bitmap {
            val base = renderVariant(source, threshold)
            val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(android.graphics.Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            cells.forEach { cell ->
                val cw = (cell.atlasRight - cell.atlasLeft).coerceAtLeast(1)
                val ch = (cell.atlasBottom - cell.atlasTop).coerceAtLeast(1)
                val l = (cell.atlasLeft + cw * xStart).toInt().coerceIn(cell.atlasLeft, cell.atlasRight - 1)
                val t = (cell.atlasTop + ch * yStart).toInt().coerceIn(cell.atlasTop, cell.atlasBottom - 1)
                val r = (l + cw * xSpan).toInt().coerceIn(l + 1, cell.atlasRight)
                val b = (t + ch * ySpan).toInt().coerceIn(t + 1, cell.atlasBottom)
                c.drawBitmap(base, Rect(l, t, r, b), Rect(cell.atlasLeft, cell.atlasTop, cell.atlasRight, cell.atlasBottom), p)
            }
            if (!base.isRecycled) base.recycle()
            return out
        }

        fun processVariant(
            rendered: Bitmap,
            xStart: Float = 0f,
            xSpan: Float = 1f,
            yStart: Float = 0f,
            ySpan: Float = 1f,
            done: () -> Unit
        ) {
            recognizer.process(InputImage.fromBitmap(rendered, 0))
                .addOnSuccessListener(executor) { result ->
                    fun add(text: String, box: Rect?) {
                        if (box == null || text.isBlank()) return
                        val cx = (box.left + box.right) / 2
                        val cy = (box.top + box.bottom) / 2
                        val cell = cells.firstOrNull { cx in it.atlasLeft..it.atlasRight && cy in it.atlasTop..it.atlasBottom } ?: return
                        val rx0 = ((box.left - cell.atlasLeft).toFloat() / (cell.atlasRight - cell.atlasLeft).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ry0 = ((box.top - cell.atlasTop).toFloat() / (cell.atlasBottom - cell.atlasTop).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val rx1 = ((box.right - cell.atlasLeft).toFloat() / (cell.atlasRight - cell.atlasLeft).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ry1 = ((box.bottom - cell.atlasTop).toFloat() / (cell.atlasBottom - cell.atlasTop).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val nx0 = (xStart + rx0 * xSpan).coerceIn(0f, 1f)
                        val ny0 = (yStart + ry0 * ySpan).coerceIn(0f, 1f)
                        val nx1 = (xStart + rx1 * xSpan).coerceIn(0f, 1f)
                        val ny1 = (yStart + ry1 * ySpan).coerceIn(0f, 1f)
                        collected += AssistToken(
                            text.trim(),
                            (cell.sourceLeft + nx0 * (cell.sourceRight - cell.sourceLeft)).toInt(),
                            (cell.sourceTop + ny0 * (cell.sourceBottom - cell.sourceTop)).toInt(),
                            (cell.sourceLeft + nx1 * (cell.sourceRight - cell.sourceLeft)).toInt(),
                            (cell.sourceTop + ny1 * (cell.sourceBottom - cell.sourceTop)).toInt(),
                            source = TokenSource.TIME_ATLAS,
                            blockHint = cell.blockIndex
                        )
                    }
                    result.textBlocks.forEach { block -> block.lines.forEach { line ->
                        add(line.text, line.boundingBox)
                        line.elements.forEach { element -> add(element.text, element.boundingBox) }
                    } }
                    if (!rendered.isRecycled) rendered.recycle()
                    done()
                }
                .addOnFailureListener(executor) {
                    if (!rendered.isRecycled) rendered.recycle()
                    done()
                }
        }

        data class VariantSpec(
            val render: () -> Bitmap,
            val xStart: Float = 0f,
            val xSpan: Float = 1f,
            val yStart: Float = 0f,
            val ySpan: Float = 1f
        )

        // Render one OCR view at a time. v19 created six full atlas bitmaps simultaneously,
        // which could exceed the heap on repeated imports. Sequential rendering keeps peak memory
        // bounded while preserving exactly the same recognition passes.
        val variants = listOf(
            VariantSpec(render = { renderVariant(atlas, false) }),
            VariantSpec(render = { renderVariant(atlas, true) }),
            VariantSpec(render = { renderPenInkVariant(atlas) }),
            VariantSpec(
                render = { renderFocusedVariant(atlas, 0f, 0.58f, 0.02f, 0.94f, false) },
                xStart = 0f, xSpan = 0.58f, yStart = 0.02f, ySpan = 0.94f
            ),
            VariantSpec(
                render = { renderFocusedVariant(atlas, 0f, 0.58f, 0.02f, 0.94f, true) },
                xStart = 0f, xSpan = 0.58f, yStart = 0.02f, ySpan = 0.94f
            ),
            VariantSpec(
                render = { renderFocusedVariant(atlas, 0.16f, 0.56f, 0f, 0.54f, true) },
                xStart = 0.16f, xSpan = 0.56f, yStart = 0f, ySpan = 0.54f
            )
        )

        fun finishAtlas() {
            if (!atlas.isRecycled) atlas.recycle()
            onComplete(collected.distinctBy {
                listOf(it.text.lowercase(Locale.ROOT), it.left, it.top, it.right, it.bottom, it.source.name, it.blockHint ?: -1)
            })
        }

        fun processNext(index: Int) {
            if (isCancelled()) {
                finishAtlas()
                return
            }
            if (index >= variants.size) {
                finishAtlas()
                return
            }
            val spec = variants[index]
            val rendered = runCatching { spec.render() }.getOrNull()
            if (rendered == null) {
                processNext(index + 1)
                return
            }
            processVariant(rendered, spec.xStart, spec.xSpan, spec.yStart, spec.ySpan) {
                processNext(index + 1)
            }
        }

        processNext(0)
    }

    // Legacy whole-column/time-strip OCR passes were removed in v20.1. They were no longer
    // called after the time-atlas architecture landed, but still carried a large amount of
    // duplicate bitmap/OCR code and several unrecycled temporary bitmaps.

    /**
     * Detect the strong horizontal rules of a photographed rota directly from pixels.
     * Handwriting OCR is often weakest on tiny time labels, while the table lines remain
     * very clear.  Knowing the current schedule block lets us look only near that block's
     * top edge instead of accidentally reusing a later 16:00 label.
     */
    /**
     * Finds the six internal day-column separators from the photographed paper itself.
     * We search around the expected 1/7 positions and score long, dark vertical strokes.
     * This remains useful even when weekday handwriting is not recognized at all.
     */
    private fun detectVerticalRules(bitmap: Bitmap, assist: AssistData): List<Float> {
        if (bitmap.width < 140 || bitmap.height < 140) return emptyList()
        val sx = bitmap.width.toFloat() / assist.imageWidth.coerceAtLeast(1)
        val yStart = (bitmap.height * 0.035f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.965f).toInt().coerceIn(yStart + 1, bitmap.height)
        val sampleStep = max(1, (yEnd - yStart) / 520)
        val expectedSpacing = bitmap.width / 7f
        val searchRadius = (expectedSpacing * 0.23f).toInt().coerceAtLeast(4)

        fun darknessScore(x: Int): Float {
            var dark = 0f
            var samples = 0
            var y = yStart
            while (y < yEnd) {
                val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                val luminance = (r * 30 + g * 59 + b * 11) / 100
                if (luminance < 175) dark += 1f
                samples++
                y += sampleStep
            }
            return if (samples == 0) 0f else dark / samples
        }

        val rulesBitmap = (1..6).map { divider ->
            val expected = (expectedSpacing * divider).toInt()
            val left = (expected - searchRadius).coerceAtLeast(1)
            val right = (expected + searchRadius).coerceAtMost(bitmap.width - 2)
            (left..right).maxByOrNull { x ->
                // Sum three adjacent columns so thin/anti-aliased pencil rules still win.
                darknessScore(x - 1) + darknessScore(x) + darknessScore(x + 1)
            } ?: expected
        }

        val minGap = bitmap.width * 0.085f
        if (rulesBitmap.zipWithNext().any { (a, b) -> b - a < minGap }) return emptyList()
        return rulesBitmap.map { it / sx }
    }

    private fun detectHorizontalRules(bitmap: Bitmap, assist: AssistData): List<List<Float>> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return List(7) { emptyList() }
        val geometry = columnGeometry(assist)
        val sx = bitmap.width.toFloat() / assist.imageWidth.coerceAtLeast(1)
        val sy = bitmap.height.toFloat() / assist.imageHeight.coerceAtLeast(1)

        return (0..6).map { column ->
            val (leftSource, rightSource) = geometry.bounds[column]
            val left = (leftSource * sx).toInt().coerceIn(0, bitmap.width - 1)
            val right = (rightSource * sx).toInt().coerceIn(left + 1, bitmap.width)
            val margin = ((right - left) * 0.08f).toInt()
            val x0 = (left + margin).coerceAtMost(right - 1)
            val x1 = (right - margin).coerceAtLeast(x0 + 1)
            val stepX = max(1, (x1 - x0) / 70)
            val hits = mutableListOf<Int>()

            var py = (bitmap.height * 0.055f).toInt()
            val maxY = (bitmap.height * 0.96f).toInt()
            while (py < maxY) {
                var dark = 0
                var total = 0
                var px = x0
                while (px < x1) {
                    val c = bitmap.getPixel(px, py)
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    if (luma < 165) dark++
                    total++
                    px += stepX
                }
                if (total > 0 && dark.toFloat() / total >= 0.46f) hits += py
                py += 2
            }

            // Collapse adjacent scan-line hits into one physical rule and map back to source coords.
            val groups = mutableListOf<MutableList<Int>>()
            hits.forEach { hit ->
                val last = groups.lastOrNull()
                if (last != null && hit - last.last() <= 6) last += hit else groups += mutableListOf(hit)
            }
            val centers = groups.map { group -> group.average().toFloat() / sy }
                .filter { it > assist.imageHeight * 0.05f && it < assist.imageHeight * 0.96f }
                .sorted()
            val collapsed = mutableListOf<Float>()
            centers.forEach { value ->
                if (collapsed.isEmpty() || value - collapsed.last() > assist.imageHeight * 0.018f) {
                    collapsed += value
                }
            }
            collapsed
        }
    }


    private data class StructuralTimeConsensus(
        val time: LocalTime,
        val supportColumns: Int,
        val agreementVotes: Int,
        val confidence: Float,
        val reason: String
    )

    /**
     * Uses the rota's repeated schedule structure as an independent time-recognition system.
     * OCR syntax alone is not considered confidence: a beautifully formatted but isolated `11:00`
     * can still be a hallucination of handwritten `9³⁰`. We therefore count agreement across
     * OCR passes and across weekday columns before allowing a local token to dominate.
     */
    private fun structuralTimeConsensusForTap(
        assist: AssistData,
        geometry: ColumnGeometry,
        clickedColumn: Int,
        tapY: Float,
        blockTop: Float,
        blockBottom: Float,
        candidates: List<AssistTimeCandidate>
    ): StructuralTimeConsensus? {
        if (candidates.isEmpty()) return null
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val rowTolerance = max(18f, h * 0.034f)

        // First schedule block is especially stable across a weekly rota and is where tiny 09:30
        // labels most often get hallucinated. Compare the earliest plausible label in each column.
        val isFirstBlock = blockTop <= h * 0.18f
        val evidence = mutableListOf<AssistTimeCandidate>()
        if (isFirstBlock) {
            for (column in 0..6) {
                val first = candidates
                    .filter { it.column == column && it.y in (h * 0.07f)..(h * 0.34f) }
                    .sortedWith(compareBy<AssistTimeCandidate> { it.y }.thenByDescending { if (it.strong) 1 else 0 })
                    .firstOrNull()
                if (first != null) evidence += first
            }
        } else {
            // For later blocks, gather labels whose physical row is close to the tapped block's
            // top. This is intentionally perspective-tolerant rather than requiring pixel-perfect
            // horizontal alignment across all seven columns.
            val targetY = blockTop
            for (column in 0..6) {
                val near = candidates
                    .filter { it.column == column }
                    .filter { it.y <= tapY + h * 0.015f }
                    .minByOrNull { abs(it.y - targetY) }
                    ?.takeIf { abs(it.y - targetY) <= max(rowTolerance * 2.0f, h * 0.075f) }
                if (near != null) evidence += near
            }
        }
        if (evidence.isEmpty()) return null

        data class Vote(val time: LocalTime, val columns: Int, val votes: Int, val strong: Int, val score: Float)
        val grouped = evidence.groupBy { it.time }.map { (time, values) ->
            val columns = values.map { it.column }.distinct().size
            val votes = values.size
            val strong = values.count { it.strong }
            val minuteBonus = if (time.minute != 0) 0.35f else 0f
            Vote(time, columns, votes, strong, columns * 3.2f + strong * 1.25f + votes * 0.35f + minuteBonus)
        }.sortedByDescending { it.score }
        val winner = grouped.firstOrNull() ?: return null
        val runner = grouped.getOrNull(1)
        val margin = winner.score - (runner?.score ?: 0f)
        val accepted = when {
            winner.columns >= 3 -> true
            winner.columns >= 2 && winner.strong >= 1 && margin >= 1.6f -> true
            isFirstBlock && winner.columns >= 2 && margin >= 2.2f -> true
            else -> false
        }
        if (!accepted) return null

        val confidence = (0.78f + (winner.columns - 2).coerceAtLeast(0) * 0.055f +
            winner.strong.coerceAtMost(4) * 0.025f + min(0.08f, margin * 0.015f)).coerceIn(0.80f, 0.985f)
        return StructuralTimeConsensus(
            time = winner.time,
            supportColumns = winner.columns,
            agreementVotes = winner.votes,
            confidence = confidence,
            reason = if (isFirstBlock) "weekly first-block consensus" else "weekly block-row consensus"
        )
    }

    private fun localTimeAgreement(
        assist: AssistData,
        candidates: List<AssistTimeCandidate>,
        chosen: AssistTimeCandidate
    ): Pair<Int, Int> {
        val yTolerance = assist.imageHeight * 0.030f
        val nearby = candidates.filter {
            it.column == chosen.column && it.time == chosen.time && abs(it.y - chosen.y) <= yTolerance
        }
        // Multiple OCR passes map to slightly different boxes. Distinct source strings and nearby
        // boxes are useful independent evidence even when all belong to the same weekday column.
        val passVotes = nearby.distinctBy { it.source to (it.y / max(4f, assist.imageHeight * 0.006f)).toInt() }.size
        val columns = candidates.filter {
            it.time == chosen.time && abs(it.y - chosen.y) <= assist.imageHeight * 0.045f
        }.map { it.column }.distinct().size
        return passVotes to columns
    }


    /**
     * Resolve the time label that actually owns the tapped employee row.
     *
     * Important reliability rule: a single OCR token is never enough to auto-lock a handwritten
     * start time.  The local hour must either repeat in another weekday column, repeat across OCR
     * passes, or agree with a repeated canonical schedule row.  This prevents one-off OCR errors
     * such as handwritten 09:30 -> 11:00 from becoming an uneditable result, while still allowing
     * repeated second-shift labels such as 16 to resolve to 16:00.
     */
    private fun localScheduleAnchorTime(
        assist: AssistData,
        candidates: List<AssistTimeCandidate>,
        column: Int,
        tapY: Float,
        blockTop: Float,
        blockBottom: Float
    ): StructuralTimeConsensus? {
        val maxGap = assist.imageHeight * 0.22f
        val pad = assist.imageHeight * 0.030f
        val local = candidates
            .filter { it.column == column }
            .filter { it.y <= tapY + assist.imageHeight * 0.010f }
            .filter { it.y >= blockTop - pad && it.y <= blockBottom + pad }
            .filter { (tapY - it.y).coerceAtLeast(0f) <= maxGap }
            .sortedByDescending { it.y }
        val anchor = local.firstOrNull() ?: return null

        val rowTolerance = max(24f, assist.imageHeight * 0.060f)
        val sameHour = candidates.filter {
            it.time.hour == anchor.time.hour && abs(it.y - anchor.y) <= rowTolerance
        }
        val hourColumns = sameHour.map { it.column }.distinct().size
        val exactGroups = sameHour.groupBy { it.time }.map { (time, values) ->
            val columns = values.map { it.column }.distinct().size
            val strong = values.count { it.strong }
            val score = columns * 10 + strong * 3 + values.size
            Triple(time, values, score)
        }.sortedByDescending { it.third }
        val exactWinner = exactGroups.firstOrNull()
        val exactColumns = exactWinner?.second?.map { it.column }?.distinct()?.size ?: 0

        val (passVotes, exactSupportColumns) = localTimeAgreement(assist, candidates, anchor)

        // Exact local time backed by another day or another OCR pass.
        if (anchor.strong && (exactSupportColumns >= 2 || passVotes >= 2)) {
            return StructuralTimeConsensus(
                anchor.time,
                max(exactSupportColumns, hourColumns),
                if (anchor.strong) 1 else 0,
                if (exactSupportColumns >= 2) 0.995f else 0.975f,
                "same-day time confirmed by repeated evidence"
            )
        }

        // For repeated two-digit handwritten hours (13/16/18), :00 is a safe structural
        // interpretation even if tiny superscript zeroes were missed.  One-off hours are NOT.
        if (anchor.time.hour >= 10 && hourColumns >= 2) {
            val resolved = if (exactWinner != null && exactColumns >= 2) exactWinner.first
                else LocalTime.of(anchor.time.hour, 0)
            return StructuralTimeConsensus(
                resolved,
                hourColumns,
                sameHour.count { it.strong },
                if (exactColumns >= 2) 0.98f else 0.94f,
                "repeated local hour across weekdays"
            )
        }

        // One-digit morning hours need minute evidence.  Never silently turn a bare 9 into 09:00.
        if (anchor.time.hour < 10 && exactWinner != null && exactColumns >= 2) {
            return StructuralTimeConsensus(
                exactWinner.first,
                exactColumns,
                exactWinner.second.count { it.strong },
                0.97f,
                "repeated morning time across weekdays"
            )
        }
        return null
    }


    /** v19: perspective-tolerant ordered block alignment shared by atlas, taps and semantics. */
    private fun structuralBlockStarts(assist: AssistData, column: Int): List<Float> =
        RotaGridModel.localStarts(assist, column)

    private fun structuralGlobalAnchors(assist: AssistData): List<Float> =
        RotaGridModel.globalAnchors(assist)

    private fun structuralBlockIndexForY(assist: AssistData, column: Int, y: Float): Int? =
        RotaGridModel.blockIndexForY(assist, column, y)

    private fun structuralTimeModel(
        assist: AssistData,
        geometry: ColumnGeometry,
        candidates: List<AssistTimeCandidate>
    ): List<RotaStructuralTimeEngine.Resolution> {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val observations = candidates.mapNotNull { candidate ->
            val block = candidate.blockHint ?: structuralBlockIndexForY(assist, candidate.column, candidate.y)
                ?: return@mapNotNull null
            // A crop's declared block is only trusted if its source coordinate still belongs
            // to that physical block. This catches stale/reconstructed atlas indices after
            // a missing horizontal grid rule shifts later blocks in one column.
            if (candidate.blockHint != null) {
                val physical = RotaGridModel.blockIndexForY(assist, candidate.column, candidate.y)
                val labelBounds = RotaGridModel.boundsForBlock(assist, candidate.column, candidate.blockHint)
                if (labelBounds == null) return@mapNotNull null
                val margin = h * 0.012f
                if (physical != null && physical != candidate.blockHint &&
                    candidate.y !in (labelBounds.first - margin)..(labelBounds.second + margin)) {
                    return@mapNotNull null
                }
            }
            val bounds = geometry.bounds.getOrNull(candidate.column) ?: return@mapNotNull null
            val columnWidth = (bounds.second - bounds.first).coerceAtLeast(1f)
            val relX = candidate.x - bounds.first
            if (relX < -columnWidth * 0.04f || relX > columnWidth * 0.42f) return@mapNotNull null

            // Atlas tokens already came from an isolated block-label crop. Whole-page OCR must
            // prove that the token is near the top of the same physical block before it can vote.
            if (candidate.tokenSource != TokenSource.TIME_ATLAS) {
                val blockBounds = RotaGridModel.boundsForBlock(assist, candidate.column, block) ?: return@mapNotNull null
                val top = blockBounds.first
                val bottom = blockBounds.second
                val fraction = (candidate.y - top) / (bottom - top).coerceAtLeast(h * 0.025f)
                if (fraction !in -0.10f..0.34f) return@mapNotNull null
            }
            RotaStructuralTimeEngine.Observation(
                blockIndex = block,
                column = candidate.column,
                time = candidate.time,
                confidence = candidate.confidence,
                strong = candidate.strong,
                atlas = candidate.tokenSource == TokenSource.TIME_ATLAS
            )
        }
        val priors = assist.semanticTimeBands.mapNotNull { band ->
            val block = band.blockIndex ?: return@mapNotNull null
            RotaStructuralTimeEngine.Prior(
                blockIndex = block,
                time = band.time,
                confidence = band.confidence,
                confirmations = band.confirmations,
                contradictions = band.contradictions
            )
        }
        return RotaStructuralTimeEngine.solve(observations, priors)
    }

    fun structuralTimeDiagnostics(assist: AssistData): List<String> {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return emptyList()
        val geometry = columnGeometry(assist)
        val candidates = collectAssistTimeCandidates(assist, geometry)
        val model: List<RotaStructuralTimeEngine.Resolution> = structuralTimeModel(assist, geometry, candidates)
        val lines: MutableList<String> = mutableListOf()
        model.forEach { row ->
            val state = if (row.ambiguous) "review" else "resolved"
            val alternatives = row.alternatives.drop(1).take(2).joinToString { alternative -> alternative.time.toString() }
            lines += buildString {
                append("B${row.blockIndex + 1} ${row.time} · ${row.supportColumns}d/${row.atlasColumns}a · ${(row.confidence * 100).toInt()}% · $state")
                if (alternatives.isNotBlank()) append(" · alt $alternatives")
            }
        }
        return lines
    }

    private fun structuralTimeResolutionForTap(
        assist: AssistData,
        geometry: ColumnGeometry,
        column: Int,
        tapY: Float,
        candidates: List<AssistTimeCandidate>
    ): RotaStructuralTimeEngine.Resolution? {
        // Automatic time ownership requires a confident physical block. Taps close to a
        // partially detected row boundary stay unresolved until explicitly confirmed.
        val block = RotaGridModel.confidentBlockForY(assist, column, tapY) ?: return null
        val result = structuralTimeModel(assist, geometry, candidates)
            .firstOrNull { it.blockIndex == block } ?: return null
        // Auto-use only independently supported block solutions. Ambiguous rows remain explicit
        // review items instead of falling back to a plausible but unrelated time.
        return result.takeIf {
            val trustedSingle = it.reason == "clear isolated block label" || it.reason == "confirmed learned block mapping"
            val repeatedAtlas = it.supportColumns >= 2 && it.atlasColumns >= 2 && it.confidence >= 0.72f
            // Two separately positioned, strong page-OCR labels for an identical full
            // time can resolve their *shared physical block* when a lone conflicting
            // label is far behind. No atlas crop is required for this narrow case.
            // Weak tokens and multiple OCR passes on one weekday must not qualify.
            val repeatedStrongPage = RotaStructuralTimeEngine.qualifiesRepeatedStrongPage(it)
            // Handwritten 13/16/18 labels frequently lose the tiny superscript 00. Three or more
            // weekday columns agreeing on the same full hour inside the same structural block is
            // stronger evidence than a single OCR confidence number. Morning one-digit hours do
            // not use this escape hatch because 9 must still distinguish 09:00 from 09:30.
            val repeatedFullHour = it.time.hour >= 10 && it.time.minute == 0 &&
                it.supportColumns >= 3 && it.confidence >= 0.68f
            // Even a mature prior is not fresh evidence on a different photograph. The solver
            // may rank it as a strong hypothesis, but automatic assignment still requires at
            // least one independent label from the current image.
            !it.ambiguous && (it.supportColumns >= 1 || it.atlasColumns >= 1) &&
                (it.confidence >= 0.76f || repeatedAtlas || repeatedFullHour || repeatedStrongPage) &&
                (it.supportColumns >= 2 || it.atlasColumns >= 2 || trustedSingle)
        }
    }

    private data class OwnedRowTimeResolution(
        val time: LocalTime,
        val confidence: Float,
        val supportColumns: Int,
        val reason: String
    )

    /**
     * v17.2 precision-first time ownership resolver.
     *
     * A handwriting match may only inherit a time from the narrow label corridor immediately
     * above its own physical row. Evidence from older rows is deliberately ineligible even when
     * OCR confidence is high. When the local label is unreadable, only same-height evidence from
     * neighbouring weekday columns may rescue it. Otherwise the caller must stay unresolved.
     */
    private fun ownedRowTimeResolution(
        assist: AssistData,
        geometry: ColumnGeometry,
        column: Int,
        tapY: Float,
        blockTop: Float,
        blockBottom: Float,
        candidates: List<AssistTimeCandidate>
    ): OwnedRowTimeResolution? {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val blockHeight = (blockBottom - blockTop).coerceAtLeast(h * 0.035f)

        fun isLeftLabelZone(candidate: AssistTimeCandidate): Boolean {
            val dayBounds = geometry.bounds.getOrNull(candidate.column) ?: return false
            val width = (dayBounds.second - dayBounds.first).coerceAtLeast(1f)
            val relX = candidate.x - dayBounds.first
            return relX >= -width * 0.04f && relX <= width * 0.40f
        }

        // Keep the owned label corridor intentionally narrow: employee names must never vote.
        val corridorTop = blockTop - min(h * 0.010f, blockHeight * 0.08f)
        val corridorBottom = minOf(
            tapY + h * 0.006f,
            blockTop + max(h * 0.028f, blockHeight * 0.34f)
        )
        if (corridorBottom <= corridorTop) return null

        val targetY = blockTop + blockHeight * 0.10f
        val local = candidates
            .filter { it.column == column && isLeftLabelZone(it) }
            .filter { it.y in corridorTop..corridorBottom }
            .sortedWith(
                compareByDescending<AssistTimeCandidate> { if (it.strong) 1 else 0 }
                    .thenByDescending { it.confidence }
                    .thenBy { abs(it.y - targetY) }
            )

        val localWinner = local.groupBy { it.time }
            .map { (time, values) ->
                val best = values.maxOf { it.confidence }
                val independent = values.distinctBy { it.source }.size
                Triple(time, best, independent)
            }
            .sortedWith(
                compareByDescending<Triple<LocalTime, Float, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull()

        if (localWinner != null && (localWinner.second >= 0.78f || localWinner.third >= 2)) {
            return OwnedRowTimeResolution(
                time = localWinner.first,
                confidence = (0.86f + (localWinner.second - 0.70f).coerceAtLeast(0f) * 0.35f +
                    (localWinner.third - 1).coerceAtLeast(0) * 0.025f).coerceIn(0.86f, 0.995f),
                supportColumns = 1,
                reason = "same-column owned row label"
            )
        }

        // Rescue a missed local label only from the same visual row in neighbouring weekdays.
        // The tight tolerance is deliberate: 13:00 and 16:00 must never merge into one cluster.
        val rowCenter = if (local.isNotEmpty()) local.map { it.y }.average().toFloat() else targetY
        val yTolerance = max(h * 0.014f, min(h * 0.030f, blockHeight * 0.28f))
        val neighbourEvidence = candidates
            .filter { isLeftLabelZone(it) }
            .filter { candidate ->
                candidate.y <= tapY + h * 0.006f && abs(candidate.y - rowCenter) <= yTolerance
            }
            .groupBy { it.column }
            .mapNotNull { (_, values) ->
                values.maxWithOrNull(
                    compareBy<AssistTimeCandidate> { if (it.strong) 1 else 0 }
                        .thenBy { it.confidence }
                )
            }

        if (neighbourEvidence.size < 2) return null

        data class Vote(val time: LocalTime, val columns: Int, val strong: Int, val score: Float)
        val votes = neighbourEvidence.groupBy { it.time }.map { (time, values) ->
            val columns = values.map { it.column }.distinct().size
            val strong = values.count { it.strong }
            val confidence = values.sumOf { it.confidence.toDouble() }.toFloat()
            Vote(time, columns, strong, columns * 2.4f + strong * 0.9f + confidence)
        }.sortedByDescending { it.score }
        val winner = votes.firstOrNull() ?: return null
        val runner = votes.getOrNull(1)
        val margin = winner.score - (runner?.score ?: 0f)

        if (winner.columns < 2 || (winner.strong == 0 && margin < 1.25f)) return null
        if (runner != null && margin < 0.70f) return null

        return OwnedRowTimeResolution(
            time = winner.time,
            confidence = (0.82f + winner.columns.coerceAtMost(5) * 0.03f +
                winner.strong.coerceAtMost(4) * 0.025f + min(0.07f, margin * 0.012f)).coerceIn(0.84f, 0.99f),
            supportColumns = winner.columns,
            reason = "same-height owned row consensus"
        )
    }

    private data class BlockTimeResolution(
        val time: LocalTime,
        val confidence: Float,
        val supportColumns: Int,
        val reason: String
    )

    /**
     * Deterministic schedule-block resolver. Instead of asking which OCR token happens to be
     * closest to the employee name, map the tap to a physical block index and aggregate the
     * start-time evidence for that same block across the week. This prevents a 16:00 employee
     * from inheriting the 09:30/11:00 label from another block.
     */
    private fun blockTimeResolution(
        assist: AssistData,
        column: Int,
        tapY: Float,
        candidates: List<AssistTimeCandidate>
    ): BlockTimeResolution? {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val localRules = assist.rowBoundaries.getOrNull(column).orEmpty().sorted()
        if (localRules.isEmpty()) return null
        val localEdges = listOf(h * 0.055f) + localRules + listOf(h * 0.955f)
        val blockIndex = (0 until localEdges.lastIndex).firstOrNull { tapY >= localEdges[it] && tapY < localEdges[it + 1] } ?: return null

        data class Evidence(val candidate: AssistTimeCandidate, val distance: Float)
        val evidence = mutableListOf<Evidence>()
        for (day in 0..6) {
            val rules = assist.rowBoundaries.getOrNull(day).orEmpty().sorted()
            val edges = listOf(h * 0.055f) + rules + listOf(h * 0.955f)
            if (blockIndex >= edges.lastIndex) continue
            val top = edges[blockIndex]
            val bottom = edges[blockIndex + 1]
            val target = top + (bottom - top) * 0.08f
            val pool = candidates.filter { it.column == day && it.y in (top - h * 0.018f)..(bottom * 1f) }
            val best = pool.minByOrNull { abs(it.y - target) } ?: continue
            // Reject a token sitting deep in the block; it is probably a name/digit, not the block label.
            if (best.y > top + (bottom - top) * 0.42f) continue
            evidence += Evidence(best, abs(best.y - target) / h)
        }
        if (evidence.isEmpty()) return null

        // Vote by exact time first, then by hour for two-digit :00 handwritten labels.
        data class Vote(val time: LocalTime, val columns: Int, val strong: Int, val votes: Int, val score: Float)
        val exact = evidence.groupBy { it.candidate.time }.map { (time, values) ->
            val cols = values.map { it.candidate.column }.distinct().size
            val strong = values.count { it.candidate.strong }
            val distancePenalty = values.fold(0f) { total, value -> total + value.distance } * 2.2f
            Vote(time, cols, strong, values.size, cols * 4.2f + strong * 1.6f + values.size * 0.4f - distancePenalty + if (time.minute != 0) 0.35f else 0f)
        }.sortedByDescending { it.score }
        val winner = exact.firstOrNull() ?: return null
        val runner = exact.getOrNull(1)
        val margin = winner.score - (runner?.score ?: 0f)

        if (winner.columns >= 2 && (winner.strong >= 1 || margin >= 2.0f)) {
            return BlockTimeResolution(
                winner.time,
                (0.82f + winner.columns * 0.035f + winner.strong * 0.025f + min(0.07f, margin * 0.012f)).coerceIn(0.84f, 0.995f),
                winner.columns,
                "same schedule block across weekdays"
            )
        }

        // If exact minute OCR is weak, repeated two-digit hours can still establish 13/16/18 :00.
        val hours = evidence.groupBy { it.candidate.time.hour }.map { (hour, values) ->
            val cols = values.map { it.candidate.column }.distinct().size
            val strong = values.count { it.candidate.strong }
            Triple(hour, cols, strong)
        }.sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.second }.thenByDescending { it.third })
        val hWinner = hours.firstOrNull()
        if (hWinner != null && hWinner.first >= 10 && hWinner.second >= 2) {
            return BlockTimeResolution(LocalTime.of(hWinner.first, 0), 0.88f + min(0.07f, hWinner.second * 0.015f), hWinner.second, "repeated handwritten hour in same block")
        }
        return null
    }

    fun fuseEvidence(
        identity: Float,
        geometry: Float,
        time: Float,
        template: Float,
        consistency: Float,
        contradictionPenalty: Float = 0f
    ): EvidenceBreakdown {
        val i = identity.coerceIn(0f, 1f)
        val g = geometry.coerceIn(0f, 1f)
        val t = time.coerceIn(0f, 1f)
        val tp = template.coerceIn(0f, 1f)
        val c = consistency.coerceIn(0f, 1f)
        // Identity can propose a row, but geometry + time must independently agree before the
        // result can look "verified". This prevents one confident OCR hallucination from owning
        // the answer. Template evidence is intentionally a prior, never a hard rule.
        var fused = i * 0.28f + g * 0.27f + t * 0.27f + c * 0.13f + tp * 0.05f
        if (g < 0.42f || t < 0.42f) fused = min(fused, 0.79f)
        if (i < 0.45f && g < 0.70f) fused = min(fused, 0.72f)
        fused = (fused - contradictionPenalty.coerceIn(0f, 0.55f)).coerceIn(0f, 1f)
        return EvidenceBreakdown(i, g, t, tp, c, contradictionPenalty, fused)
    }

    private fun templatePrior(assist: AssistData, time: LocalTime? = null): Float {
        var score = if (assist.templateFingerprint.isNotBlank()) 0.58f else 0.40f
        if (time != null && assist.learnedTimeVocabulary.isNotEmpty()) {
            score += if (time in assist.learnedTimeVocabulary) 0.30f else -0.10f
        }
        if (assist.quality.score >= 0.65f) score += 0.08f
        return score.coerceIn(0f, 1f)
    }

    fun draftFromTap(
        assist: AssistData,
        tapX: Float,
        tapY: Float,
        typicalShiftHours: Int,
        fallbackWeekStart: LocalDate,
        today: LocalDate = LocalDate.now(),
        lockedWeekStart: LocalDate? = null
    ): Draft? {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return null
        val x = tapX.coerceIn(0f, assist.imageWidth.toFloat())
        val y = tapY.coerceIn(0f, assist.imageHeight.toFloat())
        val geometry = columnGeometry(assist)
        val clickedColumn = columnIndexForX(assist, x)

        val rotaWeekStart = lockedWeekStart?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ?: resolveRotaWeekStart(assist, fallbackWeekStart, today)
        val date = rotaWeekStart.plusDays(clickedColumn.toLong())
        val candidates = collectAssistTimeCandidates(assist, geometry)

        // Determine the visual schedule block containing the tapped name.  A rota time label
        // belongs to the block that starts at the closest horizontal rule above the employee.
        // This is substantially more reliable than "nearest OCR text" on handwritten grids.
        val columnRules = assist.rowBoundaries.getOrNull(clickedColumn).orEmpty().sorted()
        val blockStart = columnRules.filter { it < y - assist.imageHeight * 0.006f }.maxOrNull()
        val blockEnd = columnRules.filter { it > y + assist.imageHeight * 0.006f }.minOrNull()
        val blockTop = blockStart ?: (assist.imageHeight * 0.055f)
        val blockBottom = blockEnd ?: (assist.imageHeight * 0.955f)
        val blockPad = assist.imageHeight * 0.024f

        // v18 authoritative path: resolve the physical block globally before looking at any
        // employee-local OCR.  The same block model is shared by every employee in the row.
        structuralTimeResolutionForTap(
            assist = assist,
            geometry = geometry,
            column = clickedColumn,
            tapY = y,
            candidates = candidates
        )?.let { structural ->
            val start = LocalDateTime.of(date, structural.time)
            return Draft(
                start = start,
                end = start.plusHours(typicalShiftHours.coerceIn(1, 16).toLong()),
                sourceLine = "Tap-assisted import · ${date.format(SHORT_DATE)} · ${structural.reason} (block ${structural.blockIndex + 1}, ${structural.supportColumns} day support) · end estimated +${typicalShiftHours}h",
                confidence = structural.confidence,
                estimatedEnd = true,
                columnIndex = clickedColumn,
                origin = DraftOrigin.HANDWRITING,
                requiresTimeConfirmation = structural.confidence < 0.88f
            )
        }

        // v20.5: an ambiguous structural row must remain unresolved. Older local / semantic
        // strategies can still inform the manual time choices, but may not silently turn
        // a handwritten name into an apparently verified planner shift.
        return null
    }

    fun timeSuggestionsForTap(assist: AssistData, tapX: Float, tapY: Float): List<TimeSuggestion> {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return emptyList()
        val x = tapX.coerceIn(0f, assist.imageWidth.toFloat())
        val y = tapY.coerceIn(0f, assist.imageHeight.toFloat())
        val geometry = columnGeometry(assist)
        val column = columnIndexForX(assist, x)
        val rules = assist.rowBoundaries.getOrNull(column).orEmpty().sorted()
        val blockTop = rules.filter { it < y - assist.imageHeight * 0.006f }.maxOrNull()
            ?: assist.imageHeight * 0.055f
        val blockBottom = rules.filter { it > y + assist.imageHeight * 0.006f }.minOrNull()
            ?: assist.imageHeight * 0.955f
        val candidates = collectAssistTimeCandidates(assist, geometry)

        structuralTimeResolutionForTap(assist, geometry, column, y, candidates)?.let { structural ->
            return listOf(TimeSuggestion(structural.time, 6.0f + structural.confidence, structural.reason))
        }

        // Automatic preview and draft creation share exactly one authoritative block solver.
        // An unsolved block should not advertise a time borrowed from another column/row.
        return emptyList()
    }

    /**
     * Common start times detected anywhere on the rota, ordered by evidence. Used as a
     * human-in-the-loop fallback when handwriting OCR cannot resolve one particular cell.
     * This keeps the workflow fast without ever inventing a time silently.
     */
    fun suggestedTimes(assist: AssistData): List<LocalTime> {
        val geometry = columnGeometry(assist)
        val detected = collectAssistTimeCandidates(assist, geometry)
            .groupBy { it.time }
            .map { (time, values) ->
                val columns = values.map { it.column }.distinct().size
                val strong = values.count { it.strong }
                Triple(time, columns, strong)
            }
            .sortedWith(
                compareByDescending<Triple<LocalTime, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first }
            )
            .map { it.first }
        // Learned vocabulary is a fallback/prior, not a replacement for evidence on this image.
        return (detected + assist.learnedTimeVocabulary).distinct().take(8)
    }

    /**
     * Best-effort recognition for a time label the user taps directly. This is deliberately
     * local: only strong time candidates in the tapped day column and close to the tapped
     * coordinates are considered. The caller still confirms the result before saving.
     */
    fun timeFromTap(assist: AssistData, tapX: Float, tapY: Float): LocalTime? {
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) return null
        val geometry = columnGeometry(assist)
        val column = columnIndexForX(assist, tapX)
        val physicalBlock = RotaGridModel.blockIndexForY(assist, column, tapY) ?: return null
        val blockBounds = RotaGridModel.boundsForBlock(assist, column, physicalBlock) ?: return null
        val dayBounds = geometry.bounds.getOrNull(column) ?: return null
        val width = (dayBounds.second - dayBounds.first).coerceAtLeast(1f)
        val allCandidates = collectAssistTimeCandidates(assist, geometry)
        // A label tap may use the already-solved global time for this SAME block.
        structuralTimeResolutionForTap(assist, geometry, column, tapY, allCandidates)?.let { return it.time }
        // Otherwise only the physical time-label corner of the tapped block is eligible.
        // Never substitute the nearest hour from another row, even for an explicit tap.
        val labelBottom = blockBounds.first + (blockBounds.second - blockBounds.first) * 0.36f
        if (tapX !in (dayBounds.first - width * 0.02f)..(dayBounds.first + width * 0.48f) ||
            tapY !in (blockBounds.first - assist.imageHeight * 0.012f)..labelBottom) return null
        val local = allCandidates.filter { candidate ->
            val owned = candidate.blockHint ?: RotaGridModel.blockIndexForY(assist, column, candidate.y)
            candidate.column == column && owned == physicalBlock && candidate.strong &&
                candidate.x <= dayBounds.first + width * 0.48f &&
                candidate.y in (blockBounds.first - assist.imageHeight * 0.012f)..labelBottom &&
                abs(candidate.y - tapY) <= assist.imageHeight * 0.027f &&
                abs(candidate.x - tapX) <= width * 0.42f
        }.sortedByDescending { it.confidence }
        val first = local.firstOrNull() ?: return null
        // Distinct, similarly plausible readings of the SAME crop indicate ambiguity.
        if (local.any { it.time != first.time && it.confidence >= first.confidence - 0.12f }) return null
        return first.time
    }

    private fun collectAssistTimeCandidates(assist: AssistData, geometry: ColumnGeometry): List<AssistTimeCandidate> {
        val direct = mutableListOf<AssistTimeCandidate>()
        assist.tokens.forEach { token ->
            // Focused header OCR exists only to strengthen document-date authority. Never allow
            // its isolated day/month fragments to masquerade as shift hours.
            if (token.source == TokenSource.HEADER_FOCUS) return@forEach
            if (token.cy < assist.imageHeight * 0.065f || token.cy > assist.imageHeight * 0.94f) return@forEach
            // Never let a genuine header date participate as a shift time.  v19.2 used a broad
            // "top 20% + any day-like number" rule here, which accidentally discarded real
            // morning labels such as 9³⁰ because the hour "9" also looks like a calendar day.
            // Only explicit date-shaped text inside the actual header corridor is excluded.
            val headerBottom = headerDateZoneBottom(assist)
            if (token.cy <= headerBottom && looksLikeExplicitHeaderDate(token.text)) return@forEach
            val column = columnIndexForX(assist, token.cx)
            val bounds = geometry.bounds[column]
            val width = (bounds.second - bounds.first).coerceAtLeast(1f)
            val relativeX = token.cx - bounds.first
            // Time labels are almost always in the left half of a rota cell.
            if (relativeX > width * 0.62f) return@forEach

            val decision = RotaTimeRecognitionEngine.analyze(token.text, assist.learnedTimeVocabulary)
            val hypotheses = listOfNotNull(decision.best, decision.runnerUp)
            if (hypotheses.isNotEmpty()) {
                hypotheses.forEachIndexed { index, hypothesis ->
                    val time = hypothesis.time
                    if (time.hour in 5..23 && time.minute in setOf(0, 15, 30, 45)) {
                        val reliable = index == 0 && !decision.ambiguous && hypothesis.score >= 0.72f
                        val ambiguityPenalty = if (decision.ambiguous) 0.12f else 0f
                        val runnerPenalty = if (index > 0) 0.18f else 0f
                        direct += AssistTimeCandidate(
                            time, column, token.cx, token.cy,
                            "${token.text} · ${hypothesis.reason}",
                            strong = reliable,
                            confidence = (hypothesis.score - ambiguityPenalty - runnerPenalty + if (token.source == TokenSource.TIME_ATLAS) 0.08f else 0f).coerceIn(0.18f, 0.99f),
                            alternate = index > 0,
                            tokenSource = token.source,
                            blockHint = token.blockHint
                        )
                    }
                }
            } else parseHourOnly(token.text)?.let { time ->
                if (time.hour in 5..23) direct += AssistTimeCandidate(time, column, token.cx, token.cy, token.text, strong = false, confidence = if (token.source == TokenSource.TIME_ATLAS) 0.46f else 0.38f, tokenSource = token.source, blockHint = token.blockHint)
            }
        }

        // Reconstruct split handwritten labels such as an OCR element "9" followed by a
        // superscript "30". ML Kit often recognizes the two pieces independently.
        val pairCandidates = mutableListOf<AssistTimeCandidate>()
        val numericTokens = assist.tokens.mapNotNull { token ->
            if (token.source == TokenSource.HEADER_FOCUS) return@mapNotNull null
            val cleaned = normalizeTimeDigits(token.text).filter(Char::isDigit)
            if (cleaned.isEmpty()) null else token to cleaned
        }
        numericTokens.forEach { (hourToken, hourDigits) ->
            if (hourDigits.length !in 1..2) return@forEach
            val hour = hourDigits.toIntOrNull() ?: return@forEach
            if (hour !in 5..23) return@forEach
            val column = columnIndexForX(assist, hourToken.cx)
            val bounds = geometry.bounds[column]
            val width = (bounds.second - bounds.first).coerceAtLeast(1f)
            if (hourToken.cx - bounds.first > width * 0.50f) return@forEach

            val hourBlock = hourToken.blockHint ?: structuralBlockIndexForY(assist, column, hourToken.cy)
            val minute = numericTokens
                .filter { (token, digits) ->
                    if (token === hourToken || columnIndexForX(assist, token.cx) != column) return@filter false
                    if (digits !in setOf("00", "15", "30", "45")) return@filter false
                    val minuteBlock = token.blockHint ?: structuralBlockIndexForY(assist, column, token.cy)
                    if (hourBlock != null && minuteBlock != null && hourBlock != minuteBlock) return@filter false
                    val hourHeight = (hourToken.bottom - hourToken.top).coerceAtLeast(1)
                    val minuteHeight = (token.bottom - token.top).coerceAtLeast(1)
                    val superscriptLike = token.cy <= hourToken.cy + hourHeight * 0.40f && minuteHeight <= hourHeight * 1.35f
                    token.left >= hourToken.left && superscriptLike &&
                        abs(token.cy - hourToken.cy) <= assist.imageHeight * 0.025f &&
                        token.cx - hourToken.cx in 0f..(width * 0.36f)
                }
                .minByOrNull { (token, _) -> abs(token.cx - hourToken.cx) + abs(token.cy - hourToken.cy) }

            if (minute != null) {
                val m = minute.second.toInt()
                pairCandidates += AssistTimeCandidate(
                    LocalTime.of(hour, m),
                    column,
                    (hourToken.cx + minute.first.cx) / 2f,
                    min(hourToken.cy, minute.first.cy),
                    "${hour}:${m.toString().padStart(2, '0')} (split OCR)",
                    strong = true,
                    confidence = if (hourToken.source == TokenSource.TIME_ATLAS || minute.first.source == TokenSource.TIME_ATLAS) 0.96f else 0.90f,
                    tokenSource = if (hourToken.source == TokenSource.TIME_ATLAS || minute.first.source == TokenSource.TIME_ATLAS) TokenSource.TIME_ATLAS else TokenSource.PAGE_OCR,
                    blockHint = hourBlock ?: hourToken.blockHint ?: minute.first.blockHint
                )
            }
        }

        // v18.1: Compose time fragments only within the same isolated atlas cell. ML Kit often
        // returns handwritten 16⁰⁰ as separate elements ("16" + "00") or 9³⁰ as "9" + "30".
        // Because TIME_ATLAS tokens already carry day + physical block identity, concatenating
        // neighbouring fragments here cannot borrow digits from employee names or another row.
        val atlasCompositeCandidates = mutableListOf<AssistTimeCandidate>()
        assist.tokens
            .filter { it.source == TokenSource.TIME_ATLAS && it.blockHint != null }
            .groupBy { Triple(columnIndexForX(assist, it.cx), it.blockHint!!, it.source) }
            .forEach { (key, blockTokens) ->
                val column = key.first
                val block = key.second
                val ordered = blockTokens.sortedBy { it.left }
                val variants = linkedSetOf<String>()
                ordered.forEach { token -> variants += token.text.trim() }
                for (i in ordered.indices) {
                    for (len in 2..3) {
                        val end = i + len
                        if (end <= ordered.size) {
                            val slice = ordered.subList(i, end)
                            val maxGap = slice.zipWithNext().maxOfOrNull { (a, b) -> b.left - a.right } ?: 0
                            val cellWidth = (geometry.bounds[column].second - geometry.bounds[column].first).coerceAtLeast(1f)
                            if (maxGap <= cellWidth * 0.20f) {
                                variants += slice.joinToString("") { it.text.trim() }
                                variants += slice.joinToString(":") { it.text.trim() }
                            }
                        }
                    }
                }
                for (raw in variants) {
                    val decision = RotaTimeRecognitionEngine.analyze(raw, assist.learnedTimeVocabulary)
                    val hypothesis = decision.best ?: continue
                    val t = hypothesis.time
                    if (t.hour in 5..23 && t.minute in setOf(0, 15, 30, 45) && hypothesis.score >= 0.58f) {
                        val x = ordered.map { it.cx }.average().toFloat()
                        val y = ordered.minOfOrNull { it.cy } ?: 0f
                        atlasCompositeCandidates += AssistTimeCandidate(
                            time = t, column = column, x = x, y = y,
                            source = "$raw · atlas block composite",
                            strong = !decision.ambiguous && hypothesis.score >= 0.72f,
                            confidence = (hypothesis.score + 0.10f).coerceIn(0.30f, 0.99f),
                            alternate = false, tokenSource = TokenSource.TIME_ATLAS, blockHint = block
                        )
                    }
                }
            }

        val merged = (direct + pairCandidates + atlasCompositeCandidates)
            .distinctBy { listOf(it.time.toString(), it.column, it.blockHint ?: -1, (it.y / 10f).toInt(), it.source) }

        // When a focused OCR pass reconstructs 9 + 30 into 09:30, discard the weak 09:00
        // hour-only candidate occupying the same physical label. Otherwise the weak token can
        // win simply because its bounding box sits a few pixels lower.
        return merged.filterNot { weak ->
            !weak.strong && merged.any { strong ->
                strong.strong && strong.column == weak.column && strong.time.hour == weak.time.hour &&
                    abs(strong.y - weak.y) <= assist.imageHeight * 0.025f
            }
        }.sortedBy { it.y }
    }

    /**
     * Reconstruct the rota's start-time vocabulary from repeated physical rows.
     *
     * A single OCR token is intentionally not enough to create a canonical row. This prevents
     * corruptions such as handwritten 09:30 being read as 11:00 from becoming authoritative.
     * Repeated evidence in different weekday columns is much more trustworthy than one token.
     */
    private fun canonicalTimeRows(assist: AssistData, geometry: ColumnGeometry): List<CanonicalTimeRow> {
        val source: List<AssistTimeCandidate> = collectAssistTimeCandidates(assist, geometry)
            .filter { candidate -> candidate.y >= assist.imageHeight * 0.075f && candidate.y <= assist.imageHeight * 0.92f }
            .distinctBy { candidate ->
                listOf(candidate.column, candidate.time.toString(), (candidate.y / max(8f, assist.imageHeight * 0.012f)).toInt())
            }
        if (source.isEmpty()) return emptyList()

        val rowTolerance = max(20f, assist.imageHeight * 0.032f)
        val rows: MutableList<MutableList<AssistTimeCandidate>> = mutableListOf()
        source.sortedBy { candidate -> candidate.y }.forEach { candidate ->
            val bestRow: MutableList<AssistTimeCandidate>? = rows.minByOrNull { row ->
                abs(row.map { item -> item.y }.average().toFloat() - candidate.y)
            }
            val center: Float? = bestRow?.map { item -> item.y }?.average()?.toFloat()
            if (bestRow != null && center != null && abs(center - candidate.y) <= rowTolerance) {
                bestRow += candidate
            } else {
                rows += mutableListOf(candidate)
            }
        }

        val canonicalRows: MutableList<CanonicalTimeRow> = mutableListOf()
        for (row in rows) {
            val distinctColumns = row.map { candidate -> candidate.column }.distinct().size
            if (distinctColumns < 2) continue

            val groupedByTime: Map<LocalTime, List<AssistTimeCandidate>> = row.groupBy { candidate -> candidate.time }
            val votes: MutableList<CanonicalVote> = mutableListOf()
            groupedByTime.forEach { (time, values) ->
                val groupedByColumn: Map<Int, List<AssistTimeCandidate>> = values.groupBy { candidate -> candidate.column }
                val byColumn: MutableMap<Int, AssistTimeCandidate> = linkedMapOf()
                groupedByColumn.forEach { (column, perColumn) ->
                    val best: AssistTimeCandidate? = perColumn.maxByOrNull { candidate -> candidate.confidence }
                    if (best != null) byColumn[column] = best
                }

                val columns = byColumn.size
                val strong = byColumn.values.count { candidate -> candidate.strong }
                val evidence = byColumn.values.sumOf { candidate -> candidate.confidence.toDouble() }.toFloat()
                val minuteSpecific = if (time.minute != 0) 0.55f else 0f
                val learnedPrior = if (time in assist.learnedTimeVocabulary) 0.55f else 0f
                val alternatePenalty = byColumn.values.count { candidate -> candidate.alternate } * 0.20f
                val score = evidence * 3.1f + columns * 1.55f + strong * 1.25f + minuteSpecific + learnedPrior - alternatePenalty
                votes += CanonicalVote(time, score, columns, strong, evidence)
            }
            votes.sortByDescending { vote -> vote.score }
            if (votes.isEmpty()) continue

            val winner: CanonicalVote = votes[0]
            val runner: CanonicalVote? = votes.getOrNull(1)
            val margin = winner.score - (runner?.score ?: 0f)
            val accepted =
                (winner.columns >= 2 && winner.evidence >= 1.15f && margin >= 0.55f) ||
                    (distinctColumns >= 3 && margin >= 2.2f && winner.evidence >= 0.82f)
            if (!accepted) continue

            val winningY: List<Float> = row.filter { candidate -> candidate.time == winner.time }.map { candidate -> candidate.y }
            if (winningY.isEmpty()) continue
            val centerY = winningY.average().toFloat()
            val confidence = (
                0.70f + (winner.columns.coerceAtMost(5) - 1) * 0.055f +
                    winner.strong.coerceAtMost(4) * 0.025f +
                    min(0.08f, margin * 0.012f) + min(0.06f, winner.evidence * 0.018f)
                ).coerceIn(0.72f, 0.98f)
            canonicalRows += CanonicalTimeRow(centerY, winner.time, winner.columns, winner.strong, confidence)
        }
        return canonicalRows.sortedBy { row -> row.y }
    }

    /** Nearest repeated schedule row above/at the selected employee name. */
    private fun canonicalTimeForTap(
        assist: AssistData,
        geometry: ColumnGeometry,
        tapY: Float,
        blockTop: Float,
        blockBottom: Float
    ): CanonicalTimeRow? {
        val pad = assist.imageHeight * 0.032f
        val rows = canonicalTimeRows(assist, geometry)
        val inBlock = rows
            .filter { it.y >= blockTop - pad && it.y <= min(blockBottom + pad, tapY + assist.imageHeight * 0.015f) }
            .minByOrNull { abs(tapY - it.y) }
            ?.takeIf { abs(tapY - it.y) <= assist.imageHeight * 0.34f }
        if (inBlock != null) return inBlock

        // Grid-line detection occasionally misses the top morning block. In that case the old
        // resolver had no canonical row and an isolated OCR hallucination (e.g. 09:30 -> 11:00)
        // could become the top suggestion. Fall back to the nearest well-supported repeated row
        // above the employee name. A repeated row is safer than one local OCR token.
        return rows
            .filter { it.y <= tapY + assist.imageHeight * 0.012f && it.supportColumns >= 2 }
            .minByOrNull { abs(tapY - it.y) }
            ?.takeIf { abs(tapY - it.y) <= assist.imageHeight * 0.30f }
    }

    /** Returns a geometry-derived date for a tap even when OCR cannot resolve its time. */
    fun dateFromTap(
        assist: AssistData,
        tapX: Float,
        fallbackWeekStart: LocalDate,
        today: LocalDate = LocalDate.now(),
        lockedWeekStart: LocalDate? = null
    ): LocalDate {
        val column = columnIndexForX(assist, tapX)
        val week = lockedWeekStart?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ?: resolveRotaWeekStart(assist, fallbackWeekStart, today)
        return week.plusDays(column.toLong())
    }

    /**
     * Geometry-independent document-date fallback. Handwritten header OCR can recover the visible
     * DD.MM sequence while misplacing individual boxes; in that case the textual order is still
     * enough to identify the rota week. The planner week is never allowed to beat three coherent
     * consecutive explicit dates.
     */
    fun detectedWeekStartFromText(
        rawText: String,
        fallbackWeekStart: LocalDate,
        today: LocalDate = LocalDate.now()
    ): LocalDate = RotaDateAuthorityEngine.resolveTextSequence(rawText, today, fallbackWeekStart).weekStart

    /**
     * v20.4 single date-authority result used by both analysis and review UI.  Focused header
     * geometry wins when strong; ordered text-sequence recovery is the backup when OCR boxes are
     * poor.  Returning the confidence/authority flag prevents a planner fallback from being shown
     * as a verified document date.
     */
    fun documentWeekResolution(
        assist: AssistData,
        rawText: String,
        fallbackWeekStart: LocalDate,
        today: LocalDate = LocalDate.now()
    ): RotaDateAuthorityEngine.Resolution {
        val fallbackMonday = fallbackWeekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (assist.imageWidth <= 0 || assist.imageHeight <= 0) {
            return RotaDateAuthorityEngine.resolveTextSequence(rawText, today, fallbackMonday)
        }
        val headerBottomRatio = headerDateZoneBottom(assist) / assist.imageHeight.coerceAtLeast(1).toFloat()
        val explicitBottomRatio = max(headerBottomRatio, 0.255f)
        val geometryTokens = assist.tokens.map { token ->
            RotaDateAuthorityEngine.Token(
                text = token.text,
                column = columnIndexForX(assist, token.cx),
                yRatio = token.cy / assist.imageHeight.coerceAtLeast(1).toFloat()
            )
        }
        val geometry = RotaDateAuthorityEngine.resolve(
            tokens = geometryTokens,
            today = today,
            fallbackWeekStart = fallbackMonday,
            explicitDateBottomRatio = explicitBottomRatio,
            weakDateBottomRatio = headerBottomRatio
        )
        val text = RotaDateAuthorityEngine.resolveTextSequence(rawText, today, fallbackMonday)
        return when {
            geometry.authoritative && !text.authoritative -> geometry
            text.authoritative && !geometry.authoritative -> text
            geometry.authoritative && text.authoritative -> if (geometry.confidence >= text.confidence) geometry else text
            geometry.confidence >= 0.84f && geometry.confidence >= text.confidence -> geometry
            text.confidence >= 0.90f -> text
            else -> RotaDateAuthorityEngine.Resolution(
                weekStart = fallbackMonday,
                confidence = maxOf(geometry.confidence, text.confidence).coerceAtMost(0.69f),
                evidenceCount = maxOf(geometry.evidenceCount, text.evidenceCount),
                explicitCount = maxOf(geometry.explicitCount, text.explicitCount),
                authoritative = false,
                reason = "planner fallback; document date evidence unresolved"
            )
        }
    }

    /** Best on-device week estimate. The UI always allows the user to override this once. */
    fun detectedWeekStart(assist: AssistData, fallbackWeekStart: LocalDate, today: LocalDate = LocalDate.now()): LocalDate =
        resolveRotaWeekStart(assist, fallbackWeekStart, today)

    private fun resolveRotaWeekStart(
        assist: AssistData,
        fallbackWeekStart: LocalDate,
        today: LocalDate
    ): LocalDate {
        if (assist.imageWidth <= 0) return nearestWeekStart(today, fallbackWeekStart)

        // v20 document authority: solve the complete weekday/date sequence before consulting the
        // planner week.  This is deliberately independent from the legacy scorer below; the old
        // path remains as a guarded fallback for rotas whose headers are genuinely unreadable.
        val headerBottomRatio = headerDateZoneBottom(assist) / assist.imageHeight.coerceAtLeast(1).toFloat()
        val explicitBottomRatio = max(headerBottomRatio, 0.255f)
        val dateTokens = assist.tokens.map { token ->
            RotaDateAuthorityEngine.Token(
                text = token.text,
                column = columnIndexForX(assist, token.cx),
                yRatio = token.cy / assist.imageHeight.coerceAtLeast(1).toFloat()
            )
        }
        val authority = RotaDateAuthorityEngine.resolve(
            tokens = dateTokens,
            today = today,
            fallbackWeekStart = fallbackWeekStart,
            explicitDateBottomRatio = explicitBottomRatio,
            weakDateBottomRatio = headerBottomRatio
        )
        if (authority.authoritative || authority.confidence >= 0.84f) return authority.weekStart

        data class HeaderObservation(
            val day: Int,
            val month: Int?,
            val column: Int,
            val explicit: Boolean,
            val raw: String
        )

        fun parseHeaderParts(raw: String): HeaderObservation? {
            val text = normalizeDigits(raw)
            val explicitMatch = Regex("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)").find(text)
            if (explicitMatch != null) {
                val parsedDay = explicitMatch.groupValues[1].toIntOrNull()
                val parsedMonth = explicitMatch.groupValues[2].toIntOrNull()
                if (parsedDay != null && parsedMonth != null && parsedDay in 1..31 && parsedMonth in 1..12) {
                    return HeaderObservation(parsedDay, parsedMonth, -1, true, raw)
                }
            }

            val compact = text.filter { ch -> ch.isDigit() }
            if (compact.length == 4) {
                val parsedDay = compact.substring(0, 2).toIntOrNull()
                val parsedMonth = compact.substring(2, 4).toIntOrNull()
                if (parsedDay != null && parsedMonth != null && parsedDay in 1..31 && parsedMonth in 1..12) {
                    return HeaderObservation(parsedDay, parsedMonth, -1, true, raw)
                }
            }

            val matches = Regex("(?<!\\d)(\\d{1,2})(?!\\d)").findAll(text)
            for (match in matches) {
                val parsedDay = match.groupValues[1].toIntOrNull()
                if (parsedDay != null && parsedDay in 1..31) {
                    return HeaderObservation(parsedDay, null, -1, false, raw)
                }
            }
            return null
        }

        val headerBottom = headerDateZoneBottom(assist)
        val explicitDateBottom = max(headerBottom, assist.imageHeight * 0.235f)
        val observations = mutableListOf<HeaderObservation>()
        for (token in assist.tokens) {
            // Explicit dd.mm / dd-mm / dd/mm tokens are safe to inspect in a wider top corridor.
            // Weak bare numbers stay restricted to the true header row so 09:30 / 13 / 16 / 18
            // shift labels cannot become calendar evidence.
            if (token.cy > explicitDateBottom) continue
            val parsed = parseHeaderParts(token.text) ?: continue
            if (!parsed.explicit && token.cy > headerBottom) continue
            val observation = parsed.copy(column = columnIndexForX(assist, token.cx))
            val duplicate = observations.any { existing ->
                existing.day == observation.day &&
                    existing.month == observation.month &&
                    existing.column == observation.column &&
                    existing.raw.equals(observation.raw, ignoreCase = true)
            }
            if (!duplicate) observations.add(observation)
        }

        // ML Kit sometimes splits a handwritten header date into separate day/month elements.
        // Rebuild those fragments only inside one weekday column and only near the header.
        for (column in 0..6) {
            val numeric = assist.tokens
                .filter { token -> token.cy <= explicitDateBottom && columnIndexForX(assist, token.cx) == column }
                .mapNotNull { token ->
                    val digits = normalizeDigits(token.text).filter { ch -> ch.isDigit() }
                    if (digits.length in 1..2) token to digits else null
                }
                .sortedWith(compareBy<Pair<AssistToken, String>> { it.first.cy }.thenBy { it.first.cx })
            for (i in numeric.indices) {
                val dayToken = numeric[i].first
                val day = numeric[i].second.toIntOrNull() ?: continue
                if (day !in 1..31) continue
                for (j in i + 1 until min(i + 4, numeric.size)) {
                    val monthToken = numeric[j].first
                    val month = numeric[j].second.toIntOrNull() ?: continue
                    if (month !in 1..12) continue
                    val sameLine = abs(monthToken.cy - dayToken.cy) <= assist.imageHeight * 0.018f
                    val closeX = monthToken.cx >= dayToken.cx && monthToken.cx - dayToken.cx <= assist.imageWidth * 0.055f
                    if (!sameLine || !closeX) continue
                    val observation = HeaderObservation(day, month, column, true, "${day}.${month}")
                    if (observations.none { it.day == day && it.month == month && it.column == column }) {
                        observations.add(observation)
                    }
                    break
                }
            }
        }

        if (observations.isEmpty()) return nearestWeekStart(today, fallbackWeekStart)

        val todayMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val fallbackMonday = fallbackWeekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        // Strong path: explicit document dates are authoritative when several weekday columns agree.
        val explicitCandidates = mutableListOf<LocalDate>()
        for (observation in observations) {
            val month = observation.month ?: continue
            if (!observation.explicit) continue
            var year = today.year - 1
            while (year <= today.year + 1) {
                val date = runCatching { LocalDate.of(year, month, observation.day) }.getOrNull()
                if (date != null) {
                    val monday = date.minusDays(observation.column.toLong())
                    if (monday.dayOfWeek == DayOfWeek.MONDAY) explicitCandidates.add(monday)
                }
                year++
            }
        }

        if (explicitCandidates.isNotEmpty()) {
            val counts = mutableMapOf<LocalDate, Int>()
            for (candidate in explicitCandidates) {
                counts[candidate] = (counts[candidate] ?: 0) + 1
            }
            val ranked = counts.entries.sortedByDescending { entry -> entry.value }
            val winner = ranked.firstOrNull()
            if (winner != null) {
                val runnerCount = if (ranked.size > 1) ranked[1].value else 0
                var explicitObservationCount = 0
                for (observation in observations) {
                    if (observation.explicit && observation.month != null) explicitObservationCount++
                }
                if (winner.value >= 3 && winner.value >= runnerCount + 1) return winner.key
                if (winner.value >= 2 && explicitObservationCount <= 3 && winner.value > runnerCount) return winner.key
            }
        }

        // Weak path: score plausible weeks using only tokens inside the date-header corridor.
        val monthCounts = mutableMapOf<Int, Int>()
        for (observation in observations) {
            val month = observation.month ?: continue
            monthCounts[month] = (monthCounts[month] ?: 0) + 1
        }
        var consensusMonth: Int? = null
        var consensusMonthCount = 0
        for ((month, count) in monthCounts) {
            if (count > consensusMonthCount) {
                consensusMonth = month
                consensusMonthCount = count
            }
        }
        if (consensusMonthCount < 2) consensusMonth = null

        var bestWeek = nearestWeekStart(today, fallbackWeekStart)
        var bestScore = Double.NEGATIVE_INFINITY
        var weekOffset = -16
        while (weekOffset <= 20) {
            val candidateWeek = todayMonday.plusWeeks(weekOffset.toLong())
            var score = 0.0
            var matchedDays = 0

            for (observation in observations) {
                val predicted = candidateWeek.plusDays(observation.column.toLong())
                if (observation.day == predicted.dayOfMonth) {
                    score += if (observation.explicit) 10.0 else 6.0
                    matchedDays++
                } else if (predicted.dayOfMonth >= 10 && observation.day == predicted.dayOfMonth % 10) {
                    score += 1.0
                } else if (abs(observation.day - predicted.dayOfMonth) == 1) {
                    score += 0.35
                } else {
                    score -= if (observation.explicit) 2.2 else 0.6
                }

                val observedMonth = observation.month
                if (observedMonth != null) {
                    score += if (observedMonth == predicted.monthValue) 4.0 else -3.0
                }
            }

            val month = consensusMonth
            if (month != null) {
                var matchingColumns = 0
                var column = 0
                while (column <= 6) {
                    if (candidateWeek.plusDays(column.toLong()).monthValue == month) matchingColumns++
                    column++
                }
                score += matchingColumns * 1.9
            }

            if (matchedDays >= 3) score += matchedDays * 2.0
            val daysFromToday = abs(java.time.temporal.ChronoUnit.DAYS.between(today, candidateWeek))
            score -= daysFromToday / 14.0
            val daysFromPlanner = abs(java.time.temporal.ChronoUnit.DAYS.between(fallbackMonday, candidateWeek))
            score -= daysFromPlanner / 80.0

            if (score > bestScore) {
                bestScore = score
                bestWeek = candidateWeek
            }
            weekOffset++
        }
        return bestWeek
    }

    private fun headerDateZoneBottom(assist: AssistData): Float {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        // Do not use RotaGridModel.localStarts() here because it intentionally injects a
        // synthetic 10.5% first block. On some photographed rotas the handwritten date row is
        // lower than that synthetic anchor, causing the real 14.09 / 15.09 ... tokens to be
        // discarded before the date resolver sees them. Prefer actual detected horizontal rules.
        val physicalFirstRules = mutableListOf<Float>()
        for (columnRules in assist.rowBoundaries) {
            val first = columnRules
                .filter { y -> y in (h * 0.095f)..(h * 0.30f) }
                .minOrNull()
            if (first != null) physicalFirstRules.add(first)
        }
        physicalFirstRules.sort()
        val structural = if (physicalFirstRules.size >= 2) {
            physicalFirstRules[physicalFirstRules.size / 2]
        } else {
            h * 0.165f
        }
        return (structural - h * 0.006f).coerceIn(h * 0.080f, h * 0.205f)
    }

    private fun looksLikeExplicitHeaderDate(value: String): Boolean {
        val text = normalizeDigits(value)
        val explicitMatch = Regex("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)").find(text)
        if (explicitMatch != null) {
            val day = explicitMatch.groupValues[1].toIntOrNull()
            val month = explicitMatch.groupValues[2].toIntOrNull()
            if (day != null && month != null && day in 1..31 && month in 1..12) return true
        }
        val compact = text.filter { ch -> ch.isDigit() }
        if (compact.length == 4) {
            val day = compact.substring(0, 2).toIntOrNull()
            val month = compact.substring(2, 4).toIntOrNull()
            if (day != null && month != null && day in 1..31 && month in 1..12) return true
        }
        return false
    }

    private fun extractHeaderDay(value: String): Int? {
        val normalized = normalizeDigits(value)
        val numbers = Regex("(?<!\\d)(\\d{1,2})(?!\\d)").findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toList()
        return numbers.firstOrNull { it in 1..31 }
    }

    private fun nearestWeekStart(today: LocalDate, plannerWeekStart: LocalDate): LocalDate {
        // Prefer the planner week when it is already close to today. Otherwise use the nearest
        // Monday, choosing next Monday on Sundays (common time to receive the following rota).
        if (abs(java.time.temporal.ChronoUnit.DAYS.between(today, plannerWeekStart)) <= 10) {
            return plannerWeekStart
        }
        val previous = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val next = previous.plusWeeks(1)
        return if (abs(java.time.temporal.ChronoUnit.DAYS.between(today, next)) <
            abs(java.time.temporal.ChronoUnit.DAYS.between(today, previous))) next else previous
    }

    private fun parseHourOnly(value: String): LocalTime? {
        val cleaned = normalizeTimeDigits(value).trim().replace(Regex("[^0-9]"), "")
        if (cleaned.length !in 1..2) return null
        val hour = cleaned.toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        return LocalTime.of(hour, 0)
    }

    private fun createEnhancedBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 1.62f
            val translate = (-0.5f * contrast + 0.5f) * 255f + 12f
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /** High-contrast black/white pass aimed specifically at faint pencil and small superscripts. */
    private fun createThresholdBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

        // Estimate paper brightness from a sparse sample, then keep handwriting/grid lines dark.
        var sum = 0L
        var count = 0
        val stride = max(1, pixels.size / 6000)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            sum += (r * 30 + g * 59 + b * 11) / 100
            count++
            i += stride
        }
        val mean = if (count == 0) 190 else (sum / count).toInt()
        val threshold = (mean - 24).coerceIn(105, 205)
        for (index in pixels.indices) {
            val c = pixels[index]
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            val luma = (r * 30 + g * 59 + b * 11) / 100
            pixels[index] = if (luma < threshold) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    /** Geometry-aware parser for weekly table rotas such as handwritten columns headed 21.09., 22.09., etc. */
    internal fun extractSpatialDrafts(
        result: Text,
        employeeName: String,
        typicalShiftHours: Int = 8,
        today: LocalDate = LocalDate.now()
    ): List<Draft> {
        if (employeeName.isBlank()) return emptyList()
        val lines = result.textBlocks.flatMap { block ->
            buildList {
                block.lines.forEach { line ->
                    line.boundingBox?.let { add(OcrLine(line.text.trim(), it)) }
                    // Handwriting OCR often splits a single name into elements even when the full
                    // line is noisy. Keep both granularities and deduplicate by text + geometry.
                    line.elements.forEach { element ->
                        element.boundingBox?.let { add(OcrLine(element.text.trim(), it)) }
                    }
                }
            }
        }.filter { it.text.isNotBlank() }.distinctBy { Triple(it.text.lowercase(Locale.ROOT), it.box.left, it.box.top) }
        if (lines.isEmpty()) return emptyList()

        val dateHeaders = lines.mapNotNull { line ->
            parseNumericDate(line.text, today)?.let { Header(it, line.cx, line) }
        }.groupBy { it.date }.map { (_, sameDate) ->
            // Prefer the narrowest OCR box for a date token. Whole handwritten header lines can
            // stretch across a column and make their center less precise than the actual date element.
            sameDate.minByOrNull { it.line.box.width() }!!
        }.sortedBy { it.cx }

        val weekdayHeaders = lines.mapNotNull { line ->
            parseWeekday(line.text)?.let { day -> day to line }
        }.groupBy { it.first }.map { (_, entries) -> entries.minByOrNull { it.second.box.width() }!! }
            .sortedBy { it.second.cx }

        val headers = when {
            dateHeaders.size >= 2 -> dateHeaders
            weekdayHeaders.size >= 4 -> {
                val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                weekdayHeaders.map { (day, line) ->
                    Header(weekStart.plusDays((day.value - DayOfWeek.MONDAY.value).toLong()), line.cx, line)
                }
            }
            else -> emptyList()
        }

        if (headers.size < 2) return emptyList()

        val employee = normalizeName(employeeName)
        val avgSpacing = headers.zipWithNext { a, b -> b.cx - a.cx }.filter { it > 0 }.average().takeIf { !it.isNaN() }?.toFloat() ?: 300f
        val maxColumnDistance = avgSpacing * 0.72f

        fun headerFor(line: OcrLine): Header? = headers.minByOrNull { abs(it.cx - line.cx) }
            ?.takeIf { abs(it.cx - line.cx) <= maxColumnDistance }

        val drafts = mutableListOf<Draft>()
        headers.forEach { header ->
            val columnLines = lines.filter { line ->
                headerFor(line)?.date == header.date && line.cy > header.line.cy
            }.sortedBy { it.cy }

            val markers = collectTimeMarkers(columnLines, avgSpacing)

            columnLines.forEach { line ->
                val score = nameSimilarity(employee, normalizeName(line.text))
                val threshold = if (employee.length >= 7) 0.80f else 0.86f
                if (score < threshold) return@forEach

                // Prefer a marker already assigned to this column. If OCR merged the time with
                // neighboring text, fall back to any parsable time above the employee within a
                // generous horizontal band around the column center.
                val marker = markers.filter { it.y < line.cy }.maxByOrNull { it.y }
                    ?: lines.asSequence()
                        .filter { it.cy < line.cy && abs(it.cx - header.cx) <= maxColumnDistance }
                        .mapNotNull { candidate -> parseTimeLenient(candidate.text)?.let { TimeMarker(it, candidate.cy, candidate.text) } }
                        .maxByOrNull { it.y }
                    ?: return@forEach
                val nextMarker = markers.filter { it.y > marker.y }.minByOrNull { it.y }
                if (nextMarker != null && line.cy >= nextMarker.y) return@forEach

                val start = LocalDateTime.of(header.date, marker.time)
                val end = start.plusHours(typicalShiftHours.toLong())
                val confidence = (0.68f + score * 0.30f).coerceAtMost(0.98f)
                drafts += Draft(
                    start = start,
                    end = end,
                    sourceLine = "Handwritten grid · ${header.date.format(SHORT_DATE)} · ${marker.source} · “${line.text}” · end estimated +${typicalShiftHours}h",
                    confidence = confidence,
                    estimatedEnd = true,
                    columnIndex = header.date.dayOfWeek.value - 1
                )
            }
        }
        return drafts.distinctBy { it.start }.sortedBy { it.start }
    }

    /** Original text-only parser retained for printed schedules containing full date + start + end. */
    internal fun extractDrafts(text: String, employeeName: String, today: LocalDate = LocalDate.now()): List<Draft> {
        if (employeeName.isBlank()) return emptyList()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val name = normalizeName(employeeName)
        val candidateLines = linkedSetOf<String>()
        lines.forEachIndexed { index, line ->
            if (nameSimilarity(name, normalizeName(line)) >= 0.82f) {
                for (i in (index - 2).coerceAtLeast(0)..(index + 2).coerceAtMost(lines.lastIndex)) candidateLines += lines[i]
            }
        }
        return candidateLines.mapNotNull { line ->
            parseLine(line, today)?.let { (start, end) -> Draft(start = start, end = end, sourceLine = line) }
        }.distinctBy { it.start to it.end }.sortedBy { it.start }
    }

    private fun collectTimeMarkers(lines: List<OcrLine>, columnSpacing: Float): List<TimeMarker> {
        val markers = mutableListOf<TimeMarker>()
        val dateLike = lines.filter { parseNumericDate(it.text, LocalDate.now()) != null }.toSet()
        lines.filterNot { it in dateLike }.forEach { line ->
            parseTimeLenient(line.text)?.let { markers += TimeMarker(it, line.cy, line.text) }
        }

        // Handwritten schedules often put "30" or "00" as a small superscript beside the hour.
        val hourOnly = lines.filter { normalizeDigits(it.text).trim().matches(Regex("^(?:[0-9]|1[0-9]|2[0-3])$")) }
        val minuteOnly = lines.filter { normalizeDigits(it.text).trim().matches(Regex("^(?:00|15|30|45)$")) }
        hourOnly.forEach { hourLine ->
            val h = normalizeDigits(hourLine.text).trim().toIntOrNull() ?: return@forEach
            val minute = minuteOnly.minByOrNull { candidate ->
                abs(candidate.box.left - hourLine.box.right).toFloat() + abs(candidate.cy - hourLine.cy)
            }?.takeIf { candidate ->
                candidate.box.left >= hourLine.box.left &&
                    abs(candidate.cy - hourLine.cy) < max(hourLine.height, candidate.height) * 2.4f &&
                    abs(candidate.box.left - hourLine.box.right) < columnSpacing * 0.28f
            }
            if (minute != null) {
                val m = normalizeDigits(minute.text).trim().toInt()
                markers += TimeMarker(LocalTime.of(h, m), min(hourLine.cy, minute.cy), "${h}:${m.toString().padStart(2, '0')}")
            }
        }

        val sortedMarkers: List<TimeMarker> = markers
            .filter { marker -> marker.time.hour in 0..23 }
            .sortedBy { marker -> marker.y }
        val deduplicated: MutableList<TimeMarker> = mutableListOf()
        sortedMarkers.forEach { marker ->
            val previous: TimeMarker? = deduplicated.lastOrNull()
            val duplicate = previous != null && abs(previous.y - marker.y) < 18f && previous.time == marker.time
            if (!duplicate) deduplicated += marker
        }
        return deduplicated
    }

    private fun normalizeTimeDigits(value: String): String {
        val base = normalizeDigits(value)
        // These substitutions are intentionally restricted to time parsing. They are common
        // handwriting OCR confusions (e.g. g30 for 9:30, I6 for 16) but would be unsafe in names.
        return base.map { c ->
            when (c) {
                'O', 'o' -> '0'
                'I', 'l', '|' -> '1'
                'G', 'g', 'q', 'Q' -> '9'
                else -> c
            }
        }.joinToString("")
    }

    private fun parseTimeLenient(value: String): LocalTime? {
        var s = normalizeTimeDigits(value).lowercase(Locale.ROOT)
            .replace(',', ':')
            .replace(';', ':')
            .replace('h', ':')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (parseNumericDate(s, LocalDate.now()) != null) return null

        val separated = Regex("(?<!\\d)([0-2]?\\d)\\s*[:.]\\s*([0-5]\\d)(?!\\d)").find(s)
        if (separated != null) return safeTime(separated.groupValues[1], separated.groupValues[2])

        val spaced = Regex("^([0-2]?\\d)\\s+([0-5]\\d)$").find(s)
        if (spaced != null) return safeTime(spaced.groupValues[1], spaced.groupValues[2])

        val compact = s.filter(Char::isDigit)
        if (compact.length in 3..4 && s.count(Char::isLetter) == 0) {
            // OCR often drops punctuation from rota dates, e.g. 23.09 -> "2309". Do not
            // interpret day-month pairs as 23:09. Shift times such as 0930, 1300, 1600 and
            // 1800 remain valid because their trailing pair is not a calendar month.
            if (compact.length == 4) {
                val first = compact.take(2).toIntOrNull()
                val last = compact.takeLast(2).toIntOrNull()
                if (first != null && last != null && first in 1..31 && last in 1..12) return null

                // Superscript handwriting sometimes becomes an extra zero, e.g. 9º30 -> 9030.
                // If the normal HHmm parse is impossible, recover h:mm from the first + last two.
                if (first != null && first > 23 && last != null && last in setOf(0, 15, 30, 45)) {
                    val oneDigitHour = compact.take(1).toIntOrNull()
                    if (oneDigitHour != null && oneDigitHour in 5..9) return LocalTime.of(oneDigitHour, last)
                }
            }
            val hour = compact.dropLast(2)
            val minute = compact.takeLast(2)
            return safeTime(hour, minute)
        }
        return null
    }

    private fun safeTime(hour: String, minute: String): LocalTime? {
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private fun parseLine(line: String, today: LocalDate): Pair<LocalDateTime, LocalDateTime>? {
        val times = TIME_REGEX.findAll(normalizeDigits(line)).mapNotNull { match -> safeTime(match.groupValues[1], match.groupValues[2].ifBlank { "0" }) }.toList()
        if (times.size < 2) return null
        val date = parseDate(line, today) ?: return null
        val start = LocalDateTime.of(date, times[0])
        var end = LocalDateTime.of(date, times[1])
        if (!end.isAfter(start)) end = end.plusDays(1)
        return start to end
    }

    private fun parseNumericDate(line: String, today: LocalDate): LocalDate? {
        val cleaned = normalizeDigits(line).replace(',', '.').replace(" ", "")
        val match = DATE_REGEX.find(cleaned) ?: return ISO_DATE_REGEX.find(cleaned)?.let {
            runCatching { LocalDate.parse(it.value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        }
        val d = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val rawYear = match.groupValues[3]
        if (d !in 1..31 || m !in 1..12) return null
        val explicitYear = when {
            rawYear.isBlank() -> null
            rawYear.length == 2 -> 2000 + rawYear.toInt()
            else -> rawYear.toInt()
        }
        if (explicitYear != null) return runCatching { LocalDate.of(explicitYear, m, d) }.getOrNull()

        // A rota crossing New Year should resolve to the occurrence nearest today.
        return listOf(today.year - 1, today.year, today.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, m, d) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) }
    }

    private fun parseWeekday(line: String): DayOfWeek? {
        val normalized = normalizeName(line)
        return DAY_NAMES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (token, _) ->
                normalized.contains(token) || (normalized.length >= 4 && similarity(normalized, token) >= 0.62f)
            }?.value
    }

    private fun parseDate(line: String, today: LocalDate): LocalDate? {
        parseNumericDate(line, today)?.let { return it }
        val normalized = normalizeName(line)
        DAY_NAMES.forEach { (token, day) ->
            if (normalized.contains(token)) {
                val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                return startOfWeek.plusDays((day.value - DayOfWeek.MONDAY.value).toLong())
            }
        }
        return null
    }

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { c ->
            append(
                when (c) {
                    '⁰' -> '0'; '¹' -> '1'; '²' -> '2'; '³' -> '3'; '⁴' -> '4'
                    '⁵' -> '5'; '⁶' -> '6'; '⁷' -> '7'; '⁸' -> '8'; '⁹' -> '9'
                    // Common OCR output for handwritten superscript minutes such as 16ºº / 9º³º.
                    'º', '°', '○', '◦' -> '0'
                    else -> c
                }
            )
        }
    }

    private fun normalizeName(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        // Locale-independent lowercasing makes LEONARDO, Leonardo and leonardo identical.
        // A few digit-to-letter substitutions handle frequent handwriting OCR confusions in names.
        return ascii.lowercase(Locale.ROOT)
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('3', 'e')
            .replace(Regex("[^a-z]"), "")
    }

    private fun nameSimilarity(target: String, candidate: String): Float {
        if (target.isBlank() || candidate.isBlank()) return 0f
        if (candidate == target) return 1f
        if (candidate.contains(target)) return 1f
        if (target.contains(candidate) && candidate.length >= 4) return 0.92f

        val words = candidate.chunkedCandidateWords()
        val best = words.maxOfOrNull { word -> similarity(target, word) } ?: similarity(target, candidate)

        // Handwriting OCR frequently loses or changes one/two letters. Short first names need a
        // slightly stricter floor; longer names can tolerate more corruption safely.
        val lengthBonus = when {
            target.length >= 8 -> 0.06f
            target.length >= 6 -> 0.03f
            else -> 0f
        }
        return (best + lengthBonus).coerceAtMost(1f)
    }

    private fun String.chunkedCandidateWords(): List<String> {
        // normalizeName removes spaces; OCR lines are usually one handwritten name. Keep whole line and
        // sliding windows so a merged OCR line such as "danijelaleonardo" can still match.
        if (length <= 14) return listOf(this)
        val result = mutableListOf(this)
        val sizes = 4..min(12, length)
        sizes.forEach { size -> for (i in 0..length - size) result += substring(i, i + size) }
        return result
    }

    private fun similarity(a: String, b: String): Float {
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / max(a.length, b.length).coerceAtLeast(1)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private val TIME_REGEX = Regex("(?<!\\d)([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))(?!\\d)")
    private val DATE_REGEX = Regex("(?<!\\d)([0-3]?\\d)[./-]([01]?\\d)(?:[./-](\\d{2,4}))?(?!\\d)")
    private val ISO_DATE_REGEX = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val DAY_NAMES = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
        "ponedjeljak" to DayOfWeek.MONDAY, "ponediljak" to DayOfWeek.MONDAY,
        "utorak" to DayOfWeek.TUESDAY,
        "srijeda" to DayOfWeek.WEDNESDAY, "srida" to DayOfWeek.WEDNESDAY,
        "cetvrtak" to DayOfWeek.THURSDAY,
        "petak" to DayOfWeek.FRIDAY,
        "subota" to DayOfWeek.SATURDAY,
        "nedjelja" to DayOfWeek.SUNDAY, "nedilja" to DayOfWeek.SUNDAY
    )
    private val SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}
