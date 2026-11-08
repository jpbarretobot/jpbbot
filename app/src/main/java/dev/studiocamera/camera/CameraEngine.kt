package dev.studiocamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream

/** Réglage d'une prise de vue dans une série (exposition, ISO, mise au point, compensation). */
data class ShotSpec(
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val focusDiopters: Float? = null,
    val evCompensation: Float? = null,
)

/** Métadonnées réelles de la prise, lues dans le résultat de capture du capteur. */
data class ShotMeta(
    val timestampNs: Long,
    val iso: Int?,
    val exposureNs: Long?,
    val focusDiopters: Float?,
    val focalLengthMm: Float?,
)

/** Une prise terminée : JPEG, DNG optionnel et métadonnées. */
class CapturedShot(val index: Int, val jpeg: ByteArray, val dng: ByteArray?, val meta: ShotMeta)

/** Valeurs réelles relues pendant l'aperçu, affichées dans le viseur. */
data class LiveReadout(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focusDiopters: Float? = null,
    val afLocked: Boolean = false,
)

sealed class EngineState {
    data object Idle : EngineState()
    data object Opening : EngineState()
    data object Ready : EngineState()
    data class Failed(val message: String) : EngineState()
}

/**
 * Moteur Camera2 : aperçu, contrôles manuels, rafales bracketées, JPEG et DNG.
 *
 * Toutes les opérations s'exécutent sur un thread dédié (Handler), ce qui sérialise l'accès aux
 * objets Camera2 et évite les conditions de course entre callbacks et appels utilisateur.
 */
class CameraEngine(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("studio-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher: CoroutineDispatcher = handler.asCoroutineDispatcher()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _readout = MutableStateFlow(LiveReadout())
    val readout: StateFlow<LiveReadout> = _readout.asStateFlow()

    private var lens: LensInfo? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var jpegSize: Size? = null
    private var rawSize: Size? = null
    private var rawActive = false
    private var settings = ManualSettings()
    private var frameCounter = 0

    private val jpegQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val rawQueue = Channel<Image>(Channel.UNLIMITED)

    /** Caméra en cours d'utilisation, ou null. */
    val activeLens: LensInfo? get() = lens

    /** Taille d'aperçu recommandée (format 4:3 si possible), à utiliser pour le SurfaceTexture. */
    fun previewSizeFor(lens: LensInfo): Size {
        val candidates = lens.previewSizes
        return candidates.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.03f && it.width <= 1440 }
            .maxByOrNull { it.width * it.height }
            ?: candidates.minByOrNull { abs(it.width * 3 - it.height * 4) }
            ?: Size(1280, 960)
    }

    /**
     * Démarre (ou reconfigure) la caméra : ouvre l'objectif si nécessaire, crée la session, lance l'aperçu.
     */
    suspend fun start(newLens: LensInfo, surface: Surface, newSettings: ManualSettings) = withContext(dispatcher) {
        try {
            _state.value = EngineState.Opening
            if (lens?.logicalId != newLens.logicalId) {
                closeDeviceLocked()
                device = openDevice(newLens.logicalId)
            }
            lens = newLens
            previewSurface = surface
            settings = newSettings
            createSessionLocked()
            startRepeatingLocked()
            _state.value = EngineState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = EngineState.Failed(e.message ?: "Erreur caméra")
            throw e
        }
    }

    /** Met à jour les réglages. Reconfigure la session seulement si le format ou la résolution change. */
    suspend fun updateSettings(newSettings: ManualSettings) = withContext(dispatcher) {
        val needsSession = newSettings.format != settings.format ||
            newSettings.resolution != settings.resolution
        settings = newSettings
        if (device == null || lens == null) return@withContext
        if (needsSession) createSessionLocked()
        startRepeatingLocked()
    }

    /** Autofocus et exposition sur un point (coordonnées normalisées 0..1, format portrait). */
    suspend fun focusAt(nx: Float, ny: Float) = withContext(dispatcher) {
        val l = lens ?: return@withContext
        val s = session ?: return@withContext
        val d = device ?: return@withContext
        val arr = l.activeArray
        val (sx, sy) = mapPortraitToSensor(nx, ny, l.sensorOrientation, l.isBack)
        val half = 0.07f * min(arr.width(), arr.height())
        val cx = arr.left + sx * arr.width()
        val cy = arr.top + sy * arr.height()
        val rect = MeteringRectangle(
            (cx - half).roundToInt().coerceIn(arr.left, arr.right - 1),
            (cy - half).roundToInt().coerceIn(arr.top, arr.bottom - 1),
            (2 * half).roundToInt(),
            (2 * half).roundToInt(),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { b.addTarget(it) }
        b.applyCommon(l, settings, null, still = false)
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        s.capture(b.build(), null, handler)
        startRepeatingLocked()
    }

    /**
     * Capture une série : une requête par [ShotSpec]. Chaque prise est transmise à [onShot] dès qu'elle est prête,
     * ce qui évite de garder toutes les images en mémoire.
     */
    suspend fun capture(
        specs: List<ShotSpec>,
        onShot: suspend (CapturedShot) -> Unit,
    ) = withContext(dispatcher) {
        val l = lens ?: throw IllegalStateException("Caméra non démarrée")
        val d = device ?: throw IllegalStateException("Caméra non ouverte")
        val s = session ?: throw IllegalStateException("Session non prête")
        require(specs.isNotEmpty())
        drainQueues()
        val results = Channel<Result<TotalCaptureResult>>(Channel.UNLIMITED)
        val requests = specs.map { spec ->
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            jpegReader?.surface?.let { b.addTarget(it) }
            if (rawActive) rawReader?.surface?.let { b.addTarget(it) }
            b.applyCommon(l, settings, spec, still = true)
            b.build()
        }
        val chars = characteristicsOf(l)
        try {
            stopRepeatingLocked()
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    results.trySend(Result.success(result))
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    results.trySend(Result.failure(IllegalStateException("Prise échouée (code ${failure.reason})")))
                }
            }, handler)

            specs.indices.forEach { i ->
                val result = withTimeout(CAPTURE_TIMEOUT_MS) { results.receive() }.getOrThrow()
                val jpeg = withTimeout(CAPTURE_TIMEOUT_MS) { jpegQueue.receive() }
                val dng = if (rawActive) {
                    val image = withTimeout(CAPTURE_TIMEOUT_MS) { rawQueue.receive() }
                    try {
                        encodeDng(chars, result, image)
                    } finally {
                        image.close()
                    }
                } else null
                onShot(CapturedShot(i, jpeg, dng, metaOf(result)))
            }
        } finally {
            results.close()
            startRepeatingLocked()
        }
    }

    /** Ferme la session et la caméra, mais garde le thread pour une réutilisation. */
    suspend fun stop() = withContext(dispatcher) {
        closeSessionLocked()
        closeDeviceLocked()
        lens = null
        _state.value = EngineState.Idle
    }

    fun shutdown() {
        handler.post {
            closeSessionLocked()
            closeDeviceLocked()
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------------------------------------
    // Gestion interne (toujours appelée sur le thread caméra)
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Caméra déconnectée"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Erreur caméra $error"))
                }
            }, handler)
        }

    private suspend fun createSessionLocked() {
        val d = device ?: return
        val l = lens ?: return
        val surface = previewSurface ?: return
        closeSessionLocked()

        val chars = characteristicsOf(l)
        if (chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null) {
            throw IllegalStateException("Configuration de flux absente")
        }
        val previewRatio = previewSizeFor(l).let { it.width.toFloat() / it.height }

        jpegSize = pickSize(l.jpegSizes, settings.resolution, previewRatio)
        rawActive = settings.format == CaptureFormat.JPEG_DNG && l.rawSupported && l.rawSizes.isNotEmpty()
        rawSize = if (rawActive) pickSize(l.rawSizes, settings.resolution, previewRatio) else null

        jpegReader?.close()
        jpegReader = ImageReader.newInstance(jpegSize!!.width, jpegSize!!.height, ImageFormat.JPEG, MAX_IMAGES).also {
            it.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.use { img -> jpegQueue.trySend(img.planes[0].buffer.toByteArray()) } }, handler)
        }
        rawReader?.close()
        rawReader = if (rawActive) {
            ImageReader.newInstance(rawSize!!.width, rawSize!!.height, ImageFormat.RAW_SENSOR, MAX_IMAGES).also {
                it.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.let { img -> rawQueue.trySend(img) } }, handler)
            }
        } else null

        val outputs = ArrayList<OutputConfiguration>()
        fun add(s: Surface) {
            val o = OutputConfiguration(s)
            if (Build.VERSION.SDK_INT >= 28) l.physicalId?.let { o.setPhysicalCameraId(it) }
            outputs.add(o)
        }
        add(surface)
        add(jpegReader!!.surface)
        rawReader?.let { add(it.surface) }

        session = configureSession(d, outputs)
    }

    private suspend fun configureSession(d: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Configuration de session refusée"))
                }
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    { runnable -> handler.post(runnable) },
                    callback,
                )
                d.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSessionByOutputConfigurations(outputs, callback, handler)
            }
        }

    private fun startRepeatingLocked() {
        val s = session ?: return
        val d = device ?: return
        val l = lens ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { b.addTarget(it) }
        b.applyCommon(l, settings, null, still = false)
        s.setRepeatingRequest(b.build(), previewCallback, handler)
    }

    private fun stopRepeatingLocked() {
        runCatching { session?.stopRepeating() }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            frameCounter++
            if (frameCounter % 6 != 0) return
            _readout.value = LiveReadout(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                afLocked = result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            )
        }
    }

    private fun closeSessionLocked() {
        runCatching { session?.stopRepeating() }
        session?.close()
        session = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        drainQueues()
    }

    private fun closeDeviceLocked() {
        closeSessionLocked()
        device?.close()
        device = null
    }

    private fun drainQueues() {
        while (jpegQueue.tryReceive().isSuccess) Unit
        while (true) {
            val image = rawQueue.tryReceive().getOrNull() ?: break
            image.close()
        }
    }

    private fun characteristicsOf(l: LensInfo): CameraCharacteristics =
        manager.getCameraCharacteristics(l.physicalId ?: l.logicalId)

    private fun encodeDng(chars: CameraCharacteristics, result: TotalCaptureResult, image: Image): ByteArray {
        val out = ByteArrayOutputStream()
        DngCreator(chars, result).use { it.writeImage(out, image) }
        return out.toByteArray()
    }

    private fun metaOf(r: TotalCaptureResult) = ShotMeta(
        timestampNs = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
        iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
        exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
        focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH),
    )

    // ---------------------------------------------------------------------------------------------
    // Paramètres de requête
    // ---------------------------------------------------------------------------------------------

    /**
     * Applique à une requête tous les réglages applicables, en ne demandant que ce que le matériel annonce.
     */
    private fun CaptureRequest.Builder.applyCommon(l: LensInfo, s: ManualSettings, shot: ShotSpec?, still: Boolean) {
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // Zoom : ratio natif si disponible (Android 11+), sinon recadrage du capteur.
        val zoom = s.zoom.coerceAtLeast(1f)
        if (Build.VERSION.SDK_INT >= 30 && l.supportsZoomRatio) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(l.minZoom, l.maxZoom))
        } else {
            set(CaptureRequest.SCALER_CROP_REGION, cropFor(l.activeArray, zoom))
        }

        // Exposition : manuelle si ISO ou vitesse imposés, sinon automatique avec compensation.
        val exposureNs = shot?.exposureNs ?: s.shutterNs
        val iso = shot?.iso ?: s.iso
        val manualExposure = (exposureNs != null || iso != null) && l.manualSensor
        if (manualExposure) {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val range = l.exposureRangeNs
            val wanted = exposureNs ?: DEFAULT_SHUTTER_NS
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, range?.let { wanted.coerceIn(it) } ?: wanted)
            val isoRange = l.isoRange
            val value = iso ?: DEFAULT_ISO
            set(CaptureRequest.SENSOR_SENSITIVITY, isoRange?.let { value.coerceIn(it) } ?: value)
        } else {
            // Le mode flash n'est demandé que si le téléphone possède un flash pour cet objectif.
            val aeMode = when {
                !l.hasFlash -> CaptureRequest.CONTROL_AE_MODE_ON
                s.flash == FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                s.flash == FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            }
            set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            val ev = shot?.evCompensation ?: s.evCompensation
            val index = (ev / l.aeCompensationStep).roundToInt().coerceIn(l.aeCompensationRange)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
        }
        if (s.flash == FlashMode.TORCH && l.hasFlash) {
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }

        // Mise au point : manuelle, ou autofocus continu pendant l'aperçu et la prise.
        val focus = shot?.focusDiopters ?: s.focusDiopters
        when {
            l.minFocusDiopters <= 0f -> set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            focus != null -> {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, l.minFocusDiopters))
            }
            else -> set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // Balance des blancs : température de couleur convertie en gains RGGB.
        val kelvin = s.whiteBalanceK
        if (kelvin != null) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, whiteBalanceGains(kelvin))
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        // HDR matériel quand le capteur le propose et que l'exposition n'est pas imposée.
        if (s.hdrScene && l.hasHdrScene && !manualExposure) {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }

        if (l.hasOis && !manualExposure) {
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        if (still) {
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, l.sensorOrientation)
        } else {
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }
    }

    companion object {
        private const val MAX_IMAGES = 12
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        const val DEFAULT_SHUTTER_NS = 1_000_000_000L / 125
        const val DEFAULT_ISO = 100

        /** Recadrage centré du capteur pour un zoom numérique. */
        fun cropFor(array: Rect, zoom: Float): Rect {
            val z = max(1f, zoom)
            val w = (array.width() / z).roundToInt()
            val h = (array.height() / z).roundToInt()
            val left = array.left + (array.width() - w) / 2
            val top = array.top + (array.height() - h) / 2
            return Rect(left, top, left + w, top + h)
        }

        /** Choisit la taille la plus proche de la cible de résolution, à format donné. */
        fun pickSize(sizes: List<Size>, preset: ResolutionPreset, ratio: Float): Size {
            val sameRatio = sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.03f }
            val pool = sameRatio.ifEmpty { sizes }
            if (pool.isEmpty()) return Size(1920, 1080)
            if (preset == ResolutionPreset.MAX || preset.targetMegapixels == Float.MAX_VALUE) {
                return pool.maxBy { it.width * it.height }
            }
            val target = preset.targetMegapixels * 1_000_000f
            return pool.minBy { abs(it.width.toLong() * it.height - target) }
        }

        /** Coordonnées portrait normalisées → coordonnées capteur, selon l'orientation du capteur. */
        fun mapPortraitToSensor(nx: Float, ny: Float, sensorOrientation: Int, back: Boolean): Pair<Float, Float> {
            val x = nx.coerceIn(0f, 1f)
            val y = ny.coerceIn(0f, 1f)
            return if (sensorOrientation == 270 || !back) {
                (1f - y) to x
            } else {
                y to (1f - x)
            }
        }

        /**
         * Gains de balance des blancs (R, G, B) pour une température de couleur donnée.
         * Approximation du corps noir (Tanner Helland) ; le scène est supposée éclairée en 6500 K.
         */
        fun whiteBalanceGains(kelvin: Int): RggbChannelVector {
            val t = blackbodyRgb(kelvin.coerceIn(1900, 12000))
            val gR = (t[1] / t[0]).toFloat().coerceIn(0.5f, 3f)
            val gB = (t[1] / t[2]).toFloat().coerceIn(0.5f, 3f)
            return RggbChannelVector(gR, 1f, 1f, gB)
        }

        private fun blackbodyRgb(kelvin: Int): DoubleArray {
            val temp = kelvin / 100.0
            val r = if (temp <= 66) 255.0 else (329.698727446 * (temp - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            val g = if (temp <= 66) {
                (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
            } else {
                (288.1221695283 * (temp - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            }
            val b = when {
                temp >= 66 -> 255.0
                temp <= 19 -> 0.0
                else -> (138.5177312231 * ln(temp - 10) - 305.0447927307).coerceIn(0.0, 255.0)
            }
            return doubleArrayOf(max(r, 1.0), max(g, 1.0), max(b, 1.0))
        }

        private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
            rewind()
            return ByteArray(remaining()).also { get(it) }
        }
    }
}
