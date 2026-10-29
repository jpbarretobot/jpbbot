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
enum class Fx { CLOUDS_LIGHT, CLOUDS, RAIN, SNOW, FOG, LIGHTNING }

data class Ambience(
    val backdrop: Backdrop,
    val fx: Set<Fx>,
    /** Teinte des nuages / de la brume (null = blanc). */
    val tint: Int? = null,
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

    private val WARM = Color.rgb(255, 196, 150)
    private val DUSK = Color.rgb(222, 170, 190)
    private val NIGHT = Color.rgb(78, 88, 108)
    private val GREY = Color.rgb(170, 178, 186)
    private val DARK = Color.rgb(96, 100, 112)

    fun of(condition: Condition, phase: Phase): Ambience {
        val night = phase == Phase.NIGHT
        val golden = phase == Phase.SUNRISE || phase == Phase.SUNSET
        val goldenBackdrop = if (phase == Phase.SUNRISE) Backdrop.SUNRISE else Backdrop.SUNSET
        val goldenTint = if (phase == Phase.SUNRISE) DUSK else WARM
        return when (condition) {
            Condition.CLEAR -> when {
                golden -> Ambience(goldenBackdrop, emptySet())
                night -> Ambience(Backdrop.CLEAR_NIGHT, emptySet())
                else -> Ambience(Backdrop.CLEAR_DAY, setOf(Fx.CLOUDS_LIGHT))
            }
            Condition.PARTLY -> when {
                golden -> Ambience(goldenBackdrop, setOf(Fx.CLOUDS_LIGHT), goldenTint)
                night -> Ambience(Backdrop.CLEAR_NIGHT, setOf(Fx.CLOUDS_LIGHT), NIGHT)
                else -> Ambience(Backdrop.CLEAR_DAY, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS))
            }
            Condition.CLOUDY -> when {
                golden -> Ambience(goldenBackdrop, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), goldenTint)
                night -> Ambience(Backdrop.CLOUDY_NIGHT, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), NIGHT)
                else -> Ambience(Backdrop.CLOUDY_DAY, setOf(Fx.CLOUDS_LIGHT, Fx.CLOUDS), GREY)
            }
            Condition.FOG -> Ambience(if (night) Backdrop.FOG_NIGHT else Backdrop.FOG_DAY, setOf(Fx.FOG), if (night) NIGHT else null)
            Condition.RAIN -> Ambience(if (night) Backdrop.RAIN_NIGHT else Backdrop.RAIN_DAY, setOf(Fx.RAIN, Fx.CLOUDS), if (night) NIGHT else DARK)
            Condition.SNOW -> Ambience(if (night) Backdrop.SNOW_NIGHT else Backdrop.SNOW_DAY, setOf(Fx.SNOW, Fx.CLOUDS_LIGHT), if (night) NIGHT else null)
            Condition.STORM -> Ambience(Backdrop.STORM, setOf(Fx.RAIN, Fx.CLOUDS, Fx.LIGHTNING), DARK)
        }
    }

    private val PHRASES = mapOf(
        "sunrise" to listOf("Chaque matin est une page blanche.", "Le jour se lève, avance avec lui.", "La lumière revient toujours."),
        "sunset" to listOf("Toujours plus loin, même quand le jour s'éteint.", "Le soleil se couche, les rêves se lèvent.", "Savoure la lumière dorée."),
        "clear_day" to listOf("Le ciel est à toi aujourd'hui.", "Un temps à aller voir plus loin.", "Respire, tout est clair."),
        "clear_night" to listOf("Sous les étoiles, tout semble possible.", "La nuit garde les plus beaux secrets.", "Lève les yeux, le ciel est immense."),
        "cloudy" to listOf("Les nuages passent, toi tu restes.", "Derrière les nuages, le soleil attend.", "Un ciel doux pour avancer tranquille."),
        "fog" to listOf("Toujours plus loin, même dans le brouillard.", "Un pas après l'autre, le chemin apparaît.", "La brume ne cache que pour mieux révéler."),
        "rain" to listOf("La pluie lave tout, même les doutes.", "Il pleut : l'heure des belles histoires.", "Danse sous la pluie, elle finira par passer."),
        "snow" to listOf("Le monde se fait silence et coton.", "Chaque flocon est unique, toi aussi.", "La neige efface tout, recommence en blanc."),
        "storm" to listOf("Après l'orage vient toujours le calme.", "Les tempêtes font les marins solides.", "Reste à l'abri, ça va passer."),
    )

    fun phrase(condition: Condition, phase: Phase, dayOfYear: Int): String {
        val key = when (condition) {
            Condition.FOG -> "fog"
            Condition.RAIN -> "rain"
            Condition.SNOW -> "snow"
            Condition.STORM -> "storm"
            else -> when (phase) {
                Phase.SUNRISE -> "sunrise"
                Phase.SUNSET -> "sunset"
                Phase.NIGHT -> "clear_night"
                Phase.DAY -> if (condition == Condition.CLOUDY) "cloudy" else "clear_day"
            }
        }
        val list = PHRASES.getValue(key)
        return list[dayOfYear % list.size]
    }
}
