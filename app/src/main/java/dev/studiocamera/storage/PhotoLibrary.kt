package dev.studiocamera.storage

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.studiocamera.camera.ShotMeta
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Grade
import dev.studiocamera.imaging.Plane
import dev.studiocamera.io.Bitmaps
import dev.studiocamera.layers.Layer
import dev.studiocamera.layers.LayerAdjust
import dev.studiocamera.layers.LayerProject
import dev.studiocamera.layers.LayerRole
import dev.studiocamera.layers.EditState
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Version rendue d'une photo (automatique ou issue du Studio). */
data class VersionInfo(
    val id: String,
    val label: String,
    val createdAt: Long,
    val kind: String, // "auto" | "studio"
)

/** Entrée de la galerie. Les fichiers sont dans le dossier de la photo. */
data class PhotoRecord(
    val id: String,
    val createdAt: Long,
    val styleId: String,
    val styleTitle: String,
    val family: String,
    val notes: List<String>,
    val generatedRatio: Float?,
    val hasProject: Boolean,
    val hasRaw: Boolean,
    val shotCount: Int,
    val lensLabel: String?,
    val versions: List<VersionInfo>,
)

/**
 * Bibliothèque locale. Règles :
 * - `original.jpg` (et `raw.dng`) sont écrits une seule fois et ne sont jamais modifiés ;
 * - chaque rendu est une nouvelle version ;
 * - rien n'est envoyé hors de l'appareil.
 */
class PhotoLibrary(private val context: Context) {

    private val root = File(context.filesDir, "library").apply { mkdirs() }

    fun list(): List<PhotoRecord> {
        return root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { runCatching { readRecord(it) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun get(id: String): PhotoRecord? = File(root, id).takeIf { it.isDirectory }?.let { runCatching { readRecord(it) }.getOrNull() }

    fun dir(id: String): File = File(root, id)

    fun originalFile(id: String): File = File(dir(id), "original.jpg")

    fun rawFile(id: String): File = File(dir(id), "raw.dng")

    /** Crée une photo : original, DNG optionnel, métadonnées. Retourne l'enregistrement créé. */
    fun create(
        styleId: String,
        styleTitle: String,
        family: String,
        original: ByteArray,
        raw: ByteArray?,
        notes: List<String>,
        generatedRatio: Float?,
        hasProject: Boolean,
        meta: ShotMeta?,
        shotCount: Int,
        lensLabel: String?,
    ): PhotoRecord {
        val id = "${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())}-${UUID.randomUUID().toString().take(6)}"
        val d = dir(id).apply { mkdirs() }
        File(d, "original.jpg").writeBytes(original)
        raw?.let { File(d, "raw.dng").writeBytes(it) }
        val record = PhotoRecord(
            id = id,
            createdAt = System.currentTimeMillis(),
            styleId = styleId,
            styleTitle = styleTitle,
            family = family,
            notes = notes,
            generatedRatio = generatedRatio,
            hasProject = hasProject,
            hasRaw = raw != null,
            shotCount = shotCount,
            lensLabel = lensLabel,
            versions = emptyList(),
        )
        writeRecord(record)
        return record
    }

    /** Ajoute une version rendue (jamais d'écrasement de l'original). */
    fun addVersion(photoId: String, jpeg: ByteArray, label: String, kind: String): VersionInfo {
        val record = requireNotNull(get(photoId)) { "Photo introuvable" }
        val versionsDir = File(dir(photoId), "versions").apply { mkdirs() }
        val version = VersionInfo(
            id = UUID.randomUUID().toString().take(8),
            label = label,
            createdAt = System.currentTimeMillis(),
            kind = kind,
        )
        val file = versionFile(photoId, version.id)
        file.writeBytes(jpeg)
        copyExif(originalFile(photoId), file)
        writeRecord(record.copy(versions = record.versions + version))
        versionsDir.mkdirs()
        return version
    }

    fun versionFile(photoId: String, versionId: String): File = File(dir(photoId), "versions/$versionId.jpg")

    fun projectDir(photoId: String): File = File(dir(photoId), "project")

    fun saveProject(photoId: String, project: LayerProject) = ProjectStore.saveProject(projectDir(photoId), project)

    fun loadProject(photoId: String): LayerProject? = ProjectStore.loadProject(projectDir(photoId))

    fun saveEditState(photoId: String, state: EditState) = ProjectStore.saveState(projectDir(photoId), state)

    fun loadEditState(photoId: String): EditState? = ProjectStore.loadState(projectDir(photoId))

    fun delete(photoId: String) {
        dir(photoId).deleteRecursively()
    }

    /** Supprime une version rendue. L'original et les autres versions ne sont pas touchés. */
    fun deleteVersion(photoId: String, versionId: String) {
        versionFile(photoId, versionId).delete()
        val record = requireNotNull(get(photoId)) { "Photo introuvable" }
        writeRecord(record.copy(versions = record.versions.filterNot { it.id == versionId }))
    }

    private fun readRecord(d: File): PhotoRecord {
        val json = JSONObject(File(d, "meta.json").readText())
        val versions = json.optJSONArray("versions") ?: JSONArray()
        return PhotoRecord(
            id = d.name,
            createdAt = json.getLong("createdAt"),
            styleId = json.getString("styleId"),
            styleTitle = json.getString("styleTitle"),
            family = json.optString("family"),
            notes = json.optJSONArray("notes")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            generatedRatio = if (json.has("generatedRatio") && !json.isNull("generatedRatio")) json.getDouble("generatedRatio").toFloat() else null,
            hasProject = json.optBoolean("hasProject"),
            hasRaw = File(d, "raw.dng").isFile,
            shotCount = json.optInt("shotCount", 1),
            lensLabel = json.optString("lensLabel").takeIf { it.isNotEmpty() },
            versions = List(versions.length()) { i ->
                val v = versions.getJSONObject(i)
                VersionInfo(v.getString("id"), v.getString("label"), v.getLong("createdAt"), v.getString("kind"))
            },
        )
    }

    private fun writeRecord(r: PhotoRecord) {
        val versions = JSONArray()
        r.versions.forEach { v ->
            versions.put(JSONObject()
                .put("id", v.id)
                .put("label", v.label)
                .put("createdAt", v.createdAt)
                .put("kind", v.kind))
        }
        val json = JSONObject()
            .put("createdAt", r.createdAt)
            .put("styleId", r.styleId)
            .put("styleTitle", r.styleTitle)
            .put("family", r.family)
            .put("notes", JSONArray(r.notes))
            .put("generatedRatio", r.generatedRatio?.toDouble() ?: JSONObject.NULL)
            .put("hasProject", r.hasProject)
            .put("shotCount", r.shotCount)
            .put("lensLabel", r.lensLabel ?: "")
            .put("versions", versions)
        File(dir(r.id), "meta.json").writeText(json.toString(2))
    }

    /**
     * Copie les métadonnées EXIF utiles de l'original vers un rendu, pour qu'il reste identifiable
     * (date, appareil, réglages). Le logiciel de traitement est indiqué dans le champ Software.
     */
    private fun copyExif(source: File, target: File) {
        try {
            val src = ExifInterface(source.absolutePath)
            val dst = ExifInterface(target.absolutePath)
            for (tag in EXIF_TAGS) {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Studio Caméra")
            dst.saveAttributes()
        } catch (e: Exception) {
            // Les métadonnées sont facultatives : un échec ne doit pas empêcher l'enregistrement.
        }
    }

    /** Exporte un fichier image (JPEG ou DNG) dans la galerie système (Pictures/StudioCamera). */
    fun exportToGallery(bytes: ByteArray, displayName: String, mimeType: String = "image/jpeg"): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudioCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    companion object {
        private val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        )
    }
}

/**
 * Sérialisation du projet multicouche (image de travail, masques PNG, profondeur) et de l'état d'édition.
 */
object ProjectStore {

    fun saveProject(dir: File, project: LayerProject) {
        dir.mkdirs()
        File(dir, "base.jpg").writeBytes(Bitmaps.encodeJpeg(project.base, 95))
        val layers = JSONArray()
        for (layer in project.layers) {
            File(dir, "layer_${layer.id}.png").writeBytes(Bitmaps.encodePlanePng(layer.alpha))
            layers.put(JSONObject()
                .put("id", layer.id)
                .put("role", layer.role.name)
                .put("depthMean", layer.depthMean.toDouble()))
        }
        project.depth?.let { File(dir, "depth.png").writeBytes(Bitmaps.encodePlanePng(it)) }
        val json = JSONObject()
            .put("width", project.width)
            .put("height", project.height)
            .put("subjectDetected", project.subjectDetected)
            .put("layers", layers)
        File(dir, "project.json").writeText(json.toString(2))
    }

    fun loadProject(dir: File): LayerProject? {
        val meta = File(dir, "project.json")
        if (!meta.isFile) return null
        val json = JSONObject(meta.readText())
        val base: Frame = Bitmaps.decodeLimited(File(dir, "base.jpg").readBytes(), maxSide = Int.MAX_VALUE / 4)
        val arr = json.getJSONArray("layers")
        val layers = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val alpha: Plane = Bitmaps.decodePlanePng(File(dir, "layer_${o.getInt("id")}.png").readBytes())
            Layer(
                id = o.getInt("id"),
                role = LayerRole.valueOf(o.getString("role")),
                alpha = alpha,
                depthMean = o.getDouble("depthMean").toFloat(),
            )
        }
        val depthFile = File(dir, "depth.png")
        val depth = if (depthFile.isFile) Bitmaps.decodePlanePng(depthFile.readBytes()) else null
        return LayerProject(base, layers, depth, json.optBoolean("subjectDetected"))
    }

    fun saveState(dir: File, state: EditState) {
        dir.mkdirs()
        val adjusts = JSONObject()
        state.adjusts.forEach { (id, a) -> adjusts.put(id.toString(), adjustToJson(a)) }
        val visible = JSONObject()
        state.visible.forEach { (id, v) -> visible.put(id.toString(), v) }
        val grade = JSONObject()
            .put("exposureEv", state.grade.exposureEv.toDouble())
            .put("contrast", state.grade.contrast.toDouble())
            .put("saturation", state.grade.saturation.toDouble())
            .put("warmth", state.grade.warmth.toDouble())
            .put("shadows", state.grade.shadows.toDouble())
            .put("highlights", state.grade.highlights.toDouble())
            .put("fade", state.grade.fade.toDouble())
            .put("vignette", state.grade.vignette.toDouble())
        val json = JSONObject()
            .put("adjusts", adjusts)
            .put("visible", visible)
            .put("grade", grade)
        File(dir, "state.json").writeText(json.toString(2))
    }

    fun loadState(dir: File): EditState? {
        val f = File(dir, "state.json")
        if (!f.isFile) return null
        val json = JSONObject(f.readText())
        val adjusts = json.getJSONObject("adjusts").let { o ->
            o.keys().asSequence().associate { k -> k.toInt() to adjustFromJson(o.getJSONObject(k)) }
        }
        val visible = json.getJSONObject("visible").let { o ->
            o.keys().asSequence().associate { k -> k.toInt() to o.getBoolean(k) }
        }
        val g = json.getJSONObject("grade")
        val grade = Grade(
            exposureEv = g.getDouble("exposureEv").toFloat(),
            contrast = g.getDouble("contrast").toFloat(),
            saturation = g.getDouble("saturation").toFloat(),
            warmth = g.getDouble("warmth").toFloat(),
            shadows = g.getDouble("shadows").toFloat(),
            highlights = g.getDouble("highlights").toFloat(),
            fade = g.getDouble("fade").toFloat(),
            vignette = g.getDouble("vignette").toFloat(),
        )
        return EditState(adjusts = adjusts, visible = visible, grade = grade)
    }

    private fun adjustToJson(a: LayerAdjust): JSONObject = JSONObject()
        .put("zoom", a.zoom.toDouble())
        .put("offsetX", a.offsetX.toDouble())
        .put("offsetY", a.offsetY.toDouble())
        .put("perspective", a.perspective.toDouble())
        .put("brightness", a.brightness.toDouble())
        .put("contrast", a.contrast.toDouble())
        .put("saturation", a.saturation.toDouble())
        .put("sharpness", a.sharpness.toDouble())
        .put("blur", a.blur.toDouble())

    private fun adjustFromJson(o: JSONObject): LayerAdjust = LayerAdjust(
        zoom = o.optDouble("zoom", 1.0).toFloat(),
        offsetX = o.optDouble("offsetX", 0.0).toFloat(),
        offsetY = o.optDouble("offsetY", 0.0).toFloat(),
        perspective = o.optDouble("perspective", 0.0).toFloat(),
        brightness = o.optDouble("brightness", 0.0).toFloat(),
        contrast = o.optDouble("contrast", 0.0).toFloat(),
        saturation = o.optDouble("saturation", 0.0).toFloat(),
        sharpness = o.optDouble("sharpness", 0.0).toFloat(),
        blur = o.optDouble("blur", 0.0).toFloat(),
    )
}

/** Décodage d'un tampon : utilitaire partagé par la galerie pour générer des vignettes. */
internal fun decodeThumbnail(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
