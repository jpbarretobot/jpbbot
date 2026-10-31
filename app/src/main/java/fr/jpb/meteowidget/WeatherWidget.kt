package fr.jpb.meteowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context

class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, WidgetRenderer.build(context))
        UpdateWorker.schedule(context)
        UpdateWorker.runNow(context)
        Refresher.schedule(context)
    }

    override fun onEnabled(context: Context) {
        UpdateWorker.schedule(context)
        Refresher.schedule(context, delayMs = 60_000L)
    }

    override fun onDisabled(context: Context) {
        UpdateWorker.cancel(context)
        Refresher.cancel(context)
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, WidgetRenderer.build(context))
        }
    }
}
