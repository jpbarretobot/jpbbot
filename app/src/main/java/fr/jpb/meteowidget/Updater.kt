package fr.jpb.meteowidget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Position (si suivie) + météo + redessin du widget. Partagé par l'alarme, WorkManager et l'appli.
 * [budgetMs] : temps disponible (une alarme en veille n'a droit au réseau que ~10 s).
 */
object Updater {

    suspend fun update(ctx: Context, source: String, budgetMs: Long = 9_000): Boolean {
        val repo = WeatherRepository(ctx)
        val start = SystemClock.elapsedRealtime()
        fun left() = budgetMs - (SystemClock.elapsedRealtime() - start)
        var moved = false
        var locNote = ""

        if (repo.followGps && Locator.hasPermission(ctx)) {
            val loc = withTimeoutOrNull((budgetMs * 0.35).toLong()) { Locator.locate(ctx, (budgetMs * 0.35).toLong()) }
            val old = repo.place
            when {
                loc == null -> locNote = if (Locator.hasBackgroundPermission(ctx)) "position indisponible" else "position refusée en arrière-plan"
                old == null || UpdateWorker.distanceKm(old.lat, old.lon, loc.latitude, loc.longitude) > 1.0 -> {
                    val name = withTimeoutOrNull((left() * 0.4).toLong().coerceAtLeast(500)) { Locator.placeName(ctx, loc) }
                        ?: String.format(Locale.FRANCE, "%.2f, %.2f", loc.latitude, loc.longitude)
                    repo.place = Place(name, loc.latitude, loc.longitude)
                    moved = true
                }
            }
        }
        // Nom provisoire (coordonnées) : on retente de trouver la ville
        repo.place?.let { p ->
            if (!moved && p.name.firstOrNull()?.let { it.isDigit() || it == '-' } == true && left() > 4_000) {
                val loc = android.location.Location("").apply { latitude = p.lat; longitude = p.lon }
                withTimeoutOrNull(2_500) { Locator.placeName(ctx, loc) }
                    ?.takeIf { it != p.name }?.let { repo.renamePlace(it) }
            }
        }

        val timeout = left().coerceIn(2_500, 15_000).toInt()
        val result = runCatching { repo.refresh(force = moved, maxAgeMs = 4 * 60_000L, timeoutMs = timeout) }
        WeatherWidget.refreshAll(ctx)

        val w = result.getOrNull()
        val msg = when {
            repo.place == null -> "aucun lieu"
            w != null -> "${w.temp.roundToInt()}° à ${repo.place?.name}" + if (moved) " (nouveau lieu)" else ""
            else -> "échec météo : ${result.exceptionOrNull()?.javaClass?.simpleName ?: "?"}"
        } + if (locNote.isNotEmpty()) " · $locNote" else ""
        UpdateLog.add(ctx, source, msg)
        return result.isSuccess && w != null
    }
}

/** Historique des dernières mises à jour, affiché dans l'appli (et dans logcat). */
object UpdateLog {
    private const val MAX = 25

    fun add(ctx: Context, source: String, msg: String) {
        Log.i("MeteoPaysage", "$source : $msg")
        val prefs = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date())
        val lines = (listOf("$time  $source : $msg") + entries(ctx)).take(MAX)
        prefs.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun entries(ctx: Context): List<String> =
        ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE).getString("log", "")!!
            .split("\n").filter { it.isNotBlank() }
}
