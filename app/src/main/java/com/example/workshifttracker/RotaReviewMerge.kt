package com.example.workshifttracker

/**
 * Pure, deterministic late-result merge used by the live photo review.
 * Explicitly edited or deleted dates are tombstones; asynchronous analysis may only ADD
 * suggestions on untouched, unoccupied days. The caller controls the photo generation.
 */
internal object RotaReviewMerge {
    fun isCurrent(eventGeneration: Int, currentGeneration: Int, reviewOpen: Boolean): Boolean =
        reviewOpen && eventGeneration == currentGeneration

    /**
     * v20.7 candidate-aware late merge. A weekday can legitimately contain two different
     * non-overlapping shifts; the old day-keyed merge silently lost the second shift.
     * Explicitly touched weekdays remain protected regardless of the scan's guessed week.
     */
    fun <T, DAY, SLOT> mergeBySlot(
        current: List<T>,
        incoming: List<T>,
        touchedDays: Set<DAY>,
        dayOf: (T) -> DAY,
        slotOf: (T) -> SLOT
    ): List<T> {
        val occupied = current.mapTo(hashSetOf(), slotOf)
        val additions = incoming.filter { item ->
            dayOf(item) !in touchedDays && occupied.add(slotOf(item))
        }
        return current + additions
    }

    fun <T, K> merge(
        current: List<T>,
        incoming: List<T>,
        touched: Set<K>,
        key: (T) -> K
    ): List<T> {
        val occupied = current.mapTo(mutableSetOf(), key)
        val additional = incoming.filter { candidate ->
            val k = key(candidate)
            k !in touched && occupied.add(k)
        }
        return current + additional
    }
}
