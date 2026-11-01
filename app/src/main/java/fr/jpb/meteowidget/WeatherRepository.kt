package fr.jpb.meteowidget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")

data class Weather(
    val temp: Double,
    val code: Int,
    val min: Double,
    val max: Double,
    val sunrises: List<Long>,
    val sunsets: List<Long>,
    val fetchedAt: Long,
)

/** Météo Open-Meteo (gratuit, sans clé) + réglages, mis en cache dans les SharedPreferences. */
class WeatherRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    var place: Place?
        get() {
            if (!prefs.contains("lat")) return null
            return Place(
                prefs.getString("place", "") ?: "",
                prefs.getFloat("lat", 0f).toDouble(),
                prefs.getFloat("lon", 0f).toDouble(),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("lat"); remove("lon"); remove("place")
                } else {
                    putFloat("lat", value.lat.toFloat())
                    putFloat("lon", value.lon.toFloat())
                    putString("place", value.name)
                }
                remove("weather")
            }.apply()
        }

    /** Change seulement le nom du lieu (la météo en cache reste valable). */
    fun renamePlace(name: String) = prefs.edit().putString("place", name).apply()

    /** true : la position GPS est suivie ; false : ville fixe choisie à la main. */
    var followGps: Boolean
        get() = prefs.getBoolean("gps", true)
        set(value) = prefs.edit().putBoolean("gps", value).apply()

    /** Ambiance imposée (tests sur émulateur), format "RAIN:DAY" ; null = météo réelle. */
    var forcedAmbience: Pair<Condition, Phase>?
        get() = prefs.getString("forced", null)?.split(":")?.let { p ->
            runCatching { Condition.valueOf(p[0]) to Phase.valueOf(p[1]) }.getOrNull()
        }
        set(value) = prefs.edit().putString("forced", value?.let { "${it.first}:${it.second}" }).apply()

    val cached: Weather?
        get() = prefs.getString("weather", null)?.let { runCatching { parse(JSONObject(it), prefs.getLong("fetchedAt", 0)) }.getOrNull() }

    /** Recharge la météo si celle en cache a plus de [maxAgeMs]. */
    suspend fun refresh(force: Boolean = false, maxAgeMs: Long = 5 * 60_000L, timeoutMs: Int = 15_000): Weather? {
        val p = place ?: return null
        val c = cached
        if (!force && c != null && System.currentTimeMillis() - c.fetchedAt < maxAgeMs) return c
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${fmt(p.lat)}&longitude=${fmt(p.lon)}" +
            "&current=temperature_2m,weather_code" +
            "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&timeformat=unixtime&forecast_days=2"
        val body = httpGet(url, timeoutMs)
        val now = System.currentTimeMillis()
        val w = parse(JSONObject(body), now)
        prefs.edit().putString("weather", body).putLong("fetchedAt", now).apply()
        return w
    }

    suspend fun searchCity(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=fr&format=json&name=" +
            URLEncoder.encode(query.trim(), "UTF-8")
        val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            val detail = listOf(o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() }.joinToString(", ")
            Place(o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"), detail)
        }
    }

    private fun parse(o: JSONObject, fetchedAt: Long): Weather {
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return Weather(
            temp = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            sunrises = longs(daily.getJSONArray("sunrise")),
            sunsets = longs(daily.getJSONArray("sunset")),
            fetchedAt = fetchedAt,
        )
    }

    private fun longs(a: JSONArray) = (0 until a.length()).map { a.getLong(it) * 1000L }

    private fun fmt(d: Double) = String.format(Locale.US, "%.4f", d)

    private suspend fun httpGet(url: String, timeoutMs: Int = 15_000): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
