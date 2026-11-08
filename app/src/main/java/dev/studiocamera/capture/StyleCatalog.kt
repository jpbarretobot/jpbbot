package dev.studiocamera.capture

import dev.studiocamera.imaging.Grade
import dev.studiocamera.layers.LayerAdjust
import dev.studiocamera.layers.LayerRole

/**
 * Catalogue des styles. Ajouter un style = ajouter une entrée ici (ou appeler [StyleCatalog.register]).
 * Les styles ne contiennent aucune logique : ils décrivent la capture et les étapes de traitement.
 */
object StyleCatalog {

    private val styles = mutableListOf<Style>()

    init {
        // ---------------------------------------------------------------- Famille 1 : professionnelle
        register(Style(
            id = "perspective-independante", family = Family.PRO,
            title = "Perspective indépendante", glyph = "◧",
            description = "Sépare sujet, premier plan et arrière-plan pour régler leur échelle et leur perspective.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
        ))
        register(Style(
            id = "portrait-cinema", family = Family.PRO,
            title = "Portrait cinéma", glyph = "◉",
            description = "Détourage du sujet, profondeur de champ maîtrisée et lumière travaillée.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            grade = Grade(contrast = 0.12f, saturation = -0.08f, warmth = -0.05f, vignette = 0.2f),
            layerPreset = LayerPreset(mapOf(
                LayerRole.BACKGROUND to LayerAdjust(blur = 0.55f, brightness = -0.15f),
                LayerRole.SUBJECT to LayerAdjust(sharpness = 0.25f, brightness = 0.1f),
            )),
        ))
        register(Style(
            id = "compression-distances", family = Family.PRO,
            title = "Compression des distances", glyph = "⇉",
            description = "Rapproche visuellement les plans éloignés, comme avec un téléobjectif.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true),
            layerPreset = LayerPreset(mapOf(
                LayerRole.BACKGROUND to LayerAdjust(zoom = 1.25f),
                LayerRole.MIDGROUND to LayerAdjust(zoom = 1.12f),
            )),
        ))
        register(Style(
            id = "architecture", family = Family.PRO,
            title = "Architecture", glyph = "▥",
            description = "Redresse les verticales et corrige la convergence des lignes.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.VERTICAL_CORRECTION),
            grade = Grade(contrast = 0.08f, saturation = 0.04f),
        ))
        register(Style(
            id = "macro-intelligente", family = Family.PRO,
            title = "Macro intelligente", glyph = "❀",
            description = "Empile des prises à mises au point différentes pour une netteté étendue en gros plan.",
            capture = CaptureProfile(Bracket.FOCUS_BRACKET, frames = 6, focusSteps = 6),
            steps = listOf(Step.ALIGN, Step.FOCUS_STACK),
            requirements = Requirements(manualFocus = true),
            grade = Grade(contrast = 0.06f, saturation = 0.05f),
        ))
        register(Style(
            id = "hdr-professionnel", family = Family.PRO,
            title = "HDR professionnel", glyph = "◐",
            description = "Trois expositions fusionnées pour garder les détails des ombres et des hautes lumières.",
            capture = CaptureProfile(Bracket.EXPOSURE_BRACKET, frames = 3, evSpread = 2f),
            steps = listOf(Step.ALIGN, Step.HDR_FUSION),
            grade = Grade(contrast = 0.05f, highlights = -0.08f),
        ))
        register(Style(
            id = "nuit-astro", family = Family.PRO,
            title = "Nuit et astrophoto", glyph = "☾",
            description = "Empilement de poses courtes : bruit réduit et détails nocturnes restaurés.",
            capture = CaptureProfile(Bracket.NIGHT_BURST, frames = 8, shutterNs = 250_000_000L, iso = 1600),
            steps = listOf(Step.ALIGN, Step.NIGHT_STACK),
            requirements = Requirements(manualSensor = true),
            grade = Grade(shadows = 0.25f, contrast = 0.08f, saturation = 0.05f),
        ))
        register(Style(
            id = "pose-longue", family = Family.PRO,
            title = "Pose longue", glyph = "∞",
            description = "Lisse l'eau, les nuages et les trajectoires lumineuses par pose longue.",
            capture = CaptureProfile(Bracket.LONG_EXPOSURE, frames = 8, shutterNs = 500_000_000L, iso = 100),
            steps = listOf(Step.ALIGN, Step.LONG_EXPOSURE_SIM),
            requirements = Requirements(manualSensor = true, minLongExposureNs = 500_000_000L),
            grade = Grade(contrast = 0.04f),
            simulatedLongExposure = true,
        ))
        register(Style(
            id = "nettete-integrale", family = Family.PRO,
            title = "Netteté intégrale", glyph = "▦",
            description = "Image nette du premier plan à l'arrière-plan grâce à la fusion de plans de netteté.",
            capture = CaptureProfile(Bracket.FOCUS_BRACKET, frames = 8, focusSteps = 8),
            steps = listOf(Step.ALIGN, Step.FOCUS_STACK),
            requirements = Requirements(manualFocus = true),
            grade = Grade(contrast = 0.05f),
        ))

        // ---------------------------------------------------------------- Famille 2 : IA créative
        register(Style(
            id = "perspective-multicouche", family = Family.AI_CREATIVE,
            title = "Perspective multicouche", glyph = "▤",
            description = "Recompose la photo en couches indépendantes que vous pouvez déplacer dans le Studio.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
        ))
        register(Style(
            id = "zoom-sujet", family = Family.AI_CREATIVE,
            title = "Zoom indépendant du sujet", glyph = "⊕",
            description = "Agrandit la personne ou l'objet sans modifier le décor.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            layerPreset = LayerPreset(mapOf(LayerRole.SUBJECT to LayerAdjust(zoom = 1.2f))),
        ))
        register(Style(
            id = "zoom-arriere-plan", family = Family.AI_CREATIVE,
            title = "Zoom indépendant de l'arrière-plan", glyph = "⊖",
            description = "Rapproche montagnes et bâtiments lointains, le sujet reste à sa taille.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            layerPreset = LayerPreset(mapOf(
                LayerRole.BACKGROUND to LayerAdjust(zoom = 1.35f),
                LayerRole.SKY to LayerAdjust(zoom = 1.1f),
            )),
        ))
        register(Style(
            id = "cadrage-intelligent", family = Family.AI_CREATIVE,
            title = "Cadrage intelligent", glyph = "⛶",
            description = "Agrandit le sujet tout en conservant un champ large reconstruit derrière lui.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            layerPreset = LayerPreset(mapOf(LayerRole.SUBJECT to LayerAdjust(zoom = 1.3f, offsetY = -0.04f))),
        ))
        register(Style(
            id = "recomposition-spatiale", family = Family.AI_CREATIVE,
            title = "Recomposition spatiale", glyph = "⇆",
            description = "Modifie les proportions apparentes et la disposition des plans.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            layerPreset = LayerPreset(mapOf(
                LayerRole.FOREGROUND to LayerAdjust(offsetX = 0.04f, zoom = 1.08f),
                LayerRole.MIDGROUND to LayerAdjust(perspective = 0.12f),
            )),
        ))
        register(Style(
            id = "lumiere-intelligente", family = Family.AI_CREATIVE,
            title = "Lumière intelligente", glyph = "☀",
            description = "Luminosité, ombres et contraste ajustés séparément pour le sujet et le décor.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = listOf(Step.PERSON_MASK, Step.DEPTH_AND_LAYERS, Step.PRESET_LAYERS),
            requirements = Requirements(usesDepth = true, usesPersonSegmentation = true),
            grade = Grade(shadows = 0.15f),
            layerPreset = LayerPreset(mapOf(
                LayerRole.SUBJECT to LayerAdjust(brightness = 0.2f, contrast = 0.1f),
                LayerRole.BACKGROUND to LayerAdjust(brightness = -0.12f, contrast = 0.05f),
            )),
        ))

        // ---------------------------------------------------------------- Famille 3 : cinématique
        register(Style(
            id = "cinema", family = Family.CINEMA,
            title = "Cinéma", glyph = "▶",
            description = "Profil colorimétrique cinéma : contraste, rendu pellicule et vignettage.",
            capture = CaptureProfile(Bracket.SINGLE),
            steps = emptyList(), // étalonnage seul, appliqué en fin de traitement
            grade = Grade(contrast = 0.15f, saturation = -0.1f, warmth = -0.12f, fade = 0.35f, vignette = 0.3f),
        ))
        register(Style(
            id = "lumiere-parfaite", family = Family.CINEMA,
            title = "Lumière parfaite", glyph = "✦",
            description = "Relève les ombres et protège les hautes lumières des portraits et contre-jours.",
            capture = CaptureProfile(Bracket.EXPOSURE_BRACKET, frames = 2, evSpread = 1f),
            steps = listOf(Step.ALIGN, Step.HDR_FUSION),
            grade = Grade(shadows = 0.3f, highlights = -0.12f, warmth = 0.05f),
        ))
        register(Style(
            id = "mouvement", family = Family.CINEMA,
            title = "Mouvement", glyph = "➤",
            description = "Sujet net sur un arrière-plan dynamique : deux prises, une figée et une filée.",
            capture = CaptureProfile(Bracket.FAST_SHUTTER_PAIR, frames = 2, shutterNs = 1_000_000_000L / 1000, iso = 800),
            steps = listOf(Step.ALIGN, Step.PERSON_MASK, Step.MOTION_COMPOSITE),
            requirements = Requirements(manualSensor = true, usesPersonSegmentation = true),
            grade = Grade(contrast = 0.1f, saturation = 0.06f),
        ))
        register(Style(
            id = "panorama-intelligent", family = Family.CINEMA,
            title = "Panorama intelligent", glyph = "▭",
            description = "Balayage guidé : les prises sont assemblées et les raccords corrigés.",
            capture = CaptureProfile(Bracket.PANORAMA_SWEEP, frames = 6, intervalMs = 350L),
            steps = listOf(Step.PANORAMA),
            grade = Grade(contrast = 0.05f),
        ))
        register(Style(
            id = "photo-de-groupe", family = Family.CINEMA,
            title = "Photo de groupe parfaite", glyph = "☺",
            description = "Choisit les meilleures expressions et remplace les visages aux yeux fermés.",
            capture = CaptureProfile(Bracket.GROUP_BURST, frames = 5),
            steps = listOf(Step.ALIGN, Step.GROUP_BEST),
            grade = Grade(contrast = 0.05f, warmth = 0.03f),
        ))
    }

    fun register(style: Style) {
        require(styles.none { it.id == style.id }) { "Style déjà enregistré : ${style.id}" }
        styles.add(style)
    }

    fun all(): List<Style> = styles.toList()

    fun byId(id: String): Style? = styles.firstOrNull { it.id == id }

    fun byFamily(family: Family): List<Style> = styles.filter { it.family == family }

    val default: Style get() = byId("perspective-multicouche") ?: styles.first()
}
