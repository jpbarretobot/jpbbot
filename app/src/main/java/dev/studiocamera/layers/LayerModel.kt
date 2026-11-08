package dev.studiocamera.layers

import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Grade
import dev.studiocamera.imaging.Plane

/** Rôle sémantique d'une couche. L'ordre de composition dépend de la profondeur, pas du rôle. */
enum class LayerRole(val label: String) {
    SUBJECT("Sujet"),
    FOREGROUND("Premier plan"),
    MIDGROUND("Plan intermédiaire"),
    BACKGROUND("Arrière-plan"),
    SKY("Ciel / lointain"),
}

/**
 * Réglages propres à une couche. Valeurs neutres : zoom 1 (= 100 %), décalages 0, reste 0.
 * Les plages sont documentées pour l'interface et la sérialisation.
 */
data class LayerAdjust(
    val zoom: Float = 1f,         // 0.5 .. 2.0 (1 = 100 %)
    val offsetX: Float = 0f,      // fraction de la largeur, -0.5 .. 0.5
    val offsetY: Float = 0f,      // fraction de la hauteur, -0.5 .. 0.5
    val perspective: Float = 0f,  // -0.5 .. 0.5 : agrandit le bas (+) ou le haut (-)
    val brightness: Float = 0f,   // -1 .. 1 (exposition en IL)
    val contrast: Float = 0f,     // -1 .. 1
    val saturation: Float = 0f,   // -1 .. 1
    val sharpness: Float = 0f,    // 0 .. 1
    val blur: Float = 0f,         // 0 .. 1
) {
    fun hasGeometry(): Boolean = zoom != 1f || offsetX != 0f || offsetY != 0f || perspective != 0f
    fun hasLook(): Boolean = brightness != 0f || contrast != 0f || saturation != 0f || sharpness != 0f || blur != 0f
    fun isNeutral(): Boolean = !hasGeometry() && !hasLook()

    companion object {
        val NEUTRAL = LayerAdjust()
    }
}

/**
 * Couche de profondeur. [alpha] est une partition de l'unité : la somme des couches vaut 1 en chaque pixel.
 * Les masques sont à la résolution de travail du projet et ne sont jamais modifiés après construction.
 */
class Layer(
    val id: Int,
    val role: LayerRole,
    val alpha: Plane,
    val depthMean: Float,
) {
    val coverage: Float by lazy { alpha.mean() }
}

/**
 * Projet multicouche : image originale (résolution de travail), couches et carte de profondeur.
 * L'original n'est jamais modifié : toutes les transformations passent par [EditState].
 */
class LayerProject(
    val base: Frame,
    val layers: List<Layer>,
    val depth: Plane?,
    val subjectDetected: Boolean,
) {
    val width: Int get() = base.width
    val height: Int get() = base.height

    fun layer(id: Int): Layer? = layers.firstOrNull { it.id == id }
}

/**
 * État d'édition immuable : tout ce qui change au fil des gestes de l'utilisateur.
 * Sa taille est minuscule, ce qui permet un historique annuler/rétablir illimité en pratique.
 */
data class EditState(
    val adjusts: Map<Int, LayerAdjust> = emptyMap(),
    val visible: Map<Int, Boolean> = emptyMap(),
    val grade: Grade = Grade.NEUTRAL,
    val selectedLayer: Int? = null,
) {
    fun adjustFor(id: Int): LayerAdjust = adjusts[id] ?: LayerAdjust.NEUTRAL
    fun isVisible(id: Int): Boolean = visible[id] ?: true

    fun withAdjust(id: Int, adjust: LayerAdjust): EditState = copy(adjusts = adjusts + (id to adjust))
    fun withVisible(id: Int, value: Boolean): EditState = copy(visible = visible + (id to value))
    fun withGrade(g: Grade): EditState = copy(grade = g)
    fun withSelected(id: Int?): EditState = copy(selectedLayer = id)

    /** Vrai si le rendu se réduit à l'image d'origine (rien à recalculer). */
    fun isIdentity(layers: List<Layer>): Boolean =
        grade.isNeutral() && layers.all { isVisible(it.id) && adjustFor(it.id).isNeutral() }
}
