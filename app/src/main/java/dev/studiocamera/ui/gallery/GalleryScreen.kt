package dev.studiocamera.ui.gallery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.studiocamera.StudioApp
import dev.studiocamera.capture.Family
import dev.studiocamera.capture.StyleCatalog
import dev.studiocamera.storage.PhotoRecord
import dev.studiocamera.storage.decodeThumbnail
import dev.studiocamera.ui.MainActivity
import dev.studiocamera.ui.Palette
import dev.studiocamera.ui.Screen
import dev.studiocamera.ui.button
import dev.studiocamera.ui.column
import dev.studiocamera.ui.dp
import dev.studiocamera.ui.pill
import dev.studiocamera.ui.row
import dev.studiocamera.ui.text
import dev.studiocamera.work.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Galerie interne, organisée par date et par album (famille de styles).
 * Les travaux en cours apparaissent en tête, avec leur état.
 */
class GalleryScreen(
    private val activity: MainActivity,
    private val app: StudioApp,
    private val scope: CoroutineScope,
    private val filter: Filter,
    private val onPick: (PhotoRecord) -> Unit,
) : Screen {

    enum class Filter { ALL, PROJECTS }

    private var album: Family? = null
    private val selected = mutableSetOf<String>()

    private fun toggle(id: String) {
        if (!selected.add(id)) selected.remove(id)
        render()
    }
    private val thumbs = object : LruCache<String, Bitmap>(120) {}
    private val list = activity.column { setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(16)) }

    /** Recherche par style ou par titre de famille. */
    private val search = android.widget.EditText(activity).apply {
        hint = "Rechercher un style"
        setSingleLine(true)
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.MUTED)
        background = pill(Palette.SURFACE_2)
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = render()
        })
    }

    override val view: ScrollView = ScrollView(activity).apply {
        setBackgroundColor(Palette.BG)
        addView(list, FrameLayout.LayoutParams(-1, -2))
    }

    init {
        scope.launch { app.jobs.statuses.collect { render() } }
    }

    override fun onShow() = render()

    private fun render() {
        list.removeAllViews()
        list.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, activity.dp(8), 0, activity.dp(4)) })
        if (selected.isNotEmpty()) {
            list.addView(activity.row {
                addView(activity.text("${selected.size} sélectionnée(s)", size = 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(activity.button("Exporter") { exportSelected() })
                addView(activity.button("Supprimer") { confirmDeleteSelected() })
                addView(activity.button("Annuler") { selected.clear(); render() })
            })
        }
        val query = search.text.toString().trim().lowercase()
        val title = if (filter == Filter.PROJECTS) "Studio IA" else "Galerie"
        list.addView(activity.text(title, size = 24f, bold = true).apply { setPadding(0, activity.dp(4), 0, activity.dp(2)) })

        val photos = app.library.list().filter { filter == Filter.ALL || it.hasProject }
        val albumFilter = if (filter == Filter.ALL) album else null
        val visible = photos
            .filter { albumFilter == null || it.family == albumFilter.name }
            .filter { query.isEmpty() || it.styleTitle.lowercase().contains(query) }

        list.addView(activity.text(
            if (filter == Filter.PROJECTS) "${visible.size} projet(s) multicouche(s) — touchez pour ouvrir dans le Studio"
            else "${visible.size} photo(s), stockées sur cet appareil. Appui long pour sélectionner.",
            size = 12f, color = Palette.MUTED,
        ))

        if (filter == Filter.ALL) {
            list.addView(activity.row {
                setPadding(0, activity.dp(8), 0, activity.dp(4))
                addView(activity.button("Toutes", active = album == null) { album = null; render() })
                Family.values().forEach { f ->
                    addView(activity.button(f.label, active = album == f) { album = f; render() })
                }
            })
        }

        val jobs = app.jobs.statuses.value.filter { it.state != JobStatus.State.DONE }
        if (jobs.isNotEmpty()) {
            list.addView(activity.text("Traitements", size = 14f, bold = true).apply { setPadding(0, activity.dp(12), 0, activity.dp(4)) })
            jobs.forEach { job ->
                val label = when (job.state) {
                    JobStatus.State.QUEUED -> "En attente"
                    JobStatus.State.RUNNING -> "En cours"
                    JobStatus.State.FAILED -> "Échec"
                    JobStatus.State.DONE -> "Terminé"
                }
                val color = if (job.state == JobStatus.State.FAILED) Palette.BAD else Palette.ACCENT
                list.addView(activity.text("● ${job.styleTitle} — $label${if (job.message.isNotEmpty()) " · ${job.message}" else ""}",
                    size = 13f, color = color).apply { setPadding(0, activity.dp(2), 0, activity.dp(2)) })
            }
        }

        if (visible.isEmpty()) {
            list.addView(activity.text(
                "Aucune photo pour l'instant. Capturez depuis l'onglet Appareil photo.",
                size = 14f, color = Palette.MUTED, gravity = Gravity.CENTER,
            ).apply { setPadding(0, activity.dp(48), 0, 0) })
            return
        }

        var lastDay: LocalDate? = null
        var rowContainer: LinearLayout? = null
        visible.forEachIndexed { index, record ->
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(record.createdAt), ZoneId.systemDefault())
            if (day != lastDay) {
                lastDay = day
                list.addView(activity.text(dayLabel(day), size = 13f, color = Palette.ACCENT, bold = true)
                    .apply { setPadding(0, activity.dp(14), 0, activity.dp(6)) })
                rowContainer = null
            }
            if (index % COLUMNS == 0 || rowContainer == null) {
                rowContainer = activity.row().also { list.addView(it) }
            }
            rowContainer!!.addView(tile(record), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
            })
        }
    }

    private fun tile(record: PhotoRecord): View {
        val size = (activity.resources.displayMetrics.widthPixels - activity.dp(24)) / COLUMNS
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.SURFACE_2)
            layoutParams = FrameLayout.LayoutParams(-1, size)
        }
        val glyph = StyleCatalog.byId(record.styleId)?.glyph ?: "◻"
        val badge = activity.text(if (record.hasProject) "$glyph ◧" else glyph, size = 11f, gravity = Gravity.CENTER).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(activity.dp(5), activity.dp(2), activity.dp(5), activity.dp(2))
        }
        loadThumb(record.id, image)
        val check = activity.text("✓", size = 14f, color = Palette.BG, bold = true, gravity = Gravity.CENTER).apply {
            background = pill(Palette.ACCENT)
            setPadding(activity.dp(6), 0, activity.dp(6), 0)
            visibility = if (record.id in selected) View.VISIBLE else View.GONE
        }
        return FrameLayout(activity).apply {
            addView(image)
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            addView(check, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            setOnClickListener { if (selected.isNotEmpty()) toggle(record.id) else onPick(record) }
            setOnLongClickListener { toggle(record.id); true }
        }
    }

    private fun loadThumb(id: String, target: ImageView) {
        thumbs.get(id)?.let {
            target.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                app.library.originalFile(id).takeIf { it.isFile }?.let { decodeThumbnail(it.readBytes(), 320) }
            }
            if (bitmap != null) {
                thumbs.put(id, bitmap)
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun exportSelected() {
        var count = 0
        selected.forEach { id ->
            val file = app.library.originalFile(id)
            if (file.isFile && app.library.exportToGallery(file.readBytes(), "$id.jpg") != null) count++
        }
        activity.toast("$count photo(s) exportée(s) dans Pictures/StudioCamera")
        selected.clear()
        render()
    }

    private fun confirmDeleteSelected() {
        AlertDialog.Builder(activity)
            .setTitle("Supprimer ${selected.size} photo(s) ?")
            .setMessage("Les photos, leurs versions et leurs projets seront supprimés de l'appareil. Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                selected.forEach { app.library.delete(it) }
                activity.toast("Photo(s) supprimée(s)")
                selected.clear()
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun dayLabel(day: LocalDate): String {
        val today = LocalDate.now()
        return when (day) {
            today -> "Aujourd'hui"
            today.minusDays(1) -> "Hier"
            else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE))
        }
    }

    companion object {
        private const val COLUMNS = 3
    }
}

/** Détail d'une photo : versions, informations, export, partage et suppression. */
class PhotoDetailScreen(
    private val activity: MainActivity,
    private val app: StudioApp,
    private val scope: CoroutineScope,
    private val photoId: String,
    private val onStudio: (String) -> Unit,
) : Screen {

    private var record: PhotoRecord? = app.library.get(photoId)
    private var selectedVersion: String? = null // null = original

    private val image = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFF000000.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, activity.resources.displayMetrics.widthPixels * 5 / 4)
    }
    private val versionRow = activity.row { setPadding(0, activity.dp(6), 0, activity.dp(6)) }
    private val info = activity.column { setPadding(0, activity.dp(8), 0, activity.dp(8)) }
    private val deleteVersionButton = activity.button("Supprimer cette version") { confirmDeleteVersion() }

    override val view: ScrollView = ScrollView(activity).apply {
        setBackgroundColor(Palette.BG)
        addView(activity.column {
            setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(16))
            addView(activity.row {
                addView(activity.button("← Galerie") { activity.closeOverlay() })
            })
            addView(image)
            addView(versionRow)
            addView(info)
            addView(activity.row {
                addView(activity.button("Ouvrir dans le Studio", active = true) { onStudio(photoId) })
            })
            addView(activity.row {
                addView(activity.button("Exporter JPEG") { exportCurrent(dng = false) })
                if (record?.hasRaw == true) addView(activity.button("Exporter DNG") { exportCurrent(dng = true) })
                addView(activity.button("Partager") { shareCurrent() })
            })
            addView(activity.row {
                addView(deleteVersionButton)
                addView(activity.button("Supprimer") { confirmDelete() })
            })
        }, FrameLayout.LayoutParams(-1, -2))
    }

    override fun onShow() {
        record = app.library.get(photoId)
        if (record == null) {
            activity.toast("Photo introuvable")
            activity.closeOverlay()
            return
        }
        renderVersions()
        renderInfo()
        loadImage()
    }

    private fun renderVersions() {
        val r = record ?: return
        deleteVersionButton.visibility = if (selectedVersion != null) View.VISIBLE else View.GONE
        versionRow.removeAllViews()
        versionRow.addView(activity.button("Original", active = selectedVersion == null) {
            selectedVersion = null
            renderVersions()
            loadImage()
        })
        r.versions.forEach { v ->
            versionRow.addView(activity.button(v.label, active = selectedVersion == v.id) {
                selectedVersion = v.id
                renderVersions()
                loadImage()
            })
        }
    }

    private fun renderInfo() {
        val r = record ?: return
        info.removeAllViews()
        val date = java.text.SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", Locale.FRANCE).format(java.util.Date(r.createdAt))
        info.addView(activity.text(r.styleTitle, size = 18f, bold = true))
        info.addView(activity.text(date, size = 13f, color = Palette.MUTED))
        val lens = r.lensLabel?.let { " · $it" } ?: ""
        info.addView(activity.text("${r.shotCount} prise(s)$lens${if (r.hasRaw) " · DNG disponible" else ""}", size = 13f, color = Palette.MUTED))
        if (r.hasProject) {
            val generated = r.generatedRatio?.let { " · ${"%.1f".format(it * 100)} % de pixels reconstruits" } ?: ""
            info.addView(activity.text("Projet multicouche$generated", size = 13f, color = Palette.ACCENT))
        }
        if (r.notes.isNotEmpty()) {
            info.addView(activity.text("Traitements", size = 14f, bold = true).apply { setPadding(0, activity.dp(10), 0, 0) })
            r.notes.forEach { note ->
                info.addView(activity.text("• $note", size = 13f, color = Palette.MUTED).apply { setPadding(0, activity.dp(2), 0, 0) })
            }
        }
    }

    private fun loadImage() {
        val file = currentFile() ?: return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeThumbnail(file.readBytes(), 1280) }
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private fun currentFile() = selectedVersion?.let { app.library.versionFile(photoId, it) } ?: app.library.originalFile(photoId)

    private fun exportCurrent(dng: Boolean) {
        val r = record ?: return
        val uri = if (dng) {
            val file = app.library.rawFile(photoId)
            if (!file.isFile) return activity.toast("Pas de DNG pour cette photo")
            app.library.exportToGallery(file.readBytes(), "${r.id}.dng", "image/x-adobe-dng")
        } else {
            val file = currentFile() ?: return
            app.library.exportToGallery(file.readBytes(), "${r.id}${selectedVersion?.let { "-$it" } ?: ""}.jpg", "image/jpeg")
        }
        activity.toast(if (uri != null) "Exporté dans Pictures/StudioCamera" else "Export impossible")
    }

    private fun shareCurrent() {
        val r = record ?: return
        val file = currentFile() ?: return
        val uri = app.library.exportToGallery(file.readBytes(), "${r.id}-partage.jpg", "image/jpeg") ?: return activity.toast("Partage impossible")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Partager la photo"))
    }

    private fun confirmDeleteVersion() {
        val versionId = selectedVersion ?: return
        AlertDialog.Builder(activity)
            .setTitle("Supprimer cette version ?")
            .setMessage("Seule cette version rendue est supprimée. L'original est conservé.")
            .setPositiveButton("Supprimer") { _, _ ->
                app.library.deleteVersion(photoId, versionId)
                selectedVersion = null
                onShow()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(activity)
            .setTitle("Supprimer la photo ?")
            .setMessage("L'original, les versions et le projet seront supprimés de l'appareil. Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                app.library.delete(photoId)
                activity.toast("Photo supprimée")
                activity.closeOverlay()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
