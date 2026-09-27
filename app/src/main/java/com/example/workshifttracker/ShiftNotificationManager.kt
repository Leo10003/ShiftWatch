package com.example.workshifttracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter

/**
 * Keeps one prominent, ongoing notification visible only while a shift is active.
 *
 * The channel ID intentionally changed in v2.0 because Android freezes notification-channel
 * importance/sound after the channel is first created. Existing v1.9 installs therefore need a
 * new channel to receive the new lock-screen + audible defaults.
 */
object ShiftNotificationManager {
    private const val CHANNEL_ID = "active_shift_v2"
    private const val LEGACY_CHANNEL_ID = "active_shift"
    private const val CHANNEL_NAME = "Active shift"
    private const val NOTIFICATION_ID = 9107
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        createChannel(manager)

        val active = ShiftStore(appContext).active()
        if (active == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clockOutIntent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CLOCK_OUT
        }
        val clockOutPendingIntent = PendingIntent.getBroadcast(
            appContext,
            1,
            clockOutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shiftwatch)
            .setContentTitle("Shift in progress")
            .setContentText("Started ${active.start.format(TIME)} • tap Clock out when you're done")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setShowWhen(true)
            .setWhen(active.start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Clock out",
                    clockOutPendingIntent
                ).build()
            )
            .build()

        // Keep it present for the lifetime of the active shift. On Android 14+ users can dismiss
        // ongoing notifications while unlocked, but Android keeps them non-dismissible while locked.
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    private fun createChannel(manager: NotificationManager) {
        // v1.9 created a LOW/silent channel. Channel importance cannot be raised after creation,
        // so remove the legacy channel and use a new ID for the upgraded behavior.
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown while a ShiftWatch shift is active"
            enableVibration(true)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
