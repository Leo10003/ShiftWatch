package com.example.workshifttracker

import kotlin.math.abs
import java.lang.ref.WeakReference

/**
 * Structural row normalizer for photographed weekly rotas.
 *
 * Different weekday columns can be vertically displaced by camera perspective and individual
 * grid lines can be missed.  This model aligns each column's locally detected block starts to a
 * shared canonical sequence using ordered dynamic programming.  Downstream time reasoning can
 * therefore refer to "block 2" consistently instead of relying on raw Y coordinates.
 */
object RotaGridModel {
    data class AlignedStart(val y: Float, val blockIndex: Int)

    /**
     * v20 import-local geometry cache.  Grid alignment used to be recomputed dozens of times per
     * tap/preview render. AssistData is immutable for one analysis generation, so identity-keyed
     * caching is safe and dramatically reduces repeated clustering/dynamic-programming work.
     */
    private data class CachedGrid(
        val anchors: List<Float>,
        val local: Array<List<Float>?> = arrayOfNulls(7),
        val expected: Array<List<Float>?> = arrayOfNulls(7)
    )
    // Only one review/import is actively queried at a time. Keep the most recent grid by object
    // identity instead of hashing the entire AssistData data class (which contains hundreds of OCR
    // tokens). A weak reference avoids retaining an old analysis after the review is closed.
    private val cacheLock = Any()
    private var cachedAssistRef: WeakReference<ScheduleImporter.AssistData> = WeakReference(null)
    private var cachedGrid: CachedGrid? = null

    private fun cached(assist: ScheduleImporter.AssistData): CachedGrid = synchronized(cacheLock) {
        val existing = cachedAssistRef.get()
        val grid = cachedGrid
        if (existing === assist && grid != null) {
            grid
        } else {
            val created = CachedGrid(computeGlobalAnchors(assist))
            cachedAssistRef = WeakReference(assist)
            cachedGrid = created
            created
        }
    }

    fun localStarts(assist: ScheduleImporter.AssistData, column: Int): List<Float> {
        if (column !in 0..6) return emptyList()
        val grid = cached(assist)
        synchronized(grid) {
            grid.local[column]?.let { return it }
            val h = assist.imageHeight.coerceAtLeast(1).toFloat()
            val raw = mutableListOf(h * 0.105f)
            raw.addAll(assist.rowBoundaries.getOrNull(column).orEmpty()
                .filter { it in (h * 0.08f)..(h * 0.90f) })
            val out = mutableListOf<Float>()
            for (y in raw.sorted()) {
                if (out.isEmpty() || y - out.last() > h * 0.030f) out.add(y)
            }
            val result = out.take(7)
            grid.local[column] = result
            return result
        }
    }

    fun globalAnchors(assist: ScheduleImporter.AssistData): List<Float> = cached(assist).anchors

    private fun computeLocalStarts(assist: ScheduleImporter.AssistData, column: Int): List<Float> {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val raw = mutableListOf(h * 0.105f)
        raw.addAll(assist.rowBoundaries.getOrNull(column).orEmpty()
            .filter { it in (h * 0.08f)..(h * 0.90f) })
        val out = mutableListOf<Float>()
        for (y in raw.sorted()) {
            if (out.isEmpty() || y - out.last() > h * 0.030f) out.add(y)
        }
        return out.take(7)
    }

    private fun computeGlobalAnchors(assist: ScheduleImporter.AssistData): List<Float> {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        data class Point(val column: Int, val y: Float)
        val points = mutableListOf<Point>()
        for (column in 0..6) {
            val raw = computeLocalStarts(assist, column)
            raw.forEach { points.add(Point(column, it)) }
        }
        if (points.isEmpty()) return emptyList()

        val tolerance = h * 0.042f
        val clusters = mutableListOf<MutableList<Point>>()
        for (point in points.sortedBy { it.y }) {
            val nearest = clusters.minByOrNull { cluster -> abs(cluster.map { it.y }.average().toFloat() - point.y) }
            val center = nearest?.map { it.y }?.average()?.toFloat()
            if (nearest != null && center != null && abs(center - point.y) <= tolerance) nearest += point
            else clusters += mutableListOf(point)
        }

        val anchors = mutableListOf<Float>()
        for (cluster in clusters) {
            val support = cluster.map { it.column }.distinct().size
            val values = cluster.map { it.y }.sorted()
            val median = values[values.size / 2]
            if (support >= 2 || abs(median - h * 0.105f) <= h * 0.025f) anchors += median
        }
        val merged = mutableListOf<Float>()
        for (y in anchors.sorted()) {
            if (merged.isEmpty() || y - merged.last() > h * 0.028f) merged += y
            else merged[merged.lastIndex] = (merged.last() + y) / 2f
        }
        return merged.take(7)
    }

    /** Align ordered local row starts to ordered canonical anchors, allowing missed grid lines. */
    fun alignedStarts(assist: ScheduleImporter.AssistData, column: Int): List<AlignedStart> {
        val local = localStarts(assist, column)
        if (local.isEmpty()) return emptyList()
        val anchors = globalAnchors(assist)
        if (anchors.isEmpty()) return local.mapIndexed { index, y -> AlignedStart(y, index) }
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val n = local.size
        val m = anchors.size
        val inf = 1_000_000f
        val dp = Array(n + 1) { FloatArray(m + 1) { inf } }
        val action = Array(n + 1) { IntArray(m + 1) }
        dp[0][0] = 0f
        val skipLocal = 0.72f
        val skipAnchor = 0.58f
        for (i in 0..n) for (j in 0..m) {
            val base = dp[i][j]
            if (base >= inf) continue
            if (i < n && j < m) {
                val distance = abs(local[i] - anchors[j]) / (h * 0.055f)
                val cost = base + distance.coerceAtMost(4.5f)
                if (cost < dp[i + 1][j + 1]) {
                    dp[i + 1][j + 1] = cost
                    action[i + 1][j + 1] = 1 // match
                }
            }
            if (i < n && base + skipLocal < dp[i + 1][j]) {
                dp[i + 1][j] = base + skipLocal
                action[i + 1][j] = 2
            }
            if (j < m && base + skipAnchor < dp[i][j + 1]) {
                dp[i][j + 1] = base + skipAnchor
                action[i][j + 1] = 3
            }
        }

        var bestJ = (0..m).minByOrNull { j -> dp[n][j] + (m - j) * skipAnchor } ?: m
        var i = n
        var j = bestJ
        val assigned = IntArray(n) { -1 }
        while (i > 0 || j > 0) {
            when (action[i][j]) {
                1 -> { assigned[i - 1] = j - 1; i--; j-- }
                2 -> { i-- }
                3 -> { j-- }
                else -> {
                    if (i > 0 && j > 0) { assigned[i - 1] = j - 1; i--; j-- }
                    else if (i > 0) i-- else j--
                }
            }
        }

        // Unmatched local starts inherit the nearest canonical anchor while preserving order.
        var previous = -1
        for (idx in assigned.indices) {
            if (assigned[idx] < 0) {
                val lower = (previous + 1).coerceAtMost(m - 1)
                val nearest = (lower until m).minByOrNull { k -> abs(anchors[k] - local[idx]) } ?: lower
                assigned[idx] = nearest
            }
            if (assigned[idx] <= previous) assigned[idx] = (previous + 1).coerceAtMost(m - 1)
            previous = assigned[idx]
        }
        return local.indices.map { idx -> AlignedStart(local[idx], assigned[idx]) }
    }

    /**
     * Expected start Y for every canonical block in one weekday column. Missing local grid rules
     * are reconstructed by interpolating between neighbouring aligned rules, so a missed line does
     * not make the following employee rows inherit the previous block index.
     */
    fun expectedStartsForColumn(assist: ScheduleImporter.AssistData, column: Int): List<Float> {
        if (column !in 0..6) return emptyList()
        val grid = cached(assist)
        synchronized(grid) { grid.expected[column]?.let { return it } }
        val anchors = globalAnchors(assist)
        if (anchors.isEmpty()) {
            val result = localStarts(assist, column)
            synchronized(grid) { grid.expected[column] = result }
            return result
        }
        val aligned = alignedStarts(assist, column)
        if (aligned.isEmpty()) return anchors
        val out = MutableList(anchors.size) { Float.NaN }
        aligned.forEach { item -> if (item.blockIndex in out.indices) out[item.blockIndex] = item.y }

        for (block in out.indices) {
            if (!out[block].isNaN()) continue
            val lower = (block - 1 downTo 0).firstOrNull { !out[it].isNaN() }
            val upper = (block + 1 until out.size).firstOrNull { !out[it].isNaN() }
            out[block] = when {
                lower != null && upper != null -> {
                    val denom = (anchors[upper] - anchors[lower]).coerceAtLeast(1f)
                    val t = ((anchors[block] - anchors[lower]) / denom).coerceIn(0f, 1f)
                    out[lower] + (out[upper] - out[lower]) * t
                }
                lower != null -> out[lower] + (anchors[block] - anchors[lower])
                upper != null -> out[upper] - (anchors[upper] - anchors[block])
                else -> anchors[block]
            }
        }
        synchronized(grid) { grid.expected[column] = out }
        return out
    }

    /**
     * Perspective-normalized Y.  Each weekday column is piecewise warped onto the canonical
     * block anchors, so the same physical shift row has one comparable coordinate across the page.
     */
    fun canonicalYForPoint(assist: ScheduleImporter.AssistData, column: Int, y: Float): Float {
        val local = expectedStartsForColumn(assist, column)
        val anchors = globalAnchors(assist)
        if (local.isEmpty() || anchors.isEmpty()) return y
        val block = blockIndexForY(assist, column, y) ?: return y
        val localTop = local.getOrNull(block) ?: return y
        val localBottom = local.getOrNull(block + 1) ?: assist.imageHeight * 0.955f
        val canonicalTop = anchors.getOrNull(block) ?: localTop
        val canonicalBottom = anchors.getOrNull(block + 1) ?: assist.imageHeight * 0.955f
        val fraction = ((y - localTop) / (localBottom - localTop).coerceAtLeast(1f)).coerceIn(-0.15f, 1.15f)
        return canonicalTop + fraction * (canonicalBottom - canonicalTop)
    }

    fun blockIndexForY(assist: ScheduleImporter.AssistData, column: Int, y: Float): Int? {
        val starts = expectedStartsForColumn(assist, column)
        if (starts.isEmpty()) return null
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        // Header/footer text is not owned by a shift block. The previous coerceAtLeast(0) forced
        // everything above the first row into block 0, which could contaminate morning-time votes.
        if (y < starts.first() - h * 0.012f || y > h * 0.985f) return null
        return starts.indexOfLast { it <= y + h * 0.006f }
            .coerceIn(0, starts.lastIndex)
    }

    fun confidentBlockForY(assist: ScheduleImporter.AssistData, column: Int, y: Float): Int? =
        RotaBlockOwnership.resolve(expectedStartsForColumn(assist, column), y,
            assist.imageHeight.coerceAtLeast(1).toFloat()).block

    fun boundsForBlock(assist: ScheduleImporter.AssistData, column: Int, blockIndex: Int): Pair<Float, Float>? {
        val starts = expectedStartsForColumn(assist, column)
        val top = starts.getOrNull(blockIndex) ?: return null
        val next = starts.getOrNull(blockIndex + 1) ?: (assist.imageHeight * 0.955f)
        return top to next.coerceAtLeast(top + assist.imageHeight * 0.025f)
    }

    fun canonicalRatioForBlock(assist: ScheduleImporter.AssistData, blockIndex: Int): Float? {
        val h = assist.imageHeight.coerceAtLeast(1).toFloat()
        val values = (0..6).mapNotNull { column -> expectedStartsForColumn(assist, column).getOrNull(blockIndex) }.sorted()
        if (values.isEmpty()) return globalAnchors(assist).getOrNull(blockIndex)?.div(h)
        return (values[values.size / 2] / h).coerceIn(0f, 1f)
    }
}
