package dev.studiocamera.settings

import android.content.Context
import android.content.SharedPreferences
import dev.studiocamera.camera.CaptureFormat
import dev.studiocamera.camera.ResolutionPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Qualité de traitement : taille de travail des calculs (mémoire vs finesse). */
enum class ProcessingQuality(val label: String, val side: Int) {
    STANDARD("Standard (1920 px)", 1920),
    HIGH("Haute (2560 px, plus de mémoire)", 2560),
}

/**
 * Réglages persistants (SharedPreferences, local uniquement).
 * Les valeurs sont exposées en StateFlow pour que l'interface se mette à jour sans redémarrage.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("studio_camera", Context.MODE_PRIVATE)

    private val _format = MutableStateFlow(readEnum("format", CaptureFormat.JPEG))
    val format: StateFlow<CaptureFormat> = _format.asStateFlow()

    private val _resolution = MutableStateFlow(readEnum("resolution", ResolutionPreset.BALANCED))
    val resolution: StateFlow<ResolutionPreset> = _resolution.asStateFlow()

    private val _quality = MutableStateFlow(readEnum("quality", ProcessingQuality.STANDARD))
    val quality: StateFlow<ProcessingQuality> = _quality.asStateFlow()

    private val _grid = MutableStateFlow(prefs.getBoolean("grid", true))
    val gridVisible: StateFlow<Boolean> = _grid.asStateFlow()

    private val _advanced = MutableStateFlow(prefs.getBoolean("advanced", false))
    val advancedMode: StateFlow<Boolean> = _advanced.asStateFlow()

    var lastStyleId: String?
        get() = prefs.getString("lastStyle", null)
        set(value) = prefs.edit().putString("lastStyle", value).apply()

    var lastFamily: String?
        get() = prefs.getString("lastFamily", null)
        set(value) = prefs.edit().putString("lastFamily", value).apply()

    fun setFormat(value: CaptureFormat) = write("format", value.name).also { _format.value = value }
    fun setResolution(value: ResolutionPreset) = write("resolution", value.name).also { _resolution.value = value }
    fun setQuality(value: ProcessingQuality) = write("quality", value.name).also { _quality.value = value }

    fun setGridVisible(value: Boolean) {
        prefs.edit().putBoolean("grid", value).apply()
        _grid.value = value
    }

    fun setAdvancedMode(value: Boolean) {
        prefs.edit().putBoolean("advanced", value).apply()
        _advanced.value = value
    }

    fun workingSide(): Int = _quality.value.side

    private fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)
}
