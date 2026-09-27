package com.example.workshifttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the active-shift notification after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ShiftNotificationManager.sync(context.applicationContext)
        }
    }
}
