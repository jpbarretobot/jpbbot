package fr.jpb.meteowidget

import android.content.Context

/** Position (si suivie) + météo + redessin du widget. Partagé par l'alarme et WorkManager. */
object Updater {

    suspend fun update(ctx: Context): Boolean {
        val repo = WeatherRepository(ctx)
        runCatching {
            if (repo.followGps) {
                Locator.locate(ctx, allowFresh = false)?.let { loc ->
                    val old = repo.place
                    val moved = old == null || UpdateWorker.distanceKm(old.lat, old.lon, loc.latitude, loc.longitude) > 3.0
                    if (moved) repo.place = Place(Locator.placeName(ctx, loc), loc.latitude, loc.longitude)
                }
            }
        }
        val ok = runCatching { repo.refresh() }.isSuccess
        WeatherWidget.refreshAll(ctx)
        return ok
    }
}
