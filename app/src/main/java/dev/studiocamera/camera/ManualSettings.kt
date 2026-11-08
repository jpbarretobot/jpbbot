package dev.studiocamera.camera

/** Mode de flash demandé par l'utilisateur. */
enum class FlashMode(val label: String) {
    OFF("Flash désactivé"),
    AUTO("Flash auto"),
    ON("Flash"),
    TORCH("Lampe"),
}

/** Préréglage de résolution : le moteur choisit la taille de capture la plus proche de la cible. */
enum class ResolutionPreset(val label: String, val targetMegapixels: Float) {
    MAX("Maximale", Float.MAX_VALUE),
    BALANCED("Équilibrée (≈12 MP)", 12f),
    LIGHT("Légère (≈5 MP)", 5f),
}

/** Format de capture : JPEG seul, ou JPEG accompagné d'un DNG (RAW) lorsque le capteur le permet. */
enum class CaptureFormat(val label: String) {
    JPEG("JPEG"),
    JPEG_DNG("JPEG + DNG"),
}

/**
 * Réglages photographiques. `null` signifie « automatique ».
 * Les valeurs manuelles désactivent l'exposition automatique correspondante.
 */
data class ManualSettings(
    val evCompensation: Float = 0f,         // IL, appliqué seulement en exposition automatique
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,       // 0 = infini ; null = autofocus
    val whiteBalanceK: Int? = null,         // null = balance automatique
    val zoom: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val hdrScene: Boolean = false,
    val resolution: ResolutionPreset = ResolutionPreset.BALANCED,
    val format: CaptureFormat = CaptureFormat.JPEG,
) {
    val isManualExposure: Boolean get() = iso != null || shutterNs != null
    val isManualFocus: Boolean get() = focusDiopters != null
}
