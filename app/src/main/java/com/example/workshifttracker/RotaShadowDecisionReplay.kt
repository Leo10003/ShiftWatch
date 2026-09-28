package com.example.workshifttracker

/** Read-only whole-week replay of the existing first-pass, weekly-rescue and mature-profile
 * acceptance policies. All candidates supplied here are anonymized copies, never returned
 * to the application matcher, persisted, or used for profile updates.
 */
internal object RotaShadowDecisionReplay {
    data class Candidate(val score: Float, val negative: Float, val separation: Float,
                         val block: Int?, val trimmed: Boolean = false)
    data class Column(val day: Int, val floor: Float, val candidates: List<Candidate>,
                      val candidateCount: Int = candidates.size)
    data class Result(val day: Int, val status: String, val best: Float?, val runner: Float?,
                      val winnerBlock: Int?, val selectedTrim: Boolean,
                      val margin: Float?, val accepted: Boolean)

    fun replay(input: List<Column>, positiveCount: Int, negativeCount: Int): List<Result> {
        require(input.size == 7 && input.map { it.day }.sorted() == (0..6).toList())
        data class Pending(val day: Int, val top: Candidate, val runner: Float, val floor: Float)
        val pending = mutableListOf<Pending>()
        val acceptedScores = mutableMapOf<Int, Float>()
        val result = input.sortedBy { it.day }.associate { col ->
            val sorted = col.candidates.sortedByDescending { it.score }
            val top = sorted.firstOrNull()
            val runner = sorted.getOrNull(1)?.score ?: 0f
            if (top == null) {
                col.day to Result(col.day,
                    if (col.candidateCount == 0) "no_candidate_lines" else "no_usable_signatures", null, null, null, false, null, false)
            } else {
                val margin = top.score - runner
                val normal = top.score >= col.floor && (margin >= .025f || top.score >= col.floor + .11f)
                val rescueFloor = (col.floor - .055f).coerceAtLeast(.50f)
                val rescue = !normal && top.score >= rescueFloor && margin >= .060f &&
                    (negativeCount == 0 || top.separation >= .075f) && top.negative < .78f
                val status = when {
                    normal -> "accepted_normal"
                    rescue -> "accepted_near_floor"
                    top.score < rescueFloor -> "rejected_below_rescue_floor"
                    margin < .060f -> "rejected_insufficient_runner_margin"
                    negativeCount > 0 && top.separation < .075f -> "rejected_confuser_separation"
                    top.negative >= .78f -> "rejected_high_confuser_similarity"
                    else -> "rejected_combined_policy"
                }
                if (normal || rescue) acceptedScores[col.day] = if (rescue) top.score * .94f else top.score
                else pending += Pending(col.day, top, runner, col.floor)
                col.day to Result(col.day, status, top.score, runner, top.block, top.trimmed, margin,
                    normal || rescue)
            }
        }.toMutableMap()
        if (acceptedScores.size >= 3 && pending.isNotEmpty()) {
            val ordered = acceptedScores.values.sorted()
            val median = ordered[ordered.size / 2]
            pending.filter { p ->
                p.top.score >= (p.floor - .085f).coerceAtLeast(.49f) &&
                    p.top.score >= median - .20f && p.top.score - p.runner >= .070f &&
                    (negativeCount == 0 || p.top.separation >= .105f) && p.top.negative < .72f
            }.sortedWith(compareByDescending<Pending> { it.top.separation }.thenByDescending { it.top.score })
                .take(2).forEach { p ->
                    if (p.day !in acceptedScores) {
                        acceptedScores[p.day] = (p.top.score * .90f).coerceIn(0f, .86f)
                        result[p.day] = result.getValue(p.day).copy(status="accepted_weekly_rescue", accepted=true)
                    }
                }
        }
        if (positiveCount >= 8 && acceptedScores.size in 1..2 && pending.isNotEmpty()) {
            val strongest = acceptedScores.values.maxOrNull() ?: 0f
            pending.filter { p ->
                p.day !in acceptedScores && p.top.score >= (p.floor - .15f).coerceAtLeast(.42f) &&
                    p.top.score >= strongest - .34f && p.top.score - p.runner >= .045f &&
                    (negativeCount == 0 || p.top.separation >= .080f) && p.top.negative < .76f
            }.sortedWith(compareByDescending<Pending> { it.top.separation }.thenByDescending { it.top.score })
                .take(5).forEach { p ->
                    if (p.day !in acceptedScores) {
                        acceptedScores[p.day] = (p.top.score * .86f).coerceIn(0f, .82f)
                        result[p.day] = result.getValue(p.day).copy(status="accepted_mature_profile_recovery", accepted=true)
                    }
                }
        }
        return (0..6).map { result.getValue(it) }
    }
}
