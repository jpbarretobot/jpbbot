package dev.studiocamera.processing

import dev.studiocamera.ai.AiServices
import dev.studiocamera.ai.FaceAnalyzer
import dev.studiocamera.camera.ShotMeta
import dev.studiocamera.capture.Step
import dev.studiocamera.capture.Style
import dev.studiocamera.imaging.Alignment
import dev.studiocamera.imaging.ExposureFusion
import dev.studiocamera.imaging.FocusStack
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Grading
import dev.studiocamera.imaging.Panorama
import dev.studiocamera.imaging.Plane
import dev.studiocamera.imaging.Stacking
import dev.studiocamera.imaging.VerticalCorrection
import dev.studiocamera.io.Bitmaps
import dev.studiocamera.layers.EditState
import dev.studiocamera.layers.LayerBuilder
import dev.studiocamera.layers.LayerProject
import dev.studiocamera.layers.LayerRenderer
import dev.studiocamera.layers.LayerAdjust
import dev.studiocamera.capture.LayerPreset
import kotlin.math.min

/**
 * Exécute les étapes d'un style sur une série de prises. Tout est local : aucun envoi réseau.
 *
 * Les images sont décodées à la résolution de travail (réduite pour les longues séries, afin de
 * borner la mémoire). L'original n'est jamais modifié : le pipeline ne fait que lire les JPEG.
 */
class ProcessingPipeline(
    private val ai: AiServices,
    private val faces: FaceAnalyzer,
) {

    class Output(
        val rendered: Frame,
        val project: LayerProject?,
        val state: EditState,
        val notes: List<String>,
        /** Part de pixels reconstruits (0..1) lorsqu'un projet multicouche est produit. */
        val generatedRatio: Float?,
    )

    suspend fun run(
        style: Style,
        shots: List<ByteArray>,
        metas: List<ShotMeta>,
        workingSide: Int,
        onStep: (String) -> Unit = {},
    ): Output {
        require(shots.isNotEmpty()) { "Aucune prise à traiter" }
        val notes = ArrayList<String>()
        // Qualité : une photo seule garde toute la résolution du capteur. Les couches et les empilements
        // travaillent à la résolution de travail, pour rester dans la mémoire du téléphone.
        val side = when {
            style.producesLayers -> workingSide
            shots.size > 4 -> min(workingSide, 2048)
            shots.size > 1 -> workingSide
            else -> Int.MAX_VALUE
        }
        var frames: List<Frame> = shots.map { Bitmaps.decodeLimited(it, side) }
        var current: Frame = frames.first()
        var depth: Plane? = null
        var mask: Plane? = null
        var project: LayerProject? = null

        if (style.steps.isEmpty()) notes += "Étalonnage seul, aucune reconstruction."

        for (step in style.steps) {
            onStep(stepLabel(step))
            when (step) {
                Step.ALIGN -> if (frames.size > 1) {
                    val set = Alignment.alignAll(frames)
                    frames = set.frames
                    current = frames.first()
                    val maxShift = set.shifts.maxOf { s -> maxOf(kotlin.math.abs(s[0]), kotlin.math.abs(s[1])) }
                    notes += "Alignement de ${frames.size} prises (décalage max $maxShift px)."
                }

                Step.HDR_FUSION -> {
                    current = ExposureFusion.fuse(frames)
                    frames = listOf(current)
                    notes += "Fusion d'expositions (${shots.size} prises)."
                }

                Step.FOCUS_STACK -> {
                    val result = FocusStack.stack(frames)
                    current = result.frame
                    depth = result.depth
                    frames = listOf(current)
                    notes += "Netteté intégrale : ${shots.size} plans de mise au point fusionnés."
                }

                Step.NIGHT_STACK -> {
                    current = Stacking.robustMean(frames)
                    frames = listOf(current)
                    notes += "Empilement de ${shots.size} poses (moyenne écrêtée, bruit réduit)."
                }

                Step.LONG_EXPOSURE_SIM -> {
                    current = Stacking.meanStack(frames)
                    frames = listOf(current)
                    notes += if (style.simulatedLongExposure) {
                        "Pose longue simulée : moyenne de ${shots.size} prises alignées."
                    } else {
                        "Pose longue : moyenne de ${shots.size} prises."
                    }
                }

                Step.PANORAMA -> {
                    current = Panorama.stitch(frames)
                    frames = listOf(current)
                    notes += "Panorama assemblé à partir de ${shots.size} prises (translation)."
                }

                Step.GROUP_BEST -> {
                    val result = GroupSelection.select(frames, faces)
                    current = result.frame
                    frames = listOf(current)
                    notes += result.notes
                }

                Step.MOTION_COMPOSITE -> {
                    if (frames.size >= 2) {
                        val fast = frames[0]
                        val slow = frames[1]
                        val m = mask ?: ai.subjectMask(fast)?.first
                        current = if (m != null) {
                            Frame.blendWeighted(listOf(fast, slow), listOf(m, m.map { 1f - it }))
                        } else {
                            notes += "Sujet non détecté : prise figée conservée sur l'ensemble de l'image."
                            fast
                        }
                        frames = listOf(current)
                        if (m != null) notes += "Sujet net composé sur un arrière-plan filé."
                    }
                }

                Step.VERTICAL_CORRECTION -> {
                    val (corrected, estimate) = VerticalCorrection.correct(current)
                    current = corrected
                    notes += if (estimate != null) {
                        "Verticales redressées (inclinaison ${"%.1f".format(estimate.tiltDegrees)}°)."
                    } else {
                        "Aucune verticale fiable détectée : image inchangée."
                    }
                }

                Step.PERSON_MASK -> {
                    val found = ai.subjectMask(current)
                    mask = found?.first
                    notes += if (found == null) {
                        "Aucun sujet détecté : traitement appliqué à l'ensemble de l'image."
                    } else {
                        "Sujet détecté (${found.second})."
                    }
                }

                Step.DEPTH_AND_LAYERS -> {
                    val d = depth ?: run {
                        val (plane, engine) = ai.depth(current)
                        notes += "Profondeur : $engine."
                        plane
                    }
                    project = LayerBuilder.build(current, d, mask).also {
                        notes += "${it.layers.size} couches créées (${it.layers.joinToString { l -> l.role.label }})."
                    }
                }

                Step.PRESET_LAYERS -> Unit // appliqué au rendu ci-dessous
            }
        }

        val proj = project
        return if (proj != null) {
            val state = EditState(
                adjusts = presetAdjusts(proj, style.layerPreset),
                grade = style.grade,
            )
            val rendered = LayerRenderer(proj).render(state, proj.width, proj.height)
            Output(rendered.frame, proj, state, notes, rendered.generated.mean())
        } else {
            Output(Grading.apply(current, style.grade), null, EditState(grade = style.grade), notes, null)
        }
    }

    private fun stepLabel(step: Step): String = when (step) {
        Step.ALIGN -> "alignement des prises…"
        Step.HDR_FUSION -> "fusion des expositions…"
        Step.FOCUS_STACK -> "empilement de la netteté…"
        Step.NIGHT_STACK -> "empilement de nuit…"
        Step.LONG_EXPOSURE_SIM -> "pose longue…"
        Step.PANORAMA -> "assemblage du panorama…"
        Step.GROUP_BEST -> "choix des visages…"
        Step.MOTION_COMPOSITE -> "composition du mouvement…"
        Step.VERTICAL_CORRECTION -> "redressement des verticales…"
        Step.PERSON_MASK -> "détection du sujet…"
        Step.DEPTH_AND_LAYERS -> "séparation des plans…"
        Step.PRESET_LAYERS -> "rendu des couches…"
    }

    private fun presetAdjusts(project: LayerProject, preset: LayerPreset?): Map<Int, LayerAdjust> {
        if (preset == null) return emptyMap()
        return project.layers.mapNotNull { layer ->
            preset.adjusts[layer.role]?.let { layer.id to it }
        }.toMap()
    }
}
