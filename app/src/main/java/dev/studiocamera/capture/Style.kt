package dev.studiocamera.capture

import dev.studiocamera.imaging.Grade
import dev.studiocamera.layers.LayerAdjust
import dev.studiocamera.layers.LayerRole

/** Famille de styles affichée dans le sélecteur. */
enum class Family(val label: String, val description: String) {
    PRO("Professionnelle", "Perspective, HDR, nuit, macro et netteté intégrale"),
    AI_CREATIVE("IA créative", "Plans séparés, zooms indépendants et recomposition"),
    CINEMA("Cinématique", "Ambiances, lumière, mouvement et panoramas"),
}

/** Méthode de prise de vue. Chaque méthode produit une série de prises, traitée ensuite. */
enum class Bracket {
    SINGLE,
    EXPOSURE_BRACKET,   // expositions décalées (HDR)
    FOCUS_BRACKET,      // mises au point successives (netteté intégrale / macro)
    NIGHT_BURST,        // poses courtes répétées, empilées
    LONG_EXPOSURE,      // poses longues (réelles si le matériel le permet, sinon empilées)
    FAST_SHUTTER_PAIR,  // une prise figée + une prise filée
    PANORAMA_SWEEP,     // balayage guidé
    GROUP_BURST,        // rafale pour choisir les meilleures expressions
}

/** Étape de traitement appliquée après la capture, dans l'ordre de la liste du style. */
enum class Step {
    ALIGN,
    HDR_FUSION,
    FOCUS_STACK,
    NIGHT_STACK,
    LONG_EXPOSURE_SIM,
    PANORAMA,
    GROUP_BEST,
    MOTION_COMPOSITE,
    VERTICAL_CORRECTION,
    PERSON_MASK,
    DEPTH_AND_LAYERS,
    PRESET_LAYERS,
}

/**
 * Profil de capture.
 * [shutterNs] et [isoCap] ne sont appliqués que si le capteur autorise l'exposition manuelle.
 */
data class CaptureProfile(
    val bracket: Bracket,
    val frames: Int = 1,
    val evSpread: Float = 0f,
    val shutterNs: Long? = null,
    val iso: Int? = null,
    val focusSteps: Int = 0,
    val intervalMs: Long = 0L,
)

/** Préréglages appliqués aux couches à l'ouverture (par rôle). */
data class LayerPreset(val adjusts: Map<LayerRole, LayerAdjust>)

/** Exigences matérielles : utilisées pour déterminer la disponibilité et les replis. */
data class Requirements(
    val manualSensor: Boolean = false,
    val manualFocus: Boolean = false,
    val minLongExposureNs: Long? = null,
    val usesDepth: Boolean = false,
    val usesPersonSegmentation: Boolean = false,
)

data class Style(
    val id: String,
    val family: Family,
    val title: String,
    val description: String,
    val glyph: String,
    val capture: CaptureProfile,
    val steps: List<Step>,
    val requirements: Requirements = Requirements(),
    val grade: Grade = Grade.NEUTRAL,
    val layerPreset: LayerPreset? = null,
    /** Vrai si le résultat est une simulation (empilement) et non une pose longue réelle. */
    val simulatedLongExposure: Boolean = false,
) {
    /** Un style produit un projet multicouche s'il contient l'étape de séparation des plans. */
    val producesLayers: Boolean get() = Step.DEPTH_AND_LAYERS in steps
}
