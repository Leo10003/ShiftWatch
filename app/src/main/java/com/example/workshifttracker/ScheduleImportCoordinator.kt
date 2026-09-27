package com.example.workshifttracker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v16 lifecycle-safe entry point for rota analysis.
 *
 * Bitmap decode and importer startup happen away from the main thread. The continuation is
 * cancellation-aware, so stale imports cannot publish results after a newer photo has been picked.
 * The legacy importer remains the recognition implementation while its stages are being split into
 * dedicated engines in subsequent releases.
 */
object ScheduleImportCoordinator {
    data class Result(
        val rawText: String,
        val drafts: List<ScheduleImporter.Draft>,
        val assist: ScheduleImporter.AssistData
    )

    suspend fun analyze(
        context: Context,
        uri: Uri,
        employeeName: String,
        typicalShiftHours: Int,
        onStage: (String) -> Unit = {},
        onProvisional: (String, ScheduleImporter.AssistData) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        suspendCancellableCoroutine { continuation ->
            ScheduleImporter.recognize(
                context = context,
                uri = uri,
                employeeName = employeeName,
                typicalShiftHours = typicalShiftHours,
                onSuccess = { raw, drafts, assist ->
                    if (continuation.isActive) continuation.resume(Result(raw, drafts, assist))
                },
                onError = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
                isCancelled = { !continuation.isActive },
                onStage = { stage -> if (continuation.isActive) onStage(stage) },
                onProvisional = { raw, assist ->
                    if (continuation.isActive) onProvisional(raw, assist)
                }
            )
        }
    }
}
