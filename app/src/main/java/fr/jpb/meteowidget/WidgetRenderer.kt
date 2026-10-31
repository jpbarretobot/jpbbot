package fr.jpb.meteowidget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.math.roundToInt

object WidgetRenderer {

    /** Calque animé : un ViewFlipper avec deux images identiques qui défilent en boucle. */
    private class Layer(val flipper: Int, val a: Int, val b: Int, val texture: String, val tinted: Boolean, val alpha: Int = 255)

    private val CLOUD_PASS = Layer(R.id.fx_cloud_pass, R.id.fx_cloud_pass_a, R.id.fx_cloud_pass_b, "fx_cloud_single", true)
    private val CLOUDS_LIGHT = Layer(R.id.fx_cloud_far, R.id.fx_cloud_far_a, R.id.fx_cloud_far_b, "fx_clouds_far", true, 200)
    private val CLOUDS = Layer(R.id.fx_cloud_near, R.id.fx_cloud_near_a, R.id.fx_cloud_near_b, "fx_clouds_near", true, 235)
    private val FOG = Layer(R.id.fx_fog, R.id.fx_fog_a, R.id.fx_fog_b, "fx_fog", true)
    private val RAIN_FAR = Layer(R.id.fx_rain_far, R.id.fx_rain_far_a, R.id.fx_rain_far_b, "fx_rain_far", false)
    private val RAIN_NEAR = Layer(R.id.fx_rain_near, R.id.fx_rain_near_a, R.id.fx_rain_near_b, "fx_rain_near", false)
    private val SNOW_FAR = Layer(R.id.fx_snow_far, R.id.fx_snow_far_a, R.id.fx_snow_far_b, "fx_snow_far", false)
    private val SNOW_NEAR = Layer(R.id.fx_snow_near, R.id.fx_snow_near_a, R.id.fx_snow_near_b, "fx_snow_near", false)
    private val ALL = listOf(CLOUD_PASS, CLOUDS_LIGHT, CLOUDS, FOG, RAIN_FAR, RAIN_NEAR, SNOW_FAR, SNOW_NEAR)

    @SuppressLint("DiscouragedApi")
    private fun texture(ctx: Context, name: String): Int =
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 } ?: R.drawable.fx_clouds_far

    /**
     * Construit le widget. [preview] force une ambiance (aperçu dans l'appli), sinon elle découle
     * de la météo en cache et de l'heure.
     */
    fun build(ctx: Context, preview: Pair<Condition, Phase>? = null): RemoteViews {
        val repo = WeatherRepository(ctx)
        val weather = repo.cached
        val now = System.currentTimeMillis()
        val forced = preview ?: repo.forcedAmbience
        val condition = forced?.first ?: weather?.let { Ambiences.condition(it.code) } ?: Condition.CLEAR
        val phase = forced?.second ?: weather?.let { Ambiences.phase(now, it.sunrises, it.sunsets) } ?: fallbackPhase()
        val ambience = Ambiences.of(condition, phase)

        val rv = RemoteViews(ctx.packageName, R.layout.widget_weather)

        val photo = Photos.load(ctx, ambience.backdrop)
        if (photo != null) rv.setImageViewBitmap(R.id.backdrop, photo)
        else rv.setImageViewResource(R.id.backdrop, ambience.backdrop.res)

        val active = buildList {
            if (Fx.CLOUD_PASS in ambience.fx) add(CLOUD_PASS)
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
            val res = texture(ctx, layer.texture + if (layer.tinted) ambience.tone else "")
            for (id in intArrayOf(layer.a, layer.b)) {
                rv.setImageViewResource(id, res)
                rv.setInt(id, "setImageAlpha", layer.alpha)
            }
            // Lance la première transition tout de suite plutôt qu'après un intervalle complet.
            rv.showNext(layer.flipper)
        }

        if (Fx.METEORS in ambience.fx) {
            rv.setViewVisibility(R.id.fx_meteor, View.VISIBLE)
            rv.setImageViewResource(R.id.fx_meteor_3, R.drawable.fx_meteor_a)
            rv.setImageViewResource(R.id.fx_meteor_10, R.drawable.fx_meteor_b)
        } else {
            rv.setViewVisibility(R.id.fx_meteor, View.GONE)
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
        } else {
            rv.setTextViewText(R.id.temp, "--°")
            rv.setTextViewText(R.id.condition, "")
        }
        val place = repo.place
        rv.setTextViewText(R.id.place, if (place != null) "⌖ ${place.name}" else ctx.getString(R.string.place_unknown))

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
