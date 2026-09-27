package com.example.workshifttracker

import kotlin.math.abs
import kotlin.math.min

/** Geometry-only ownership guard. A name close to a shared block border must not inherit an hour. */
internal object RotaBlockOwnership {
    data class Assignment(val block: Int?, val reason: String)
    fun resolve(starts: List<Float>, y: Float, imageHeight: Float): Assignment {
        if (starts.isEmpty() || imageHeight <= 0f) return Assignment(null, "missing row boundaries")
        val ordered = starts.sorted()
        if (y < ordered.first() || y > imageHeight * .955f) return Assignment(null, "outside schedule blocks")
        val idx = ordered.indexOfLast { it <= y }.coerceAtLeast(0)
        val top = ordered[idx]
        val bottom = ordered.getOrNull(idx + 1) ?: imageHeight * .955f
        val localHeight = bottom - top
        if (localHeight < imageHeight * .025f) return Assignment(null, "collapsed block")
        // Border tolerance depends on local block height: a tiny row shouldn't be entirely
        // classified as ambiguous, but a visually borderline name must be reviewed.
        val margin = min(imageHeight * .010f, localHeight * .12f)
        if (idx > 0 && abs(y - top) <= margin) return Assignment(null, "near upper block boundary")
        if (idx < ordered.lastIndex && abs(y - bottom) <= margin) return Assignment(null, "near lower block boundary")
        return Assignment(idx, "inside physical block")
    }
}
