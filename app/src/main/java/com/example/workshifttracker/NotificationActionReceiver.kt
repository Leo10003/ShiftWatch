package com.example.workshifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.time.format.DateTimeFormatter

/** Handles the persistent notification's CLOCK OUT action. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLOCK_OUT) return

        val store = ShiftStore(context.applicationContext)
        val closed = store.endActiveNow() ?: run {
            ShiftNotificationManager.cancel(context)
            return
        }

        Toast.makeText(
            context,
            "Clocked out at ${closed.end?.format(TIME)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val ACTION_CLOCK_OUT = "com.example.workshifttracker.action.NOTIFICATION_CLOCK_OUT"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}
