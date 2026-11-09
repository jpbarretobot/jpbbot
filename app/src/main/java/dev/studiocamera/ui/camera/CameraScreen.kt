package dev.studiocamera.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.studiocamera.StudioApp
import dev.studiocamera.ai.AiServices
import dev.studiocamera.camera.CapabilityProbe
import dev.studiocamera.camera.CaptureFormat
import dev.studiocamera.camera.DeviceCapabilities
import dev.studiocamera.camera.EngineState
import dev.studiocamera.camera.FlashMode
import dev.studiocamera.camera.LensInfo
import dev.studiocamera.camera.LiveReadout
import dev.studiocamera.camera.ManualSettings
import dev.studiocamera.camera.ResolutionPreset
import dev.studiocamera.camera.ShotMeta
import dev.studiocamera.capture.Availability
import dev.studiocamera.capture.CaptureOrchestrator
import dev.studiocamera.capture.Compatibility
import dev.studiocamera.capture.Family
import dev.studiocamera.capture.Style
import dev.studiocamera.storage.decodeThumbnail
import dev.studiocamera.ui.FocusRing
import dev.studiocamera.ui.GridOverlay
import dev.studiocamera.ui.MainActivity
import dev.studiocamera.ui.Palette
import dev.studiocamera.ui.ParamSlider
import dev.studiocamera.ui.Screen
import dev.studiocamera.ui.add
import dev.studiocamera.ui.button
import dev.studiocamera.ui.column
import dev.studiocamera.ui.dp
import dev.studiocamera.ui.pill
import dev.studiocamera.ui.row
import dev.studiocamera.ui.text
import dev.studiocamera.work.JobStatus
import dev.studiocamera.work.ProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Viseur : aperçu temps réel, contrôles manuels, objectifs, styles, minuteur et déclenchement.
 * La prise est écrite sur disque immédiatement ; le traitement se fait ensuite en arrière-plan.
 */
class CameraScreen(
    private val activity: MainActivity,
    private val app: StudioApp,
    private val scope: CoroutineScope,
) : Screen {

    private val engine = app.camera
    private val orchestrator = CaptureOrchestrator(engine)
    private val caps: DeviceCapabilities = CapabilityProbe.probe(activity)

    private var lens: LensInfo? = caps.mainBack ?: caps.frontLenses.firstOrNull()
    private var family: Family = app.settings.lastFamily?.let { runCatching { Family.valueOf(it) }.getOrNull() } ?: Family.PRO
    private var style: Style = app.settings.lastStyleId?.let { app.catalog.byId(it) } ?: app.catalog.default
    private var cfg = ManualSettings()
    private var manualMode = false
    private var timerSeconds = 0
    private var busy = false
    private var surface: Surface? = null
    private var previewStarted = false
    private var focusRingJob: Job? = null

    // Éléments d'interface. Déclarés avant l'initialisation : leur ordre compte.
    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            if (activity.hasCameraPermission()) startPreview(st)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = configureTransform()

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            previewStarted = false
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    private val preview: TextureView = TextureView(activity).apply {
        surfaceTextureListener = previewListener
        // La rotation n'a d'effet qu'une fois la vue dimensionnée : on la réapplique à chaque mise en page.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> configureTransform() }
        setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val nx = event.x / v.width
                val ny = event.y / v.height
                showFocusRing(event.x, event.y)
                scope.launch { engine.focusAt(nx, ny) }
                v.performClick()
            }
            true
        }
    }
    private val gridOverlay = GridOverlay(activity)
    private val focusRing = FocusRing(activity).apply { visibility = View.GONE }
    private val countdownView = activity.text("", size = 56f, bold = true, gravity = Gravity.CENTER).apply {
        visibility = View.GONE
    }
    private val readoutView = activity.text("Aperçu", size = 11f).apply {
        setBackgroundColor(0x66000000)
        setPadding(activity.dp(6), activity.dp(3), activity.dp(6), activity.dp(3))
    }
    private val previewBox = FrameLayout(activity).apply {
        setBackgroundColor(Palette.BG)
        addView(preview, FrameLayout.LayoutParams(-1, -1))
        addView(gridOverlay, FrameLayout.LayoutParams(-1, -1))
        addView(focusRing, FrameLayout.LayoutParams(activity.dp(72), activity.dp(72)))
        addView(countdownView, FrameLayout.LayoutParams(-1, -1))
        addView(readoutView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(activity.dp(8), 0, activity.dp(8), activity.dp(44))
        })
    }
    private val lensRow = activity.row { setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4)) }
    private val permissionView = activity.column {
        setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
        addView(activity.text("L'accès à la caméra est nécessaire pour le viseur.", size = 15f, gravity = Gravity.CENTER))
        addView(activity.button("Autoriser la caméra") { activity.requestCameraPermission() })
        visibility = View.GONE
    }
    private val topBar = activity.row { setPadding(activity.dp(4), activity.dp(6), activity.dp(4), activity.dp(6)) }
    private val styleTitle = activity.text("", size = 17f, bold = true)
    private val styleDescription = activity.text("", size = 13f, color = Palette.MUTED)
    private val styleAvailability = activity.text("", size = 12f)
    private val familyRow = activity.row()
    private val styleStrip = activity.row()
    private val shutterButton = activity.text("", size = 13f, gravity = Gravity.CENTER).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.TEXT)
            setStroke(activity.dp(4), Palette.ACCENT)
        }
        layoutParams = LinearLayout.LayoutParams(activity.dp(78), activity.dp(78))
        setOnClickListener { onShutter() }
    }
    private val thumbnail = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = pill(Palette.SURFACE_2)
        clipToOutline = true
        setOnClickListener { activity.openGallery() }
        layoutParams = LinearLayout.LayoutParams(activity.dp(52), activity.dp(52))
    }
    private val jobsView = activity.text("", size = 12f, color = Palette.MUTED).apply {
        setOnClickListener { activity.openGallery() }
    }

    private val isoSlider = slider("ISO", 50f, 3200f, log = true, auto = true, fmt = { "%.0f".format(it) }) { v ->
        cfg = cfg.copy(iso = v?.roundToInt())
    }
    private val shutterSlider = slider("Vitesse", 1f / 8000f, 1f, log = true, auto = true, fmt = { formatShutter(it) }) { v ->
        cfg = cfg.copy(shutterNs = v?.let { (it * 1e9f).toLong() })
    }
    private val focusSlider = slider("Mise au point", 0f, 10f, log = false, auto = true, fmt = { formatFocus(it) }) { v ->
        cfg = cfg.copy(focusDiopters = v)
    }
    private val wbSlider = slider("Balance des blancs", 2500f, 9000f, log = false, auto = true, fmt = { "${it.roundToInt()} K" }) { v ->
        cfg = cfg.copy(whiteBalanceK = v?.roundToInt())
    }
    private val evSlider = slider("Compensation", -3f, 3f, log = false, auto = false, fmt = { "%+.1f IL".format(it) }) { v ->
        cfg = cfg.copy(evCompensation = v ?: 0f)
    }
    private val manualPanel = activity.column {
        setPadding(activity.dp(4), 0, activity.dp(4), 0)
        addView(isoSlider)
        addView(shutterSlider)
        addView(focusSlider)
        addView(wbSlider)
        addView(evSlider)
    }

    override val view: View = ScrollView(activity).apply {
        setBackgroundColor(Palette.BG)
        isFillViewport = true
        addView(activity.column {
            setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(10))
            addView(topBar)
            addView(previewBox, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.widthPixels * 4f / 3f).roundToInt()))
            addView(lensRow)
            addView(permissionView)
            addView(manualPanel)
            addView(familyRow)
            addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(styleStrip)
            })
            addView(styleTitle.apply { setPadding(activity.dp(4), activity.dp(8), 0, 0) })
            addView(styleDescription.apply { setPadding(activity.dp(4), 0, activity.dp(4), 0) })
            addView(styleAvailability.apply { setPadding(activity.dp(4), activity.dp(2), 0, activity.dp(10)) })
            addView(activity.row {
                gravity = Gravity.CENTER
                add(thumbnail)
                add(shutterButton)
                add(jobsView, width = 0, weight = 1f)
            })
        }, ViewGroup.LayoutParams(-1, -2))
    }

    init {
        evSlider.setValue(0f)
        lens?.let { applyLensLimits(it) }
        buildTopBar()
        buildFamilyRow()
        refreshLensRow()
        refreshStyleUi()

        scope.launch { engine.readout.collect { r -> readoutView.text = formatReadout(r) } }
        scope.launch {
            engine.state.collect { s -> if (s is EngineState.Failed) activity.toast("Caméra : ${s.message}") }
        }
        scope.launch { app.jobs.statuses.collect { renderJobs(it) } }
        scope.launch(Dispatchers.IO) {
            val last = app.library.list().firstOrNull()
            val bitmap = last?.let { app.library.originalFile(it.id) }?.takeIf { it.isFile }?.let { decodeThumbnail(it.readBytes(), 160) }
            if (bitmap != null) scope.launch { thumbnail.setImageBitmap(bitmap) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cycle de vie
    // ---------------------------------------------------------------------------------------------

    override fun onShow() {
        // Les réglages de format et de résolution peuvent avoir été changés dans les Paramètres.
        cfg = cfg.copy(format = app.settings.format.value, resolution = app.settings.resolution.value)
        buildTopBar()
        if (!activity.hasCameraPermission()) {
            permissionView.visibility = View.VISIBLE
            return
        }
        permissionView.visibility = View.GONE
        if (preview.isAvailable && !previewStarted) preview.surfaceTexture?.let { startPreview(it) }
    }

    override fun onHide() {
        previewStarted = false
        surface?.release()
        surface = null
        scope.launch { engine.stop() }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) onShow()
    }

    // ---------------------------------------------------------------------------------------------
    // Aperçu
    // ---------------------------------------------------------------------------------------------

    private fun startPreview(st: SurfaceTexture) {
        val l = lens ?: return
        if (previewStarted) return
        previewStarted = true
        val size = engine.previewSizeFor(l)
        st.setDefaultBufferSize(size.width, size.height)
        configureTransform()
        val s = Surface(st)
        surface = s
        scope.launch {
            try {
                engine.start(l, s, cfg)
            } catch (e: Exception) {
                previewStarted = false
                activity.toast("Caméra indisponible : ${e.message}")
            }
        }
    }

    /**
     * Oriente l'aperçu : le capteur produit des images en paysage, l'écran est en portrait.
     * On tourne le tampon selon l'orientation du capteur, puis on le met à l'échelle pour couvrir la vue.
     */
    private fun configureTransform() {
        val l = lens ?: return
        val viewW = preview.width.toFloat()
        val viewH = preview.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val size = engine.previewSizeFor(l)
        val bw = size.width.toFloat()
        val bh = size.height.toFloat()
        val m = Matrix()
        m.postTranslate(-bw / 2f, -bh / 2f)
        // Le haut de la scène apparaissait à droite : la rotation n'était pas appliquée à l'aperçu.
        // Tourner l'image de 90° vers la gauche correspond à 360° - orientation du capteur (270° sur S22).
        val rotation = ((360 - l.sensorOrientation) % 360).toFloat()
        val swapped = rotation == 90f || rotation == 270f
        val dw = if (swapped) bh else bw
        val dh = if (swapped) bw else bh
        m.postRotate(rotation)
        val scale = maxOf(viewW / dw, viewH / dh)
        m.postScale(scale, scale)
        m.postTranslate(viewW / 2f, viewH / 2f)
        preview.setTransform(m)
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRing.visibility = View.VISIBLE
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRingJob?.cancel()
        focusRingJob = scope.launch {
            delay(900)
            focusRing.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Curseurs
    // ---------------------------------------------------------------------------------------------

    private fun slider(
        title: String,
        min: Float,
        max: Float,
        log: Boolean,
        auto: Boolean,
        fmt: (Float) -> String,
        onChange: (Float?) -> Unit,
    ): ParamSlider = ParamSlider(activity, title, min, max, log, fmt, auto, { v ->
        onChange(v)
        pushSettings()
    })

    /** Adapte les curseurs aux plages réelles de l'objectif sélectionné. */
    private fun applyLensLimits(l: LensInfo) {
        l.isoRange?.let { isoSlider.setRange(it.first.toFloat(), it.last.toFloat()) }
        l.exposureRangeNs?.let { shutterSlider.setRange(it.first / 1e9f, it.last / 1e9f) }
        if (l.minFocusDiopters > 0f) focusSlider.setRange(0f, l.minFocusDiopters)
    }

    private fun updateManualVisibility() {
        val v = if (manualMode) View.VISIBLE else View.GONE
        isoSlider.visibility = v
        shutterSlider.visibility = v
        wbSlider.visibility = v
        focusSlider.visibility = if (manualMode && (lens?.minFocusDiopters ?: 0f) > 0f) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------------
    // Barre supérieure
    // ---------------------------------------------------------------------------------------------

    private fun buildTopBar() {
        topBar.removeAllViews()
        topBar.addView(activity.button(flashShort(cfg.flash), active = cfg.flash != FlashMode.OFF) {
            cfg = cfg.copy(flash = FlashMode.values()[(cfg.flash.ordinal + 1) % FlashMode.values().size])
            buildTopBar()
            pushSettings()
        })
        topBar.addView(activity.button(if (timerSeconds == 0) "⏱ Off" else "⏱ ${timerSeconds}s", active = timerSeconds > 0) {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            buildTopBar()
        })
        val gridOn = app.settings.gridVisible.value
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
        topBar.addView(activity.button("▦", active = gridOn) {
            app.settings.setGridVisible(!gridOn)
            buildTopBar()
        })
        val hdrAvailable = lens?.hasHdrScene == true
        topBar.addView(activity.button("HDR", active = cfg.hdrScene) {
            if (!hdrAvailable) activity.toast("HDR matériel indisponible : le style HDR utilise la fusion logicielle.")
            cfg = cfg.copy(hdrScene = !cfg.hdrScene && hdrAvailable)
            buildTopBar()
            pushSettings()
        })
        val rawOk = caps.supportsRaw
        topBar.addView(activity.button(if (cfg.format == CaptureFormat.JPEG_DNG) "DNG" else "JPEG", active = cfg.format == CaptureFormat.JPEG_DNG) {
            if (!rawOk) activity.toast("Capture RAW non disponible sur cet appareil.")
            cfg = cfg.copy(format = if (cfg.format == CaptureFormat.JPEG && rawOk) CaptureFormat.JPEG_DNG else CaptureFormat.JPEG)
            buildTopBar()
            pushSettings()
        })
        val resLabel = when (cfg.resolution) {
            ResolutionPreset.MAX -> "Max"
            ResolutionPreset.BALANCED -> "12 MP"
            ResolutionPreset.LIGHT -> "5 MP"
        }
        topBar.addView(activity.button(resLabel) {
            val all = ResolutionPreset.values()
            cfg = cfg.copy(resolution = all[(cfg.resolution.ordinal + 1) % all.size])
            buildTopBar()
            pushSettings()
        })
        topBar.addView(activity.button(if (manualMode) "Manuel" else "Auto", active = manualMode) {
            manualMode = !manualMode
            if (!manualMode) {
                cfg = cfg.copy(iso = null, shutterNs = null, focusDiopters = null, whiteBalanceK = null, evCompensation = 0f)
                listOf(isoSlider, shutterSlider, focusSlider, wbSlider).forEach { it.setValue(null) }
                evSlider.setValue(0f)
            }
            updateManualVisibility()
            buildTopBar()
            pushSettings()
        })
        updateManualVisibility()
    }

    private fun flashShort(mode: FlashMode) = when (mode) {
        FlashMode.OFF -> "⚡ Off"
        FlashMode.AUTO -> "⚡ Auto"
        FlashMode.ON -> "⚡ On"
        FlashMode.TORCH -> "🔦"
    }

    // ---------------------------------------------------------------------------------------------
    // Objectifs et zoom numérique
    // ---------------------------------------------------------------------------------------------

    private fun refreshLensRow() {
        lensRow.removeAllViews()
        val main = caps.mainBack
        caps.backLenses.forEach { l ->
            val factor = if (main != null && main.focalEquivalent35mm > 0f && l.focalEquivalent35mm > 0f) {
                l.focalEquivalent35mm / main.focalEquivalent35mm
            } else 1f
            val label = "%.1f×".format(factor).replace(".0×", "×")
            lensRow.addView(activity.button(label, active = lens?.id == l.id) { selectLens(l) })
        }
        caps.frontLenses.firstOrNull()?.let { front ->
            lensRow.addView(activity.button("Avant", active = lens?.id == front.id) { selectLens(front) })
        }
        if (lens?.isBack == true) {
            listOf(1f, 2f, 4f).forEach { z ->
                lensRow.addView(activity.button("${z.toInt()}× num.", active = cfg.zoom == z) {
                    cfg = cfg.copy(zoom = z)
                    refreshLensRow()
                    pushSettings()
                })
            }
        }
    }

    private fun selectLens(l: LensInfo) {
        if (lens?.id == l.id) return
        lens = l
        onHide()
        applyLensLimits(l)
        updateManualVisibility()
        refreshLensRow()
        onShow()
    }

    // ---------------------------------------------------------------------------------------------
    // Styles
    // ---------------------------------------------------------------------------------------------

    private fun buildFamilyRow() {
        familyRow.removeAllViews()
        Family.values().forEach { f ->
            familyRow.addView(activity.button(f.label, active = f == family) {
                family = f
                app.settings.lastFamily = f.name
                buildFamilyRow()
                refreshStyleUi()
            })
        }
    }

    private fun refreshStyleUi() {
        styleStrip.removeAllViews()
        app.catalog.byFamily(family).forEach { s ->
            val avail = availability(s)
            val selected = s.id == style.id
            val chip = activity.text("${s.glyph}  ${s.title}", size = 13f,
                color = if (selected) Palette.BG else Palette.TEXT, bold = selected, gravity = Gravity.CENTER).apply {
                background = pill(if (selected) Palette.ACCENT else Palette.SURFACE_2, stroke = dotColor(avail))
                setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                setOnClickListener { selectStyle(s) }
            }
            styleStrip.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4)) })
        }
        styleTitle.text = "${style.glyph}  ${style.title}"
        styleDescription.text = style.description
        val avail = availability(style)
        styleAvailability.text = when (avail) {
            Availability.Ready -> "● Disponible sur cet appareil"
            is Availability.Degraded -> "● Repli : ${avail.reason}"
            is Availability.Unavailable -> "● Indisponible : ${avail.reason}"
        }
        styleAvailability.setTextColor(dotColor(avail))
    }

    private fun selectStyle(s: Style) {
        style = s
        app.settings.lastStyleId = s.id
        refreshStyleUi()
    }

    private fun availability(s: Style): Availability =
        Compatibility.evaluate(s, caps, app.ai.hasImportedModel(AiServices.ModelKind.DEPTH))

    private fun dotColor(a: Availability): Int = when (a) {
        Availability.Ready -> Palette.OK
        is Availability.Degraded -> Palette.WARN
        is Availability.Unavailable -> Palette.BAD
    }

    // ---------------------------------------------------------------------------------------------
    // Déclenchement
    // ---------------------------------------------------------------------------------------------

    private fun onShutter() {
        if (busy) return
        val l = lens ?: run {
            activity.toast("Aucun objectif disponible.")
            return
        }
        val avail = availability(style)
        if (avail is Availability.Unavailable) {
            activity.toast(avail.reason)
            return
        }
        busy = true
        shutterButton.alpha = 0.5f
        scope.launch {
            try {
                if (timerSeconds > 0) {
                    countdownView.visibility = View.VISIBLE
                    for (s in timerSeconds downTo 1) {
                        countdownView.text = s.toString()
                        delay(1000)
                    }
                    countdownView.visibility = View.GONE
                }
                val plan = orchestrator.plan(style, l)
                val shots = mutableListOf<ByteArray>()
                val raws = mutableListOf<ByteArray?>()
                val metas = mutableListOf<ShotMeta?>()
                orchestrator.shoot(plan, cfg) { shot ->
                    shots += shot.jpeg
                    raws += shot.dng
                    metas += shot.meta
                }
                check(shots.isNotEmpty()) { "aucune prise reçue" }
                val notes = plan.notes + listOfNotNull((avail as? Availability.Degraded)?.let { "Repli matériel : ${it.reason}" })
                app.jobs.write(style.id, style.title, l.label, notes, shots, raws, metas)
                ProcessingService.start(activity)
                activity.toast("Photo capturée : traitement en arrière-plan")
            } catch (e: Exception) {
                activity.toast("Échec de la prise : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                shutterButton.alpha = 1f
            }
        }
    }

    private fun pushSettings() {
        scope.launch { engine.updateSettings(cfg) }
    }

    private fun renderJobs(list: List<JobStatus>) {
        val active = list.count { it.state == JobStatus.State.RUNNING || it.state == JobStatus.State.QUEUED }
        jobsView.text = if (active > 0) {
            "Traitement : $active en cours\nLe viseur reste disponible"
        } else {
            "Toucher pour ouvrir la galerie"
        }
    }

    private fun formatReadout(r: LiveReadout): String {
        val iso = r.iso?.let { "ISO $it" } ?: "ISO —"
        val exp = r.exposureNs?.let { formatShutter(it / 1e9f) } ?: "—"
        val focus = r.focusDiopters?.let { formatFocus(it) } ?: "—"
        return "$iso · $exp · MAP $focus${if (r.afLocked) " ✓" else ""}"
    }

    companion object {
        fun formatShutter(seconds: Float): String =
            if (seconds >= 1f) "%.1f s".format(seconds) else "1/${(1f / seconds).roundToInt()}"

        fun formatFocus(diopters: Float): String =
            if (diopters <= 0.01f) "∞" else "%.0f cm".format(100f / diopters)
    }
}
