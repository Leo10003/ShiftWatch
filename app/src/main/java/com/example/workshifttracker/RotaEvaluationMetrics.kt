package com.example.workshifttracker

import java.time.LocalTime

/** Offline evaluation against user-authorized, explicitly labeled rota cases. */
internal object RotaEvaluationMetrics {
    data class ShiftLabel(val weekday: Int, val block: Int?, val time: LocalTime)
    data class Score(val matched: Int, val proposed: Int, val expected: Int) {
        val precision: Double get() = if (proposed == 0) { if (expected == 0) 1.0 else 0.0 } else matched.toDouble() / proposed
        val recall: Double get() = if (expected == 0) 1.0 else matched.toDouble() / expected
    }
    /** Deterministic one-to-one matching; a duplicate proposal cannot claim two correct labels. */
    fun evaluate(expected: List<ShiftLabel>, proposed: List<ShiftLabel>): Score {
        val remaining = expected.toMutableList()
        var matches = 0
        for (candidate in proposed) {
            val idx = remaining.indexOfFirst {
                it.weekday == candidate.weekday && it.time == candidate.time &&
                    (it.block == null || candidate.block == null || it.block == candidate.block)
            }
            if (idx >= 0) { remaining.removeAt(idx); matches++ }
        }
        return Score(matches, proposed.size, expected.size)
    }
}
