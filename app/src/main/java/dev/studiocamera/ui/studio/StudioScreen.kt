package dev.studiocamera.ui.studio

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.studiocamera.StudioApp
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Grade
import dev.studiocamera.imaging.Plane
import dev.studiocamera.io.Bitmaps
import dev.studiocamera.layers.EditState
import dev.studiocamera.layers.Layer
import dev.studiocamera.layers.LayerAdjust
import dev.studiocamera.layers.LayerBuilder
import dev.studiocamera.layers.LayerProject
import dev.studiocamera.layers.LayerRenderer
import dev.studiocamera.layers.LayerRole
import dev.studiocamera.storage.decodeThumbnail
import dev.studiocamera.ui.MainActivity
import dev.studiocamera.ui.Palette
import dev.studiocamera.ui.ParamSlider
import dev.studiocamera.ui.Screen
import dev.studiocamera.ui.ZoomImageView
import dev.studiocamera.ui.button
import dev.studiocamera.ui.column
import dev.studiocamera.ui.dp
import dev.studiocamera.ui.row
import dev.studiocamera.ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Studio IA : recomposition par couches, étalonnage, comparaison avant/après, historique et versions.
 *
 * Les réglages sont appliqués au rendu en direct ; l'original et le projet ne sont jamais écrasés :
 * chaque sauvegarde crée une nouvelle version.
 */
class StudioScreen(
    private val activity: MainActivity,
    private val app: StudioApp,
    private val scope: CoroutineScope,
    private val photoId: String,
) : Screen {

    private var project: LayerProject? = null
    private var renderer: LayerRenderer? = null
    private var fromStoredProject = false
    private var state = EditState()
    private val history = ArrayList<EditState>()
    private var historyIndex = -1
    private var selectedLayer: Int? = null
    private var showGenerated = false
    private var showOriginal = false
    private var renderJob: Job? = null
    private var loaded = false

    private val preview = ZoomImageView(activity).apply { setBackgroundColor(0xFF000000.toInt()) }
    private val status = activity.text("Chargement…", size = 12f, color = Palette.MUTED)
    private val layerChips = activity.row { setPadding(0, activity.dp(6), 0, activity.dp(4)) }
    private val layerControls = activity.column()
    private val gradeControls = activity.column()
    private val toggles = activity.row()
    private val undoButtons = activity.row()
    private val brightnessLabel = activity.text("", size = 13f, color = Palette.ACCENT, bold = true)
    private val brightnessSlider: ParamSlider = ParamSlider(
        activity, "Luminosité", -1f, 1f, false,
        { v -> brightnessText(v) }, false,
        { v -> if (v != null) applyBrightness(v) },
        { commit() },
    )

    private val screenHeight = activity.resources.displayMetrics.heightPixels
    private val panelHeight = (screenHeight * 0.5f).toInt()

    /** Maintenir le doigt : affiche la photo originale, relâcher pour revenir au rendu. */
    private val compareButton: TextView = activity.button("◐ Maintenir : original") {}.apply {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showOriginal = true
                    showOriginalNow()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showOriginal = false
                    scheduleRender()
                }
            }
            true
        }
    }
    private val layerLegend = activity.text(
        "Pourcentage : part de la photo occupée par chaque couche.",
        size = 12f, color = Palette.MUTED,
    )

    /** Réglages posés sur la moitié basse de l'écran : la photo reste visible au-dessus. */
    private val panel: LinearLayout = activity.column {
        setPadding(activity.dp(14), activity.dp(16), activity.dp(14), activity.dp(16))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xF2000000.toInt()))
        addView(compareButton)
        addView(brightnessLabel)
        addView(brightnessSlider)
        addView(status.apply { setPadding(0, activity.dp(4), 0, 0) })
        addView(layerLegend)
        addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(layerChips)
        })
        addView(layerControls)
        addView(activity.text("Étalonnage de l'image", size = 14f, bold = true).apply { setPadding(0, activity.dp(10), 0, 0) })
        addView(gradeControls)
        addView(toggles)
        addView(activity.row {
            addView(activity.button("Enregistrer une version", active = true) { saveVersion(exportToGallery = false) })
            addView(activity.button("Exporter JPEG") { saveVersion(exportToGallery = true) })
            addView(activity.button("Réinitialiser") { reset() })
        })
    }
    private val panelScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        addView(panel)
    }

    /** Barre du haut : retour, annuler / rétablir (sur fond sombre, sous la barre d'état). */
    private val topBar = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0xB3000000.toInt())
        addView(activity.row {
            setPadding(activity.dp(8), activity.dp(36), activity.dp(8), activity.dp(8))
            addView(activity.button("← Retour") { activity.closeOverlay() })
            addView(undoButtons, LinearLayout.LayoutParams(-2, -2))
        })
    }

    override val view: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(0xFF000000.toInt())
        // La photo est dessinée dans la zone libre au-dessus des réglages.
        preview.setPadding(0, activity.dp(72), 0, panelHeight)
        addView(preview, FrameLayout.LayoutParams(-1, -1))
        addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        addView(panelScroll, FrameLayout.LayoutParams(-1, panelHeight, Gravity.BOTTOM))
    }

    override fun onShow() {
        if (!loaded) {
            loaded = true
            load()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Chargement
    // ---------------------------------------------------------------------------------------------

    private fun load() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadProject() }
            if (result == null) {
                status.text = "Photo introuvable."
                return@launch
            }
            project = result.first
            fromStoredProject = result.third
            renderer = LayerRenderer(result.first)
            state = result.second
            history.clear()
            history.add(state)
            historyIndex = 0
            selectedLayer = if (fromStoredProject) result.first.layers.firstOrNull()?.id else null
            rebuildAll()
            scheduleRender()
        }
    }

    /** Projet enregistré, sinon projet de repli : une couche unique couvrant l'image (réglages globaux). */
    private fun loadProject(): Triple<LayerProject, EditState, Boolean>? {
        val stored = app.library.loadProject(photoId)
        if (stored != null) {
            return Triple(stored, app.library.loadEditState(photoId) ?: EditState(), true)
        }
        val original = app.library.originalFile(photoId).takeIf { it.isFile }?.readBytes() ?: return null
        val base = Bitmaps.decodeLimited(original, app.settings.workingSide())
        val layer = Layer(id = 0, role = LayerRole.BACKGROUND, alpha = Plane.filled(base.width, base.height, 1f), depthMean = 0.5f)
        return Triple(LayerProject(base, listOf(layer), null, subjectDetected = false), EditState(), false)
    }

    private fun rebuildAll() {
        rebuildLayerChips()
        rebuildLayerControls()
        rebuildGradeControls()
        rebuildToggles()
        rebuildUndo()
    }

    // ---------------------------------------------------------------------------------------------
    // Rendu
    // ---------------------------------------------------------------------------------------------

    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = scope.launch {
            delay(30)
            val p = project ?: return@launch
            val r = renderer ?: return@launch
            val snapshot = state
            val (w, h) = LayerRenderer.fitSize(p.width, p.height, LayerRenderer.PREVIEW_SIDE)
            val (bitmap, generated) = withContext(Dispatchers.Default) {
                val out = r.render(snapshot, w, h)
                val bmp = Bitmaps.toBitmap(if (showGenerated) overlayGenerated(out.frame, out.generated) else out.frame)
                bmp to out.generated.mean()
            }
            preview.setImageBitmap(bitmap)
            updateStatus(generated)
        }
    }

    private fun showOriginalNow() {
        val p = project ?: return
        val (w, h) = LayerRenderer.fitSize(p.width, p.height, LayerRenderer.PREVIEW_SIDE)
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { Bitmaps.toBitmap(p.base.resize(w, h)) }
            if (showOriginal) preview.setImageBitmap(bmp)
            status.text = "Original capturé (relâcher pour revenir au rendu)"
        }
    }

    private fun updateStatus(generatedMean: Float) {
        val p = project ?: return
        val base = if (fromStoredProject) "Projet multicouche · ${p.layers.size} couches" else "Réglages globaux (aucune couche enregistrée)"
        status.text = if (app.settings.advancedMode.value) {
            "$base · pixels reconstruits ${"%.2f".format(generatedMean * 100)} % · ${p.width}×${p.height}"
        } else {
            base
        }
    }

    /** Colore en magenta les pixels reconstruits, pour les distinguer des pixels capturés. */
    private fun overlayGenerated(frame: Frame, generated: Plane): Frame {
        val out = IntArray(frame.pixels.size)
        for (i in out.indices) {
            val g = generated.data[i].coerceIn(0f, 1f)
            val p = frame.pixels[i]
            if (g < 0.02f) {
                out[i] = p
                continue
            }
            val a = 0.45f * g
            val r = (((p shr 16) and 0xFF) * (1 - a) + 255 * a).roundToInt()
            val gg = (((p shr 8) and 0xFF) * (1 - a) + 61 * a).roundToInt()
            val b = ((p and 0xFF) * (1 - a) + 165 * a).roundToInt()
            out[i] = Frame.pack(r, gg, b)
        }
        return Frame(frame.width, frame.height, out)
    }

    // ---------------------------------------------------------------------------------------------
    // Historique
    // ---------------------------------------------------------------------------------------------

    private fun commit() {
        if (history.getOrNull(historyIndex) == state) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(state)
        historyIndex = history.lastIndex
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
            historyIndex--
        }
        persistState()
        rebuildUndo()
    }

    private fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        state = history[historyIndex]
        afterHistoryJump()
    }

    private fun redo() {
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        state = history[historyIndex]
        afterHistoryJump()
    }

    private fun afterHistoryJump() {
        persistState()
        rebuildLayerChips()
        rebuildLayerControls()
        rebuildGradeControls()
        rebuildToggles()
        rebuildUndo()
        scheduleRender()
    }

    private fun reset() {
        state = EditState()
        commit()
        afterHistoryJump()
    }

    private fun persistState() {
        if (!fromStoredProject) return
        val snapshot = state
        scope.launch(Dispatchers.IO) { app.library.saveEditState(photoId, snapshot) }
    }

    private fun rebuildUndo() {
        undoButtons.removeAllViews()
        undoButtons.addView(activity.button("↶ Annuler", active = false) { undo() })
        undoButtons.addView(activity.button("↷ Rétablir", active = false) { redo() })
        undoButtons.addView(activity.text(
            "Historique ${historyIndex + 1}/${history.size}",
            size = 12f,
            color = Palette.MUTED,
        ).apply { setPadding(activity.dp(8), 0, 0, 0) })
    }

    // ---------------------------------------------------------------------------------------------
    // Couches
    // ---------------------------------------------------------------------------------------------

    private fun rebuildLayerChips() {
        val p = project ?: return
        layerChips.removeAllViews()
        layerChips.visibility = if (fromStoredProject) View.VISIBLE else View.GONE
        layerLegend.visibility = layerChips.visibility
        p.layers.forEach { layer ->
            val selected = layer.id == selectedLayer
            val visible = state.isVisible(layer.id)
            val label = "${if (visible) "◉" else "○"} ${layer.role.label} ${(layer.coverage * 100).roundToInt()}%"
            layerChips.addView(activity.button(label, active = selected) {
                selectedLayer = layer.id
                rebuildLayerChips()
                rebuildLayerControls()
            })
        }
    }

    /** Couche visée par la barre de luminosité : la couche sélectionnée, ou null pour l'image entière. */
    private fun brightnessTarget(): Int? {
        val id = selectedLayer ?: return null
        return project?.layers?.firstOrNull { it.id == id }?.id
    }

    private fun brightnessText(v: Float): String =
        if (brightnessTarget() != null) "%+d %%".format((v * 100).roundToInt()) else "%+.1f IL".format(v * 2f)

    private fun applyBrightness(v: Float) {
        val id = brightnessTarget()
        state = if (id != null) {
            state.withAdjust(id, state.adjustFor(id).copy(brightness = v))
        } else {
            state.withGrade(state.grade.copy(exposureEv = v * 2f))
        }
        scheduleRender()
    }

    /** Met la barre de luminosité en accord avec la couche sélectionnée et l'état courant. */
    private fun refreshBrightness() {
        val id = brightnessTarget()
        brightnessLabel.text = if (id != null) {
            "Luminosité · ${project?.layers?.firstOrNull { it.id == id }?.role?.label ?: ""}"
        } else {
            "Luminosité · image entière"
        }
        brightnessSlider.setValue(if (id != null) state.adjustFor(id).brightness else state.grade.exposureEv / 2f)
    }

    private fun rebuildLayerControls() {
        refreshBrightness()
        layerControls.removeAllViews()
        if (!fromStoredProject) {
            layerControls.addView(activity.text(
                "Cette photo n'a pas de couches à déplacer : les réglages globaux sont ci-dessous.",
                size = 13f, color = Palette.MUTED,
            ))
            return
        }
        val p = project ?: return
        val layer = p.layers.firstOrNull { it.id == selectedLayer } ?: run {
            layerControls.addView(activity.text("Sélectionnez une couche pour régler son zoom, sa position et son étalonnage.",
                size = 12f, color = Palette.MUTED))
            return
        }
        val id = layer.id
        val a = state.adjustFor(id)
        val visible = state.isVisible(id)
        layerControls.addView(activity.row {
            addView(activity.text("Réglages : ${layer.role.label}", size = 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.button(if (visible) "Masquer la couche" else "Afficher la couche") {
                state = state.withVisible(id, !visible)
                commit()
                rebuildLayerChips()
                rebuildLayerControls()
                scheduleRender()
            })
        })
        fun layerSlider(title: String, min: Float, max: Float, value: Float, fmt: (Float) -> String, apply: (LayerAdjust, Float) -> LayerAdjust) {
            val slider = ParamSlider(activity, title, min, max, false, fmt, false, { v ->
                if (v == null) return@ParamSlider
                val current = state.adjustFor(id)
                state = state.withAdjust(id, apply(current, v))
                scheduleRender()
            }, { commit() }, if (title == "Zoom") 1f else 0f)
            slider.setValue(value)
            layerControls.addView(slider)
        }
        layerSlider("Zoom", 0.5f, 2f, a.zoom, { "${(it * 100).roundToInt()} %" }) { adj, v -> adj.copy(zoom = v) }
        layerSlider("Position horizontale", -0.5f, 0.5f, a.offsetX, { "%+d %%".format((it * 100).roundToInt()) }) { adj, v -> adj.copy(offsetX = v) }
        layerSlider("Position verticale", -0.5f, 0.5f, a.offsetY, { "%+d %%".format((it * 100).roundToInt()) }) { adj, v -> adj.copy(offsetY = v) }
        layerSlider("Perspective", -0.5f, 0.5f, a.perspective, { "%+d".format((it * 100).roundToInt()) }) { adj, v -> adj.copy(perspective = v) }
        layerSlider("Contraste", -1f, 1f, a.contrast, { "%+d".format((it * 100).roundToInt()) }) { adj, v -> adj.copy(contrast = v) }
        layerSlider("Saturation", -1f, 1f, a.saturation, { "%+d".format((it * 100).roundToInt()) }) { adj, v -> adj.copy(saturation = v) }
        layerSlider("Netteté", 0f, 1f, a.sharpness, { "${(it * 100).roundToInt()}" }) { adj, v -> adj.copy(sharpness = v) }
        layerSlider("Flou", 0f, 1f, a.blur, { "${(it * 100).roundToInt()}" }) { adj, v -> adj.copy(blur = v) }
    }

    private fun rebuildToggles() {
        toggles.removeAllViews()
        toggles.addView(activity.button(if (showGenerated) "Pixels reconstruits : visibles" else "Afficher les pixels reconstruits", active = showGenerated) {
            showGenerated = !showGenerated
            rebuildToggles()
            scheduleRender()
        })
    }

    // ---------------------------------------------------------------------------------------------
    // Étalonnage global
    // ---------------------------------------------------------------------------------------------

    private fun rebuildGradeControls() {
        gradeControls.removeAllViews()
        fun gradeSlider(title: String, min: Float, max: Float, value: Float, apply: (Grade, Float) -> Grade) {
            val slider = ParamSlider(activity, title, min, max, false, { "%+.2f".format(it) }, false, { v ->
                if (v == null) return@ParamSlider
                state = state.withGrade(apply(state.grade, v))
                scheduleRender()
            }, { commit() }, 0f)
            slider.setValue(value)
            gradeControls.addView(slider)
        }
        val g = state.grade
        gradeSlider("Exposition (IL)", -2f, 2f, g.exposureEv) { grade, v -> grade.copy(exposureEv = v) }
        gradeSlider("Contraste", -1f, 1f, g.contrast) { grade, v -> grade.copy(contrast = v) }
        gradeSlider("Saturation", -1f, 1f, g.saturation) { grade, v -> grade.copy(saturation = v) }
        gradeSlider("Température", -1f, 1f, g.warmth) { grade, v -> grade.copy(warmth = v) }
        gradeSlider("Ombres", -1f, 1f, g.shadows) { grade, v -> grade.copy(shadows = v) }
        gradeSlider("Hautes lumières", -1f, 1f, g.highlights) { grade, v -> grade.copy(highlights = v) }
        gradeSlider("Rendu pellicule", 0f, 1f, g.fade) { grade, v -> grade.copy(fade = v) }
        gradeSlider("Vignettage", 0f, 1f, g.vignette) { grade, v -> grade.copy(vignette = v) }
    }

    // ---------------------------------------------------------------------------------------------
    // Versions et export (pleine résolution de travail, jamais d'écrasement)
    // ---------------------------------------------------------------------------------------------

    private fun saveVersion(exportToGallery: Boolean) {
        val p = project ?: return
        val snapshot = state
        status.text = "Rendu en cours…"
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                val out = LayerRenderer(p).render(snapshot, p.width, p.height)
                Bitmaps.encodeJpeg(out.frame, 94)
            }
            val label = "Studio ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())}"
            val version = withContext(Dispatchers.IO) {
                if (fromStoredProject) {
                    app.library.saveEditState(photoId, snapshot)
                }
                app.library.addVersion(photoId, bytes, label, "studio")
            }
            if (exportToGallery) {
                val uri = withContext(Dispatchers.IO) {
                    app.library.exportToGallery(bytes, "${photoId}-${version.id}.jpg")
                }
                activity.toast(if (uri != null) "Version exportée dans Pictures/StudioCamera" else "Export impossible")
            } else {
                activity.toast("Version « $label » enregistrée")
            }
            status.text = "Version enregistrée · ${p.width}×${p.height}"
        }
    }

    override fun onHide() {
        renderJob?.cancel()
    }

    override fun onBack(): Boolean = false

    companion object {
        private const val MAX_HISTORY = 80
    }
}
