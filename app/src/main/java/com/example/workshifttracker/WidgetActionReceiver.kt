package com.example.workshifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.time.format.DateTimeFormatter

/** Handles explicit one-tap widget actions and writes to the same ShiftStore as the app. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_SHIFT) return

        val store = ShiftStore(context.applicationContext)
        val wasActive = store.active() != null
        val changed = store.toggleNow()

        // ShiftStore writes through the shared pipeline and refreshes all widgets.
        val message = if (wasActive) {
            "Clocked out at ${changed.end?.format(TIME)}"
        } else {
            "Clocked in at ${changed.start.format(TIME)}"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_TOGGLE_SHIFT = "com.example.workshifttracker.action.TOGGLE_SHIFT"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}
