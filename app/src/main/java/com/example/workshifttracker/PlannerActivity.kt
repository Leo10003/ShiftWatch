package com.example.workshifttracker

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class PlannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val view = LocalView.current
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            ShiftWatchTheme {
                PlannerScreen(
                    store = remember { ShiftStore(this) },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerScreen(store: ShiftStore, onBack: () -> Unit) {
    var weekStart by remember { mutableStateOf(startOfWeek(LocalDate.now())) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<ShiftStore.PlannedShift?>(null) }
    var adding by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var employeeName by remember { mutableStateOf(store.savedEmployeeName()) }
    var typicalShiftHours by remember { mutableIntStateOf(store.savedTypicalShiftHours()) }
    var importBusy by remember { mutableStateOf(false) }
    var savingImport by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var extractedText by remember { mutableStateOf("") }
    var reviewDrafts by remember { mutableStateOf<List<ScheduleImporter.Draft>>(emptyList()) }
    var assistData by remember { mutableStateOf<ScheduleImporter.AssistData?>(null) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var duplicateRotaWarning by remember { mutableStateOf(false) }
    var resumableSession by remember { mutableStateOf(store.savedRotaImportSession()) }
    var resumedReviewSession by remember { mutableStateOf<ShiftStore.RotaImportSession?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importGeneration by remember { mutableIntStateOf(0) }
    var scanStartElapsedMs by remember { mutableLongStateOf(0L) }
    var lastScanDurationMs by remember { mutableLongStateOf(0L) }
    var scanTrace by remember { mutableStateOf(RotaScanTrace.Snapshot()) }
    var scanSession by remember { mutableStateOf(RotaSessionEngine.begin("", 0)) }

    fun rebaseDraftsToWeek(items: List<ScheduleImporter.Draft>, targetWeek: LocalDate): List<ScheduleImporter.Draft> {
        val monday = startOfWeek(targetWeek)
        return items.map { draft ->
            val column = draft.columnIndex ?: return@map draft
            val minutes = Duration.between(draft.start, draft.end).toMinutes().coerceAtLeast(30)
            val newStart = LocalDateTime.of(monday.plusDays(column.coerceIn(0, 6).toLong()), draft.start.toLocalTime())
            draft.copy(start = newStart, end = newStart.plusMinutes(minutes))
        }.sortedBy { it.start }
    }

    fun committedRotaKey(data: ScheduleImporter.AssistData, documentWeek: LocalDate): String =
        RotaVerificationEngine.documentFingerprint(data) + "|" + startOfWeek(documentWeek)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importJob?.cancel()
        importGeneration++
        val generation = importGeneration
        scanSession = RotaSessionEngine.begin(uri.toString(), generation)
        scanStartElapsedMs = SystemClock.elapsedRealtime()
        lastScanDurationMs = 0L
        scanTrace = RotaScanTrace.Snapshot(stageStartedMs = scanStartElapsedMs)
        selectedImageUri = uri
        resumedReviewSession = null
        // The user owns review immediately; OCR is a separate progressive enhancement.
        // Manual date/time entry does not wait for OCR, identity matching or time atlases.
        extractedText = ""
        reviewDrafts = emptyList()
        // Persist manual entries even if the app is interrupted before the first OCR callback.
        assistData = ScheduleImporter.AssistData(1, 1, emptyList())
        duplicateRotaWarning = false
        showReview = true
        importBusy = true
        importError = null
        importJob = scope.launch {
            val runningJob = coroutineContext[Job]
            try {
                val dimensions = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    store.context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    val rawW = options.outWidth.coerceAtLeast(1)
                    val rawH = options.outHeight.coerceAtLeast(1)
                    var sample = 1
                    while (maxOf(rawW / sample, rawH / sample) > 3000) sample *= 2
                    (rawW / sample).coerceAtLeast(1) to (rawH / sample).coerceAtLeast(1)
                }
                if (!RotaReviewMerge.isCurrent(generation, importGeneration, showReview)) return@launch
                assistData = ScheduleImporter.AssistData(dimensions.first, dimensions.second, emptyList())
                val result = ScheduleImportCoordinator.analyze(
                    context = store.context,
                    uri = uri,
                    employeeName = employeeName,
                    typicalShiftHours = typicalShiftHours,
                    onStage = { stage ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (RotaSessionEngine.accepts(scanSession, uri.toString(), generation) &&
                                RotaReviewMerge.isCurrent(generation, importGeneration, showReview)) {
                                RotaSessionEngine.Stage.entries.firstOrNull { it.name == stage }?.let {
                                    scanSession = RotaSessionEngine.progress(scanSession, uri.toString(), generation, it)
                                }
                                scanTrace = RotaScanTrace.advance(scanTrace, stage, SystemClock.elapsedRealtime())
                            }
                        }
                    },
                    onProvisional = { provisionalText, provisionalAssist ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (RotaSessionEngine.accepts(scanSession, uri.toString(), generation) &&
                                RotaReviewMerge.isCurrent(generation, importGeneration, showReview)) {
                                extractedText = provisionalText
                                assistData = provisionalAssist
                            }
                        }
                    }
                )
                if (!RotaSessionEngine.accepts(scanSession, uri.toString(), generation) ||
                    !RotaReviewMerge.isCurrent(generation, importGeneration, showReview)) return@launch
                val raw = result.rawText
                val drafts = result.drafts
                val assist = result.assist
                val candidates = withContext(Dispatchers.IO) { store.savedRotaTemplates(employeeName) }
                scanTrace = RotaScanTrace.advance(scanTrace, "VERIFY", SystemClock.elapsedRealtime())
                val prepared = withContext(Dispatchers.Default) {
                    val bestTemplate = ScheduleImporter.chooseBestTemplate(assist, candidates)
                    val templated = ScheduleImporter.applyTemplate(assist, bestTemplate)
                    val reassessed = ScheduleImporter.withAssessment(templated, employeeName)
                    val weekResolution = ScheduleImporter.documentWeekResolution(reassessed, raw, weekStart)
                    val documentWeek = weekResolution.weekStart
                    val rebased = rebaseDraftsToWeek(drafts, documentWeek)
                    val verified = RotaVerificationEngine.verifyWeek(reassessed, rebased)
                    Triple(reassessed, verified, documentWeek)
                }
                val reassessed = prepared.first
                val verifiedDrafts = prepared.second
                val documentWeek = prepared.third
                // v20.4 deterministic review: automatic drafts from handwritten/mixed rotas are
                // not carried into the planner list. Identity hits remain visible as selector
                // suggestions and become drafts only after explicit user confirmation.
                val reviewCandidates = if (reassessed.documentKind == ScheduleImporter.DocumentKind.PRINTED_TABLE && reassessed.printedConfidence >= 0.86f) {
                    verifiedDrafts.filter { it.origin == ScheduleImporter.DraftOrigin.PRINTED_TABLE }
                } else emptyList()
                val duplicateKey = committedRotaKey(reassessed, documentWeek)
                val duplicate = withContext(Dispatchers.IO) { store.isKnownRotaFingerprint(duplicateKey) }
                withContext(Dispatchers.IO) {
                    // Only the live review state writes sessions; analyzer-only drafts can be stale.
                    if (reassessed.verticalRules.size == 6 || reassessed.rowBoundaries.count { it.size >= 3 } >= 4) {
                        store.saveRotaTemplate(employeeName, ScheduleImporter.templateSnapshot(reassessed))
                    }
                }
                if (!RotaReviewMerge.isCurrent(generation, importGeneration, showReview)) return@launch
                extractedText = raw
                assistData = reassessed
                duplicateRotaWarning = duplicate
                reviewDrafts = reviewCandidates
                scanSession = RotaSessionEngine.progress(scanSession, uri.toString(), generation, RotaSessionEngine.Stage.COMPLETE)
                resumableSession = null // Live review remains mounted; no remount on OCR completion.

            } catch (_: CancellationException) {
                // A newer image superseded this analysis. Never let stale results mutate the UI.
            } catch (error: Throwable) {
                if (generation == importGeneration) importError = error.message ?: "Could not read this schedule image. Manual entry is still available."
            } finally {
                if (generation == importGeneration && importJob === runningJob) {
                    lastScanDurationMs = SystemClock.elapsedRealtime() - scanStartElapsedMs
                    scanTrace = RotaScanTrace.advance(scanTrace,
                        if (importError != null) "ERROR" else if (importBusy) "COMPLETE" else "STOPPED",
                        SystemClock.elapsedRealtime())
                    importBusy = false
                }
            }
        }
    }

    val all = remember(revision) { store.allPlanned() }
    val weekEnd = weekStart.plusDays(6)
    val week = all.filter { !it.start.toLocalDate().isBefore(weekStart) && !it.start.toLocalDate().isAfter(weekEnd) }
    val totalMinutes = week.sumOf { it.durationMinutes() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.shiftwatch_brand_mark),
                            contentDescription = null,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Planner", fontWeight = FontWeight.Bold)
                            Text("Upcoming work schedule", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { askName = true }, enabled = !importBusy) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Import schedule photo")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Plan shift") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 108.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                WeekCard(
                    weekStart = weekStart,
                    shiftCount = week.size,
                    totalMinutes = totalMinutes,
                    onPrevious = { weekStart = weekStart.minusWeeks(1) },
                    onNext = { weekStart = weekStart.plusWeeks(1) },
                    onToday = { weekStart = startOfWeek(LocalDate.now()) }
                )
            }

            item {
                ImportCard(
                    busy = importBusy,
                    error = importError,
                    onImport = { askName = true }
                )
            }

            resumableSession?.let { session ->
                item {
                    OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Resume rota analysis", fontWeight = FontWeight.SemiBold)
                            Text("${session.drafts.size} detected shifts are preserved from the previous review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !importBusy, onClick = {
                                    employeeName = session.employeeName
                                    typicalShiftHours = session.typicalShiftHours
                                    selectedImageUri = session.imageUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                    extractedText = session.rawText
                                    assistData = session.assistData
                                    resumedReviewSession = session
                                    val resumedResolution = ScheduleImporter.documentWeekResolution(session.assistData, session.rawText, weekStart)
                                    // Restore explicit user decisions regardless of document mode.
                                    // Previous builds discarded all handwritten review drafts on resume.
                                    reviewDrafts = session.drafts.filter { draft ->
                                        draft.userConfirmedIdentity || draft.userConfirmedDate || draft.userConfirmedTime ||
                                            draft.verificationNotes.any { it.contains("user", ignoreCase = true) } ||
                                            (draft.origin == ScheduleImporter.DraftOrigin.PRINTED_TABLE && session.assistData.printedConfidence >= 0.86f)
                                    }
                                    duplicateRotaWarning = store.isKnownRotaFingerprint(committedRotaKey(session.assistData, resumedResolution.weekStart))
                                    showReview = true
                                }) { Text("Resume") }
                                TextButton(onClick = { store.clearRotaImportSession(); resumableSession = null }) { Text("Discard") }
                            }
                        }
                    }
                }
            }

            item {
                Text("Planned shifts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (week.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text("No planned shifts", fontWeight = FontWeight.Bold)
                            Text(
                                "Add one manually or import a photo of your rota.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(week, key = { it.id }) { shift ->
                    PlannedShiftCard(shift) { editing = shift }
                }
            }
        }
    }

    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Import rota", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Who should ShiftWatch look for? You can type the name in any capitalization.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = employeeName,
                        onValueChange = { employeeName = it },
                        singleLine = true,
                        label = { Text("Name on schedule") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Typical shift length", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(6, 8, 10, 12).forEach { hours ->
                            FilterChip(
                                selected = typicalShiftHours == hours,
                                onClick = { typicalShiftHours = hours },
                                label = { Text("${hours}h") }
                            )
                        }
                    }
                    Text(
                        "Used only when the rota shows a start time without an end time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                    ) {
                        Text(
                            "Private by design • schedule analysis stays on this phone.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = employeeName.trim().length >= 2,
                    onClick = {
                        store.saveImportProfile(employeeName, typicalShiftHours)
                        askName = false
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) { Text("Choose photo") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel") } }
        )
    }

    if (showReview) {
        ImportReviewSheet(
            store = store,
            employeeName = employeeName,
            rawText = extractedText,
            drafts = reviewDrafts,
            imageUri = selectedImageUri,
            initialAssistData = assistData,
            typicalShiftHours = typicalShiftHours,
            fallbackWeekStart = weekStart,
            duplicateRotaWarning = duplicateRotaWarning,
            scanning = importBusy,
            scanError = importError,
            scanElapsedMillis = if (!importBusy) lastScanDurationMs.takeIf { it > 0L } else null,
            scanStartElapsedMillis = scanStartElapsedMs,
            scanTrace = scanTrace,
            saving = savingImport,
            resumedSession = resumedReviewSession,
            onCancelAnalysis = {
                scanSession = RotaSessionEngine.stop(scanSession)
                scanTrace = RotaScanTrace.advance(scanTrace, "STOPPED", SystemClock.elapsedRealtime())
                importGeneration++
                importJob?.cancel()
                importBusy = false
            },
            onDismiss = {
                scanSession = RotaSessionEngine.stop(scanSession)
                importGeneration++
                importJob?.cancel()
                importBusy = false
                showReview = false
            },
            onSave = save@{ accepted, sessionEpoch ->
                if (savingImport || accepted.isEmpty()) return@save
                savingImport = true
                store.pauseImportCheckpoint(sessionEpoch)
                scanSession = RotaSessionEngine.stop(scanSession)
                importGeneration++
                importJob?.cancel()
                importBusy = false
                val modelSnapshot = assistData
                val candidates = accepted.map {
                    ShiftStore.PlannedShift(
                        id = UUID.randomUUID().toString(),
                        start = it.start, end = it.end, source = "schedule photo"
                    )
                }
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val batch = store.addReviewedPlannedAtomically(candidates)
                            if (batch.inserted.isNotEmpty()) {
                                // Planner data is the transaction boundary. Optional model/template
                                // learning must never turn a successful commit into a retryable error.
                                store.finishImportCheckpoint(sessionEpoch)
                                runCatching {
                                    modelSnapshot?.let { data ->
                                        val learned = (data.learnedTimeVocabulary + batch.inserted.map { it.start.toLocalTime() }).distinct().sorted()
                                        val trained = data.copy(learnedTimeVocabulary = learned)
                                        if (trained.verticalRules.size == 6 || trained.rowBoundaries.count { it.size >= 3 } >= 4) {
                                            store.saveRotaTemplate(employeeName, ScheduleImporter.templateSnapshot(trained))
                                        }
                                        if (trained.tokens.isNotEmpty()) {
                                            val week = startOfWeek(batch.inserted.minOf { it.start.toLocalDate() })
                                            store.rememberRotaFingerprint(committedRotaKey(trained, week))
                                        }
                                    }
                                }
                            }
                            batch
                        }
                        if (result.error != null) {
                            store.resumeImportCheckpoint(sessionEpoch)
                            importError = result.error
                        } else {
                            revision++
                            resumableSession = null
                            resumedReviewSession = null
                            showReview = false
                            weekStart = startOfWeek(result.inserted.minOf { it.start.toLocalDate() })
                        }
                    } catch (error: Exception) {
                        store.resumeImportCheckpoint(sessionEpoch)
                        importError = error.message ?: "Unable to save shifts. Your review has been preserved."
                    } finally {
                        savingImport = false
                    }
                }
            }
        )
    }

    if (adding) {
        PlannedShiftEditor(
            initial = null,
            onDismiss = { adding = false },
            validate = { start, end -> store.validatePlannedCandidate(start, end) },
            onSave = { start, end ->
                store.upsertPlanned(ShiftStore.PlannedShift(UUID.randomUUID().toString(), start, end))
                revision++
                weekStart = startOfWeek(start.toLocalDate())
                adding = false
            },
            onDelete = null
        )
    }

    editing?.let { current ->
        PlannedShiftEditor(
            initial = current,
            onDismiss = { editing = null },
            validate = { start, end -> store.validatePlannedCandidate(start, end, current.id) },
            onSave = { start, end ->
                store.upsertPlanned(current.copy(start = start, end = end))
                revision++
                editing = null
            },
            onDelete = {
                store.deletePlanned(current.id)
                revision++
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "Planned shift deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        runCatching { store.upsertPlanned(current) }
                        revision++
                    }
                }
            }
        )
    }
}

@Composable
private fun WeekCard(
    weekStart: LocalDate,
    shiftCount: Int,
    totalMinutes: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(weekStart) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                    onDragEnd = {
                        if (drag <= -threshold) onNext() else if (drag >= threshold) onPrevious()
                        drag = 0f
                    }
                )
            },
        shape = RoundedCornerShape(28.dp)
    ) {
        AnimatedContent(
            targetState = weekStart,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "week"
        ) { start ->
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous week") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${start.format(WEEK_DATE)} – ${start.plusDays(6).format(WEEK_DATE)}", fontWeight = FontWeight.Bold)
                        Text("Weekly overview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next week") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlannerStat("Planned", "$shiftCount shifts", Modifier.weight(1f))
                    PlannerStat("Hours", formatDuration(totalMinutes), Modifier.weight(1f))
                }
                TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.Today, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Current week")
                }
            }
        }
    }
}

@Composable
private fun PlannerStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ImportCard(busy: Boolean, error: String?, onImport: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Smart rota import", style = MaterialTheme.typography.titleMedium)
                    Text("RotaVision reads the grid, dates and times on-device. If handwriting is difficult, two confirmed examples teach RotaVision your name more reliably.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reading schedule…")
                } else {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose photo")
                }
            }
        }
    }
}

@Composable
private fun PlannedShiftCard(shift: ShiftStore.PlannedShift, onClick: () -> Unit) {
    val date = shift.start.toLocalDate()
    val today = LocalDate.now()
    val status = when {
        date == today -> "Today"
        date.isAfter(today) -> "Upcoming"
        else -> "Past plan"
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).animateContentSize(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.width(58.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(shift.start.format(DAY), style = MaterialTheme.typography.labelMedium)
                    Text(shift.start.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${shift.start.format(TIME)} – ${shift.end.format(TIME)}", fontWeight = FontWeight.Bold)
                Text("${formatDuration(shift.durationMinutes())} · ${shift.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
                Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Edit, "Edit planned shift", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportReviewSheet(
    store: ShiftStore,
    employeeName: String,
    rawText: String,
    drafts: List<ScheduleImporter.Draft>,
    imageUri: Uri?,
    initialAssistData: ScheduleImporter.AssistData?,
    typicalShiftHours: Int,
    fallbackWeekStart: LocalDate,
    duplicateRotaWarning: Boolean,
    scanning: Boolean,
    scanError: String?,
    scanElapsedMillis: Long?,
    scanStartElapsedMillis: Long,
    scanTrace: RotaScanTrace.Snapshot,
    saving: Boolean,
    resumedSession: ShiftStore.RotaImportSession?,
    onCancelAnalysis: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<ScheduleImporter.Draft>, String) -> Unit
) {
    var assistData by remember(imageUri) { mutableStateOf(initialAssistData) }
    // Explicit actions are immutable against arriving OCR/template results.
    var manuallyChangedDays by remember(imageUri) {
        mutableStateOf(resumedSession?.touchedWeekdays?.map { startOfWeek(resumedSession.confirmedWeek ?: fallbackWeekStart).plusDays(it.toLong()) }?.toSet().orEmpty())
    }
    var manuallyConfirmedDates by remember(imageUri) {
        mutableStateOf<Set<String>>(drafts.filter { it.userConfirmedDate }.map { it.id }.toSet())
    }
    var userRevision by remember(imageUri) { mutableIntStateOf(0) }
    val weekResolution by produceState(
        initialValue = RotaDateAuthorityEngine.resolveTextSequence("", LocalDate.now(), fallbackWeekStart),
        assistData, rawText, fallbackWeekStart
    ) {
        value = withContext(Dispatchers.Default) {
            assistData?.let { ScheduleImporter.documentWeekResolution(it, rawText, fallbackWeekStart) }
                ?: RotaDateAuthorityEngine.resolveTextSequence(rawText, LocalDate.now(), fallbackWeekStart)
        }
    }
    val detectedWeekStart = startOfWeek(weekResolution.weekStart)
    var rotaWeekStart by remember(imageUri) { mutableStateOf(resumedSession?.confirmedWeek ?: detectedWeekStart) }
    var weekManuallyOverridden by remember(imageUri) { mutableStateOf(resumedSession?.confirmedWeek != null) }
    var pickRotaWeek by remember { mutableStateOf(false) }

    fun rebaseDraftToWeek(draft: ScheduleImporter.Draft, weekStart: LocalDate): ScheduleImporter.Draft {
        val column = draft.columnIndex ?: return draft
        val minutes = Duration.between(draft.start, draft.end).toMinutes().coerceAtLeast(30)
        val newStart = LocalDateTime.of(weekStart.plusDays(column.coerceIn(0, 6).toLong()), draft.start.toLocalTime())
        return draft.copy(start = newStart, end = newStart.plusMinutes(minutes))
    }

    var workingDrafts by remember(imageUri) { mutableStateOf(drafts) }
    var viewerMarkers by remember(imageUri) { mutableStateOf(RotaDiagnosticEvidence.MarkerSummary()) }
    var viewerRecognitionCache by remember(imageUri) { mutableStateOf<RotaViewerRecognitionCache?>(null) }
    var selected by remember(imageUri) {
        mutableStateOf<Set<String>>(resumedSession?.selectedIds?.intersect(drafts.map { it.id }.toSet()).orEmpty())
    }
    var addingManualDraft by remember(imageUri) { mutableStateOf(false) }
    var suppressLateSuggestions by remember(imageUri) { mutableStateOf(resumedSession?.suppressLateSuggestions == true) }
    // The only legal late analysis mutation is adding suggestions on dates the user has NOT
    // touched. Never replace user-edited/removed drafts, reset checkboxes or rebase manual dates.
    LaunchedEffect(initialAssistData, drafts) {
        if (initialAssistData != null) assistData = initialAssistData
        if (drafts.isNotEmpty() && !suppressLateSuggestions) {
            // Weekday ownership survives a bad initial fallback week. If the user corrected
            // Monday on a provisional December week, a late September Monday cannot duplicate it.
            workingDrafts = RotaReviewMerge.mergeBySlot(
                current = workingDrafts,
                incoming = drafts,
                touchedDays = manuallyChangedDays.map { it.dayOfWeek }.toSet(),
                dayOf = { it.start.dayOfWeek },
                slotOf = { draft ->
                    // Stable across OCR passes and wrong-year guesses, unlike randomized Draft.id.
                    // A known second physical start time on the same day remains available.
                    "${draft.start.dayOfWeek}|${draft.columnIndex ?: -1}|${draft.start.toLocalTime()}"
                }
            ).sortedBy { it.start }
        }
    }
    // v20.3 safety: "importable" and "safe to auto-select" are different concepts.
    // Handwritten/mixed results may be good enough for the user to choose, but they must never
    // silently enter the planner. Only deterministic printed-table rows can be preselected.
    var assistMessage by remember(imageUri) { mutableStateOf<String?>(null) }
    fun documentDateTrusted(): Boolean = weekManuallyOverridden || weekResolution.authoritative
    fun confirmDisplayedWeek() {
        weekManuallyOverridden = true
        userRevision++
        assistMessage = "Confirmed ${rotaWeekStart.format(WEEK_DATE)} – ${rotaWeekStart.plusDays(6).format(WEEK_DATE)}. Individual shift times still require confirmation."
    }

    // A planner fallback is NOT a date recognized in the photograph. Require the user to
    // actively choose its date before approving every shift; otherwise a tempting single
    // button can confirm the wrong week (the Sept 21 vs Sept 7 field regression).
    fun requestWeekConfirmation() {
        if (weekResolution.authoritative) confirmDisplayedWeek()
        else pickRotaWeek = true
    }

    fun evidenceFor(draft: ScheduleImporter.Draft, checked: Boolean = draft.id in selected): RotaReviewPolicy.Evidence {
        val legacyDate = draft.verificationNotes.any {
            it.contains("user confirmed date and start time", ignoreCase = true) ||
                it.contains("user corrected day/time", ignoreCase = true)
        }
        val legacyTime = draft.verificationNotes.any {
            it.contains("user confirmed start time", ignoreCase = true) || legacyDate
        }
        val identity = when (draft.origin) {
            ScheduleImporter.DraftOrigin.ASSISTED -> draft.userConfirmedIdentity || draft.userConfirmedTime || legacyTime ||
                draft.sourceLine.contains("User confirmed identity", ignoreCase = true)
            ScheduleImporter.DraftOrigin.PRINTED_TABLE -> draft.userConfirmedIdentity || (
                draft.verificationState in setOf(
                    RotaVerificationEngine.State.CONFIRMED,
                    RotaVerificationEngine.State.HIGH_CONFIDENCE
                ) && draft.confidence >= .94f)
            else -> draft.userConfirmedIdentity
        }
        val date = draft.userConfirmedDate || legacyDate || (documentDateTrusted() &&
            !draft.start.toLocalDate().isBefore(rotaWeekStart) &&
            !draft.start.toLocalDate().isAfter(rotaWeekStart.plusDays(6)))
        val time = !draft.requiresTimeConfirmation && when (draft.origin) {
            ScheduleImporter.DraftOrigin.ASSISTED -> draft.userConfirmedTime || legacyTime
            ScheduleImporter.DraftOrigin.PRINTED_TABLE -> draft.userConfirmedTime || draft.verificationState in setOf(
                RotaVerificationEngine.State.CONFIRMED,
                RotaVerificationEngine.State.HIGH_CONFIDENCE
            )
            else -> draft.userConfirmedTime
        }
        return RotaReviewPolicy.Evidence(
            identityConfirmed = identity,
            dateConfirmed = date,
            timeConfirmed = time,
            conflicting = draft.verificationState == RotaVerificationEngine.State.CONFLICT ||
                (draft.origin != ScheduleImporter.DraftOrigin.ASSISTED &&
                    draft.verificationState == RotaVerificationEngine.State.UNRESOLVED),
            selected = checked
        )
    }
    fun isImportable(draft: ScheduleImporter.Draft): Boolean =
        RotaReviewPolicy.status(evidenceFor(draft, checked = true)) == RotaReviewPolicy.Status.READY

    // v20.4: automatic detection and import selection are intentionally separate concepts.
    // Every detection starts unselected; the user must explicitly choose Select ready or tick an
    // individual row before anything can enter the planner.
    var lastRemovedDraft by remember { mutableStateOf<ScheduleImporter.Draft?>(null) }
    val suggestedTimes by produceState(initialValue = emptyList<LocalTime>(), assistData) {
        value = withContext(Dispatchers.Default) {
            assistData?.let { ScheduleImporter.suggestedTimes(it) }.orEmpty()
        }
    }
    LaunchedEffect(detectedWeekStart, weekResolution.confidence, weekResolution.authoritative) {
        if (!weekManuallyOverridden && rotaWeekStart != detectedWeekStart && weekResolution.authoritative) {
            val changedIds = workingDrafts.filter { it.columnIndex != null && !it.userConfirmedDate && it.id !in manuallyConfirmedDates }.mapTo(hashSetOf()) { it.id }
            rotaWeekStart = detectedWeekStart
            workingDrafts = workingDrafts.map { draft ->
                if (draft.id in changedIds) rebaseDraftToWeek(draft, detectedWeekStart) else draft
            }.sortedBy { it.start }
            // A new OCR-derived week cannot silently preserve approvals for altered dates.
            // Fully manual calendar entries and their selection survive unchanged.
            selected = selected - changedIds
            assistMessage = "Document week updated to ${detectedWeekStart.format(WEEK_DATE)}. Reconfirm any changed dates; your manual entries were preserved."
        }
    }
    var editingImport by remember { mutableStateOf<ScheduleImporter.Draft?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var bitmapReloadKey by remember(imageUri) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUri, bitmapReloadKey) {
        value = withContext(Dispatchers.IO) { imageUri?.let { loadScheduleBitmap(context, it) } }
    }
    DisposableEffect(bitmap) {
        // `bitmap` comes from delegated Compose state. Capture the instance for this
        // effect so disposal does not re-read a potentially different/null state value.
        val bitmapToDispose = bitmap
        onDispose {
            if (bitmapToDispose != null && !bitmapToDispose.isRecycled) {
                bitmapToDispose.recycle()
            }
        }
    }
    LaunchedEffect(bitmap, scanning) {
        val preview = bitmap
        val model = assistData
        if (scanning && preview != null && model != null && model.tokens.isEmpty() &&
            (model.imageWidth != preview.width || model.imageHeight != preview.height)) {
            assistData = model.copy(imageWidth = preview.width, imageHeight = preview.height)
        }
    }
    val sessionEpoch = remember(imageUri) { UUID.randomUUID().toString() }
    LaunchedEffect(workingDrafts, assistData, imageUri, employeeName, typicalShiftHours, selected,
        manuallyChangedDays, suppressLateSuggestions, weekManuallyOverridden, rotaWeekStart, saving) {
        if (saving) return@LaunchedEffect
        val data = assistData ?: return@LaunchedEffect
        val draftSnapshot = workingDrafts
        val uriSnapshot = imageUri?.toString().orEmpty()
        // Coalesce rapid taps/drag edits into one checkpoint instead of serializing the entire
        // OCR session for every transient recomposition.
        delay(220)
        withContext(Dispatchers.IO) {
            store.saveRotaImportSession(employeeName, typicalShiftHours, uriSnapshot, rawText, draftSnapshot, data,
                confirmedWeek = rotaWeekStart.takeIf { weekManuallyOverridden },
                selectedIds = selected,
                touchedWeekdays = manuallyChangedDays.map { it.dayOfWeek.value - 1 }.toSet(),
                suppressLateSuggestions = suppressLateSuggestions,
                sessionEpoch = sessionEpoch)
        }
    }
    val diagnosticSession = sessionEpoch
    val diagnosticScope = rememberCoroutineScope()
    // Separate, explicit opt-in for labeled regression metadata. The ordinary diagnostic
    // export remains sanitized and does NOT include this extra manual ground truth.
    val exportLabeledCase = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val confirmed = workingDrafts.filter { draft ->
                (draft.userConfirmedIdentity || draft.sourceLine.contains("User confirmed identity", true)) &&
                    (draft.userConfirmedDate || documentDateTrusted()) &&
                    draft.userConfirmedTime && !draft.requiresTimeConfirmation
            }
            diagnosticScope.launch(Dispatchers.IO) {
                val resolver = context.contentResolver
                val payload = JSONObject().apply {
                    put("schemaVersion", 1)
                    put("purpose", "opt-in user-confirmed rota recognition evaluation")
                    put("privacy", "no employee names, no photos, no OCR text, no calendar dates")
                    put("shifts", JSONArray().apply {
                        confirmed.forEach { draft ->
                            put(JSONObject().apply {
                                put("weekday", draft.start.dayOfWeek.value - 1)
                                put("physicalBlock", draft.physicalBlockId ?: JSONObject.NULL)
                                put("startTime", draft.start.toLocalTime().toString())
                            })
                        }
                    })
                }
                // Only explicit action writes this file; no silent diagnostic harvesting.
                val exported = runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(payload.toString(2).toByteArray(Charsets.UTF_8)) }
                }
                if (exported.isFailure) withContext(Dispatchers.Main) {
                    assistMessage = "Unable to export that file. Please choose another location."
                }
            }
        }
    }
    // Use the installed package metadata: diagnostic schema and installed app version
    // are separate values, and stale hardcoded labels obscure real scan comparisons.
    val diagnosticContext = LocalContext.current
    val installedAppVersion = remember(diagnosticContext) {
        runCatching {
            diagnosticContext.packageManager.getPackageInfo(diagnosticContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    val exportDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val dataSnapshot = assistData
            val candidatesSnapshot = workingDrafts
            val selectedSnapshot = selected
            val touchedSnapshot = manuallyChangedDays
            val resolutionSnapshot = weekResolution
            val scanSnapshot = scanning
            val traceSnapshot = scanTrace
            val markerSnapshot = viewerMarkers
            val fallbackSnapshot = fallbackWeekStart
            val displayedWeekSnapshot = rotaWeekStart
            val textSnapshot = rawText
            val manualWeekConfirmedSnapshot = weekManuallyOverridden
            val displayedWeekOffsetSnapshot = java.time.temporal.ChronoUnit.DAYS.between(startOfWeek(fallbackWeekStart), rotaWeekStart)
            val elapsedSnapshot = scanElapsedMillis ?: if (scanStartElapsedMillis > 0L) {
                SystemClock.elapsedRealtime() - scanStartElapsedMillis
            } else null
            diagnosticScope.launch(Dispatchers.IO) {
                try {
                    // Privacy by default: no name, photo, OCR text, exact dates or handwriting
                    // crops. Even the candidate IDs are replaced with local ordinal indices.
                    val result = JSONObject().apply {
                        put("schemaVersion", 8)
                        put("appVersion", installedAppVersion)
                        put("sessionId", diagnosticSession)
                        put("captureUtc", java.time.Instant.now().toString())
                        put("scanInProgress", scanSnapshot)
                        put("scanStage", traceSnapshot.current)
                        put("scanStageDurationsMs", JSONObject().apply {
                            traceSnapshot.elapsed.forEach { (stage, duration) -> put(stage, duration) }
                        })
                        put("scanElapsedMs", elapsedSnapshot ?: JSONObject.NULL)
                        put("privacy", "sanitized; no names/photos/raw OCR text")
                        put("header", JSONObject().apply {
                            put("confidence", resolutionSnapshot.confidence.toDouble())
                            put("authoritative", resolutionSnapshot.authoritative)
                            put("reason", resolutionSnapshot.reason)
                            put("manualReviewTouchedDays", touchedSnapshot.size)
                            put("userConfirmedWeek", manualWeekConfirmedSnapshot)
                            put("displayedWeekOffsetDaysFromPlannerFallback", displayedWeekOffsetSnapshot)
                        })
                        if (dataSnapshot != null) {
                            put("layout", JSONObject().apply {
                                put("imageWidth", dataSnapshot.imageWidth)
                                put("imageHeight", dataSnapshot.imageHeight)
                                put("documentKind", dataSnapshot.documentKind.name)
                                put("ocrPasses", dataSnapshot.ocrPasses)
                                put("ocrTokenCount", dataSnapshot.tokens.size)
                                put("headerFocusTokenCount", dataSnapshot.tokens.count { it.source == ScheduleImporter.TokenSource.HEADER_FOCUS })
                                put("timeAtlasTokenCount", dataSnapshot.tokens.count { it.source == ScheduleImporter.TokenSource.TIME_ATLAS })
                                put("verticalGridRules", dataSnapshot.verticalRules.size)
                                put("rowBoundaryCounts", JSONArray(dataSnapshot.rowBoundaries.map { it.size }))
                            })
                            val structural = ScheduleImporter.structuredTimeDiagnostics(dataSnapshot)
                            put("structuralTimeBands", JSONArray(structural.map { row ->
                                "B${row.blockIndex + 1} ${row.time} · ${row.supportColumns}d/${row.atlasColumns}a · ${(row.confidence * 100).toInt()}% · ${if (row.ambiguous) "review" else "resolved"}"
                            }))
                            put("structuralTimeEvidence", JSONArray().apply {
                                structural.forEach { band ->
                                    put(JSONObject().apply {
                                        put("physicalBlockIndex", band.blockIndex)
                                        put("proposedTime", band.time.toString())
                                        put("confidence", band.confidence.toDouble())
                                        put("requiresReview", band.ambiguous)
                                        put("distinctSupportColumns", band.supportColumns)
                                        put("strongSupportColumns", band.strongColumns)
                                        put("independentAtlasColumns", band.atlasColumns)
                                        put("reason", band.reason)
                                        put("alternatives", JSONArray().apply {
                                            band.alternatives.forEach { alt ->
                                                put(JSONObject().apply {
                                                    put("time", alt.time.toString())
                                                    put("score", alt.score.toDouble())
                                                })
                                            }
                                        })
                                    })
                                }
                            })
                            val (headerCounts, explicitCounts) = ScheduleImporter.diagnosticHeaderObservationCounts(dataSnapshot)
                            put("headerObservationCountsByWeekday", JSONArray(headerCounts))
                            put("explicitHeaderObservationCountsByWeekday", JSONArray(explicitCounts))
                            put("timeCandidateTrace", JSONArray().apply {
                                ScheduleImporter.diagnosticStructuralTimeInputs(dataSnapshot).forEach { entry ->
                                    put(JSONObject().apply {
                                        put("physicalBlockIndex", entry.physicalBlockIndex ?: JSONObject.NULL)
                                        put("weekdayColumn", entry.weekdayColumn)
                                        put("time", entry.proposedTime)
                                        put("source", entry.tokenSource)
                                        put("strong", entry.strong)
                                        put("alternate", entry.alternate)
                                        put("confidenceDecile", entry.confidenceDecile)
                                        put("geometryDecision", entry.geometryDecision)
                                    })
                                }
                            })
                            put("weekEvidenceComparison", JSONObject().apply {
                                val fallbackFit = ScheduleImporter.diagnosticHeaderWeekFit(dataSnapshot, fallbackSnapshot)
                                val shownFit = ScheduleImporter.diagnosticHeaderWeekFit(dataSnapshot, displayedWeekSnapshot)
                                fun JSONObject.putFit(fit: RotaDiagnosticEvidence.HeaderWeekFit) {
                                    put("matchingObservations", fit.observationMatches)
                                    put("matchingExplicitObservations", fit.explicitMatches)
                                    put("matchingDistinctWeekdayColumns", fit.matchingColumns)
                                    put("totalObservations", fit.totalObservations)
                                }
                                put("plannerFallback", JSONObject().apply { putFit(fallbackFit) })
                                put("displayedWeek", JSONObject().apply { putFit(shownFit) })
                                put("displayedWeekConfirmedByUser", manualWeekConfirmedSnapshot)
                            })
                            put("dateHypotheses", JSONArray().apply {
                                ScheduleImporter.diagnosticDateHypotheses(dataSnapshot, textSnapshot, fallbackSnapshot).forEach { h ->
                                    put(JSONObject().apply {
                                        put("source", h.source)
                                        put("confidence", h.confidence.toDouble())
                                        put("authoritative", h.authoritative)
                                        put("evidenceCount", h.evidenceCount)
                                        put("explicitCount", h.explicitCount)
                                        put("reason", h.reason)
                                        put("offsetDaysFromPlannerFallback", h.offsetDaysFromPlannerFallback)
                                    })
                                }
                            })
                        }
                        put("review", JSONObject().apply {
                            put("draftCount", candidatesSnapshot.size)
                            put("selectedCount", candidatesSnapshot.count { it.id in selectedSnapshot })
                            put("note", "Review drafts and blue viewer suggestions have distinct identities and must not be treated as the same set")
                        })
                        put("viewerMarkers", JSONObject().apply {
                            put("ocrNameHitsByWeekdayColumn", JSONArray(markerSnapshot.ocrHitsByColumn))
                            put("savedProfileStatus", markerSnapshot.profileStatus)
                            put("recognitionRunId", markerSnapshot.recognitionRunId ?: JSONObject.NULL)
                            put("recognitionLifecycle", markerSnapshot.recognitionLifecycle)
                            put("seededSearchStatus", markerSnapshot.seededSearchStatus)
                            fun JSONArray.addDecisions(decisions: List<RotaDiagnosticEvidence.ProfileDecision>) {
                                decisions.forEach { decision ->
                                    put(JSONObject().apply {
                                        put("weekdayColumn", decision.weekdayColumn)
                                        put("candidateLineCount", decision.candidateCount)
                                        put("scoredLineCount", decision.scoredCount)
                                        put("topScore", decision.bestScore?.toDouble() ?: JSONObject.NULL)
                                        put("runnerScore", decision.runnerScore?.toDouble() ?: JSONObject.NULL)
                                        put("acceptanceFloor", decision.acceptanceFloor?.toDouble() ?: JSONObject.NULL)
                                        put("confuserScore", decision.confuserScore?.toDouble() ?: JSONObject.NULL)
                                        put("decision", decision.status)
                                        put("runnerOverlapFraction", decision.runnerOverlapFraction?.toDouble() ?: JSONObject.NULL)
                                        put("runnerIsSamePhysicalBlock", decision.runnerIsSamePhysicalBlock ?: JSONObject.NULL)
                                        put("candidatePipeline", decision.pipeline?.let { pipe -> JSONObject().apply {
                                            put("strictCount", pipe.strictCount)
                                            put("looseObserved", pipe.looseObserved)
                                            put("looseAdded", pipe.looseAdded)
                                            put("eligibleOcrTokens", pipe.eligibleOcrTokens)
                                            put("ocrTokenDeciles", JSONArray(pipe.ocrTokenDeciles))
                                            put("ocrMerged", pipe.ocrMerged)
                                            put("ocrAdded", pipe.ocrAdded)
                                            put("probesAttempted", pipe.probesAttempted)
                                            put("probesNearExisting", pipe.probesNearExisting)
                                            put("probesInkRejected", pipe.probesInkRejected)
                                            put("probesAdded", pipe.probesAdded)
                                            put("rejectedHeight", pipe.rejectedHeight)
                                            put("rejectedBody", pipe.rejectedBody)
                                            put("finalCandidates", pipe.finalCandidates)
                                            put("finalOcrCandidates", pipe.finalOcrCandidates)
                                            put("survivorsByBlock", JSONObject().apply {
                                                pipe.survivorsByBlock.forEach { (block, count) -> put(block.toString(), count) }
                                            })
                                        } } ?: JSONObject.NULL)
                                        put("candidateSources", JSONArray().apply {
                                            decision.candidateSources.forEach { source ->
                                                put(JSONObject().apply {
                                                    put("origin", source.origin)
                                                    put("scoredCount", source.scoredCount)
                                                    put("strongestAdjustedScore", source.strongestAdjustedScore.toDouble())
                                                    put("strongestPositiveScore", source.strongestPositiveScore.toDouble())
                                                    put("strongestConfuserScore", source.strongestConfuserScore.toDouble())
                                                    put("strongestPhysicalBlockIndex", source.strongestPhysicalBlockIndex ?: JSONObject.NULL)
                                                })
                                            }
                                        })
                                        put("topCandidates", JSONArray().apply {
                                            decision.rankedCandidates.forEach { candidate ->
                                                put(JSONObject().apply {
                                                    put("rank", candidate.rank)
                                                    put("physicalBlockIndex", candidate.physicalBlockIndex ?: JSONObject.NULL)
                                                    put("verticalDecile", candidate.verticalDecile)
                                                    put("adjustedScore", candidate.adjustedScore.toDouble())
                                                    put("positiveScore", candidate.positiveScore.toDouble())
                                                    put("confuserScore", candidate.confuserScore.toDouble())
                                                    put("rawSeparation", candidate.rawSeparation.toDouble())
                                                    put("confuserPenalty", candidate.confuserPenalty.toDouble())
                                                    put("separationAdjustment", candidate.separationAdjustment.toDouble())
                                                    put("candidateOrigin", candidate.candidateOrigin)
                                                })
                                            }
                                        })
                                    })
                                }
                            }
                            put("seededVisualDecisions", JSONArray().apply { addDecisions(markerSnapshot.seededSearchDecisions) })
                            put("savedProfileDecisions", JSONArray().apply {
                                markerSnapshot.profileDecisions.forEach { decision ->
                                    put(JSONObject().apply {
                                        put("weekdayColumn", decision.weekdayColumn)
                                        put("candidateLineCount", decision.candidateCount)
                                        put("scoredLineCount", decision.scoredCount)
                                        put("topScore", decision.bestScore?.toDouble() ?: JSONObject.NULL)
                                        put("runnerScore", decision.runnerScore?.toDouble() ?: JSONObject.NULL)
                                        put("acceptanceFloor", decision.acceptanceFloor?.toDouble() ?: JSONObject.NULL)
                                        put("confuserScore", decision.confuserScore?.toDouble() ?: JSONObject.NULL)
                                        put("decision", decision.status)
                                        put("runnerOverlapFraction", decision.runnerOverlapFraction?.toDouble() ?: JSONObject.NULL)
                                        put("runnerIsSamePhysicalBlock", decision.runnerIsSamePhysicalBlock ?: JSONObject.NULL)
                                        put("candidatePipeline", decision.pipeline?.let { pipe -> JSONObject().apply {
                                            put("strictCount", pipe.strictCount)
                                            put("looseObserved", pipe.looseObserved)
                                            put("looseAdded", pipe.looseAdded)
                                            put("eligibleOcrTokens", pipe.eligibleOcrTokens)
                                            put("ocrTokenDeciles", JSONArray(pipe.ocrTokenDeciles))
                                            put("ocrMerged", pipe.ocrMerged)
                                            put("ocrAdded", pipe.ocrAdded)
                                            put("probesAttempted", pipe.probesAttempted)
                                            put("probesNearExisting", pipe.probesNearExisting)
                                            put("probesInkRejected", pipe.probesInkRejected)
                                            put("probesAdded", pipe.probesAdded)
                                            put("rejectedHeight", pipe.rejectedHeight)
                                            put("rejectedBody", pipe.rejectedBody)
                                            put("finalCandidates", pipe.finalCandidates)
                                            put("finalOcrCandidates", pipe.finalOcrCandidates)
                                            put("survivorsByBlock", JSONObject().apply {
                                                pipe.survivorsByBlock.forEach { (block, count) -> put(block.toString(), count) }
                                            })
                                        } } ?: JSONObject.NULL)
                                        put("candidateSources", JSONArray().apply {
                                            decision.candidateSources.forEach { source ->
                                                put(JSONObject().apply {
                                                    put("origin", source.origin)
                                                    put("scoredCount", source.scoredCount)
                                                    put("strongestAdjustedScore", source.strongestAdjustedScore.toDouble())
                                                    put("strongestPositiveScore", source.strongestPositiveScore.toDouble())
                                                    put("strongestConfuserScore", source.strongestConfuserScore.toDouble())
                                                    put("strongestPhysicalBlockIndex", source.strongestPhysicalBlockIndex ?: JSONObject.NULL)
                                                })
                                            }
                                        })
                                        put("topCandidates", JSONArray().apply {
                                            decision.rankedCandidates.forEach { candidate ->
                                                put(JSONObject().apply {
                                                    put("rank", candidate.rank)
                                                    put("physicalBlockIndex", candidate.physicalBlockIndex ?: JSONObject.NULL)
                                                    put("verticalDecile", candidate.verticalDecile)
                                                    put("adjustedScore", candidate.adjustedScore.toDouble())
                                                    put("positiveScore", candidate.positiveScore.toDouble())
                                                    put("confuserScore", candidate.confuserScore.toDouble())
                                                    put("rawSeparation", candidate.rawSeparation.toDouble())
                                                    put("confuserPenalty", candidate.confuserPenalty.toDouble())
                                                    put("separationAdjustment", candidate.separationAdjustment.toDouble())
                                                    put("candidateOrigin", candidate.candidateOrigin)
                                                })
                                            }
                                        })
                                    })
                                }
                            })
                            put("markerDetails", JSONArray().apply {
                                markerSnapshot.markerDetails.forEach { marker ->
                                    put(JSONObject().apply {
                                        put("weekdayColumn", marker.weekdayColumn)
                                        put("physicalBlockIndex", marker.physicalBlockIndex ?: JSONObject.NULL)
                                        put("origin", marker.origin)
                                        put("scoreDecile", marker.scoreBucket ?: JSONObject.NULL)
                                        put("confirmed", marker.confirmed)
                                    })
                                }
                            })
                            put("status", RotaDiagnosticEvidence.status(markerSnapshot))
                            put("suggestedCount", markerSnapshot.suggested)
                            put("confirmedCount", markerSnapshot.confirmed)
                            put("suggestionsByWeekdayColumn", JSONArray(markerSnapshot.suggestionsByColumn))
                            put("confirmedByWeekdayColumn", JSONArray(markerSnapshot.confirmedByColumn))
                        })
                        put("candidates", JSONArray().apply {
                            candidatesSnapshot.forEachIndexed { index, d ->
                                put(JSONObject().apply {
                                    put("index", index)
                                    put("weekday", d.start.dayOfWeek.name)
                                    put("origin", d.origin.name)
                                    put("columnIndex", d.columnIndex ?: JSONObject.NULL)
                                    put("physicalBlockIndex", d.physicalBlockId ?: JSONObject.NULL)
                                    put("identityUserConfirmed", d.userConfirmedIdentity)
                                    put("dateUserConfirmed", d.userConfirmedDate)
                                    put("timeUserConfirmed", d.userConfirmedTime)
                                    put("selected", d.id in selectedSnapshot)
                                    put("verification", d.verificationState.name)
                                    put("requiresTimeConfirmation", d.requiresTimeConfirmation)
                                    put("confidence", d.confidence.toDouble())
                                    put("manuallyConfirmed", d.verificationNotes.any { it.contains("user confirmed", true) || it.contains("user corrected", true) })
                                    put("startTime", if (d.requiresTimeConfirmation) JSONObject.NULL else d.start.toLocalTime().toString())
                                })
                            }
                        })
                    }
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("The selected destination cannot be written")
                    stream.use { out -> out.write(result.toString(2).toByteArray(Charsets.UTF_8)) }
                    withContext(Dispatchers.Main) {
                        assistMessage = "Exported ${candidatesSnapshot.size} review drafts and ${markerSnapshot.suggested} observed viewer suggestions."
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        assistMessage = "Diagnostic export failed; please choose another location."
                    }
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Review rota", style = MaterialTheme.typography.headlineSmall)
            Text("Tracking “$employeeName” • review before anything is saved", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scanning) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Analyzing in background · ${scanTrace.current.replace('_', ' ').lowercase()}", fontWeight = FontWeight.SemiBold)
                        Text("You can confirm the week, add or edit shifts and save now. Automatic results cannot overwrite your changes.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = onCancelAnalysis) { Text("Stop analysis") }
                        }
                    }
                }
            }
            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Saving checked shifts…")
                }
            }
            if (scanError != null) Text(scanError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { addingManualDraft = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add shift manually")
            }
            TextButton(onClick = {
                exportDiagnostics.launch("shiftwatch-diagnostic-${LocalDate.now()}-${diagnosticSession.take(8)}.json")
            }) {
                Text("Export sanitized import diagnostics")
            }
            TextButton(onClick = {
                exportLabeledCase.launch("shiftwatch-reviewed-example-${diagnosticSession.take(8)}.json")
            }) {
                Text("Export my confirmed example (opt-in · includes weekdays and times)")
            }
            if (duplicateRotaWarning) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Possible duplicate rota", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("This document fingerprint matches a rota that was already imported. Review carefully before adding shifts again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }

            assistData?.let { data ->
                val modeLabel = when (data.documentKind) {
                    ScheduleImporter.DocumentKind.PRINTED_TABLE -> "Printed table"
                    ScheduleImporter.DocumentKind.HANDWRITTEN_GRID -> "Handwritten rota"
                    ScheduleImporter.DocumentKind.MIXED -> "Mixed rota"
                    else -> "Schedule image"
                }
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(7.dp))
                        Text("$modeLabel · ${data.tokens.size} text regions · ${data.verticalRules.size + 1} grid columns", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            assistData?.let { data ->
                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(if (showDiagnostics) "Hide analysis details" else "Analysis details")
                }
                AnimatedVisibility(showDiagnostics) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Recognition diagnostics", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                            Text("Mode: ${data.documentKind} · printed confidence ${(data.printedConfidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Text("OCR passes: ${data.ocrPasses} · tokens: ${data.tokens.size}", style = MaterialTheme.typography.bodySmall)
                            Text("Vertical grid rules: ${data.verticalRules.size}/6 · row blocks: ${data.rowBoundaries.sumOf { it.size }}", style = MaterialTheme.typography.bodySmall)
                            Text("Image quality: ${(data.quality.score * 100).toInt()}% · contrast ${(data.quality.contrast * 100).toInt()}% · sharpness ${(data.quality.sharpness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Text("Perception: ${(data.perception.score * 100).toInt()}% · time ${(data.perception.timeReadability * 100).toInt()}% · structure ${(data.perception.structuralReadability * 100).toInt()}% · ambiguity ${(data.perception.ambiguity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            if (data.perception.recognizedTimes.isNotEmpty()) Text("Perceived time bands: ${data.perception.recognizedTimes.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            if (data.semanticTimeBands.isNotEmpty()) Text("Semantic rows: ${data.semanticTimeBands.joinToString { band -> "B${band.blockIndex?.plus(1) ?: "?"}:${band.time}@${(band.yRatio * 100).toInt()}%(${(band.confidence * 100).toInt()}%)" }}", style = MaterialTheme.typography.bodySmall)
                            val structuralTimes = remember(data) { ScheduleImporter.structuralTimeDiagnostics(data) }
                            if (structuralTimes.isNotEmpty()) Text("Structural time model: ${structuralTimes.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                            if (data.perception.warnings.isNotEmpty()) Text(data.perception.warnings.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            if (data.learnedTimeVocabulary.isNotEmpty()) Text("Learned shift times: ${data.learnedTimeVocabulary.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            if (data.quality.warnings.isNotEmpty()) Text(data.quality.warnings.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            val graph = remember(data, workingDrafts) { RotaVerificationEngine.buildGraph(data, workingDrafts) }
                            Text("Document fingerprint: ${graph.fingerprint.take(12)} · graph candidates: ${graph.candidates.size}", style = MaterialTheme.typography.bodySmall)
                            Text("Time bands: ${graph.timeBands.size} · graph conflicts: ${graph.conflicts}", style = MaterialTheme.typography.bodySmall)
                            Text("Local correction events: ${store.rotaCorrectionCount()}", style = MaterialTheme.typography.bodySmall)
                            Text("Detected shifts: ${workingDrafts.size} · selected: ${selected.size}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rota week", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${rotaWeekStart.format(WEEK_DATE)} – ${rotaWeekStart.plusDays(6).format(WEEK_DATE)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (weekManuallyOverridden) "Manual week override. Tap Change to choose another week."
                            else if (documentDateTrusted()) "Using dates read from the rota document · ${(weekResolution.confidence * 100).toInt()}% confidence."
                            else "Document dates are unresolved; choose the correct rota week before importing.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (!weekManuallyOverridden) {
                            TextButton(onClick = {
                                requestWeekConfirmation()
                            }) { Text(if (weekResolution.authoritative) "Confirm week" else "Choose week") }
                        }
                        TextButton(onClick = { pickRotaWeek = true }) { Text("Change") }
                    }
                }
            }
            if (!weekManuallyOverridden && documentDateTrusted()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.50f)
                ) {
                    Text(
                        "Rota dates detected: ${detectedWeekStart.format(WEEK_DATE)} – ${detectedWeekStart.plusDays(6).format(WEEK_DATE)}. Document dates are being used.",
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (workingDrafts.isEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Teach RotaVision your handwriting", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap your name on two different days. RotaVision compares both examples before suggesting the remaining matches, reducing false positives.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (rawText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Preliminary header recognized; verify the week before import.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            val currentAssistData = assistData
            val previewBitmap = bitmap
            if (previewBitmap != null && currentAssistData != null) {
                AssistedScheduleImage(
                    store = store,
                    employeeName = employeeName,
                    bitmap = previewBitmap,
                    sourceWidth = currentAssistData.imageWidth,
                    sourceHeight = currentAssistData.imageHeight,
                    assistData = currentAssistData,
                    onAssistDataChanged = { updated -> assistData = updated },
                    onMarkerSummary = { viewerMarkers = it },
                    viewerRecognitionCache = viewerRecognitionCache,
                    onViewerRecognitionCache = { viewerRecognitionCache = it },
                    selectedDrafts = workingDrafts,
                    onTap = { sourceX, sourceY ->
                        // Immediate interaction: do NOT run structural-time OCR on the touch thread.
                        val column = ScheduleImporter.columnIndexForX(currentAssistData, sourceX)
                        val start = LocalDateTime.of(rotaWeekStart.plusDays(column.toLong()), LocalTime.MIDNIGHT)
                        val draft = ScheduleImporter.Draft(
                            start = start,
                            end = start.plusHours(typicalShiftHours.coerceIn(1, 16).toLong()),
                            sourceLine = "User confirmed identity · exact time required",
                            confidence = .1f,
                            estimatedEnd = true,
                            columnIndex = column,
                            origin = ScheduleImporter.DraftOrigin.ASSISTED,
                            requiresTimeConfirmation = true,
                            verificationState = RotaVerificationEngine.State.UNRESOLVED,
                            userConfirmedIdentity = true,
                            physicalBlockId = RotaGridModel.blockIndexForY(currentAssistData, column, sourceY)
                        )
                        val existing = workingDrafts.firstOrNull { prior ->
                            prior.columnIndex == draft.columnIndex &&
                                (if (prior.physicalBlockId != null && draft.physicalBlockId != null)
                                    prior.physicalBlockId == draft.physicalBlockId
                                else prior.start == draft.start)
                        }
                        if (existing == null) {
                            workingDrafts = (workingDrafts + draft).sortedBy { it.start }
                            manuallyChangedDays = manuallyChangedDays + draft.start.toLocalDate()
                            userRevision++
                            val added = draft
                            selected = selected - added.id
                            assistMessage = if (isImportable(added))
                                "Detected ${added.start.format(REVIEW_DATE)} at ${added.start.format(TIME)}. Review it, then tick the checkbox if it is correct."
                            else if (!documentDateTrusted())
                                "Identity confirmed, but the rota date is still unresolved. Confirm the rota week before importing."
                            else "Date selected for ${added.start.format(REVIEW_DATE)}. Start time still needs confirmation."
                            added
                        } else {
                            // Return the existing draft so the viewer can treat repeated taps on
                            // the same resolved shift as the same selection instead of stacking
                            // duplicate markers on top of each other.
                            existing
                        }
                    },
                    onDeselect = { draft ->
                        store.recordRotaCorrection("false_shift", draft.start.toString(), "removed")
                        manuallyChangedDays = manuallyChangedDays + draft.start.toLocalDate()
                        userRevision++
                        workingDrafts = workingDrafts.filterNot { it.id == draft.id }
                        selected = selected - draft.id
                        assistMessage = "Removed ${draft.start.format(REVIEW_DATE)} at ${draft.start.format(TIME)}"
                    },
                    quickTimes = (suggestedTimes + listOf(
                        LocalTime.of(9, 30),
                        LocalTime.of(10, 0),
                        LocalTime.of(13, 0),
                        LocalTime.of(16, 0),
                        LocalTime.of(18, 0)
                    )).distinct().sorted(),
                    onConfirmTime = { draft, time ->
                        val currentDraft = workingDrafts.firstOrNull { it.id == draft.id } ?: draft
                        val correctedStart = LocalDateTime.of(currentDraft.start.toLocalDate(), time)
                        if (draft.start.toLocalTime() != time) store.recordRotaCorrection("wrong_time", draft.start.toLocalTime().toString(), time.toString())
                        val corrected = currentDraft.copy(
                            start = correctedStart,
                            end = correctedStart.plusHours(typicalShiftHours.coerceIn(1, 16).toLong()),
                            sourceLine = "Tap-assisted import · start time confirmed in rota viewer",
                            confidence = 1f,
                            estimatedEnd = true,
                            requiresTimeConfirmation = false,
                            verificationState = RotaVerificationEngine.State.CONFIRMED,
                            verificationNotes = currentDraft.verificationNotes + "+ user confirmed start time",
                            userConfirmedTime = true,
                            userConfirmedIdentity = true
                        )
                        manuallyChangedDays = manuallyChangedDays + currentDraft.start.toLocalDate() + corrected.start.toLocalDate()
                        userRevision++
                        workingDrafts = workingDrafts.map { if (it.id == draft.id) corrected else it }.sortedBy { it.start }
                        selected = selected + corrected.id
                        assistMessage = "Confirmed ${corrected.start.format(REVIEW_DATE)} at ${corrected.start.format(TIME)}"
                        corrected
                    }
                )
                assistMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            } else if (imageUri != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Preview unavailable",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "The schedule image could not be reopened for preview. Detected shifts are preserved; reload the preview without rerunning recognition.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        OutlinedButton(onClick = { bitmapReloadKey++ }) { Text("Reload preview") }
                    }
                }
            }

            if (workingDrafts.isNotEmpty()) {
                if (!documentDateTrusted() && workingDrafts.any { it.columnIndex != null || !it.userConfirmedDate }) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (weekResolution.authoritative) "Confirm this rota week once" else "Choose the correct rota week", fontWeight = FontWeight.SemiBold)
                            Text("${rotaWeekStart.format(WEEK_DATE)} – ${rotaWeekStart.plusDays(6).format(WEEK_DATE)}. ${if (weekResolution.authoritative) "Verify these document dates." else "These dates came from a planner fallback, NOT the photograph. Select your actual rota week before importing."} Uncertain times remain blocked.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = ::requestWeekConfirmation) { Text(if (weekResolution.authoritative) "Confirm week" else "Choose actual week") }
                                TextButton(onClick = { pickRotaWeek = true }) { Text("Change dates") }
                            }
                        }
                    }
                }
                Text(
                    "Manage detected shifts",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${workingDrafts.size} detected · ${selected.size} selected for import",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Automatic choices are suggestions only. Deselect them, edit them, or remove them completely before saving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { selected = emptySet() },
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear selection") }
                            OutlinedButton(
                                onClick = {
                                    val ready = workingDrafts.filter(::isImportable)
                                    selected = ready.map { it.id }.toSet()
                                    manuallyChangedDays = manuallyChangedDays + ready.map { it.start.toLocalDate() }
                                },
                                enabled = workingDrafts.any(::isImportable),
                                modifier = Modifier.weight(1f)
                            ) { Text("Select ready") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            lastRemovedDraft?.let { removed ->
                                TextButton(
                                    onClick = {
                                        val data = assistData
                                        workingDrafts = (workingDrafts + removed).distinctBy { it.id }.sortedBy { it.start }
                                        lastRemovedDraft = null
                                        assistMessage = "Restored ${removed.start.format(REVIEW_DATE)}"
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Undo remove") }
                            }
                            TextButton(
                                onClick = {
                                    manuallyChangedDays = manuallyChangedDays + workingDrafts.map { it.start.toLocalDate() }
                                    userRevision++
                                    suppressLateSuggestions = true
                                    workingDrafts = emptyList()
                                    selected = emptySet()
                                    lastRemovedDraft = null
                                    assistMessage = "Cleared all detected candidates. Tap the rota to add only the shifts you want."
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Remove all", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                workingDrafts.forEach { draft ->
                    val checked = draft.id in selected
                    val evidence = evidenceFor(draft, checked)
                    val status = RotaReviewPolicy.status(evidence)
                    val resolved = evidence.identityConfirmed && evidence.dateConfirmed && evidence.timeConfirmed && !evidence.conflicting
                    val ready = status == RotaReviewPolicy.Status.READY
                    ElevatedCard(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (checked) 2.dp else 1.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                enabled = resolved || checked,
                                onCheckedChange = { yes ->
                                    selected = if (yes) selected + draft.id else selected - draft.id
                                    if (yes) manuallyChangedDays = manuallyChangedDays + draft.start.toLocalDate()
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(draft.start.format(REVIEW_DATE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            status.name.replace('_', ' '),
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                                Text(
                                    if (!resolved) {
                                        when (status) {
                                            RotaReviewPolicy.Status.CHECK_DATE -> "Rota date needs confirmation"
                                            RotaReviewPolicy.Status.CHECK_NAME -> "Employee identity needs confirmation"
                                            RotaReviewPolicy.Status.CONFLICT -> "Conflicting recognition evidence · review manually"
                                            else -> "Start time needs confirmation"
                                        }
                                    } else "${draft.start.format(TIME)} – ${draft.end.format(TIME)}  •  ${formatDuration(Duration.between(draft.start, draft.end).toMinutes())}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (resolved) FontWeight.Medium else FontWeight.Normal
                                )
                                if (draft.estimatedEnd) {
                                    Text(
                                        "End time estimated from your ${typicalShiftHours}h typical shift",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (draft.verificationNotes.isNotEmpty()) {
                                    Text(
                                        draft.verificationNotes.take(2).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (draft.verificationState == RotaVerificationEngine.State.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!resolved) {
                                    Text(
                                        when (status) {
                                            RotaReviewPolicy.Status.CHECK_DATE -> "Confirm the rota week or correct this shift's date."
                                            RotaReviewPolicy.Status.CHECK_NAME -> "Confirm the employee name at the highlighted position."
                                            else -> "Open the selector or use the pencil to confirm the exact start time."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { editingImport = draft }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit imported shift")
                                }
                                IconButton(onClick = {
                                    store.recordRotaCorrection("false_shift", draft.start.toString(), "removed from review")
                                    lastRemovedDraft = draft
                                    manuallyChangedDays = manuallyChangedDays + draft.start.toLocalDate()
                                    userRevision++
                                    workingDrafts = workingDrafts.filterNot { it.id == draft.id }
                                    selected = selected - draft.id
                                    assistMessage = "Removed ${draft.start.format(REVIEW_DATE)}. Use Undo remove if needed."
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove detected shift", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            val readySelected = workingDrafts.count { it.id in selected && isImportable(it) }
            val dateBlockedSelected = workingDrafts.any {
                it.id in selected && !it.userConfirmedDate && !documentDateTrusted()
            }
            if (dateBlockedSelected) {
                FilledTonalButton(onClick = ::requestWeekConfirmation, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (weekResolution.authoritative) "CONFIRM WEEK ${rotaWeekStart.format(WEEK_DATE)}" else "CHOOSE ACTUAL ROTA WEEK")
                }
            }
            Button(
                onClick = { onSave(workingDrafts.filter { it.id in selected && isImportable(it) }, sessionEpoch) },
                enabled = readySelected > 0 && !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (readySelected == 1) "ADD 1 SHIFT TO PLANNER" else "ADD $readySelected SHIFTS TO PLANNER", fontWeight = FontWeight.Bold) }
        }
    }

    if (pickRotaWeek) {
        val initialMillis = rotaWeekStart.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickRotaWeek = false },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: rotaWeekStart
                    val newWeek = startOfWeek(chosen)
                    rotaWeekStart = newWeek
                    weekManuallyOverridden = true
                    val rebasedIds = workingDrafts.filter { d ->
                        d.columnIndex != null && !d.userConfirmedDate && d.id !in manuallyConfirmedDates
                    }.mapTo(hashSetOf()) { it.id }
                    selected = selected - rebasedIds
                    workingDrafts = workingDrafts.map { d ->
                        if (d.id !in rebasedIds) d else rebaseDraftToWeek(d, newWeek)
                    }.sortedBy { it.start }
                    assistMessage = "Rota week set to ${newWeek.format(WEEK_DATE)} – ${newWeek.plusDays(6).format(WEEK_DATE)}"
                    pickRotaWeek = false
                }) { Text("Use this week") }
            },
            dismissButton = { TextButton(onClick = { pickRotaWeek = false }) { Text("Cancel") } }
        ) { DatePicker(state = state, showModeToggle = false) }
    }

    if (addingManualDraft) {
        ManualRotaShiftDialog(
            initialDate = rotaWeekStart,
            typicalShiftHours = typicalShiftHours,
            onDismiss = { addingManualDraft = false },
            onSave = { date, time ->
                val start = LocalDateTime.of(date, time)
                val draft = ScheduleImporter.Draft(
                    start = start,
                    end = start.plusHours(typicalShiftHours.coerceIn(1, 16).toLong()),
                    sourceLine = "Manually entered and confirmed during review",
                    confidence = 1f,
                    estimatedEnd = true,
                    columnIndex = null, // Explicit calendar date is authoritative; never rebase.
                    origin = ScheduleImporter.DraftOrigin.ASSISTED,
                    requiresTimeConfirmation = false,
                    verificationState = RotaVerificationEngine.State.CONFIRMED,
                    verificationNotes = listOf("+ user confirmed date and start time"),
                    userConfirmedDate = true,
                    userConfirmedTime = true,
                    userConfirmedIdentity = true
                )
                val overlaps = workingDrafts.any { existing ->
                    draft.start.isBefore(existing.end) && existing.start.isBefore(draft.end)
                }
                if (!overlaps) {
                    workingDrafts = (workingDrafts + draft).sortedBy { it.start }
                    selected = selected + draft.id
                    manuallyChangedDays = manuallyChangedDays + date
                    manuallyConfirmedDates = manuallyConfirmedDates + draft.id
                    userRevision++
                    addingManualDraft = false
                } else {
                    assistMessage = "This shift overlaps an existing candidate. Adjust the start or edit the existing shift."
                }
            }
        )
    }
    editingImport?.let { draft ->
        ImportedDraftEditor(
            draft = draft,
            onDismiss = { editingImport = null },
            onSave = { corrected ->
                if (corrected.start.toLocalDate() != draft.start.toLocalDate()) store.recordRotaCorrection("wrong_day", draft.start.toLocalDate().toString(), corrected.start.toLocalDate().toString())
                if (corrected.start.toLocalTime() != draft.start.toLocalTime()) store.recordRotaCorrection("wrong_time", draft.start.toLocalTime().toString(), corrected.start.toLocalTime().toString())
                manuallyChangedDays = manuallyChangedDays + draft.start.toLocalDate() + corrected.start.toLocalDate()
                manuallyConfirmedDates = manuallyConfirmedDates + draft.id
                userRevision++
                workingDrafts = workingDrafts.map { d -> if (d.id == draft.id) corrected else d }.sortedBy { d -> d.start }
                selected = selected + corrected.id
                assistMessage = "Corrected ${corrected.start.format(REVIEW_DATE)} at ${corrected.start.format(TIME)}"
                editingImport = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualRotaShiftDialog(
    initialDate: LocalDate,
    typicalShiftHours: Int,
    onDismiss: () -> Unit,
    onSave: (LocalDate, LocalTime) -> Unit
) {
    var date by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf(LocalTime.of(9, 30)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a confirmed shift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manual entry remains available while analysis runs. OCR will not overwrite your date or time.")
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.format(REVIEW_DATE))
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start ${time.format(TIME)}")
                }
                Text("End estimated using your ${typicalShiftHours}h typical shift.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(date, time) }) { Text("Add confirmed shift") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickDate = false
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = picker) }
    }
    if (pickTime) {
        val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Exact start time") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = picker) } },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(picker.hour, picker.minute); pickTime = false }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportedDraftEditor(
    draft: ScheduleImporter.Draft,
    onDismiss: () -> Unit,
    onSave: (ScheduleImporter.Draft) -> Unit
) {
    var date by remember(draft.id) { mutableStateOf(draft.start.toLocalDate()) }
    var startTime by remember(draft.id) { mutableStateOf(draft.start.toLocalTime()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val durationMinutes = remember(draft.id) { Duration.between(draft.start, draft.end).toMinutes().coerceAtLeast(30) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct selected shift", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Confirm the day and start time ShiftWatch should use for this highlighted name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.format(REVIEW_DATE))
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start ${startTime.format(TIME)}")
                }
                Text("End time will stay ${durationMinutes / 60}h ${durationMinutes % 60}m after the start.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val start = LocalDateTime.of(date, startTime)
                onSave(draft.copy(
                    start = start,
                    end = start.plusMinutes(durationMinutes),
                    confidence = 1f,
                    sourceLine = "Tap-assisted import · corrected by user",
                    requiresTimeConfirmation = false,
                    verificationState = RotaVerificationEngine.State.CONFIRMED,
                    verificationNotes = draft.verificationNotes + "+ user corrected day/time",
                    userConfirmedDate = true,
                    userConfirmedTime = true,
                    userConfirmedIdentity = true
                ))
            }) { Text("Save correction") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = startTime.hour, initialMinute = startTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Select start time") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
            confirmButton = {
                TextButton(onClick = { startTime = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AssistedScheduleImage(
    store: ShiftStore,
    employeeName: String,
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    assistData: ScheduleImporter.AssistData,
    onAssistDataChanged: (ScheduleImporter.AssistData) -> Unit,
    onMarkerSummary: (RotaDiagnosticEvidence.MarkerSummary) -> Unit,
    viewerRecognitionCache: RotaViewerRecognitionCache?,
    onViewerRecognitionCache: (RotaViewerRecognitionCache?) -> Unit,
    selectedDrafts: List<ScheduleImporter.Draft>,
    onTap: (Float, Float) -> ScheduleImporter.Draft?,
    onDeselect: (ScheduleImporter.Draft) -> Unit,
    quickTimes: List<LocalTime>,
    onConfirmTime: (ScheduleImporter.Draft, LocalTime) -> ScheduleImporter.Draft
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var learnedProfile by remember(employeeName) { mutableStateOf(store.savedRotaVisionProfile(employeeName)) }
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    val automaticTable = assistData.documentKind == ScheduleImporter.DocumentKind.PRINTED_TABLE ||
        (selectedDrafts.isNotEmpty() && selectedDrafts.all { it.origin == ScheduleImporter.DraftOrigin.PRINTED_TABLE })
    // Printed/Excel rotas open straight into a visual review: positions do not require manual
    // confirmation, but the user still sees the original table and detected data before saving.
    // Keep the viewer lifecycle tied to the image, not to changing selection data.
    // Previously, adding/removing a draft changed the remember key while the user was touching
    // the preview, disposing and recreating the dialog mid-gesture. On some devices this left
    // only the empty dialog surface.
    // The original automatic full-screen dialog covered the review sheet as soon as analysis
    // began, effectively hiding manual controls for minutes. Only open it on an explicit tap.
    var fullScreen by remember(bitmap) { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (automaticTable) "Automatic table review" else "Smart rota selection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            automaticTable -> "${selectedDrafts.size} shift${if (selectedDrafts.size == 1) "" else "s"} detected • review and explicitly select the rows you want to import"
                            selectedDrafts.isEmpty() -> "ShiftWatch scans printed text first, then handwriting patterns. Tap examples only when automatic detection needs help."
                            else -> "${selectedDrafts.size} shift${if (selectedDrafts.size == 1) "" else "s"} detected • none are saved until you explicitly select them"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "ON-DEVICE",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { fullScreen = true }
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Imported rota preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(ComposeColor.Black.copy(alpha = 0.04f))
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 2.dp
                ) {
                    Text(
                        "Open smart selector",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            FilledTonalButton(onClick = { fullScreen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (automaticTable) "REVIEW DETECTED TABLE" else if (selectedDrafts.isEmpty()) "SELECT SHIFTS" else "REVIEW SELECTIONS")
            }
        }
    }

    if (fullScreen) {
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (automaticTable) "Review detected table" else "Select shifts", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (automaticTable) "Detected table rows are suggestions. Compare them with the original and explicitly select what you want to import."
                                else "Automatic scan runs first. Review detected shifts; tap examples only for names the engine could not resolve.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { fullScreen = false }) { Text("Done") }
                    }
                    ZoomableRotaImage(
                        store = store,
                        image = image,
                        rawBitmap = bitmap,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        assistData = assistData,
                        onAssistDataChanged = onAssistDataChanged,
                        onMarkerSummary = onMarkerSummary,
                        viewerRecognitionCache = viewerRecognitionCache,
                        onViewerRecognitionCache = onViewerRecognitionCache,
                        onTap = onTap,
                        onDeselect = onDeselect,
                        quickTimes = quickTimes,
                        onConfirmTime = onConfirmTime,
                        employeeName = employeeName,
                        initialVisionProfile = learnedProfile,
                        onVisionProfileLearned = { profile ->
                            val merged = OfflineRotaVision.mergeProfiles(learnedProfile, profile) ?: profile
                            store.saveRotaVisionProfile(employeeName, merged)
                            learnedProfile = merged
                        },
                        reviewOnly = automaticTable,
                        initialDrafts = selectedDrafts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoomableRotaImage(
    store: ShiftStore,
    image: androidx.compose.ui.graphics.ImageBitmap,
    rawBitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    assistData: ScheduleImporter.AssistData,
    onAssistDataChanged: (ScheduleImporter.AssistData) -> Unit,
    onMarkerSummary: (RotaDiagnosticEvidence.MarkerSummary) -> Unit,
    viewerRecognitionCache: RotaViewerRecognitionCache?,
    onViewerRecognitionCache: (RotaViewerRecognitionCache?) -> Unit,
    onTap: (Float, Float) -> ScheduleImporter.Draft?,
    onDeselect: (ScheduleImporter.Draft) -> Unit,
    quickTimes: List<LocalTime>,
    onConfirmTime: (ScheduleImporter.Draft, LocalTime) -> ScheduleImporter.Draft,
    employeeName: String,
    initialVisionProfile: String?,
    onVisionProfileLearned: (String) -> Unit,
    reviewOnly: Boolean = false,
    initialDrafts: List<ScheduleImporter.Draft> = emptyList(),
    modifier: Modifier = Modifier
) {
    data class TapMarker(
        val x: Float,
        val y: Float,
        val draft: ScheduleImporter.Draft?,
        val suggestionScore: Float? = null,
        val origin: String = "user_tap"
    )

    val reusedCache = remember(rawBitmap) {
        viewerRecognitionCache?.takeIf {
            it.reusableFor(initialVisionProfile, assistData.ocrPasses, assistData.tokens.size, reviewOnly)
        }
    }
    val startupVisionProfile = remember(rawBitmap) { initialVisionProfile }
    val recognitionRunId = remember(rawBitmap) { reusedCache?.runId ?: UUID.randomUUID().toString() }
    val recognitionState = if (reusedCache != null) "reused_completed" else "fresh_viewer_run"
    val currentCacheCallback by rememberUpdatedState(onViewerRecognitionCache)
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fitWidth by remember(rawBitmap) { mutableStateOf(false) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var markers by remember(rawBitmap) {
        mutableStateOf(reusedCache?.availableSuggestions { suggestion ->
            val column = ScheduleImporter.columnIndexForX(assistData, suggestion.x)
            initialDrafts.any { draft ->
                draft.columnIndex == column && draft.physicalBlockId != null &&
                    draft.physicalBlockId == RotaGridModel.blockIndexForY(assistData, column, suggestion.y)
            }
        }?.map { TapMarker(it.x, it.y, null, it.score, it.origin) }.orEmpty())
    }
    val currentMarkerCallback by rememberUpdatedState(onMarkerSummary)
    var ocrHitCounts by remember(rawBitmap) { mutableStateOf(reusedCache?.ocrHitsByColumn ?: List(7) { 0 }) }
    var profileColumnDecisions by remember(rawBitmap) { mutableStateOf(reusedCache?.profileDecisions.orEmpty()) }
    var profileTraceStatus by remember(rawBitmap) { mutableStateOf(reusedCache?.profileStatus ?: "not_attempted") }
    var seededColumnDecisions by remember(rawBitmap) { mutableStateOf(reusedCache?.seededDecisions.orEmpty()) }
    var seededTraceStatus by remember(rawBitmap) { mutableStateOf(reusedCache?.seededStatus ?: "not_attempted") }
    LaunchedEffect(markers, assistData, ocrHitCounts, profileColumnDecisions, profileTraceStatus, seededColumnDecisions, seededTraceStatus) {
        val suggestedByColumn = MutableList(7) { 0 }
        val confirmedByColumn = MutableList(7) { 0 }
        markers.forEach { marker ->
            val column = ScheduleImporter.columnIndexForX(assistData, marker.x)
            if (column in 0..6) {
                if (marker.draft == null) suggestedByColumn[column]++
                else confirmedByColumn[column]++
            }
        }
        if (profileTraceStatus == "completed") {
            currentCacheCallback(RotaViewerRecognitionCache(
                runId = recognitionRunId,
                profileFingerprint = startupVisionProfile?.hashCode(),
                ocrPasses = assistData.ocrPasses,
                tokenCount = assistData.tokens.size,
                reviewOnly = reviewOnly,
                suggestions = markers.filter { it.draft == null }.map {
                    RotaViewerRecognitionCache.Suggestion(it.x, it.y, it.suggestionScore, it.origin)
                },
                ocrHitsByColumn = ocrHitCounts,
                profileDecisions = profileColumnDecisions,
                profileStatus = profileTraceStatus,
                seededDecisions = seededColumnDecisions,
                seededStatus = seededTraceStatus
            ))
        }
        currentMarkerCallback(RotaDiagnosticEvidence.MarkerSummary(
            recognitionRunId = recognitionRunId,
            recognitionLifecycle = recognitionState,
            viewerOpened = true,
            suggested = suggestedByColumn.sum(),
            confirmed = confirmedByColumn.sum(),
            suggestionsByColumn = suggestedByColumn,
            confirmedByColumn = confirmedByColumn,
            ocrHitsByColumn = ocrHitCounts,
            profileDecisions = profileColumnDecisions,
            profileStatus = profileTraceStatus,
            seededSearchDecisions = seededColumnDecisions,
            seededSearchStatus = seededTraceStatus,
            markerDetails = markers.map { marker ->
                val column = ScheduleImporter.columnIndexForX(assistData, marker.x)
                RotaDiagnosticEvidence.MarkerDetail(
                    weekdayColumn = column,
                    physicalBlockIndex = RotaGridModel.blockIndexForY(assistData, column, marker.y),
                    origin = if (marker.draft != null) "confirmed_user_selection" else marker.origin,
                    scoreBucket = marker.suggestionScore?.let { (it.coerceIn(0f, 1f) * 10).toInt().coerceAtMost(9) },
                    confirmed = marker.draft != null
                )
            }
        ))
    }
    var pendingDraftId by remember { mutableStateOf<String?>(null) }
    var pendingHint by remember { mutableStateOf<String?>(null) }
    var showExactTimePicker by remember { mutableStateOf(false) }
    var visionSeeds by remember(rawBitmap) { mutableStateOf<List<Offset>>(emptyList()) }
    var visionBusy by remember { mutableStateOf(false) }
    var tapHandlingBusy by remember { mutableStateOf(false) }
    var lastInteractionMillis by remember { mutableLongStateOf(0L) }
    var showBlockDiagnostics by remember(rawBitmap) { mutableStateOf(false) }
    var visionMessage by remember(rawBitmap) {
        mutableStateOf(
            if (reusedCache != null) RotaViewerStatus.completedSuggestions(
                markers.count { it.draft == null }
            ) else "Analyzing name, table structure and shift times…"
        )
    }
    var currentVisionProfile by remember(rawBitmap) { mutableStateOf(initialVisionProfile) }
    LaunchedEffect(initialVisionProfile) { currentVisionProfile = initialVisionProfile }
    LaunchedEffect(initialDrafts) {
        // Marker overlays share the current review's authoritative candidate objects.
        // Rebased dates, edited start times and deletions cannot leave a stale marker copy.
        val latest = initialDrafts.associateBy { it.id }
        markers = markers.mapNotNull { marker ->
            val old = marker.draft
            if (old == null) marker else latest[old.id]?.let { marker.copy(draft = it) }
        }
    }
    var profileLoaded by remember(rawBitmap) { mutableStateOf(reusedCache != null) }
    // Bound deep handwriting jobs to one CPU worker in this viewer. They must not compete
    // with Compose rendering or run simultaneously after repeated teaching taps.
    val visionDispatcher = remember(rawBitmap) { Dispatchers.Default.limitedParallelism(1) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun mergeAutoMarkers(current: List<TapMarker>, incoming: List<TapMarker>): List<TapMarker> {
        val out = current.toMutableList()
        incoming.forEach { candidate ->
            val column = ScheduleImporter.columnIndexForX(assistData, candidate.x)
            val index = out.indexOfFirst { existing ->
                ScheduleImporter.columnIndexForX(assistData, existing.x) == column &&
                    kotlin.math.abs(existing.y - candidate.y) <= assistData.imageHeight * 0.035f
            }
            if (index < 0) out += candidate
            else {
                val existing = out[index]
                val preferCandidate = when {
                    candidate.draft != null && existing.draft == null -> true
                    candidate.draft == null && existing.draft != null -> false
                    else -> (candidate.suggestionScore ?: 1f) > (existing.suggestionScore ?: 1f)
                }
                if (preferCandidate) out[index] = candidate
            }
        }
        return out.sortedWith(compareBy<TapMarker> { ScheduleImporter.columnIndexForX(assistData, it.x) }.thenBy { it.y })
    }

    // Text-first autonomous bootstrap. Printed / Excel rotas must not require the handwriting
    // teaching flow: if OCR reads the requested employee, use those locations directly. When two
    // or more reliable text hits exist, RotaVision may additionally search for visually matching
    // occurrences that OCR missed.
    LaunchedEffect(rawBitmap, assistData.ocrPasses, assistData.tokens.size, employeeName, startupVisionProfile, reviewOnly) {
        // Avoid running the saved-profile matcher and OCR-bootstrap matcher at the same time.
        // They both scan the full rota and previously could overlap during first composition.
        if (visionBusy) return@LaunchedEffect
        if (visionSeeds.isEmpty()) {
            if (assistData.tokens.isEmpty()) {
                visionMessage = "No OCR regions found • select shifts manually"
                return@LaunchedEffect
            }
            val directHits = OfflineRotaVision.ocrNameHits(assistData, employeeName)
                .filter { it.exact || it.score >= 0.84f }
            ocrHitCounts = (0..6).map { column -> directHits.count { it.column == column } }
            // Single bootstrap owner: saved-profile matching runs BEFORE OCR-based visual
            // expansion, never in a competing LaunchedEffect. This also allows OCR expansion
            // after a saved profile finds no matches (the old launch race could skip it forever).
            if (!reviewOnly && !profileLoaded && !startupVisionProfile.isNullOrBlank() && directHits.size < 2) {
                profileLoaded = true
                visionBusy = true
                visionMessage = "Checking your saved handwriting model…"
                val profileReport = try {
                    kotlinx.coroutines.withContext(visionDispatcher) {
                        OfflineRotaVision.findMatchesFromProfile(rawBitmap, assistData, startupVisionProfile)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    visionBusy = false
                }
                profileTraceStatus = if (profileReport == null) "failed" else "completed"
                profileColumnDecisions = profileReport?.columnDecisions.orEmpty()
                if (profileReport != null && profileReport.matches.isNotEmpty()) {
                    val proposed = profileReport.matches.map { match ->
                        TapMarker(match.x, match.y, draft = null, suggestionScore = match.score, origin = "saved_profile")
                    }
                    markers = mergeAutoMarkers(markers, proposed)
                    visionMessage = RotaViewerStatus.completedSuggestions(profileReport.matches.size)
                } else if (directHits.isEmpty()) {
                    visionMessage = if (profileReport == null) {
                        "Handwriting analysis unavailable • select shifts manually"
                    } else {
                        RotaViewerStatus.completedSuggestions(0)
                    }
                }
            }
            if (directHits.isNotEmpty()) {
                visionBusy = true
                visionSeeds = directHits.map { Offset(it.x, it.y) }
                val directMarkers = directHits.map { hit ->
                    if (reviewOnly) {
                        val draft = initialDrafts.firstOrNull { it.columnIndex == hit.column }
                        TapMarker(hit.x, hit.y, draft = draft, suggestionScore = if (draft == null) hit.score else null, origin = "ocr_name")
                    } else {
                        TapMarker(hit.x, hit.y, draft = null, suggestionScore = if (hit.exact) 0.99f else hit.score, origin = "ocr_name")
                    }
                }
                var mergedMarkers = mergeAutoMarkers(markers, directMarkers)
                markers = mergedMarkers

                val strongHits = directHits.filter { it.exact || it.score >= 0.90f }
                if (!reviewOnly && strongHits.isNotEmpty()) {
                    visionMessage = "Recognized ${strongHits.size} occurrence${if (strongHits.size == 1) "" else "s"} of your name • checking for missed rows…"
                    val seeds = strongHits.map { it.x to it.y }
                    val report = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            OfflineRotaVision.findSimilarNames(rawBitmap, assistData, seeds, employeeName)
                        }
                    } catch (cancelled: CancellationException) {
                        visionBusy = false
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    seededTraceStatus = if (report == null) "failed" else "completed"
                    seededColumnDecisions = report?.columnDecisions.orEmpty()
                    if (report != null) {
                        val visualMarkers = report.matches.map { match ->
                            TapMarker(match.x, match.y, draft = null, suggestionScore = match.score, origin = "seeded_visual_search")
                        }
                        mergedMarkers = mergeAutoMarkers(mergedMarkers, visualMarkers)
                        markers = mergedMarkers
                    }
                }
                val resolved = mergedMarkers.count { it.draft != null }
                val review = mergedMarkers.size - resolved
                visionMessage = when {
                    reviewOnly -> "$resolved detected shift${if (resolved == 1) "" else "s"} ready for review • choose only the ones you want to import"
                    resolved > 0 && review > 0 -> "$resolved candidate${if (resolved == 1) "" else "s"} resolved from text • $review need review"
                    resolved > 0 -> "$resolved candidate${if (resolved == 1) "" else "s"} recognized • review before selecting"
                    directHits.size == 1 -> "Found your name once automatically • review this shift or add another example if the rota contains more"
                    else -> "Found your name automatically • confirm unresolved times before saving"
                }
                visionBusy = false
            }
        }
    }

    fun confirmPendingTime(draft: ScheduleImporter.Draft, time: LocalTime) {
        // v19: turn an explicit user time correction into durable block-level template knowledge.
        // Only confirmed input trains the structural model; automatic guesses never reinforce
        // themselves, which prevents a bad OCR result from poisoning future rotas.
        markers.firstOrNull { it.draft?.id == draft.id }?.let { marker ->
            val trainedAssist = ScheduleImporter.withConfirmedBlockTime(assistData, marker.x, marker.y, time)
            store.saveRotaTemplate(employeeName, ScheduleImporter.templateSnapshot(trainedAssist))
            // v19.2: keep the active review session in sync with the manual calibration.  Older
            // builds only persisted the mapping for the *next* import, so the other matches in
            // the same physical row stayed unresolved until the screen was reopened.
            onAssistDataChanged(trainedAssist)
        }
        val corrected = onConfirmTime(draft, time)
        markers = markers.map { marker ->
            if (marker.draft?.id == draft.id) marker.copy(draft = corrected, suggestionScore = null) else marker
        }
        pendingDraftId = null
        pendingHint = null
        showExactTimePicker = false
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // v19.2: one explicit row calibration should resolve every already-detected employee in the
    // same physical block immediately.  This keeps manual teaching small: confirm 09:30 once for
    // the morning row and all morning matches can inherit it without reopening/re-importing.
    LaunchedEffect(assistData.semanticTimeBands) {
        if (reviewOnly || markers.isEmpty()) return@LaunchedEffect
        var changed = false
        val recalibrated = markers.map { marker ->
            val calibrated = ScheduleImporter.confirmedBlockTimeForPoint(assistData, marker.x, marker.y)
                ?: return@map marker
            val existing = marker.draft
            when {
                existing != null && existing.requiresTimeConfirmation -> {
                    changed = true
                    marker.copy(draft = onConfirmTime(existing, calibrated), suggestionScore = null)
                }
                existing == null -> marker // Time calibration never confirms an unconfirmed identity.
                else -> marker
            }
        }
        if (changed) {
            markers = recalibrated
            visionMessage = "Calibrated row time applied to matching shifts"
        }
    }

    fun addResolvedTap(sourceX: Float, sourceY: Float): ScheduleImporter.Draft? {
        val draft = onTap(sourceX, sourceY) ?: return null
        val sameDraftIndex = markers.indexOfFirst {
            it.draft?.id == draft.id || (draft.confidence >= 0.92f && it.draft?.start == draft.start)
        }
        markers = if (sameDraftIndex >= 0) {
            markers.mapIndexed { index, marker ->
                if (index == sameDraftIndex) TapMarker(sourceX, sourceY, draft) else marker
            }
        } else {
            markers + TapMarker(sourceX, sourceY, draft)
        }
        if (draft.confidence < 0.92f) {
            pendingDraftId = draft.id
            pendingHint = "Shift day found. Confirm the handwritten start time."
        }
        return draft
    }

    fun learnFromConfirmed(sourceX: Float, sourceY: Float, recomputeSuggestions: Boolean = true) {
        val column = ScheduleImporter.columnIndexForX(assistData, sourceX)
        val duplicate = visionSeeds.any { seed ->
            ScheduleImporter.columnIndexForX(assistData, seed.x) == column &&
                kotlin.math.abs(seed.y - sourceY) < sourceHeight * 0.035f
        }
        val newSeeds = if (duplicate) visionSeeds else visionSeeds + Offset(sourceX, sourceY)
        visionSeeds = newSeeds
        if (newSeeds.size >= 2) {
            scope.launch {
                val profile = kotlinx.coroutines.withContext(visionDispatcher) {
                    runCatching {
                        OfflineRotaVision.createProfileSet(rawBitmap, assistData, newSeeds.map { it.x to it.y })
                    }.getOrNull()
                }
                profile?.let { learned ->
                    val merged = OfflineRotaVision.mergeProfiles(currentVisionProfile, learned) ?: learned
                    currentVisionProfile = merged
                    onVisionProfileLearned(merged)
                }
            }
        }

        // Keep already-good blue suggestions while the model learns another example. Older builds
        // cleared them immediately, so a correct suggestion visibly disappeared as soon as the user
        // tapped a different day. Confirmed shifts and high-confidence suggestions now persist.
        val retainedSuggestions = markers.filter { it.draft == null && (it.suggestionScore ?: 0f) >= 0.70f }
        if (newSeeds.size < 2) {
            visionMessage = "Example 1 learned • tap the same name on one more day"
            return
        }
        if (!recomputeSuggestions) {
            val remaining = markers.count { it.draft == null }
            visionMessage = if (remaining > 0) "$remaining suggested match${if (remaining == 1) "" else "es"} ready • confirm them before teaching more" else "Selection learned"
            return
        }
        if (visionBusy) return
        visionBusy = true
        visionMessage = "Comparing ${newSeeds.size} confirmed handwriting examples…"
        scope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(visionDispatcher) {
                    OfflineRotaVision.findSimilarNames(
                        rawBitmap,
                        assistData,
                        newSeeds.map { it.x to it.y },
                        employeeName
                    )
                }
            }
            result.onSuccess { report ->
                seededTraceStatus = "completed"
                seededColumnDecisions = report.columnDecisions
                fun candidateBlock(column: Int, y: Float): Int =
                    RotaGridModel.blockIndexForY(assistData, column, y)
                        ?: (y / (sourceHeight * .06f).coerceAtLeast(1f)).toInt()
                val confirmedBlocks = markers.filter { it.draft != null }.map { marker ->
                    val column = ScheduleImporter.columnIndexForX(assistData, marker.x)
                    column to candidateBlock(column, marker.y)
                }.toSet()
                val suggestions = report.matches
                    .filter { (it.column to candidateBlock(it.column, it.y)) !in confirmedBlocks }
                    .filter { match -> markers.none { marker ->
                        marker.draft != null &&
                            ScheduleImporter.columnIndexForX(assistData, marker.x) == match.column &&
                            kotlin.math.abs(marker.y - match.y) < sourceHeight * 0.035f
                    } }
                    .map { match ->
                        // Newly learned handwriting matches are also review suggestions only.
                        TapMarker(match.x, match.y, draft = null, suggestionScore = match.score, origin = "learned_visual_search")
                    }
                val confirmed = (markers.filter { it.draft != null } + suggestions.filter { it.draft != null })
                    .distinctBy { it.draft?.id ?: "${it.x}:${it.y}" }
                val unresolvedSuggestions = suggestions.filter { it.draft == null }
                val confirmedKeys = confirmed.map { marker ->
                    val col = ScheduleImporter.columnIndexForX(assistData, marker.x)
                    col to candidateBlock(col, marker.y)
                }.toSet()
                val mergedSuggestions = (retainedSuggestions + unresolvedSuggestions)
                    .filter { marker ->
                        val col = ScheduleImporter.columnIndexForX(assistData, marker.x)
                        (col to candidateBlock(col, marker.y)) !in confirmedKeys
                    }
                    .groupBy { marker ->
                        val col = ScheduleImporter.columnIndexForX(assistData, marker.x)
                        col to candidateBlock(col, marker.y)
                    }
                    .mapNotNull { (_, items) -> items.maxByOrNull { it.suggestionScore ?: 0f } }
                markers = confirmed + mergedSuggestions
                visionMessage = when {
                    report.matches.isEmpty() -> "No strong automatic matches yet • tap one more correct example"
                    unresolvedSuggestions.size == 1 -> "1 suggested match • tap it to confirm"
                    else -> "${unresolvedSuggestions.size} suggested matches • tap blue markers to confirm"
                }
            }.onFailure {
                seededTraceStatus = "failed"
                seededColumnDecisions = emptyList()
                visionMessage = "Smart matching paused • manual selection remains available"
            }
            visionBusy = false
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        val widthFit = viewportWidthPx / sourceWidth.coerceAtLeast(1).toFloat()
        val contentHeight = sourceHeight * widthFit * scale
        val baseOverflow = (contentHeight + (if (fitWidth) with(density) { 44.dp.toPx() } else 0f) +
            with(density) { 84.dp.toPx() } - viewportHeightPx).coerceAtLeast(0f)
        offset = when {
            fitWidth && scale <= 1.01f -> Offset(0f, (offset.y + panChange.y).coerceIn(-baseOverflow, 0f))
            scale <= 1.01f -> Offset.Zero
            else -> offset + panChange
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(ComposeColor.Black)
            .onSizeChanged { viewportWidthPx = it.width.toFloat(); viewportHeightPx = it.height.toFloat() }
            .transformable(transformState)
            .pointerInput(scale, offset, fitWidth, sourceWidth, sourceHeight, markers, pendingDraftId, tapHandlingBusy) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val newScale = if (scale < 1.8f) 2.5f else 1f
                        if (newScale == 1f) offset = Offset.Zero
                        else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the finger's document location stationary while zooming.
                            offset = tap - center - (tap - center - offset) * (newScale / scale)
                        }
                        scale = newScale
                    },
                    onTap = { tap ->
                        // v20.3 single-flight interaction guard. Repeated taps while handwriting
                        // matching is running used to queue expensive analysis and could trigger ANRs.
                        val now = SystemClock.uptimeMillis()
                        if (tapHandlingBusy || now - lastInteractionMillis < 180L) return@detectTapGestures
                        lastInteractionMillis = now
                        tapHandlingBusy = true
                        try {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseTap = Offset(
                            x = (tap.x - center.x - offset.x) / scale + center.x,
                            y = (tap.y - center.y - offset.y) / scale + center.y
                        )
                        val fit = if (fitWidth) size.width.toFloat() / sourceWidth.coerceAtLeast(1)
                            else minOf(size.width.toFloat() / sourceWidth.coerceAtLeast(1),
                                size.height.toFloat() / sourceHeight.coerceAtLeast(1))
                        val drawnWidth = sourceWidth * fit
                        val drawnHeight = sourceHeight * fit
                        val left = (size.width - drawnWidth) / 2f
                        val top = if (fitWidth) 44.dp.toPx() else (size.height - drawnHeight) / 2f
                        if (baseTap.x !in left..(left + drawnWidth) || baseTap.y !in top..(top + drawnHeight)) return@detectTapGestures

                        val sourceX = ((baseTap.x - left) / fit).coerceIn(0f, sourceWidth.toFloat())
                        val sourceY = ((baseTap.y - top) / fit).coerceIn(0f, sourceHeight.toFloat())
                        val tappedColumn = ScheduleImporter.columnIndexForX(assistData, sourceX)
                        val tapRadiusY = sourceHeight * 0.042f
                        val nearbyIndex = markers.indexOfFirst { marker ->
                            ScheduleImporter.columnIndexForX(assistData, marker.x) == tappedColumn &&
                                kotlin.math.abs(marker.y - sourceY) <= tapRadiusY
                        }

                        if (nearbyIndex >= 0) {
                            val marker = markers[nearbyIndex]
                            when {
                                marker.draft == null -> {
                                    // Blue suggestion: confirm it with one tap.
                                    val draft = onTap(marker.x, marker.y)
                                    if (draft != null) {
                                        markers = markers.mapIndexed { index, item ->
                                            if (index == nearbyIndex) item.copy(draft = draft, suggestionScore = null) else item
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        learnFromConfirmed(marker.x, marker.y, recomputeSuggestions = true)
                                        if (draft.confidence < 0.92f) {
                                            pendingDraftId = draft.id
                                            pendingHint = "Name confirmed. Now confirm its start time."
                                        }
                                    }
                                }
                                else -> {
                                    // Every resolved handwriting result remains editable.  A high
                                    // confidence OCR guess must never become an irreversible lock:
                                    // tapping the marker opens the same correction panel. Removal
                                    // is available explicitly inside that panel.
                                    pendingDraftId = marker.draft.id
                                    pendingHint = if (marker.draft.confidence >= 0.92f)
                                        "Review the detected start time. Change it if the handwriting was interpreted incorrectly."
                                    else "Confirm the start time for this shift."
                                }
                            }
                        } else {
                            val pending = markers.firstOrNull { it.draft?.id == pendingDraftId }?.draft
                            if (pending != null) {
                                pendingHint = "Checking the label in the background. Exact time is available now."
                                val expectedId = pending.id
                                scope.launch {
                                    val tappedTime = withContext(Dispatchers.Default) {
                                        ScheduleImporter.timeFromTap(assistData, sourceX, sourceY)
                                    }
                                    if (pendingDraftId == expectedId) {
                                        if (tappedTime != null) confirmPendingTime(pending, tappedTime)
                                        else pendingHint = "Label unclear. Use a quick time or Exact time."
                                    }
                                }
                            } else {
                                val draft = addResolvedTap(sourceX, sourceY)
                                if (draft != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    learnFromConfirmed(sourceX, sourceY)
                                }
                            }
                        }
                        } finally {
                            tapHandlingBusy = false
                        }
                    }
                )
            }
    ) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }
        ) {
            val fit = if (fitWidth) size.width / sourceWidth.coerceAtLeast(1)
                else minOf(size.width / sourceWidth.coerceAtLeast(1), size.height / sourceHeight.coerceAtLeast(1))
            val dstWidth = (sourceWidth * fit).roundToInt().coerceAtLeast(1)
            val dstHeight = (sourceHeight * fit).roundToInt().coerceAtLeast(1)
            val left = ((size.width - dstWidth) / 2f).roundToInt()
            val top = if (fitWidth) 44.dp.toPx().roundToInt() else ((size.height - dstHeight) / 2f).roundToInt()
            drawImage(
                image = image,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(dstWidth, dstHeight)
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            val fit = if (fitWidth) size.width / sourceWidth.coerceAtLeast(1)
                else minOf(size.width / sourceWidth.coerceAtLeast(1), size.height / sourceHeight.coerceAtLeast(1))
            val drawnWidth = sourceWidth * fit
            val drawnHeight = sourceHeight * fit
            val left = (size.width - drawnWidth) / 2f
            val top = if (fitWidth) 44.dp.toPx() else (size.height - drawnHeight) / 2f
            val highlightHeight = (sourceHeight * 0.048f).coerceAtLeast(50f) * fit
            val placedLabelRects = mutableListOf<android.graphics.RectF>()

            markers.forEach { marker ->
                val cx = left + marker.x * fit
                val cy = top + marker.y * fit
                val sourceColumn = ScheduleImporter.columnIndexForX(assistData, marker.x)
                val sourceBounds = ScheduleImporter.columnBounds(assistData, sourceColumn)
                val columnLeft = left + sourceBounds.first * fit
                val columnRight = left + sourceBounds.second * fit
                val highlightWidth = ((sourceBounds.second - sourceBounds.first) * 0.68f * fit).coerceAtLeast(1f)
                val clampedLeft = (cx - highlightWidth / 2f).coerceIn(
                    columnLeft + 3.dp.toPx(),
                    (columnRight - highlightWidth - 3.dp.toPx()).coerceAtLeast(columnLeft + 3.dp.toPx())
                )
                val topLeft = Offset(clampedLeft, cy - highlightHeight / 2f)
                val suggested = marker.draft == null
                val resolved = marker.draft?.let { !it.requiresTimeConfirmation && it.tier != ScheduleImporter.ConfidenceTier.UNRESOLVED } == true
                val fill = when {
                    suggested -> ComposeColor(0x263E8BFF)
                    resolved -> ComposeColor(0x4D4CAF50)
                    else -> ComposeColor(0x4DFF9800)
                }
                val outline = when {
                    suggested -> ComposeColor(0xFF72A7FF)
                    resolved -> ComposeColor(0xFF86E48F)
                    else -> ComposeColor(0xFFFFC46B)
                }
                drawRoundRect(
                    color = fill,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(highlightWidth, highlightHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(highlightHeight / 2f, highlightHeight / 2f)
                )
                drawRoundRect(
                    color = outline,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(highlightWidth, highlightHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(highlightHeight / 2f, highlightHeight / 2f),
                    style = Stroke(width = if (suggested) 2.dp.toPx() else 3.dp.toPx())
                )
                drawCircle(color = outline, radius = 6.dp.toPx(), center = Offset(cx, cy))

                val label = when {
                    suggested -> {
                        // Preview must never run OCR/structural inference inside Canvas.draw.
                        // Blue markers represent identity proposals only; confirm the name first.
                        if (showBlockDiagnostics) {
                            val block = RotaGridModel.blockIndexForY(assistData, sourceColumn, marker.y)
                            if (block == null) "match · row?" else "match · B${block + 1}"
                        } else "match"
                    }
                    marker.draft == null -> null
                    marker.draft.requiresTimeConfirmation || marker.draft.tier == ScheduleImporter.ConfidenceTier.UNRESOLVED -> {
                        val day = marker.draft.start.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        "$day · time?"
                    }
                    else -> {
                        val day = marker.draft.start.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        "$day ${marker.draft.start.format(TIME)}"
                    }
                }
                if (label != null) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 10.dp.toPx()
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val bubblePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(232, 24, 21, 30)
                    }
                    val leaderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(190, 255, 255, 255)
                        strokeWidth = 1.2.dp.toPx()
                    }
                    val padX = 6.dp.toPx()
                    val bubbleHeight = 20.dp.toPx()
                    val bubbleWidth = paint.measureText(label) + padX * 2f
                    val margin = 4.dp.toPx()
                    fun candidateRect(centerX: Float, topY: Float): android.graphics.RectF {
                        val safeLeft = columnLeft + margin
                        val safeRight = columnRight - margin
                        val actualWidth = bubbleWidth.coerceAtMost((safeRight - safeLeft).coerceAtLeast(1f))
                        var l = centerX - actualWidth / 2f
                        var r = centerX + actualWidth / 2f
                        if (l < safeLeft) { r += safeLeft - l; l = safeLeft }
                        if (r > safeRight) { l -= r - safeRight; r = safeRight }
                        return android.graphics.RectF(l, topY, r, topY + bubbleHeight)
                    }
                    val gap = 5.dp.toPx()
                    val above = topLeft.y - bubbleHeight - gap
                    val below = topLeft.y + highlightHeight + gap
                    val candidates = buildList {
                        add(candidateRect(cx, above)); add(candidateRect(cx, below))
                        for (step in 1..4) {
                            add(candidateRect(cx, above - step * (bubbleHeight + 3.dp.toPx())))
                            add(candidateRect(cx, below + step * (bubbleHeight + 3.dp.toPx())))
                        }
                    }
                    val imageTop = top + margin
                    val imageBottom = top + drawnHeight - margin
                    val rect = candidates.firstOrNull { it.top >= imageTop && it.bottom <= imageBottom && placedLabelRects.none { placed -> android.graphics.RectF.intersects(placed, it) } }
                        ?: candidateRect(cx, above.coerceAtLeast(imageTop))
                    placedLabelRects += android.graphics.RectF(rect)
                    val anchorY = if (rect.centerY() < cy) rect.bottom else rect.top
                    drawContext.canvas.nativeCanvas.drawLine(cx, cy, rect.centerX(), anchorY, leaderPaint)
                    drawContext.canvas.nativeCanvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, 9.dp.toPx(), 9.dp.toPx(), bubblePaint)
                    drawContext.canvas.nativeCanvas.drawText(label, rect.centerX(), rect.top + 13.5f.dp.toPx(), paint)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clickable { fitWidth = false; scale = 1f; offset = Offset.Zero },
                shape = RoundedCornerShape(999.dp),
                color = if (!fitWidth && scale <= 1.03f) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f) else ComposeColor.Black.copy(alpha = 0.74f)
            ) {
                Text("Fit page", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = if (!fitWidth && scale <= 1.03f) MaterialTheme.colorScheme.onPrimaryContainer else ComposeColor.White, style = MaterialTheme.typography.labelMedium)
            }
            Surface(
                modifier = Modifier.clickable { fitWidth = true; scale = 1f; offset = Offset.Zero },
                shape = RoundedCornerShape(999.dp),
                color = if (fitWidth && scale <= 1.03f) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f) else ComposeColor.Black.copy(alpha = 0.74f)
            ) {
                Text("Fit width", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (fitWidth && scale <= 1.03f) MaterialTheme.colorScheme.onPrimaryContainer else ComposeColor.White,
                    style = MaterialTheme.typography.labelMedium)
            }
            Surface(
                modifier = Modifier.clickable { scale = 1.85f; offset = Offset.Zero },
                shape = RoundedCornerShape(999.dp),
                color = if (scale > 1.03f) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f) else ComposeColor.Black.copy(alpha = 0.74f)
            ) {
                Text("Inspect · ${(scale * 100).toInt()}%", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = if (scale > 1.03f) MaterialTheme.colorScheme.onPrimaryContainer else ComposeColor.White, style = MaterialTheme.typography.labelMedium)
            }
            if (visionBusy) {
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Finding matches", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        val pendingMarker = markers.firstOrNull { it.draft?.id == pendingDraftId }
        val pendingDraft = pendingMarker?.draft
        if (pendingDraft != null) {
            val pendingX = pendingMarker?.x ?: 0f
            val pendingY = pendingMarker?.y ?: 0f
            val contextual by produceState<List<ScheduleImporter.TimeSuggestion>>(
                initialValue = emptyList(), pendingX, pendingY, assistData
            ) {
                value = withContext(Dispatchers.Default) {
                    ScheduleImporter.timeSuggestionsForTap(assistData, pendingX, pendingY)
                }
            }
            val rankedTimes = (contextual.map { it.time } + quickTimes + listOf(
                LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(16, 0), LocalTime.of(18, 0)
            )).distinct().take(7)
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.985f),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Confirm start time", style = MaterialTheme.typography.titleMedium)
                            Text(
                                pendingDraft.start.toLocalDate().format(REVIEW_DATE),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            // Identity-specific learning: removing a detected employee occurrence is
                            // evidence that this handwriting is a confuser. Time edits deliberately
                            // do not train identity. Learn asynchronously so the review UI remains instant.
                            val profileBeforeReject = currentVisionProfile
                            if (!profileBeforeReject.isNullOrBlank()) {
                                scope.launch {
                                    val hardened = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        OfflineRotaVision.learnHardNegative(
                                            rawBitmap, assistData, profileBeforeReject, pendingX, pendingY
                                        )
                                    }
                                    if (!hardened.isNullOrBlank() && hardened != profileBeforeReject) {
                                        currentVisionProfile = hardened
                                        onVisionProfileLearned(hardened)
                                    }
                                }
                            }
                            onDeselect(pendingDraft)
                            markers = markers.filterNot { it.draft?.id == pendingDraft.id }
                            pendingDraftId = null
                            pendingHint = null
                        }) { Text("Remove") }
                    }
                    Text(
                        pendingHint ?: "Confirm this row once. ShiftWatch will apply the same calibrated time to every match in this physical block.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (contextual.isNotEmpty()) {
                        Text("Best matches", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rankedTimes.chunked(3).forEachIndexed { rowIndex, rowTimes ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                rowTimes.forEachIndexed { itemIndex, time ->
                                    val index = rowIndex * 3 + itemIndex
                                    if (index < contextual.size) {
                                        ElevatedAssistChip(
                                            onClick = { confirmPendingTime(pendingDraft, time) },
                                            label = { Text(time.format(TIME)) },
                                            leadingIcon = { Text("★", color = MaterialTheme.colorScheme.primary) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        AssistChip(
                                            onClick = { confirmPendingTime(pendingDraft, time) },
                                            label = { Text(time.format(TIME)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                repeat(3 - rowTimes.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { showExactTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose exact time")
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                shape = RoundedCornerShape(16.dp),
                color = ComposeColor.Black.copy(alpha = 0.76f)
            ) {
                val identityHealth = remember(currentVisionProfile) { OfflineRotaVision.profileDiagnostics(currentVisionProfile) }
                val structuralRows = remember(assistData, showBlockDiagnostics) {
                    if (showBlockDiagnostics) ScheduleImporter.structuralTimeDiagnostics(assistData).take(6) else emptyList()
                }
                // A time is RESOLVED only after it belongs to a confirmed candidate.
                // This is cheap even during pinch zoom and cannot count unconfirmed blue markers.
                val confirmedCount = markers.count { it.draft != null }
                val proposedCount = markers.size - confirmedCount
                val resolvedTimeCount = markers.count { marker ->
                    val draft = marker.draft
                    draft != null && !draft.requiresTimeConfirmation &&
                        draft.tier != ScheduleImporter.ConfidenceTier.UNRESOLVED
                }
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        visionMessage,
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                    if (markers.isNotEmpty()) {
                        Text(
                            "Confirmed times · $resolvedTimeCount/$confirmedCount · $proposedCount suggestions",
                            color = ComposeColor.White.copy(alpha = if (resolvedTimeCount == confirmedCount) 0.92f else 0.78f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (showBlockDiagnostics && confirmedCount > 0 && resolvedTimeCount < confirmedCount) {
                        Text(
                            if (resolvedTimeCount == 0) "Time rows need calibration or clearer block labels" else "Unresolved rows can be taught once and reused",
                            color = ComposeColor.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                    TextButton(onClick = { showBlockDiagnostics = !showBlockDiagnostics }) {
                        Text(if (showBlockDiagnostics) "Hide row diagnostics" else "Show row diagnostics",
                            color = ComposeColor.White.copy(alpha = 0.84f))
                    }
                    if (structuralRows.isNotEmpty()) {
                        Text(
                            "Rows · ${structuralRows.joinToString(" | ")}",
                            color = ComposeColor.White.copy(alpha = 0.66f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                    if (showBlockDiagnostics && identityHealth.stylePrototypes > 0) {
                        Text(
                            "Identity · ${identityHealth.stylePrototypes} styles · ${identityHealth.confuserPrototypes} confusers · ${(identityHealth.separationHealth * 100).toInt()}% boundary",
                            color = ComposeColor.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (showExactTimePicker && pendingDraft != null) {
            val initial = pendingDraft.start.toLocalTime().takeUnless { it == LocalTime.MIDNIGHT }
                ?: quickTimes.firstOrNull() ?: LocalTime.of(9, 0)
            val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { showExactTimePicker = false },
                title = { Text("Set start time") },
                text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
                confirmButton = { TextButton(onClick = { confirmPendingTime(pendingDraft, LocalTime.of(state.hour, state.minute)) }) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = { showExactTimePicker = false }) { Text("Cancel") } }
            )
        }
    }
}

private fun loadScheduleBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            // RotaVision samples pixels directly. ImageDecoder otherwise may return a HARDWARE
            // bitmap; calling getPixel() on that bitmap crashes on Android. Force a software-backed
            // ARGB bitmap for deterministic on-device analysis.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            // Must match ScheduleImporter.loadAnalysisBitmap's sampling exactly. Otherwise
            // visual word coordinates and OCR/grid coordinates use different pixel spaces.
            val maxSide = maxOf(info.size.width, info.size.height)
            val sample = maxOf(1, (maxSide + 3000 - 1) / 3000)
            decoder.setTargetSampleSize(sample)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 3000) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
    when {
        decoded == null -> null
        decoded.config == Bitmap.Config.HARDWARE ->
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== decoded && !decoded.isRecycled) decoded.recycle() }
        decoded.config == null -> decoded.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== decoded && !decoded.isRecycled) decoded.recycle() }
        else -> decoded
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedShiftEditor(
    initial: ShiftStore.PlannedShift?,
    onDismiss: () -> Unit,
    validate: (LocalDateTime, LocalDateTime) -> String?,
    onSave: (LocalDateTime, LocalDateTime) -> Unit,
    onDelete: (() -> Unit)?
) {
    val now = LocalDateTime.now()
    var date by remember { mutableStateOf(initial?.start?.toLocalDate() ?: LocalDate.now()) }
    var start by remember { mutableStateOf(initial?.start?.toLocalTime() ?: now.toLocalTime().withSecond(0).withNano(0)) }
    var end by remember { mutableStateOf(initial?.end?.toLocalTime() ?: now.plusHours(8).toLocalTime().withSecond(0).withNano(0)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (initial == null) "Plan a shift" else "Edit planned shift", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Planned hours never count as worked hours until you actually clock them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) IconButton(onClick = { deleteConfirm = true }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            }
            PlannerPicker("DATE", date.format(REVIEW_DATE), Icons.Default.CalendarMonth) { pickDate = true }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlannerPicker("START", start.format(TIME), Icons.Default.Schedule, Modifier.weight(1f)) { pickStart = true }
                PlannerPicker("END", end.format(TIME), Icons.Default.Schedule, Modifier.weight(1f)) { pickEnd = true }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val s = LocalDateTime.of(date, start)
                    var e = LocalDateTime.of(date, end)
                    if (!e.isAfter(s)) e = e.plusDays(1)
                    val validationMessage = validate(s, e)
                    if (validationMessage != null) {
                        error = validationMessage
                    } else {
                        error = null
                        onSave(s, e)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Save plan", fontWeight = FontWeight.Bold) }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickDate = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (pickStart) PlannerTimeDialog(start, { pickStart = false }, { start = it; pickStart = false })
    if (pickEnd) PlannerTimeDialog(end, { pickEnd = false }, { end = it; pickEnd = false })
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Delete planned shift?") },
            text = { Text("This only removes the plan. It does not change your recorded work history.") },
            confirmButton = { TextButton(onClick = { deleteConfirm = false; onDelete?.invoke() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PlannerPicker(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose time") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun startOfWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
private fun formatDuration(minutes: Long): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m".replace(" 0m", "")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val WEEK_DATE: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val REVIEW_DATE: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
