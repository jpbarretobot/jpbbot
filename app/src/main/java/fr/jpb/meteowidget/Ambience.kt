package fr.jpb.meteowidget

import android.graphics.Color

/** Fonds disponibles (illustration fournie ou photo choisie par l'utilisateur). */
enum class Backdrop(val res: Int, val label: String) {
    SUNRISE(R.drawable.scene_sunrise, "Lever du soleil"),
    SUNSET(R.drawable.scene_sunset, "Coucher du soleil"),
    CLEAR_DAY(R.drawable.scene_clear_day, "Grand soleil"),
    CLEAR_NIGHT(R.drawable.scene_clear_night, "Nuit étoilée"),
    CLOUDY_DAY(R.drawable.scene_cloudy_day, "Nuageux (jour)"),
    CLOUDY_NIGHT(R.drawable.scene_cloudy_night, "Nuageux (nuit)"),
    RAIN_DAY(R.drawable.scene_rain_day, "Pluie (jour)"),
    RAIN_NIGHT(R.drawable.scene_rain_night, "Pluie (nuit)"),
    STORM(R.drawable.scene_storm, "Orage"),
    SNOW_DAY(R.drawable.scene_snow_day, "Neige (jour)"),
    SNOW_NIGHT(R.drawable.scene_snow_night, "Neige (nuit)"),
    FOG_DAY(R.drawable.scene_fog_day, "Brouillard (jour)"),
    FOG_NIGHT(R.drawable.scene_fog_night, "Brouillard (nuit)"),
}

enum class Condition { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

enum class Phase { SUNRISE, DAY, SUNSET, NIGHT }

/** Calques animés superposés au fond. */
enum class Fx { CLOUD_PASS, CLOUDS_LIGHT, CLOUDS, RAIN, SNOW, FOG, LIGHTNING, METEORS }

data class Ambience(
    val backdrop: Backdrop,
    val fx: Set<Fx>,
    /** Teinte des nuages / de la brume : suffixe des textures ("" = blanc, "_warm", "_night", "_grey", "_dark"). */
    val tone: String = "",
)

object Ambiences {

    fun condition(code: Int): Condition = when (code) {
        0, 1 -> Condition.CLEAR
        2 -> Condition.PARTLY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        in 51..67, in 80..82 -> Condition.RAIN
        in 71..77, 85, 86 -> Condition.SNOW
        in 95..99 -> Condition.STORM
        else -> Condition.CLOUDY
    }

    fun label(code: Int): String = when (code) {
        0 -> "Ciel dégagé"
        1 -> "Plutôt ensoleillé"
        2 -> "Partiellement nuageux"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grésil"
        80, 81 -> "Averses"
        82 -> "Violentes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage et grêle"
        else -> ""
    }

    private const val MIN = 60_000L

    /** Moment de la journée : la « golden hour » encadre le lever et le coucher du soleil. */
    fun phase(now: Long, sunrises: List<Long>, sunsets: List<Long>): Phase {
        for (r in sunrises) if (now in (r - 40 * MIN)..(r + 50 * MIN)) return Phase.SUNRISE
        for (s in sunsets) if (now in (s - 60 * MIN)..(s + 30 * MIN)) return Phase.SUNSET
        for (i in sunrises.indices) {
            if (i < sunsets.size && now in sunrises[i]..sunsets[i]) return Phase.DAY
        }
        return Phase.NIGHT
    }

    /** Chaque ambiance a toujours au moins une animation. */
    fun of(condition: Condition, phase: Phase): Ambience {
        val night = phase == Phase.NIGHT
        val golden = phase == Phase.SUNRISE || phase == Phase.SUNSET
        val goldenBackdrop = if (phase == Phase.SUNRISE) Backdrop.SUNRISE else Backdrop.SUNSET
        return when (condition) {
            Condition.CLEAR -> when {
                golden -> Ambience(goldenBackdrop, setOf(Fx.CLOUD_PASS), "_warm")
                night -> Ambience(Backdrop.CLEAR_NIGHT, setOf(Fx.METEORS))
                else -> Ambience(Backdrop.CLEAR_DAY, setOf(Fx.CLOUD_PASS))
            }
            Condition.PARTLY -> when {
                golden -> Ambience(goldenBackdrop, setOf(Fx.CLOUD_PASS, Fx.CLOUDS_LIGHT), "_warm")
                night -> Ambience(Backdrop.CLEAR_NIGHT, setOf(Fx.METEORS, Fx.CLOUD_PASS), "_night")
                else -> Ambience(Backdrop.CLEAR_DAY, setOf(Fx.CLOUD_PASS, Fx.CLOUDS_LIGHT))
            }
            Condition.CLOUDY -> when {
                golden -> Ambience(goldenBackdrop, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), "_warm")
                night -> Ambience(Backdrop.CLOUDY_NIGHT, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), "_night")
                else -> Ambience(Backdrop.CLOUDY_DAY, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), "_grey")
            }
            Condition.FOG -> Ambience(if (night) Backdrop.FOG_NIGHT else Backdrop.FOG_DAY, setOf(Fx.FOG), if (night) "_night" else "")
            Condition.RAIN -> Ambience(if (night) Backdrop.RAIN_NIGHT else Backdrop.RAIN_DAY, setOf(Fx.RAIN, Fx.CLOUDS), if (night) "_night" else "_dark")
            Condition.SNOW -> Ambience(if (night) Backdrop.SNOW_NIGHT else Backdrop.SNOW_DAY, setOf(Fx.SNOW, Fx.CLOUDS_LIGHT), if (night) "_night" else "")
            Condition.STORM -> Ambience(Backdrop.STORM, setOf(Fx.RAIN, Fx.CLOUDS, Fx.LIGHTNING), "_dark")
        }
    }
}
