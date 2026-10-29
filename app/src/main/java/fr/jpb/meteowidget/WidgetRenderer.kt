package fr.jpb.meteowidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.math.roundToInt

object WidgetRenderer {

    private class Layer(val flipper: Int, val a: Int, val b: Int, val res: Int, val tinted: Boolean, val alpha: Int = 255)

    private val CLOUDS_LIGHT = Layer(R.id.fx_cloud_far, R.id.fx_cloud_far_a, R.id.fx_cloud_far_b, R.drawable.fx_clouds_far, true, 200)
    private val CLOUDS = Layer(R.id.fx_cloud_near, R.id.fx_cloud_near_a, R.id.fx_cloud_near_b, R.drawable.fx_clouds_near, true, 235)
    private val FOG = Layer(R.id.fx_fog, R.id.fx_fog_a, R.id.fx_fog_b, R.drawable.fx_fog, true)
    private val RAIN_FAR = Layer(R.id.fx_rain_far, R.id.fx_rain_far_a, R.id.fx_rain_far_b, R.drawable.fx_rain_far, false)
    private val RAIN_NEAR = Layer(R.id.fx_rain_near, R.id.fx_rain_near_a, R.id.fx_rain_near_b, R.drawable.fx_rain_near, false)
    private val SNOW_FAR = Layer(R.id.fx_snow_far, R.id.fx_snow_far_a, R.id.fx_snow_far_b, R.drawable.fx_snow_far, false)
    private val SNOW_NEAR = Layer(R.id.fx_snow_near, R.id.fx_snow_near_a, R.id.fx_snow_near_b, R.drawable.fx_snow_near, false)
    private val ALL = listOf(CLOUDS_LIGHT, CLOUDS, FOG, RAIN_FAR, RAIN_NEAR, SNOW_FAR, SNOW_NEAR)

    /**
     * Construit le widget. [preview] force une ambiance (aperçu dans l'appli), sinon elle découle
     * de la météo en cache et de l'heure.
     */
    fun build(ctx: Context, preview: Pair<Condition, Phase>? = null): RemoteViews {
        val repo = WeatherRepository(ctx)
        val weather = repo.cached
        val now = System.currentTimeMillis()
        val condition = preview?.first ?: weather?.let { Ambiences.condition(it.code) } ?: Condition.CLEAR
        val phase = preview?.second ?: weather?.let { Ambiences.phase(now, it.sunrises, it.sunsets) } ?: fallbackPhase()
        val ambience = Ambiences.of(condition, phase)

        val rv = RemoteViews(ctx.packageName, R.layout.widget_weather)

        val photo = Photos.load(ctx, ambience.backdrop)
        if (photo != null) rv.setImageViewBitmap(R.id.backdrop, photo)
        else rv.setImageViewResource(R.id.backdrop, ambience.backdrop.res)

        val active = buildList {
            if (Fx.CLOUDS_LIGHT in ambience.fx) add(CLOUDS_LIGHT)
            if (Fx.CLOUDS in ambience.fx) add(CLOUDS)
            if (Fx.FOG in ambience.fx) add(FOG)
            if (Fx.RAIN in ambience.fx) { add(RAIN_FAR); add(RAIN_NEAR) }
            if (Fx.SNOW in ambience.fx) { add(SNOW_FAR); add(SNOW_NEAR) }
        }
        for (layer in ALL) {
            if (layer !in active) {
                rv.setViewVisibility(layer.flipper, View.GONE)
                continue
            }
            rv.setViewVisibility(layer.flipper, View.VISIBLE)
            for (id in intArrayOf(layer.a, layer.b)) {
                rv.setImageViewResource(id, layer.res)
                rv.setInt(id, "setImageAlpha", layer.alpha)
                if (layer.tinted && ambience.tint != null) rv.setInt(id, "setColorFilter", ambience.tint)
            }
            // Lance la première transition tout de suite plutôt qu'après un intervalle complet.
            rv.showNext(layer.flipper)
        }

        if (Fx.LIGHTNING in ambience.fx) {
            rv.setViewVisibility(R.id.fx_flash, View.VISIBLE)
            rv.setImageViewResource(R.id.fx_flash_5, R.drawable.fx_lightning)
            rv.setImageViewResource(R.id.fx_flash_7, R.drawable.fx_lightning)
        } else {
            rv.setViewVisibility(R.id.fx_flash, View.GONE)
        }

        if (weather != null) {
            rv.setTextViewText(R.id.temp, "${weather.temp.roundToInt()}°")
            rv.setTextViewText(R.id.condition, Ambiences.label(weather.code))
            rv.setTextViewText(R.id.minmax, "↑ ${weather.max.roundToInt()}°   ↓ ${weather.min.roundToInt()}°")
        } else {
            rv.setTextViewText(R.id.temp, "--°")
            rv.setTextViewText(R.id.condition, "")
            rv.setTextViewText(R.id.minmax, "")
        }
        val place = repo.place
        rv.setTextViewText(R.id.place, if (place != null) "⌖  ${place.name}" else ctx.getString(R.string.place_unknown))
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        rv.setTextViewText(R.id.phrase, Ambiences.phrase(condition, phase, day))

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        rv.setOnClickPendingIntent(android.R.id.background, open)
        return rv
    }

    /** Sans données météo : approximation 7 h – 19 h. */
    private fun fallbackPhase(): Phase = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        6 -> Phase.SUNRISE
        in 7..17 -> Phase.DAY
        18 -> Phase.SUNSET
        else -> Phase.NIGHT
    }
}
