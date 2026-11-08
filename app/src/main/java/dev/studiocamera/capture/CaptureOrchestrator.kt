package dev.studiocamera.capture

import dev.studiocamera.camera.CameraEngine
import dev.studiocamera.camera.CapturedShot
import dev.studiocamera.camera.LensInfo
import dev.studiocamera.camera.ManualSettings
import dev.studiocamera.camera.ShotSpec
import kotlinx.coroutines.delay

/**
 * Traduit un style en série de prises réelles (réglages par prise), puis exécute la capture.
 * Les réglages manuels ne sont demandés que si le capteur les autorise ; sinon un repli est noté.
 */
class CaptureOrchestrator(private val engine: CameraEngine) {

    data class Plan(
        val specs: List<ShotSpec>,
        val intervalMs: Long,
        val notes: List<String>,
    )

    fun plan(style: Style, lens: LensInfo): Plan {
        val p = style.capture
        val notes = ArrayList<String>()
        val specs: List<ShotSpec> = when (p.bracket) {
            Bracket.SINGLE, Bracket.PANORAMA_SWEEP, Bracket.GROUP_BURST -> List(p.frames.coerceAtLeast(1)) { ShotSpec() }
                .let { if (p.bracket == Bracket.SINGLE) listOf(ShotSpec()) else it }

            Bracket.EXPOSURE_BRACKET -> {
                val n = p.frames.coerceAtLeast(2)
                List(n) { k ->
                    val ev = -p.evSpread + 2f * p.evSpread * k / (n - 1)
                    ShotSpec(evCompensation = ev)
                }
            }

            Bracket.FOCUS_BRACKET -> {
                // Du plan le plus proche (valeur maximale en dioptries) vers l'infini (0).
                val near = lens.minFocusDiopters.takeIf { it > 0f } ?: 0f
                val n = p.frames.coerceAtLeast(2)
                List(n) { k -> ShotSpec(focusDiopters = near * (1f - k.toFloat() / (n - 1))) }
            }

            Bracket.NIGHT_BURST, Bracket.LONG_EXPOSURE -> {
                if (lens.manualSensor) {
                    val exposure = clampExposure(lens, p.shutterNs ?: CameraEngine.DEFAULT_SHUTTER_NS, notes)
                    val iso = p.iso?.let { v -> lens.isoRange?.let { v.coerceIn(it) } ?: v }
                    List(p.frames.coerceAtLeast(1)) { ShotSpec(exposureNs = exposure, iso = iso) }
                } else {
                    notes += "Contrôle manuel indisponible : prises en exposition automatique."
                    List(p.frames.coerceAtLeast(1)) { ShotSpec() }
                }
            }

            Bracket.FAST_SHUTTER_PAIR -> {
                if (lens.manualSensor) {
                    val fast = clampExposure(lens, p.shutterNs ?: 1_000_000_000L / 1000, notes)
                    val slow = clampExposure(lens, 1_000_000_000L / 30, notes)
                    listOf(
                        ShotSpec(exposureNs = fast, iso = p.iso?.coerceIn(lens.isoRange ?: 100..6400) ?: 800),
                        ShotSpec(exposureNs = slow, iso = 100),
                    )
                } else {
                    notes += "Contrôle manuel indisponible : le mouvement filé n'est pas garanti."
                    List(2) { ShotSpec() }
                }
            }
        }
        return Plan(specs, p.intervalMs, notes)
    }

    /**
     * Exécute le plan. Pour les balayages, les prises sont espacées dans le temps pour suivre le mouvement.
     */
    suspend fun shoot(
        plan: Plan,
        @Suppress("UNUSED_PARAMETER") settings: ManualSettings,
        onShot: suspend (CapturedShot) -> Unit,
    ) {
        if (plan.intervalMs <= 0L) {
            engine.capture(plan.specs, onShot)
            return
        }
        var index = 0
        plan.specs.forEachIndexed { i, spec ->
            if (i > 0) delay(plan.intervalMs)
            engine.capture(listOf(spec)) { shot ->
                onShot(CapturedShot(index++, shot.jpeg, shot.dng, shot.meta))
            }
        }
    }

    private fun clampExposure(lens: LensInfo, wanted: Long, notes: MutableList<String>): Long {
        val range = lens.exposureRangeNs ?: return wanted
        val clamped = wanted.coerceIn(range)
        if (clamped != wanted) {
            notes += "Vitesse limitée à ${"%.2f".format(clamped / 1e9)} s par le capteur."
        }
        return clamped
    }
}
