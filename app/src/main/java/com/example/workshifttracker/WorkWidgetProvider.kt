package com.example.workshifttracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class WorkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        update(context, appWidgetManager, appWidgetId)
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE = DateTimeFormatter.ofPattern("dd MMM")

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WorkWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val store = ShiftStore(context.applicationContext)
            val active = store.active()
            val isActive = active != null
            val todayMinutes = store.totalMinutesFor(LocalDate.now())
            val monthMinutes = store.totalMinutes(YearMonth.now())

            val views = RemoteViews(context.packageName, R.layout.work_widget)

            views.setTextViewText(R.id.widget_status, if (isActive) "Shift in progress" else "Ready to work")
            views.setTextViewText(
                R.id.widget_detail,
                if (active != null) "Started ${active.start.format(TIME)} · ${active.start.format(DATE)}" else "Tap below to clock in"
            )
            views.setTextViewText(R.id.widget_action_button, if (isActive) "Clock out" else "Clock in")
            views.setTextViewText(R.id.widget_today_value, formatDuration(todayMinutes))
            views.setTextViewText(R.id.widget_month_value, formatDuration(monthMinutes))
            views.setViewVisibility(R.id.widget_active_dot, if (isActive) View.VISIBLE else View.INVISIBLE)

            val toggleIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActionReceiver.ACTION_TOGGLE_SHIFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            val togglePending = PendingIntent.getBroadcast(
                context,
                10_000 + id,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_action_button, togglePending)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                20_000 + id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)
            views.setOnClickPendingIntent(R.id.widget_header, openPending)
            views.setOnClickPendingIntent(R.id.widget_status_area, openPending)

            manager.updateAppWidget(id, views)
        }

        private fun formatDuration(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
    }
}
