package fr.jpb.meteowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mise à jour toutes les 10 minutes, même téléphone en veille : alarme système « exacte et
 * autorisée en veille » (WorkManager ne descend pas sous 15 min et Samsung le retarde souvent).
 */
object Refresher {

    const val INTERVAL_MS = 10 * 60_000L

    fun schedule(ctx: Context, delayMs: Long = INTERVAL_MS) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx)
        if (!hasWidgets(ctx)) {
            am.cancel(pi)
            return
        }
        val at = System.currentTimeMillis() + delayMs
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx))
    }

    private fun hasWidgets(ctx: Context) =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WeatherWidget::class.java)).isNotEmpty()

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, RefreshReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Reçoit l'alarme (et le redémarrage du téléphone / la mise à jour de l'appli). */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (withTimeoutOrNull(8_000) { Updater.update(ctx) } == null) {
                    // réseau trop lent : on réessaie sans limite de temps via WorkManager
                    UpdateWorker.runNow(ctx)
                    WeatherWidget.refreshAll(ctx)
                }
            } finally {
                Refresher.schedule(ctx)
                pending.finish()
            }
        }
    }
}
