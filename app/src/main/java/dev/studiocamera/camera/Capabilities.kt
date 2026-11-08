package dev.studiocamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Un objectif utilisable : une caméra logique, ou une caméra physique d'un ensemble multi-caméras.
 * Toutes les valeurs proviennent des caractéristiques réelles du capteur ; rien n'est supposé.
 */
data class LensInfo(
    val logicalId: String,
    val physicalId: String?,
    val facing: Int,
    val focalEquivalent35mm: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val supportsZoomRatio: Boolean,
    val hasOis: Boolean,
    val minFocusDiopters: Float,
    val hasFlash: Boolean,
    val manualSensor: Boolean,
    val rawSupported: Boolean,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    val aeCompensationRange: IntRange,
    val aeCompensationStep: Float,
    val hasHdrScene: Boolean,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val jpegSizes: List<Size>,
    val rawSizes: List<Size>,
    val previewSizes: List<Size>,
) {
    val id: String get() = physicalId ?: logicalId

    val isBack: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK

    /** Libellé lisible, fondé sur la focale équivalente 35 mm. */
    val label: String
        get() {
            val f = focalEquivalent35mm
            val kind = when {
                f <= 0f -> "Objectif"
                f < 20f -> "Ultra grand-angle"
                f < 32f -> "Grand-angle"
                f < 60f -> "Principal"
                f < 110f -> "Téléobjectif"
                else -> "Super-téléobjectif"
            }
            return if (f > 0f) "$kind ${"%.0f".format(f)} mm" else kind
        }

    val maxJpegPixels: Long get() = jpegSizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
}

/** Ensemble des capacités détectées sur l'appareil. */
data class DeviceCapabilities(
    val lenses: List<LensInfo>,
    val deviceModel: String,
    val androidApi: Int,
) {
    val backLenses: List<LensInfo> get() = lenses.filter { it.isBack }.sortedBy { it.focalEquivalent35mm }
    val frontLenses: List<LensInfo> get() = lenses.filter { !it.isBack }

    /** Objectif principal : focale la plus proche de 26 mm (standard smartphone), à défaut le premier. */
    val mainBack: LensInfo? get() = backLenses.minByOrNull { abs(it.focalEquivalent35mm - 26f) }
    val mainFront: LensInfo? get() = frontLenses.firstOrNull()

    val supportsRaw: Boolean get() = lenses.any { it.rawSupported }
    val supportsManualSensor: Boolean get() = lenses.any { it.manualSensor }

    fun summary(): String = buildString {
        append("${backLenses.size} objectif(s) arrière, ${frontLenses.size} avant")
        append(" · RAW ${if (supportsRaw) "oui" else "non"}")
        append(" · Contrôle manuel ${if (supportsManualSensor) "oui" else "non"}")
    }
}

object CapabilityProbe {

    fun probe(context: Context): DeviceCapabilities {
        val manager = context.getSystemService(CameraManager::class.java)
        val lenses = ArrayList<LensInfo>()
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            // Caméra logique multi-objectifs : on expose chaque caméra physique comme un objectif distinct.
            val physicalIds = if (Build.VERSION.SDK_INT >= 28) chars.physicalCameraIds.toList() else emptyList()
            if (physicalIds.isNotEmpty()) {
                for (pid in physicalIds) {
                    val pChars = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    describe(id, pid, pChars)?.let { lenses.add(it) }
                }
            } else {
                describe(id, null, chars)?.let { lenses.add(it) }
            }
        }
        return DeviceCapabilities(
            lenses = lenses,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidApi = Build.VERSION.SDK_INT,
        )
    }

    private fun describe(logicalId: String, physicalId: String?, c: CameraCharacteristics): LensInfo? {
        val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = physical?.let { sqrt(it.width * it.width + it.height * it.height) } ?: 0f
        val eq35 = if (focal > 0f && diag > 0f) focal * 43.27f / diag else 0f

        val zoomRange = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }
        val ae = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()
        val sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList()
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        return LensInfo(
            logicalId = logicalId,
            physicalId = physicalId,
            facing = facing,
            focalEquivalent35mm = eq35,
            minZoom = zoomRange?.lower?.toFloat() ?: 1f,
            maxZoom = zoomRange?.upper?.toFloat() ?: 1f,
            supportsZoomRatio = zoomRange != null,
            hasOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            minFocusDiopters = minFocus,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            rawSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            isoRange = isoRange,
            exposureRangeNs = expRange,
            aeCompensationRange = ae?.let { it.lower..it.upper } ?: 0..0,
            aeCompensationStep = if (aeStep > 0f) aeStep else 1f,
            hasHdrScene = sceneModes.contains(CameraCharacteristics.CONTROL_SCENE_MODE_HDR),
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 1, 1),
            jpegSizes = sizesOf(map, ImageFormat.JPEG),
            rawSizes = if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                sizesOf(map, ImageFormat.RAW_SENSOR)
            } else emptyList(),
            previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                ?.sortedByDescending { it.width * it.height }
                ?.filter { it.width <= 1920 }
                ?: emptyList(),
        )
    }

    private fun sizesOf(map: StreamConfigurationMap, format: Int): List<Size> =
        map.getOutputSizes(format)?.sortedByDescending { it.width * it.height } ?: emptyList()
}
