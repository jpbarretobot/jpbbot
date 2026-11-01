package fr.jpb.meteowidget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

object Locator {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** « Toujours autoriser » : nécessaire pour suivre la position quand l'appli est fermée. */
    fun hasBackgroundPermission(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Position actuelle : dernière position connue de moins de 5 min, sinon une nouvelle mesure
     * (au plus [timeoutMs]), sinon la dernière connue.
     */
    @SuppressLint("MissingPermission")
    suspend fun locate(ctx: Context, timeoutMs: Long = 10_000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val last = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000L) return last
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last
        val providers = lm.getProviders(true)
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return last
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                runCatching {
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { if (cont.isActive) cont.resume(it) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: last
    }

    /** Nom de la ville : géocodeur du téléphone, sinon service en ligne gratuit, sinon coordonnées. */
    suspend fun placeName(ctx: Context, loc: Location): String = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.FRANCE).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull()
            ?: runCatching { onlineName(loc) }.getOrNull()
            ?: String.format(Locale.FRANCE, "%.2f, %.2f", loc.latitude, loc.longitude)
    }

    private fun onlineName(loc: Location): String? {
        val url = String.format(
            Locale.US,
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.5f&longitude=%.5f&localityLanguage=fr",
            loc.latitude, loc.longitude,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        return try {
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            listOf("city", "locality", "principalSubdivision").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }
}
