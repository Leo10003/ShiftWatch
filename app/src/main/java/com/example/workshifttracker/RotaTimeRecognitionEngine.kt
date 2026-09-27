package com.example.workshifttracker

import java.time.LocalTime
import kotlin.math.abs

/**
 * RotaVision v14 specialist time recognizer.
 *
 * Time labels are a tiny vocabulary, so this engine deliberately avoids treating them as generic
 * OCR text. It generates several plausible readings, scores OCR-confusion repairs, uses learned
 * workplace times only as soft priors, and exposes ambiguity rather than silently forcing a value.
 */
object RotaTimeRecognitionEngine {
    data class Hypothesis(
        val time: LocalTime,
        val score: Float,
        val normalized: String,
        val reason: String
    )

    data class Decision(
        val best: Hypothesis?,
        val runnerUp: Hypothesis?,
        val margin: Float,
        val ambiguous: Boolean
    )

    private val commonMinutes = setOf(0, 15, 30, 45)

    fun analyze(raw: String, learned: List<LocalTime> = emptyList()): Decision {
        // v17.1: never run OCR-confusion repair on arbitrary words.  Previously a name such as
        // SANJUTA could become "5" because S -> 5, and that stray digit was then promoted to
        // 05:00.  A token must first look like a time before letters such as O/I/S are repaired.
        if (!looksTimeLike(raw)) return Decision(null, null, 0f, true)
        val normalized = normalize(raw)
        if (normalized.isBlank()) return Decision(null, null, 0f, true)

        val scored = linkedMapOf<LocalTime, Pair<Float, String>>()
        fun offer(time: LocalTime?, base: Float, reason: String) {
            if (time == null) return
            var score = base
            if (time.minute in commonMinutes) score += 0.12f else score -= 0.13f
            if (time.hour in 5..23) score += 0.05f else score -= 0.10f
            if (time in learned) score += 0.12f
            if (learned.isNotEmpty() && time !in learned) {
                val nearest = learned.minOf { abs(it.toSecondOfDay() - time.toSecondOfDay()) / 60 }
                if (nearest <= 15) score += 0.04f
                if (nearest >= 180) score -= 0.04f
            }
            val old = scored[time]
            if (old == null || score > old.first) scored[time] = score to reason
        }

        Regex("(?<!\\d)([0-2]?\\d)\\s*[:.]\\s*([0-5]?\\d)(?!\\d)").findAll(normalized).forEach { m ->
            val h = m.groupValues[1].toIntOrNull()
            val mm = m.groupValues[2].toIntOrNull()
            if (h != null && mm != null) {
                if (mm in 0..59) offer(safe(h, mm), 0.78f, "explicit hour/minute label")
                if (mm in 3..5) offer(safe(h, mm * 10), 0.74f, "single-digit minute repaired")
            }
        }

        val digits = normalized.filter(Char::isDigit)
        when (digits.length) {
            1, 2 -> digits.toIntOrNull()?.let { offer(safe(it, 0), 0.46f, "hour-only label") }
            3 -> {
                val h = digits.substring(0, 1).toIntOrNull()
                val mm = digits.substring(1).toIntOrNull()
                if (h != null && mm != null) offer(safe(h, mm), 0.68f, "compact hMM label")
                val hh = digits.substring(0, 2).toIntOrNull()
                val m = digits.substring(2).toIntOrNull()
                if (hh != null && m != null && m in 0..5) offer(safe(hh, m * 10), 0.54f, "compact HHm label")
            }
            4 -> {
                val hh = digits.substring(0, 2).toIntOrNull()
                val mm = digits.substring(2).toIntOrNull()
                if (hh != null && mm != null) offer(safe(hh, mm), 0.72f, "compact HHMM label")
                // Common superscript corruption: 9º30 -> 9030.
                val h = digits.substring(0, 1).toIntOrNull()
                if (h != null && mm != null && h in 5..9 && mm in commonMinutes) {
                    offer(safe(h, mm), 0.76f, "superscript minute repair")
                }
            }
        }

        // Common OCR confusions that cannot be solved by character substitution alone.
        // A bare morning 900 is deliberately ambiguous with 9:30 unless local/row consensus exists.
        if (digits == "900") {
            offer(LocalTime.of(9, 0), 0.52f, "ambiguous compact 900")
            offer(LocalTime.of(9, 30), 0.50f, "possible lost superscript 30")
        }
        if (digits == "930") offer(LocalTime.of(9, 30), 0.82f, "common compact 09:30")

        val list = scored.map { (time, sr) ->
            Hypothesis(time, sr.first.coerceIn(0f, 0.99f), normalized, sr.second)
        }.sortedByDescending { it.score }
        val best = list.getOrNull(0)
        val runner = list.getOrNull(1)
        val margin = if (best == null) 0f else best.score - (runner?.score ?: 0f)
        // Compact bare 900 is genuinely ambiguous when the tiny superscript 30 is lost.
        // Never allow the high generic 09:00 parsing score to suppress that uncertainty.
        val ambiguous = best == null || best.score < 0.62f || (runner != null && margin < 0.10f) ||
            (digits == "900" && normalized.none { it == ':' || it == '.' })
        return Decision(best, runner, margin, ambiguous)
    }

    fun best(raw: String, learned: List<LocalTime> = emptyList(), allowAmbiguous: Boolean = false): Hypothesis? {
        val decision = analyze(raw, learned)
        return decision.best?.takeIf { allowAmbiguous || !decision.ambiguous }
    }


    /**
     * Conservative pre-filter for time OCR.  Letter substitutions are useful for strings such as
     * O9.3O or I6:OO, but dangerous on employee names.  We therefore allow only the small set of
     * characters that commonly substitute for digits/separators and require real numeric/time
     * structure whenever alphabetic confusion characters are present.
     */
    internal fun looksTimeLike(raw: String): Boolean {
        val text = normalizeUnicodeDigits(raw).uppercase().trim()
        if (text.isBlank() || text.length > 12) return false

        val allowedConfusionLetters = setOf('O', 'D', 'Q', 'G', 'I', 'L', 'S', 'B', 'H')
        val letters = text.filter(Char::isLetter)
        if (letters.any { it !in allowedConfusionLetters }) return false

        val digitCount = text.count(Char::isDigit)
        val hasSeparator = text.any { it in setOf(':', '.', ',', ';', 'º', '°', '·', '•') }

        // A bare OCR letter such as S/O/I is not time evidence.  Confusion repair is allowed only
        // when the token also contains a real digit or explicit time separator.
        if (letters.isNotEmpty() && digitCount == 0 && !hasSeparator) return false

        // Long alphabetic-looking tokens are overwhelmingly names, even if every character is in
        // the confusion alphabet by coincidence.
        if (letters.length >= 3 && digitCount <= 1 && !hasSeparator) return false

        // Pure numeric labels are useful, but keep them within plausible compact-time lengths.
        val compactDigits = text.count(Char::isDigit)
        if (letters.isEmpty() && !hasSeparator && compactDigits !in 1..4) return false

        return digitCount > 0 || hasSeparator
    }

    private fun normalize(raw: String): String = normalizeUnicodeDigits(raw).uppercase()
        .replace('O', '0').replace('D', '0').replace('Q', '9').replace('G', '9')
        .replace('I', '1').replace('L', '1').replace('|', '1')
        .replace('S', '5').replace('B', '8')
        .replace(',', '.').replace(';', ':').replace('H', ':')
        .replace('º', '.').replace('°', '.').replace('·', '.').replace('•', '.')
        .filter { it.isDigit() || it == ':' || it == '.' || it.isWhitespace() }
        .trim()


    private fun normalizeUnicodeDigits(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            append(
                when (ch) {
                    '⁰', '₀' -> '0'
                    '¹', '₁' -> '1'
                    '²', '₂' -> '2'
                    '³', '₃' -> '3'
                    '⁴', '₄' -> '4'
                    '⁵', '₅' -> '5'
                    '⁶', '₆' -> '6'
                    '⁷', '₇' -> '7'
                    '⁸', '₈' -> '8'
                    '⁹', '₉' -> '9'
                    else -> ch
                }
            )
        }
    }

    private fun safe(hour: Int, minute: Int): LocalTime? =
        if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
}
