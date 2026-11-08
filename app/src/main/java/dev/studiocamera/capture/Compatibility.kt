package dev.studiocamera.capture

import dev.studiocamera.camera.DeviceCapabilities

/** Disponibilité d'un style sur l'appareil courant. */
sealed class Availability {
    /** Le style fonctionne comme décrit. */
    data object Ready : Availability()

    /** Le style fonctionne avec un repli : [reason] explique ce qui change. */
    data class Degraded(val reason: String) : Availability()

    /** Le style ne peut pas produire son résultat sur cet appareil. */
    data class Unavailable(val reason: String) : Availability()

    val isUsable: Boolean get() = this !is Unavailable
}

/**
 * Confronte les exigences d'un style aux capacités réelles du téléphone.
 * Rien n'est supposé : un style sans matériel suffisant est signalé, jamais simulé en silence.
 */
object Compatibility {

    fun evaluate(style: Style, caps: DeviceCapabilities, hasDepthModel: Boolean): Availability {
        if (caps.lenses.isEmpty()) return Availability.Unavailable("Aucune caméra détectée.")
        val r = style.requirements
        val lenses = caps.lenses

        if (r.manualFocus && lenses.none { it.minFocusDiopters > 0f }) {
            return Availability.Unavailable("Cet appareil n'a pas de mise au point réglable : style indisponible.")
        }

        if (r.manualSensor && !caps.supportsManualSensor) {
            return Availability.Degraded("Pas de contrôle manuel du capteur : prises en exposition automatique, résultat moins maîtrisé.")
        }

        r.minLongExposureNs?.let { needed ->
            val longest = lenses.mapNotNull { it.exposureRangeNs?.last }.maxOrNull() ?: 0L
            if (longest in 1 until needed) {
                return Availability.Degraded("Pose maximale ${longest / 1_000_000_000f} s : pose plus courte que prévue.")
            }
        }

        if (r.usesDepth && !hasDepthModel) {
            return Availability.Degraded("Profondeur estimée par heuristique : importez un modèle de profondeur dans les Paramètres pour un résultat plus précis.")
        }

        return Availability.Ready
    }
}
