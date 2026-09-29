package com.example.workshifttracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
        ShiftNotificationManager.sync(this)
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
                val store = remember { ShiftStore(this) }
                WorkShiftApp(store)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShiftNotificationManager.sync(this)
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 401
    }
}

private data class MonthPanelState(
    val month: YearMonth,
    val total: Long,
    val shiftCount: Int,
    val average: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkShiftApp(store: ShiftStore) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShiftStore.Shift?>(null) }
    var adding by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val noticeHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var showDataMenu by remember { mutableStateOf(false) }

    val exportBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { writer ->
                    writer.write(store.exportBackupJson())
                }
            }
            scope.launch {
                noticeHostState.showSnackbar(if (result.isSuccess) "Backup saved" else "Could not save backup")
            }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val raw = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                store.importBackupJson(raw)
            }
            revision++
            scope.launch {
                val imported = result.getOrNull()
                noticeHostState.showSnackbar(
                    if (imported != null) "Imported ${imported.recordedAdded} worked + ${imported.plannedAdded} planned shifts"
                    else "This is not a valid ShiftWatch backup"
                )
            }
        }
    }

    // Re-read persisted state whenever the Activity comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                revision++
                ShiftNotificationManager.sync(store.context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Listen to both SharedPreferences changes and an explicit in-app broadcast.
    // The broadcast makes widget -> app synchronization reliable even if Android
    // temporarily pauses the Activity while the launcher is visible.
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ShiftStore.KEY_SHIFTS || key == ShiftStore.KEY_REVISION) revision++
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ShiftStore.ACTION_DATA_CHANGED) revision++
            }
        }
        store.registerListener(listener)
        ContextCompat.registerReceiver(
            store.context,
            receiver,
            IntentFilter(ShiftStore.ACTION_DATA_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            store.unregisterListener(listener)
            runCatching { store.context.unregisterReceiver(receiver) }
        }
    }

    val all = remember(revision) { store.all() }
    val planned = remember(revision) { store.allPlanned() }
    val active = all.firstOrNull { it.end == null }
    val now = LocalDateTime.now()
    val nextPlanned = planned.filter { it.end >= now }.minByOrNull { it.start }
    val shifts = all.filter { YearMonth.from(it.start) == month }
    val completed = shifts.filter { it.end != null }
    val total = completed.sumOf { it.durationMinutes() }
    val average = if (completed.isEmpty()) 0L else total / completed.size

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(noticeHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.shiftwatch_brand_mark),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Image(
                                painter = painterResource(R.drawable.shiftwatch_wordmark),
                                contentDescription = "ShiftWatch",
                                modifier = Modifier
                                    .width(166.dp)
                                    .height(28.dp)
                            )
                            InstalledAppVersionLabel()
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            store.context.startActivity(Intent(store.context, PlannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    ) {
                        Icon(Icons.Default.EventNote, contentDescription = "Open planner")
                    }
                    Box {
                        IconButton(onClick = { showDataMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Data and backup options")
                        }
                        DropdownMenu(expanded = showDataMenu, onDismissRequest = { showDataMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export backup") },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                onClick = {
                                    showDataMenu = false
                                    exportBackup.launch("ShiftWatch-backup-${LocalDate.now()}.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import backup") },
                                leadingIcon = { Icon(Icons.Default.Restore, null) },
                                onClick = {
                                    showDataMenu = false
                                    importBackup.launch(arrayOf("application/json", "text/plain"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Check local data") },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                                onClick = {
                                    showDataMenu = false
                                    val report = store.verifyAndRepair()
                                    revision++
                                    scope.launch {
                                        val message = if (report.hasWarnings) {
                                            "Data checked: ${report.recordedCount} worked, ${report.plannedCount} planned. Some stored entries needed attention."
                                        } else {
                                            "Data healthy: ${report.recordedCount} worked and ${report.plannedCount} planned shifts"
                                        }
                                        noticeHostState.showSnackbar(message)
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add shift") }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!refreshing) {
                    scope.launch {
                        refreshing = true
                        val report = store.verifyAndRepair()
                        revision++
                        WorkWidgetProvider.updateAll(store.context)
                        ShiftNotificationManager.sync(store.context)
                        // Give the gesture visible feedback while doing a real local integrity pass.
                        delay(250.milliseconds)
                        refreshing = false
                        noticeHostState.currentSnackbarData?.dismiss()
                        noticeHostState.showSnackbar(
                            message = if (report.hasWarnings) "Refreshed • local data checked" else "Up to date • data healthy",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    StatusCard(active = active) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        store.toggleNow()
                        revision++
                    }
                }

                if (nextPlanned != null) {
                    item {
                        NextPlannedCard(nextPlanned) {
                            store.context.startActivity(Intent(store.context, PlannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }

                item {
                    MonthSummary(
                        month = month,
                        total = total,
                        shiftCount = completed.size,
                        average = average,
                        onPrevious = { month = month.minusMonths(1) },
                        onNext = { month = month.plusMonths(1) }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Shifts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Tap a shift to edit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (shifts.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(shifts, key = { it.id }) { shift ->
                        ShiftRow(shift) { editing = shift }
                    }
                }
            }
        }
    }

    if (adding) {
        ShiftEditor(
            initial = null,
            hasAnotherActiveShift = active != null,
            onDismiss = { adding = false },
            validate = { start, end -> store.validateRecordedCandidate(start, end) },
            onSave = { start, end ->
                store.upsert(ShiftStore.Shift(UUID.randomUUID().toString(), start, end))
                revision++
                adding = false
            },
            onDelete = null
        )
    }

    editing?.let { shift ->
        ShiftEditor(
            initial = shift,
            hasAnotherActiveShift = active != null && active.id != shift.id,
            onDismiss = { editing = null },
            validate = { start, end -> store.validateRecordedCandidate(start, end, shift.id) },
            onSave = { start, end ->
                store.upsert(shift.copy(start = start, end = end))
                revision++
                editing = null
            },
            onDelete = {
                store.delete(shift.id)
                revision++
                editing = null
                scope.launch {
                    val result = noticeHostState.showSnackbar(
                        message = "Shift deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        runCatching { store.upsert(shift) }
                        revision++
                    }
                }
            }
        )
    }
}

@Composable
private fun StatusCard(active: ShiftStore.Shift?, onToggle: () -> Unit) {
    val isActive = active != null
    val statusContainer by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        label = "status card color"
    )
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = statusContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
    ) {
        AnimatedContent(
            targetState = isActive,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.985f)) togetherWith
                    (fadeOut() + scaleOut(targetScale = 0.985f))
            },
            label = "work status"
        ) { activeState ->
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (activeState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (activeState) Icons.Default.Schedule else Icons.Default.Work,
                                contentDescription = null,
                                tint = if (activeState) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (activeState) "Shift in progress" else "Ready to start", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (activeState) "Started ${active?.start?.format(TIME)} · ${active?.start?.format(DATE)}" else "Clock in when your workday begins",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    AnimatedContent(
                        targetState = activeState,
                        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.94f)) },
                        label = "clock action"
                    ) { working ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (working) Icons.Default.CheckCircle else Icons.Default.Work, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(if (working) "CLOCK OUT" else "CLOCK IN", fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Recorded times are rounded to the nearest 30 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NextPlannedCard(shift: ShiftStore.PlannedShift, onOpenPlanner: () -> Unit) {
    val today = LocalDate.now()
    val date = shift.start.toLocalDate()
    val dayLabel = when {
        date == today -> "Today"
        date == today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlanner),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.EventNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Next planned shift", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("$dayLabel • ${shift.start.format(DateTimeFormatter.ofPattern("HH:mm"))}–${shift.end.format(DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.titleMedium)
            }
            Text("Planner", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MonthSummary(
    month: YearMonth,
    total: Long,
    shiftCount: Int,
    average: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    val panel = MonthPanelState(month, total, shiftCount, average)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(month) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        when {
                            dragDistance <= -swipeThresholdPx -> onNext()
                            dragDistance >= swipeThresholdPx -> onPrevious()
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        AnimatedContent(
            targetState = panel,
            transitionSpec = {
                val movingForward = targetState.month > initialState.month
                val enter = slideInHorizontally { width -> if (movingForward) width / 4 else -width / 4 } + fadeIn()
                val exit = slideOutHorizontally { width -> if (movingForward) -width / 4 else width / 4 } + fadeOut()
                enter togetherWith exit
            },
            label = "month summary"
        ) { state ->
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalIconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous month") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.month.format(MONTH), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Monthly overview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next month") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("TOTAL", formatDuration(state.total), Modifier.weight(1.2f))
                    MetricCard("SHIFTS", state.shiftCount.toString(), Modifier.weight(1f))
                    MetricCard("AVERAGE", if (state.shiftCount == 0) "—" else formatDuration(state.average), Modifier.weight(1.2f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ShiftRow(shift: ShiftStore.Shift, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(width = 58.dp, height = 62.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(shift.start.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(shift.start.format(SHORT_MONTH).uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(shift.start.format(DAY), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (shift.end == null) "Started ${shift.start.format(TIME)}" else "${shift.start.format(TIME)} – ${shift.end.format(TIME)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(999.dp), color = if (shift.end == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    AnimatedContent(
                        targetState = if (shift.end == null) "In progress" else formatDuration(shift.durationMinutes()),
                        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.92f)) },
                        label = "shift status"
                    ) { label ->
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Icon(Icons.Default.Edit, contentDescription = "Edit shift", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("No shifts yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Your recorded shifts for this month will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftEditor(
    initial: ShiftStore.Shift?,
    hasAnotherActiveShift: Boolean,
    onDismiss: () -> Unit,
    validate: (LocalDateTime, LocalDateTime?) -> String?,
    onSave: (LocalDateTime, LocalDateTime?) -> Unit,
    onDelete: (() -> Unit)?
) {
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    var date by remember { mutableStateOf((initial?.start ?: now).toLocalDate()) }
    var startTime by remember { mutableStateOf((initial?.start ?: now).toLocalTime().withSecond(0).withNano(0)) }
    var endTime by remember { mutableStateOf((initial?.end ?: now.plusHours(8)).toLocalTime().withSecond(0).withNano(0)) }
    var inProgress by remember { mutableStateOf(initial?.end == null && initial != null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(date) {
        // A manually-created open shift only makes sense for today. If the user
        // moves the editor to a past or future day, automatically restore a
        // completed-shift workflow.
        if (initial == null && date != today) inProgress = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (initial == null) "Add shift" else "Edit shift", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Choose the date and times below", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.Delete, "Delete shift", tint = MaterialTheme.colorScheme.error) }
                }
            }

            PickerField(
                icon = Icons.Default.CalendarMonth,
                label = "DATE",
                value = date.format(FRIENDLY_DATE),
                helper = "Tap to open calendar",
                onClick = { showDatePicker = true }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(
                    icon = Icons.Default.Schedule,
                    label = "START",
                    value = startTime.format(TIME),
                    helper = "Tap for clock",
                    modifier = Modifier.weight(1f),
                    onClick = { showStartPicker = true }
                )
                PickerField(
                    icon = Icons.Default.Schedule,
                    label = "END",
                    value = if (inProgress) "In progress" else endTime.format(TIME),
                    helper = if (inProgress) "No end time yet" else "Tap for clock",
                    modifier = Modifier.weight(1f),
                    enabled = !inProgress,
                    onClick = { if (!inProgress) showEndPicker = true }
                )
            }

            AnimatedVisibility(
                visible = initial == null && date == today,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (inProgress) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.animateContentSize()
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = inProgress,
                            enabled = !hasAnotherActiveShift,
                            onCheckedChange = { checked ->
                                inProgress = checked
                                error = null
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("This shift is currently active", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasAnotherActiveShift)
                                    "Another shift is already in progress. Clock it out before opening a second one."
                                else
                                    "Use this if you forgot to clock in earlier and you are still working now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = initial?.end == null && initial != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep shift in progress", fontWeight = FontWeight.SemiBold)
                            Text("Leave the end time open until you clock out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = inProgress, onCheckedChange = { inProgress = it })
                    }
                }
            }

            val roundedStart = ShiftStore.roundToHalfHour(LocalDateTime.of(date, startTime))
            val roundedEnd = if (inProgress) null else run {
                var e = ShiftStore.roundToHalfHour(LocalDateTime.of(date, endTime))
                if (e.isBefore(roundedStart)) e = e.plusDays(1)
                e
            }

            val previewText = if (roundedEnd == null) {
                "${roundedStart.format(TIME)} · shift remains in progress"
            } else {
                "${roundedStart.format(TIME)} – ${roundedEnd.format(TIME)}  •  ${formatDuration(java.time.Duration.between(roundedStart, roundedEnd).toMinutes())}"
            }

            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f), modifier = Modifier.animateContentSize()) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Rounded result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(4.dp))
                    AnimatedContent(
                        targetState = previewText,
                        transitionSpec = { (fadeIn() + slideInHorizontally { it / 8 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 8 }) },
                        label = "rounded preview"
                    ) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(
                onClick = {
                    try {
                        if (inProgress && initial == null && date != today) {
                            error = "Only a shift for today can be saved as currently active."
                            return@Button
                        }
                        if (inProgress && hasAnotherActiveShift) {
                            error = "A shift is already in progress. Clock it out before starting another one."
                            return@Button
                        }
                        if (roundedEnd != null && !roundedEnd.isAfter(roundedStart)) throw IllegalArgumentException()
                        val validationMessage = validate(roundedStart, roundedEnd)
                        if (validationMessage != null) {
                            error = validationMessage
                            return@Button
                        }
                        onSave(roundedStart, roundedEnd)
                    } catch (_: Exception) {
                        error = "Please choose a valid start and end time."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("Save shift", fontWeight = FontWeight.Bold) }
        }
    }

    if (showDatePicker) {
        CalendarPickerDialog(
            initial = date,
            onDismiss = { showDatePicker = false },
            onConfirm = { date = it; showDatePicker = false }
        )
    }
    if (showStartPicker) {
        ClockPickerDialog(
            title = "Start time",
            initial = startTime,
            onDismiss = { showStartPicker = false },
            onConfirm = { startTime = it; showStartPicker = false }
        )
    }
    if (showEndPicker) {
        ClockPickerDialog(
            title = "End time",
            initial = endTime,
            onDismiss = { showEndPicker = false },
            onConfirm = { endTime = it; showEndPicker = false }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete this shift?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete?.invoke() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PickerField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    helper: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.48f else 0.25f),
        tonalElevation = 1.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(helper, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPickerDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val initialMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis ?: initialMillis
                onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockPickerDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatDuration(minutes: Long): String = if (minutes <= 0) "0h" else if (minutes % 60 == 0L) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"

private val TIME: DateTimeFormatter get() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val DATE: DateTimeFormatter get() = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
private val DAY: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val MONTH: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val SHORT_MONTH: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
private val FRIENDLY_DATE: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.getDefault())
