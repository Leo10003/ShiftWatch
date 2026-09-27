package com.example.workshifttracker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Single source of truth for recorded and planned shifts.
 *
 * Reliability goals:
 * - all writes are serialized, so widget/notification/activity actions cannot overwrite each other;
 * - every successful write keeps a last-known-good JSON backup;
 * - malformed primary JSON automatically falls back to its backup;
 * - invalid/overlapping shift candidates are rejected before persistence;
 * - export/import is local JSON only and requires no account or network.
 */
class ShiftStore(val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    // Session-only monotonic revision. A slow older JSON checkpoint must never resurrect a
    // cleared session or replace a more recent manual correction after asynchronous IO.

    data class Shift(
        val id: String,
        val start: LocalDateTime,
        val end: LocalDateTime?
    ) {
        fun durationMinutes(): Long = if (end == null) 0 else Duration.between(start, end).toMinutes().coerceAtLeast(0)
    }

    data class PlannedShift(
        val id: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val source: String = "manual"
    ) {
        fun durationMinutes(): Long = Duration.between(start, end).toMinutes().coerceAtLeast(0)
    }

    data class IntegrityReport(
        val recordedCount: Int,
        val plannedCount: Int,
        val recoveredRecordedBackup: Boolean,
        val recoveredPlannedBackup: Boolean,
        val multipleActiveShifts: Int,
        val invalidRecorded: Int,
        val invalidPlanned: Int
    ) {
        val hasWarnings: Boolean
            get() = recoveredRecordedBackup || recoveredPlannedBackup || multipleActiveShifts > 1 || invalidRecorded > 0 || invalidPlanned > 0
    }

    data class BackupImportResult(
        val recordedAdded: Int,
        val plannedAdded: Int,
        val skipped: Int
    )

    data class RotaImportSession(
        val employeeName: String,
        val typicalShiftHours: Int,
        val imageUri: String,
        val rawText: String,
        val drafts: List<ScheduleImporter.Draft>,
        val assistData: ScheduleImporter.AssistData,
        val savedAt: String,
        val confirmedWeek: LocalDate? = null,
        val selectedIds: Set<String> = emptySet(),
        val touchedWeekdays: Set<Int> = emptySet(),
        val suppressLateSuggestions: Boolean = false
    )

    private data class ParseResult<T>(val items: List<T>, val invalidCount: Int, val validJson: Boolean)

    fun allPlanned(): List<PlannedShift> = readPlanned().items.sortedBy { it.start }

    fun upsertPlanned(shift: PlannedShift) {
        require(validatePlannedCandidate(shift.start, shift.end, shift.id) == null) {
            validatePlannedCandidate(shift.start, shift.end, shift.id) ?: "Invalid planned shift"
        }
        synchronized(WRITE_LOCK) {
            val list = allPlanned().filterNot { it.id == shift.id }.toMutableList()
            list += shift
            savePlannedLocked(list)
        }
    }

    fun deletePlanned(id: String) = synchronized(WRITE_LOCK) {
        savePlannedLocked(allPlanned().filterNot { it.id == id })
    }

    fun addPlanned(shifts: List<PlannedShift>): List<PlannedShift> {
        if (shifts.isEmpty()) return emptyList()
        return synchronized(WRITE_LOCK) {
            val current = allPlanned().toMutableList()
            val existingKeys = current.map { it.start to it.end }.toMutableSet()
            val inserted = mutableListOf<PlannedShift>()
            shifts.sortedBy { it.start }.forEach { shift ->
                val overlaps = current.any { existing -> intervalsOverlap(shift.start, shift.end, existing.start, existing.end) }
                if (validatePlannedBasics(shift.start, shift.end) == null && (shift.start to shift.end) !in existingKeys && !overlaps) {
                    current += shift
                    inserted += shift
                    existingKeys += shift.start to shift.end
                }
            }
            if (inserted.isNotEmpty()) savePlannedLocked(current)
            inserted.toList()
        }
    }

    /** All-or-nothing reviewed import: no partial planner writes or silent dropped shifts. */
    data class BatchInsertResult(val inserted: List<PlannedShift>, val error: String? = null)

    fun addReviewedPlannedAtomically(shifts: List<PlannedShift>): BatchInsertResult {
        if (shifts.isEmpty()) return BatchInsertResult(emptyList(), "No shifts were selected")
        return synchronized(WRITE_LOCK) {
            val existing = allPlanned()
            val pending = mutableListOf<PlannedShift>()
            for (shift in shifts.sortedBy { it.start }) {
                val basics = validatePlannedBasics(shift.start, shift.end)
                if (basics != null) return@synchronized BatchInsertResult(emptyList(), basics)
                val conflict = (existing + pending).firstOrNull {
                    intervalsOverlap(shift.start, shift.end, it.start, it.end)
                }
                if (conflict != null) return@synchronized BatchInsertResult(emptyList(),
                    "A shift overlaps an existing plan (${conflict.start}). Nothing has been imported.")
                pending += shift
            }
            savePlannedLocked(existing + pending)
            BatchInsertResult(pending.toList())
        }
    }

    fun all(): List<Shift> = readShifts().items.sortedByDescending { it.start }

    fun active(): Shift? = all().firstOrNull { it.end == null }

    fun shiftsFor(month: YearMonth): List<Shift> = all().filter { YearMonth.from(it.start) == month }

    fun totalMinutes(month: YearMonth): Long = shiftsFor(month).sumOf { it.durationMinutes() }

    fun totalMinutesFor(date: LocalDate): Long = all()
        .filter { it.start.toLocalDate() == date && it.end != null }
        .sumOf { it.durationMinutes() }

    fun toggleNow(): Shift = synchronized(WRITE_LOCK) {
        val current = active()
        if (current == null) {
            val created = Shift(UUID.randomUUID().toString(), roundToHalfHour(LocalDateTime.now()), null)
            upsertLocked(created)
            created
        } else {
            endActiveNowLocked() ?: current
        }
    }

    /** Ends the current shift without accidentally creating a new one if none is active. */
    fun endActiveNow(): Shift? = synchronized(WRITE_LOCK) { endActiveNowLocked() }

    private fun endActiveNowLocked(): Shift? {
        val current = active() ?: return null
        var roundedEnd = roundToHalfHour(LocalDateTime.now())
        // Start/end rounding can collapse a short real shift onto the same half-hour. Persisting
        // that zero-length interval violates the store invariant and made the shift disappear on
        // the next parse. Keep completed shifts valid with the smallest supported 30-minute unit.
        if (!roundedEnd.isAfter(current.start)) roundedEnd = current.start.plusMinutes(30)
        val closed = current.copy(end = roundedEnd)
        upsertLocked(closed)
        return closed
    }

    fun upsert(shift: Shift) {
        val error = validateRecordedCandidate(shift.start, shift.end, shift.id)
        require(error == null) { error ?: "Invalid shift" }
        synchronized(WRITE_LOCK) { upsertLocked(shift) }
    }

    private fun upsertLocked(shift: Shift) {
        val list = all().filterNot { it.id == shift.id }.toMutableList()
        list += shift
        saveShiftsLocked(list)
    }

    fun delete(id: String) = synchronized(WRITE_LOCK) {
        saveShiftsLocked(all().filterNot { it.id == id })
    }

    /** Returns a user-facing validation message, or null when the shift is safe to save. */
    fun validateRecordedCandidate(start: LocalDateTime, end: LocalDateTime?, excludingId: String? = null): String? {
        val now = LocalDateTime.now()
        if (start.isAfter(now.plusMinutes(20))) return "Worked shifts cannot start in the future. Use Planner for upcoming shifts."
        if (end != null) {
            if (!end.isAfter(start)) return "End time must be after start time."
            val minutes = Duration.between(start, end).toMinutes()
            if (minutes > MAX_RECORDED_SHIFT_MINUTES) return "A recorded shift cannot be longer than 36 hours."
            if (end.isAfter(now.plusMinutes(20))) return "Worked shifts cannot end in the future."
        }

        val openEnd = end ?: LocalDateTime.MAX
        val conflict = all().firstOrNull { existing ->
            existing.id != excludingId && intervalsOverlap(start, openEnd, existing.start, existing.end ?: LocalDateTime.MAX)
        }
        if (conflict != null) return "This shift overlaps another recorded shift. Edit the existing shift first."

        if (end == null) {
            val otherActive = all().firstOrNull { it.id != excludingId && it.end == null }
            if (otherActive != null) return "Another shift is already in progress."
        }
        return null
    }

    fun validatePlannedCandidate(start: LocalDateTime, end: LocalDateTime, excludingId: String? = null): String? {
        validatePlannedBasics(start, end)?.let { return it }
        val conflict = allPlanned().firstOrNull { existing ->
            existing.id != excludingId && intervalsOverlap(start, end, existing.start, existing.end)
        }
        if (conflict != null) return "This planned shift overlaps another planned shift."
        return null
    }

    private fun validatePlannedBasics(start: LocalDateTime, end: LocalDateTime): String? {
        if (!end.isAfter(start)) return "End time must be after start time."
        val minutes = Duration.between(start, end).toMinutes()
        if (minutes > MAX_PLANNED_SHIFT_MINUTES) return "A planned shift cannot be longer than 36 hours."
        return null
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun savedEmployeeName(): String = prefs.getString(KEY_EMPLOYEE_NAME, "")?.trim().orEmpty()

    fun savedTypicalShiftHours(): Int = prefs.getInt(KEY_TYPICAL_SHIFT_HOURS, 8).coerceIn(1, 16)

    fun savedRotaVisionProfile(employeeName: String): String? {
        val key = visionProfileKey(employeeName)
        if (key.isBlank()) return null
        val raw = prefs.getString(KEY_ROTA_VISION_PROFILES, "{}") ?: "{}"
        return runCatching { JSONObject(raw).optString(key).takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun saveRotaVisionProfile(employeeName: String, profile: String) {
        val key = visionProfileKey(employeeName)
        if (key.isBlank() || profile.isBlank()) return
        synchronized(WRITE_LOCK) {
            val raw = prefs.getString(KEY_ROTA_VISION_PROFILES, "{}") ?: "{}"
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            json.put(key, profile)
            prefs.edit().putString(KEY_ROTA_VISION_PROFILES, json.toString()).apply()
        }
    }

    private fun visionProfileKey(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
        .filter { it.isLetterOrDigit() }


    fun savedRotaTemplate(employeeName: String): ScheduleImporter.TemplateSnapshot? = savedRotaTemplates(employeeName).firstOrNull()

    fun savedRotaTemplates(employeeName: String): List<ScheduleImporter.TemplateSnapshot> {
        val key = visionProfileKey(employeeName)
        if (key.isBlank()) return emptyList()
        val raw = prefs.getString(KEY_ROTA_TEMPLATES, "{}") ?: "{}"
        return runCatching {
            val root = JSONObject(raw)
            val value = root.opt(key) ?: return@runCatching emptyList()
            val objects = when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                is JSONObject -> listOf(value) // v9 migration path
                else -> emptyList()
            }
            objects.mapNotNull(::parseRotaTemplate)
        }.getOrElse { emptyList() }
    }

    private fun parseRotaTemplate(obj: JSONObject): ScheduleImporter.TemplateSnapshot? {
        fun floats(name: String): List<Float> {
            val a = obj.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optDouble(i).takeIf { !it.isNaN() }?.toFloat() }
        }
        fun strings(name: String): List<String> {
            val a = obj.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }
        }
        val columns = floats("columns")
        if (columns.size != 7) return null
        return ScheduleImporter.TemplateSnapshot(
            columnCenters = columns,
            rowRatios = floats("rows"),
            canonicalTimes = strings("times"),
            fingerprint = obj.optString("fingerprint", ""),
            documentKind = obj.optString("kind", ScheduleImporter.DocumentKind.UNKNOWN.name),
            qualityScore = obj.optDouble("quality", 0.0).toFloat(),
            timeVocabulary = strings("vocabulary"),
            observations = obj.optInt("observations", 1).coerceAtLeast(1)
        )
    }

    private fun rotaTemplateJson(template: ScheduleImporter.TemplateSnapshot): JSONObject = JSONObject().apply {
        put("columns", JSONArray(template.columnCenters))
        put("rows", JSONArray(template.rowRatios))
        put("times", JSONArray(template.canonicalTimes))
        put("fingerprint", template.fingerprint)
        put("kind", template.documentKind)
        put("quality", template.qualityScore.toDouble())
        put("vocabulary", JSONArray(template.timeVocabulary))
        put("observations", template.observations)
    }

    fun saveRotaTemplate(employeeName: String, template: ScheduleImporter.TemplateSnapshot) {
        val key = visionProfileKey(employeeName)
        if (key.isBlank() || template.columnCenters.size != 7) return
        synchronized(WRITE_LOCK) {
            val raw = prefs.getString(KEY_ROTA_TEMPLATES, "{}") ?: "{}"
            val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            val current = savedRotaTemplates(employeeName).toMutableList()
            val similarIndex = current.indices.maxByOrNull { i ->
                val other = current[i]
                if (other.fingerprint.isNotBlank() && other.fingerprint == template.fingerprint) 1000f
                else other.columnCenters.zip(template.columnCenters).map { (a, b) -> kotlin.math.abs(a - b) }.average().toFloat() * -1f
            }?.takeIf { i ->
                val other = current[i]
                other.fingerprint == template.fingerprint || other.columnCenters.zip(template.columnCenters).map { (a, b) -> kotlin.math.abs(a - b) }.average() < 0.035
            }
            if (similarIndex != null) {
                val old = current[similarIndex]
                // v19.2: accumulate calibrated structural rows instead of replacing the old
                // mapping with whichever subset happened to be visible in the newest import.
                // New values win for the same block, while untouched learned blocks are retained.
                val mergedTimes = linkedMapOf<String, String>()
                (old.canonicalTimes + template.canonicalTimes).forEach { encoded ->
                    val keyForRow = encoded.substringBefore('|').takeIf { it.startsWith("B") } ?: encoded
                    mergedTimes[keyForRow] = encoded
                }
                current[similarIndex] = template.copy(
                    canonicalTimes = mergedTimes.values.toList().takeLast(16),
                    timeVocabulary = (old.timeVocabulary + template.timeVocabulary).distinct().takeLast(16),
                    observations = old.observations + 1,
                    qualityScore = maxOf(old.qualityScore, template.qualityScore)
                )
            } else {
                current += template
            }
            val kept = current.sortedWith(compareByDescending<ScheduleImporter.TemplateSnapshot> { it.observations }.thenByDescending { it.qualityScore }).take(5)
            val array = JSONArray()
            kept.forEach { array.put(rotaTemplateJson(it)) }
            root.put(key, array)
            prefs.edit().putString(KEY_ROTA_TEMPLATES, root.toString()).apply()
        }
    }


    fun saveRotaImportSession(
        employeeName: String,
        typicalShiftHours: Int,
        imageUri: String,
        rawText: String,
        drafts: List<ScheduleImporter.Draft>,
        assistData: ScheduleImporter.AssistData,
        confirmedWeek: LocalDate? = null,
        selectedIds: Set<String> = emptySet(),
        touchedWeekdays: Set<Int> = emptySet(),
        suppressLateSuggestions: Boolean = false,
        sessionEpoch: String = ""
    ) {
        val writeRevision = synchronized(WRITE_LOCK) {
            if (sessionEpoch.isNotBlank() &&
                (sessionEpoch in pausedImportEpochs || sessionEpoch in closedImportEpochs)) -1L
            else ++sessionWriteRevisionGlobal
        }
        if (writeRevision < 0L) return
        val root = JSONObject().apply {
            put("employee", employeeName)
            put("hours", typicalShiftHours.coerceIn(1, 16))
            put("uri", imageUri)
            put("raw", rawText.take(20000))
            put("savedAt", LocalDateTime.now().format(iso))
            put("confirmedWeek", confirmedWeek?.toString() ?: "")
            put("selected", JSONArray(selectedIds.sorted()))
            put("touchedWeekdays", JSONArray(touchedWeekdays.sorted()))
            put("suppressLateSuggestions", suppressLateSuggestions)
            put("drafts", JSONArray().apply {
                drafts.forEach { d ->
                    put(JSONObject().apply {
                        put("id", d.id); put("start", d.start.format(iso)); put("end", d.end.format(iso))
                        put("source", d.sourceLine); put("confidence", d.confidence.toDouble())
                        put("estimatedEnd", d.estimatedEnd); put("column", d.columnIndex ?: -1)
                        put("origin", d.origin.name); put("requiresTime", d.requiresTimeConfirmation)
                        put("verification", d.verificationState.name)
                        put("notes", JSONArray(d.verificationNotes))
                        put("humanDate", d.userConfirmedDate)
                        put("humanTime", d.userConfirmedTime)
                        put("humanIdentity", d.userConfirmedIdentity)
                        put("block", d.physicalBlockId ?: -1)
                    })
                }
            })
            put("assist", JSONObject().apply {
                put("w", assistData.imageWidth); put("h", assistData.imageHeight); put("passes", assistData.ocrPasses)
                put("kind", assistData.documentKind.name); put("printed", assistData.printedConfidence.toDouble())
                put("template", assistData.templateFingerprint)
                put("vocabulary", JSONArray(assistData.learnedTimeVocabulary.map { it.toString() }))
                put("vertical", JSONArray(assistData.verticalRules))
                put("rows", JSONArray().apply { assistData.rowBoundaries.forEach { put(JSONArray(it)) } })
                put("semanticBands", JSONArray().apply { assistData.semanticTimeBands.forEach { band -> put(JSONObject().apply { put("y", band.yRatio.toDouble()); put("time", band.time.toString()); put("confidence", band.confidence.toDouble()); put("support", band.supportColumns); put("learned", band.learned); put("block", band.blockIndex ?: -1); put("confirmations", band.confirmations); put("contradictions", band.contradictions) }) } })
                put("tokens", JSONArray().apply {
                    assistData.tokens.take(1200).forEach { t ->
                        put(JSONObject().apply { put("t", t.text); put("l", t.left); put("top", t.top); put("r", t.right); put("b", t.bottom); put("src", t.source.name); put("block", t.blockHint ?: -1) })
                    }
                })
                put("quality", JSONObject().apply {
                    put("contrast", assistData.quality.contrast.toDouble()); put("sharpness", assistData.quality.sharpness.toDouble())
                    put("grid", assistData.quality.gridCompleteness.toDouble()); put("crop", assistData.quality.cropCoverage.toDouble())
                    put("score", assistData.quality.score.toDouble()); put("warnings", JSONArray(assistData.quality.warnings))
                })
            })
        }
        synchronized(WRITE_LOCK) {
            if (writeRevision == sessionWriteRevisionGlobal &&
                (sessionEpoch.isBlank() ||
                    (sessionEpoch !in pausedImportEpochs && sessionEpoch !in closedImportEpochs))) {
                prefs.edit().putString(KEY_ROTA_IMPORT_SESSION, root.toString()).commit()
            }
        }
    }

    fun savedRotaImportSession(): RotaImportSession? {
        val raw = prefs.getString(KEY_ROTA_IMPORT_SESSION, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val assistJson = root.getJSONObject("assist")
            fun floatList(name: String): List<Float> {
                val a = assistJson.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).map { a.optDouble(it).toFloat() }
            }
            val tokensArr = assistJson.optJSONArray("tokens") ?: JSONArray()
            val tokens = (0 until tokensArr.length()).mapNotNull { i ->
                val o = tokensArr.optJSONObject(i) ?: return@mapNotNull null
                ScheduleImporter.AssistToken(
                    o.optString("t"), o.optInt("l"), o.optInt("top"), o.optInt("r"), o.optInt("b"),
                    source = runCatching { ScheduleImporter.TokenSource.valueOf(o.optString("src", ScheduleImporter.TokenSource.PAGE_OCR.name)) }.getOrDefault(ScheduleImporter.TokenSource.PAGE_OCR),
                    blockHint = o.optInt("block", -1).takeIf { it >= 0 }
                )
            }
            val rowsArr = assistJson.optJSONArray("rows") ?: JSONArray()
            val rows = (0 until 7).map { col ->
                val a = rowsArr.optJSONArray(col) ?: JSONArray()
                (0 until a.length()).map { a.optDouble(it).toFloat() }
            }
            val vocabArr = assistJson.optJSONArray("vocabulary") ?: JSONArray()
            val vocab = (0 until vocabArr.length()).mapNotNull { i -> runCatching { LocalTime.parse(vocabArr.optString(i)) }.getOrNull() }
            val q = assistJson.optJSONObject("quality") ?: JSONObject()
            val warningsArr = q.optJSONArray("warnings") ?: JSONArray()
            val semanticArr = assistJson.optJSONArray("semanticBands") ?: JSONArray()
            val semanticBands = (0 until semanticArr.length()).mapNotNull { i ->
                val o = semanticArr.optJSONObject(i) ?: return@mapNotNull null
                val time = runCatching { LocalTime.parse(o.optString("time")) }.getOrNull() ?: return@mapNotNull null
                RotaSemanticTimeBands.Band(
                    yRatio = o.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f),
                    time = time,
                    confidence = o.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                    supportColumns = o.optInt("support", 0).coerceAtLeast(0),
                    learned = o.optBoolean("learned", false),
                    blockIndex = o.optInt("block", -1).takeIf { it >= 0 },
                    confirmations = o.optInt("confirmations", 0).coerceAtLeast(0),
                    contradictions = o.optInt("contradictions", 0).coerceAtLeast(0)
                )
            }
            val quality = ScheduleImporter.QualityAssessment(
                contrast = q.optDouble("contrast", 0.0).toFloat(), sharpness = q.optDouble("sharpness", 0.0).toFloat(),
                gridCompleteness = q.optDouble("grid", 0.0).toFloat(), cropCoverage = q.optDouble("crop", 1.0).toFloat(),
                score = q.optDouble("score", 0.0).toFloat(),
                warnings = (0 until warningsArr.length()).mapNotNull { warningsArr.optString(it).takeIf(String::isNotBlank) }
            )
            val restoredAssist = ScheduleImporter.AssistData(
                imageWidth = assistJson.optInt("w", 1), imageHeight = assistJson.optInt("h", 1), tokens = tokens,
                rowBoundaries = rows, verticalRules = floatList("vertical"), ocrPasses = assistJson.optInt("passes", 1),
                documentKind = runCatching { ScheduleImporter.DocumentKind.valueOf(assistJson.optString("kind")) }.getOrDefault(ScheduleImporter.DocumentKind.UNKNOWN),
                printedConfidence = assistJson.optDouble("printed", 0.0).toFloat(), quality = quality,
                templateFingerprint = assistJson.optString("template", ""), learnedTimeVocabulary = vocab,
                semanticTimeBands = semanticBands
            )
            // Perception is derived from tokens/geometry rather than persisted. Rebuild it so a
            // resumed review has the same verifier behavior as the original live analysis.
            val assist = restoredAssist.copy(perception = RotaPerceptionEngine.assess(restoredAssist))
            val draftsArr = root.optJSONArray("drafts") ?: JSONArray()
            val drafts = (0 until draftsArr.length()).mapNotNull { i ->
                val o = draftsArr.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    val notesArr = o.optJSONArray("notes") ?: JSONArray()
                    ScheduleImporter.Draft(
                        id = o.optString("id"), start = LocalDateTime.parse(o.getString("start"), iso), end = LocalDateTime.parse(o.getString("end"), iso),
                        sourceLine = o.optString("source"), confidence = o.optDouble("confidence", 0.0).toFloat(), estimatedEnd = o.optBoolean("estimatedEnd"),
                        columnIndex = o.optInt("column", -1).takeIf { it >= 0 },
                        origin = runCatching { ScheduleImporter.DraftOrigin.valueOf(o.optString("origin")) }.getOrDefault(ScheduleImporter.DraftOrigin.UNKNOWN),
                        requiresTimeConfirmation = o.optBoolean("requiresTime"),
                        verificationState = runCatching { RotaVerificationEngine.State.valueOf(o.optString("verification")) }.getOrDefault(RotaVerificationEngine.State.REVIEW),
                        verificationNotes = (0 until notesArr.length()).mapNotNull { notesArr.optString(it).takeIf(String::isNotBlank) },
                        userConfirmedDate = o.optBoolean("humanDate"),
                        userConfirmedTime = o.optBoolean("humanTime"),
                        userConfirmedIdentity = o.optBoolean("humanIdentity"),
                        physicalBlockId = o.optInt("block", -1).takeIf { it >= 0 }
                    )
                }.getOrNull()
            }
            RotaImportSession(
                employeeName = root.optString("employee"), typicalShiftHours = root.optInt("hours", 8).coerceIn(1, 16),
                imageUri = root.optString("uri"), rawText = root.optString("raw"), drafts = drafts, assistData = assist,
                savedAt = root.optString("savedAt"),
                confirmedWeek = root.optString("confirmedWeek").takeIf { it.isNotBlank() }?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
                selectedIds = (root.optJSONArray("selected") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
                },
                touchedWeekdays = (root.optJSONArray("touchedWeekdays") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { arr.optInt(it) }.filter { it in 0..6 }.toSet()
                },
                suppressLateSuggestions = root.optBoolean("suppressLateSuggestions", false)
            )
        }.getOrNull()
    }

    fun pauseImportCheckpoint(epoch: String) = synchronized(WRITE_LOCK) {
        if (epoch.isNotBlank()) pausedImportEpochs.add(epoch)
        ++sessionWriteRevisionGlobal // invalidate already-serialized but not yet committed checkpoint
    }

    fun resumeImportCheckpoint(epoch: String) = synchronized(WRITE_LOCK) {
        pausedImportEpochs.remove(epoch)
        ++sessionWriteRevisionGlobal
    }

    fun finishImportCheckpoint(epoch: String) = synchronized(WRITE_LOCK) {
        if (epoch.isNotBlank()) {
            pausedImportEpochs.remove(epoch)
            closedImportEpochs.add(epoch)
        }
        ++sessionWriteRevisionGlobal
        prefs.edit().remove(KEY_ROTA_IMPORT_SESSION).commit()
    }

    fun clearRotaImportSession() {
        synchronized(WRITE_LOCK) {
            ++sessionWriteRevisionGlobal
            prefs.edit().remove(KEY_ROTA_IMPORT_SESSION).commit()
        }
    }

    fun isKnownRotaFingerprint(fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        val raw = prefs.getString(KEY_ROTA_FINGERPRINTS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).any { arr.optString(it) == fingerprint }
        }.getOrDefault(false)
    }

    fun rememberRotaFingerprint(fingerprint: String) {
        if (fingerprint.isBlank()) return
        synchronized(WRITE_LOCK) {
            val raw = prefs.getString(KEY_ROTA_FINGERPRINTS, "[]") ?: "[]"
            val current = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.getOrDefault(emptyList())
            val kept = (current.filterNot { it == fingerprint } + fingerprint).takeLast(40)
            prefs.edit().putString(KEY_ROTA_FINGERPRINTS, JSONArray(kept).toString()).apply()
        }
    }

    fun recordRotaCorrection(kind: String, before: String, after: String) {
        if (kind.isBlank()) return
        synchronized(WRITE_LOCK) {
            val raw = prefs.getString(KEY_ROTA_CORRECTIONS, "[]") ?: "[]"
            val old = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
            val list = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.takeLast(149).toMutableList()
            list += JSONObject().apply {
                put("at", LocalDateTime.now().format(iso))
                put("kind", kind)
                put("before", before.take(180))
                put("after", after.take(180))
            }
            val out = JSONArray(); list.forEach { out.put(it) }
            prefs.edit().putString(KEY_ROTA_CORRECTIONS, out.toString()).apply()
        }
    }

    fun rotaCorrectionCount(): Int = runCatching {
        JSONArray(prefs.getString(KEY_ROTA_CORRECTIONS, "[]") ?: "[]").length()
    }.getOrDefault(0)

    fun saveImportProfile(employeeName: String, typicalShiftHours: Int) {
        prefs.edit()
            .putString(KEY_EMPLOYEE_NAME, employeeName.trim())
            .putInt(KEY_TYPICAL_SHIFT_HOURS, typicalShiftHours.coerceIn(1, 16))
            .apply()
    }

    /** A real refresh: parse both stores, recover backups if needed, and report integrity issues. */
    fun verifyAndRepair(): IntegrityReport {
        val shifts = readShifts(recover = true)
        val planned = readPlanned(recover = true)
        if (shifts.invalidCount > 0) synchronized(WRITE_LOCK) { saveShiftsLocked(shifts.items) }
        if (planned.invalidCount > 0) synchronized(WRITE_LOCK) { savePlannedLocked(planned.items) }
        val activeCount = shifts.items.count { it.end == null }
        return IntegrityReport(
            recordedCount = shifts.items.size,
            plannedCount = planned.items.size,
            recoveredRecordedBackup = shifts.validJson.not() && prefs.getString(KEY_SHIFTS_BACKUP, null) != null,
            recoveredPlannedBackup = planned.validJson.not() && prefs.getString(KEY_PLANNED_BACKUP, null) != null,
            multipleActiveShifts = activeCount,
            invalidRecorded = shifts.invalidCount,
            invalidPlanned = planned.invalidCount
        )
    }

    fun exportBackupJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", LocalDateTime.now().format(iso))
        put("shifts", shiftsToJson(all()))
        put("planned", plannedToJson(allPlanned()))
        put("profile", JSONObject().apply {
            put("employeeName", savedEmployeeName())
            put("typicalShiftHours", savedTypicalShiftHours())
            put("rotaVision", JSONObject(prefs.getString(KEY_ROTA_VISION_PROFILES, "{}") ?: "{}"))
            put("rotaTemplates", JSONObject(prefs.getString(KEY_ROTA_TEMPLATES, "{}") ?: "{}"))
        })
    }.toString(2)

    /** Merge a ShiftWatch JSON backup without deleting current data. */
    fun importBackupJson(raw: String): BackupImportResult {
        val root = JSONObject(raw)
        val importedShifts = parseShiftArray(root.optJSONArray("shifts") ?: JSONArray())
        val importedPlanned = parsePlannedArray(root.optJSONArray("planned") ?: JSONArray())
        var recordedAdded = 0
        var plannedAdded = 0
        var skipped = importedShifts.invalidCount + importedPlanned.invalidCount

        synchronized(WRITE_LOCK) {
            val currentShifts = all().toMutableList()
            val recordedKeys = currentShifts.map { Triple(it.start, it.end, it.id) }.toMutableSet()
            importedShifts.items.forEach { shift ->
                val duplicate = currentShifts.any { it.start == shift.start && it.end == shift.end }
                val conflict = validateImportedRecordedBasics(shift) != null || currentShifts.any {
                    intervalsOverlap(shift.start, shift.end ?: LocalDateTime.MAX, it.start, it.end ?: LocalDateTime.MAX)
                }
                if (!duplicate && !conflict) {
                    val safe = if (recordedKeys.any { it.third == shift.id }) shift.copy(id = UUID.randomUUID().toString()) else shift
                    currentShifts += safe
                    recordedKeys += Triple(safe.start, safe.end, safe.id)
                    recordedAdded++
                } else skipped++
            }
            saveShiftsLocked(currentShifts)

            val currentPlanned = allPlanned().toMutableList()
            importedPlanned.items.forEach { shift ->
                val duplicate = currentPlanned.any { it.start == shift.start && it.end == shift.end }
                val overlap = currentPlanned.any { existing ->
                    intervalsOverlap(shift.start, shift.end, existing.start, existing.end)
                }
                if (!duplicate && !overlap && validatePlannedBasics(shift.start, shift.end) == null) {
                    val safe = if (currentPlanned.any { it.id == shift.id }) shift.copy(id = UUID.randomUUID().toString()) else shift
                    currentPlanned += safe
                    plannedAdded++
                } else skipped++
            }
            savePlannedLocked(currentPlanned)

            root.optJSONObject("profile")?.let { profile ->
                saveImportProfile(profile.optString("employeeName", savedEmployeeName()), profile.optInt("typicalShiftHours", savedTypicalShiftHours()))
                profile.optJSONObject("rotaVision")?.let { vision ->
                    prefs.edit().putString(KEY_ROTA_VISION_PROFILES, vision.toString()).commit()
                }
                profile.optJSONObject("rotaTemplates")?.let { templates ->
                    prefs.edit().putString(KEY_ROTA_TEMPLATES, templates.toString()).commit()
                }
            }
        }
        return BackupImportResult(recordedAdded, plannedAdded, skipped)
    }

    private fun readShifts(recover: Boolean = true): ParseResult<Shift> {
        val primaryRaw = prefs.getString(KEY_SHIFTS, "[]") ?: "[]"
        val primary = parseShiftRaw(primaryRaw)
        if (primary.validJson) return primary
        val backupRaw = prefs.getString(KEY_SHIFTS_BACKUP, null) ?: return primary
        val backup = parseShiftRaw(backupRaw)
        if (backup.validJson && recover) {
            prefs.edit().putString(KEY_SHIFTS, backupRaw).commit()
        }
        return if (backup.validJson) backup.copy(validJson = false) else primary
    }

    private fun readPlanned(recover: Boolean = true): ParseResult<PlannedShift> {
        val primaryRaw = prefs.getString(KEY_PLANNED, "[]") ?: "[]"
        val primary = parsePlannedRaw(primaryRaw)
        if (primary.validJson) return primary
        val backupRaw = prefs.getString(KEY_PLANNED_BACKUP, null) ?: return primary
        val backup = parsePlannedRaw(backupRaw)
        if (backup.validJson && recover) {
            prefs.edit().putString(KEY_PLANNED, backupRaw).commit()
        }
        return if (backup.validJson) backup.copy(validJson = false) else primary
    }

    private fun parseShiftRaw(raw: String): ParseResult<Shift> = runCatching { parseShiftArray(JSONArray(raw)) }
        .getOrElse { ParseResult(emptyList(), 0, false) }

    private fun parsePlannedRaw(raw: String): ParseResult<PlannedShift> = runCatching { parsePlannedArray(JSONArray(raw)) }
        .getOrElse { ParseResult(emptyList(), 0, false) }

    private fun parseShiftArray(arr: JSONArray): ParseResult<Shift> {
        val items = mutableListOf<Shift>()
        var invalid = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { invalid++; continue }
            val start = runCatching { LocalDateTime.parse(o.optString("start"), iso) }.getOrNull()
            if (start == null) { invalid++; continue }
            val endString = o.optString("end", "")
            val end = if (endString.isBlank()) null else runCatching { LocalDateTime.parse(endString, iso) }.getOrNull()
            if (endString.isNotBlank() && end == null) { invalid++; continue }
            val candidate = Shift(o.optString("id").ifBlank { UUID.randomUUID().toString() }, start, end)
            if (validateImportedRecordedBasics(candidate) != null) { invalid++; continue }
            items += candidate
        }
        return ParseResult(items.distinctBy { it.id }, invalid, true)
    }

    private fun parsePlannedArray(arr: JSONArray): ParseResult<PlannedShift> {
        val items = mutableListOf<PlannedShift>()
        var invalid = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { invalid++; continue }
            val start = runCatching { LocalDateTime.parse(o.optString("start"), iso) }.getOrNull()
            val end = runCatching { LocalDateTime.parse(o.optString("end"), iso) }.getOrNull()
            if (start == null || end == null || validatePlannedBasics(start, end) != null) { invalid++; continue }
            items += PlannedShift(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                start = start,
                end = end,
                source = o.optString("source", "manual")
            )
        }
        return ParseResult(items.distinctBy { it.id }, invalid, true)
    }

    private fun validateImportedRecordedBasics(shift: Shift): String? {
        if (shift.end != null) {
            if (!shift.end.isAfter(shift.start)) return "end-before-start"
            if (Duration.between(shift.start, shift.end).toMinutes() > MAX_RECORDED_SHIFT_MINUTES) return "too-long"
        }
        return null
    }

    private fun saveShiftsLocked(list: List<Shift>) {
        val raw = shiftsToJson(list).toString()
        val previous = prefs.getString(KEY_SHIFTS, "[]") ?: "[]"
        val nextRevision = prefs.getLong(KEY_REVISION, 0L) + 1L
        prefs.edit()
            .putString(KEY_SHIFTS_BACKUP, previous)
            .putString(KEY_SHIFTS, raw)
            .putLong(KEY_REVISION, nextRevision)
            .putInt(KEY_SCHEMA_VERSION, STORAGE_SCHEMA)
            .commit()
        notifyDataChanged()
    }

    private fun savePlannedLocked(list: List<PlannedShift>) {
        val raw = plannedToJson(list).toString()
        val previous = prefs.getString(KEY_PLANNED, "[]") ?: "[]"
        val nextRevision = prefs.getLong(KEY_REVISION, 0L) + 1L
        prefs.edit()
            .putString(KEY_PLANNED_BACKUP, previous)
            .putString(KEY_PLANNED, raw)
            .putLong(KEY_REVISION, nextRevision)
            .putInt(KEY_SCHEMA_VERSION, STORAGE_SCHEMA)
            .commit()
        notifyDataChanged(refreshNotification = false)
    }

    private fun shiftsToJson(list: List<Shift>): JSONArray = JSONArray().apply {
        list.sortedBy { it.start }.forEach { shift ->
            put(JSONObject().apply {
                put("id", shift.id)
                put("start", shift.start.format(iso))
                put("end", shift.end?.format(iso) ?: "")
            })
        }
    }

    private fun plannedToJson(list: List<PlannedShift>): JSONArray = JSONArray().apply {
        list.sortedBy { it.start }.forEach { shift ->
            put(JSONObject().apply {
                put("id", shift.id)
                put("start", shift.start.format(iso))
                put("end", shift.end.format(iso))
                put("source", shift.source)
            })
        }
    }

    private fun notifyDataChanged(refreshNotification: Boolean = true) {
        appContext.sendBroadcast(android.content.Intent(ACTION_DATA_CHANGED).setPackage(appContext.packageName))
        WorkWidgetProvider.updateAll(appContext)
        if (refreshNotification) ShiftNotificationManager.sync(appContext)
    }

    companion object {
        const val PREFS_NAME = "work_shifts"
        const val KEY_SHIFTS = "shifts"
        const val KEY_REVISION = "revision"
        const val KEY_PLANNED = "planned_shifts"
        const val ACTION_DATA_CHANGED = "com.example.workshifttracker.action.DATA_CHANGED"

        private const val KEY_SHIFTS_BACKUP = "shifts_last_good"
        private const val KEY_PLANNED_BACKUP = "planned_last_good"
        private const val KEY_SCHEMA_VERSION = "storage_schema_version"
        private const val KEY_EMPLOYEE_NAME = "schedule_employee_name"
        private const val KEY_TYPICAL_SHIFT_HOURS = "schedule_typical_hours"
        private const val KEY_ROTA_VISION_PROFILES = "rota_vision_profiles"
        private const val KEY_ROTA_TEMPLATES = "rota_templates"
        private const val KEY_ROTA_FINGERPRINTS = "rota_fingerprints"
        private const val KEY_ROTA_CORRECTIONS = "rota_corrections"
        private const val KEY_ROTA_IMPORT_SESSION = "rota_import_session_v11"
        private const val STORAGE_SCHEMA = 2
        private const val BACKUP_SCHEMA = 1
        private const val MAX_RECORDED_SHIFT_MINUTES = 36L * 60L
        private const val MAX_PLANNED_SHIFT_MINUTES = 36L * 60L
        private val WRITE_LOCK = Any()
        // Shared across activity/store instances; rotation cannot let a stale checkpoint
        // resurrect a session already committed and cleared by the next activity.
        private var sessionWriteRevisionGlobal: Long = 0L
        private val pausedImportEpochs = mutableSetOf<String>()
        private val closedImportEpochs = mutableSetOf<String>()

        /** Round to the nearest 30 minutes: 09:40 -> 09:30, 09:46 -> 10:00. */
        fun roundToHalfHour(value: LocalDateTime): LocalDateTime {
            val base = value.withSecond(0).withNano(0)
            val totalMinutes = base.hour * 60 + base.minute
            val rounded = ((totalMinutes + 15) / 30) * 30
            val dayOffset = rounded / (24 * 60)
            val minuteInDay = rounded % (24 * 60)
            return base.toLocalDate().plusDays(dayOffset.toLong()).atTime(minuteInDay / 60, minuteInDay % 60)
        }

        private fun intervalsOverlap(aStart: LocalDateTime, aEnd: LocalDateTime, bStart: LocalDateTime, bEnd: LocalDateTime): Boolean =
            aStart.isBefore(bEnd) && bStart.isBefore(aEnd)
    }
}
