package com.example.workshifttracker

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * v20 document-first week resolver.
 *
 * The planner week is only a fallback.  Explicit rota header dates are treated as document
 * evidence and scored as a coherent seven-day sequence.  This engine is deliberately independent
 * from OCR/UI classes so it can be regression tested without Android.
 */
object RotaDateAuthorityEngine {
    data class Token(
        val text: String,
        val column: Int,
        val yRatio: Float
    )

    data class Observation(
        val day: Int,
        val month: Int?,
        val column: Int,
        val explicit: Boolean,
        val raw: String
    )

    data class Resolution(
        val weekStart: LocalDate,
        val confidence: Float,
        val evidenceCount: Int,
        val explicitCount: Int,
        val authoritative: Boolean,
        val reason: String
    )

    fun resolve(
        tokens: List<Token>,
        today: LocalDate,
        fallbackWeekStart: LocalDate,
        explicitDateBottomRatio: Float,
        weakDateBottomRatio: Float
    ): Resolution {
        val observations = collectObservations(tokens, explicitDateBottomRatio, weakDateBottomRatio)
        val fallbackMonday = fallbackWeekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val nearestMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (observations.isEmpty()) {
            return Resolution(fallbackMonday, 0.18f, 0, 0, false, "no readable header dates")
        }

        val explicit = observations.filter { it.explicit && it.month != null }
        val candidateWeeks = linkedSetOf<LocalDate>()
        for (o in explicit) {
            val month = o.month ?: continue
            for (year in (today.year - 1)..(today.year + 1)) {
                val date = runCatching { LocalDate.of(year, month, o.day) }.getOrNull() ?: continue
                candidateWeeks.add(date.minusDays(o.column.toLong()))
            }
        }

        // If OCR only recovered day numbers, evaluate a broad but finite date window.  Header
        // geometry/column order then decides the sequence instead of the planner week.
        if (candidateWeeks.isEmpty()) {
            for (offset in -32..32) candidateWeeks.add(nearestMonday.plusWeeks(offset.toLong()))
        }

        data class Scored(val week: LocalDate, val score: Double, val exact: Int, val explicitExact: Int, val longestRun: Int)
        val scored = mutableListOf<Scored>()
        for (week in candidateWeeks) {
            if (week.dayOfWeek != DayOfWeek.MONDAY) continue
            var score = 0.0
            var exact = 0
            var explicitExact = 0
            for (o in observations) {
                val predicted = week.plusDays(o.column.toLong())
                val dayMatches = o.day == predicted.dayOfMonth
                val monthMatches = o.month == null || o.month == predicted.monthValue
                if (dayMatches && monthMatches) {
                    score += if (o.explicit) 15.0 else 6.5
                    exact++
                    if (o.explicit) explicitExact++
                } else {
                    if (o.explicit) {
                        score -= if (o.month != null && o.month != predicted.monthValue) 8.0 else 4.0
                    } else if (abs(o.day - predicted.dayOfMonth) <= 1) {
                        score += 0.25
                    } else {
                        score -= 0.75
                    }
                }
            }

            // Strongly reward a coherent run across distinct weekday columns.
            val matchedColumnList = observations.mapNotNull { o ->
                val p = week.plusDays(o.column.toLong())
                if (o.day == p.dayOfMonth && (o.month == null || o.month == p.monthValue)) o.column else null
            }.distinct().sorted()
            val matchedColumns = matchedColumnList.size
            var longestRun = 0
            var currentRun = 0
            var previousColumn: Int? = null
            for (column in matchedColumnList) {
                currentRun = if (previousColumn != null && column == previousColumn + 1) currentRun + 1 else 1
                if (currentRun > longestRun) longestRun = currentRun
                previousColumn = column
            }
            score += matchedColumns * matchedColumns * 1.15
            score += longestRun * 0.85

            // Time proximity is only a tie-breaker.  It must never beat coherent document dates.
            val todayDistance = abs(java.time.temporal.ChronoUnit.DAYS.between(today, week)).toDouble()
            score -= todayDistance / 180.0
            scored.add(Scored(week, score, exact, explicitExact, longestRun))
        }

        val ranked = scored.sortedByDescending { it.score }
        val best = ranked.firstOrNull()
            ?: return Resolution(fallbackMonday, 0.18f, observations.size, explicit.size, false, "no valid date sequence")
        val runner = ranked.getOrNull(1)
        val margin = best.score - (runner?.score ?: (best.score - 10.0))

        val authoritative = best.explicitExact >= 3 ||
            (best.explicitExact >= 2 && best.longestRun >= 2 && margin >= 4.0) ||
            (best.exact >= 5 && best.longestRun >= 4)
        val confidence = when {
            best.explicitExact >= 4 && best.longestRun >= 4 -> 0.995f
            best.explicitExact >= 3 && best.longestRun >= 3 -> 0.985f
            best.explicitExact >= 2 && best.longestRun >= 2 && margin >= 4.0 -> 0.95f
            best.exact >= 5 && best.longestRun >= 4 -> 0.91f
            // Bare day-only dates do not establish a month, but four consecutive weekday
            // headers close to today's week can still propose a useful unverified date.
            // No month means NEVER authoritative: the user must still confirm it.
            best.explicitExact == 0 && best.exact >= 4 && best.longestRun >= 4 &&
                abs(java.time.temporal.ChronoUnit.DAYS.between(today, best.week)) <= 14 -> 0.85f
            best.exact >= 4 && best.longestRun >= 3 && margin >= 3.0 -> 0.84f
            best.exact >= 3 && best.longestRun >= 2 -> 0.70f
            else -> 0.48f
        }
        val chosen = if (authoritative || confidence >= 0.70f) best.week else fallbackMonday
        val reason = when {
            best.explicitExact >= 2 -> "explicit rota header sequence"
            best.exact >= 4 -> "weekday/date sequence"
            chosen == fallbackMonday -> "planner fallback; weak header evidence"
            else -> "partial header sequence"
        }
        return Resolution(chosen, confidence, observations.size, explicit.size, authoritative, reason)
    }

    fun collectObservations(
        tokens: List<Token>,
        explicitDateBottomRatio: Float,
        weakDateBottomRatio: Float
    ): List<Observation> {
        val out = mutableListOf<Observation>()
        for (token in tokens) {
            if (token.column !in 0..6) continue
            if (token.yRatio > explicitDateBottomRatio) continue
            val parsed = parse(token.text) ?: continue
            if (!parsed.explicit && token.yRatio > weakDateBottomRatio) continue
            val item = parsed.copy(column = token.column)
            if (out.none { it.day == item.day && it.month == item.month && it.column == item.column && it.explicit == item.explicit }) {
                out.add(item)
            }
        }

        // Rebuild split DD + MM fragments in the same column.  Keep only close header fragments
        // and never combine values outside calendar ranges.
        for (column in 0..6) {
            val fragments = tokens.filter { it.column == column && it.yRatio <= explicitDateBottomRatio }
                .mapNotNull { token ->
                    val digits = normalize(token.text).filter { it.isDigit() }
                    if (digits.length in 1..2) token to digits.toIntOrNull() else null
                }
                .filter { it.second != null }
            for (a in fragments.indices) {
                val day = fragments[a].second ?: continue
                if (day !in 1..31) continue
                for (b in fragments.indices) {
                    if (a == b) continue
                    val month = fragments[b].second ?: continue
                    if (month !in 1..12) continue
                    if (abs(fragments[a].first.yRatio - fragments[b].first.yRatio) > 0.025f) continue
                    val item = Observation(day, month, column, true, "$day.$month")
                    if (out.none { it.day == day && it.month == month && it.column == column }) out.add(item)
                }
            }
        }
        return out
    }

    private fun parse(raw: String): Observation? {
        val text = normalize(raw)
        val explicit = Regex("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)").find(text)
        if (explicit != null) {
            val d = explicit.groupValues[1].toIntOrNull()
            val m = explicit.groupValues[2].toIntOrNull()
            if (d != null && m != null && d in 1..31 && m in 1..12) return Observation(d, m, -1, true, raw)
        }
        val compact = text.filter { it.isDigit() }
        if (compact.length == 4) {
            val d = compact.substring(0, 2).toIntOrNull()
            val m = compact.substring(2, 4).toIntOrNull()
            if (d != null && m != null && d in 1..31 && m in 1..12) return Observation(d, m, -1, true, raw)
        }
        val day = Regex("(?<!\\d)(\\d{1,2})(?!\\d)").findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in 1..31 }
        return day?.let { Observation(it, null, -1, false, raw) }
    }

    /**
     * Geometry-independent fallback for OCR that reads the header dates but loses reliable box
     * placement. Three or more explicit DD.MM values inside one calendar week are enough to
     * recover that Monday. This never consults the planner week except as a final fallback.
     */
    fun resolveTextSequence(
        rawText: String,
        today: LocalDate,
        fallbackWeekStart: LocalDate
    ): Resolution {
        val fallbackMonday = fallbackWeekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (rawText.isBlank()) return Resolution(fallbackMonday, 0.18f, 0, 0, false, "no OCR text")

        data class HeaderDate(val day: Int, val month: Int, val index: Int)
        val matches = Regex("(?<!\\d)([0-3]?\\d)\\s*[./-]\\s*([01]?\\d)(?!\\d)")
            .findAll(normalize(rawText))
            .mapIndexedNotNull { index, match ->
                val day = match.groupValues[1].toIntOrNull()
                val month = match.groupValues[2].toIntOrNull()
                if (day != null && month != null && day in 1..31 && month in 1..12) HeaderDate(day, month, index) else null
            }
            .toList()
        if (matches.size < 3) return Resolution(fallbackMonday, 0.25f, matches.size, matches.size, false, "too few explicit header dates")

        data class RunCandidate(
            val monday: LocalDate,
            val runLength: Int,
            val distinctDays: Int,
            val startIndex: Int,
            val distanceDays: Long
        )

        val candidates = mutableListOf<RunCandidate>()
        for (start in matches.indices) {
            for (year in (today.year - 1)..(today.year + 1)) {
                val first = runCatching { LocalDate.of(year, matches[start].month, matches[start].day) }.getOrNull() ?: continue
                var previous = first
                var runLength = 1
                val seen = linkedSetOf<LocalDate>()
                seen.add(first)

                for (i in (start + 1) until matches.size) {
                    val item = matches[i]
                    val expected = previous.plusDays(1)
                    val possibilities = listOf(expected.year - 1, expected.year, expected.year + 1).mapNotNull { y ->
                        runCatching { LocalDate.of(y, item.month, item.day) }.getOrNull()
                    }
                    val exactNext = possibilities.firstOrNull { it == expected }
                    if (exactNext != null) {
                        previous = exactNext
                        runLength++
                        seen.add(exactNext)
                        continue
                    }
                    // OCR often repeats one header token across passes. Ignore exact duplicates,
                    // but stop as soon as the calendar sequence genuinely breaks.
                    val duplicate = possibilities.any { it == previous }
                    if (duplicate) continue
                    break
                }

                if (runLength >= 3) {
                    val monday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val distance = abs(java.time.temporal.ChronoUnit.DAYS.between(today, monday))
                    candidates.add(RunCandidate(monday, runLength, seen.size, start, distance))
                }
            }
        }

        val best = candidates.maxWithOrNull(
            compareBy<RunCandidate> { it.runLength }
                .thenBy { it.distinctDays }
                .thenBy { -it.distanceDays.toInt() }
                .thenBy { -it.startIndex }
        ) ?: return Resolution(fallbackMonday, 0.25f, matches.size, matches.size, false, "no ordered consecutive header sequence")

        val authoritative = best.runLength >= 4
        val confidence = when {
            best.runLength >= 7 -> 0.999f
            best.runLength >= 6 -> 0.995f
            best.runLength >= 5 -> 0.985f
            best.runLength >= 4 -> 0.97f
            else -> 0.90f
        }
        return Resolution(
            weekStart = if (authoritative || confidence >= 0.90f) best.monday else fallbackMonday,
            confidence = confidence,
            evidenceCount = matches.size,
            explicitCount = matches.size,
            authoritative = authoritative,
            reason = "ordered consecutive header dates"
        )
    }

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return ascii
            .replace('O', '0', ignoreCase = true)
            .replace('I', '1', ignoreCase = true)
            .replace('l', '1')
            .replace(',', '.')
    }
}
