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
import java.util.Locale
import kotlin.coroutines.resume

object Locator {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Position actuelle : dernière position connue récente, sinon une nouvelle mesure (si autorisé). */
    @SuppressLint("MissingPermission")
    suspend fun locate(ctx: Context, allowFresh: Boolean): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val last = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time < 20 * 60_000L) return last
        if (!allowFresh || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val fresh = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                runCatching {
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        }
        return fresh ?: last
    }

    /** Nom de la ville correspondant à une position (géocodage inverse du téléphone). */
    suspend fun placeName(ctx: Context, loc: Location): String = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.FRANCE).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull() ?: String.format(Locale.FRANCE, "%.2f, %.2f", loc.latitude, loc.longitude)
    }
}
