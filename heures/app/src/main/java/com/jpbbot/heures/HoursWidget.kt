package com.jpbbot.heures

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.YearMonth

class HoursWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val store = HoursStore(context)
        when (intent.action) {
            ACTION_GO -> store.start()
            ACTION_FIN -> store.finish()
            else -> return
        }
        refreshAll(context)
    }

    companion object {
        const val ACTION_GO = "com.jpbbot.heures.action.GO"
        const val ACTION_FIN = "com.jpbbot.heures.action.FIN"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HoursWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val store = HoursStore(context)
            val running = store.runningStart()
            val monthMinutes = HoursMath.totalMinutes(
                HoursMath.sessionsOf(YearMonth.now(), store.sessions()),
            )

            val views = RemoteViews(context.packageName, R.layout.widget_hours).apply {
                setTextViewText(
                    R.id.widgetStatus,
                    if (running == null) {
                        context.getString(R.string.status_idle)
                    } else {
                        context.getString(R.string.status_running, formatTime(running))
                    },
                )
                setTextViewText(
                    R.id.widgetTotal,
                    context.getString(R.string.widget_month_total, formatDuration(monthMinutes)),
                )
                setOnClickPendingIntent(R.id.widgetGo, actionIntent(context, ACTION_GO))
                setOnClickPendingIntent(R.id.widgetFin, actionIntent(context, ACTION_FIN))
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun actionIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, HoursWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
