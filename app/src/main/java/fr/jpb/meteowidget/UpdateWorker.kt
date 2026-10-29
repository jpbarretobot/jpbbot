package fr.jpb.meteowidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Toutes les 15 minutes : met à jour la position (si suivie), la météo (au plus toutes les 25 min)
 * et redessine le widget pour suivre l'heure (lever / jour / coucher / nuit).
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = WeatherRepository(ctx)
        runCatching {
            if (repo.followGps) {
                Locator.locate(ctx, allowFresh = false)?.let { loc ->
                    val old = repo.place
                    val moved = old == null || distanceKm(old.lat, old.lon, loc.latitude, loc.longitude) > 3.0
                    if (moved) repo.place = Place(Locator.placeName(ctx, loc), loc.latitude, loc.longitude)
                }
            }
        }
        val ok = runCatching { repo.refresh() }.isSuccess
        WeatherWidget.refreshAll(ctx)
        return if (ok || runAttemptCount > 2) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "meteo-periodic"
        private const val NOW = "meteo-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, out)
            return out[0] / 1000.0
        }
    }
}
