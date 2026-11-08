package dev.studiocamera.work

import android.content.Context
import dev.studiocamera.camera.ShotMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Travail de traitement en attente : prises brutes écrites sur disque avant tout calcul. */
class PendingJob(
    val id: String,
    val styleId: String,
    val lensLabel: String?,
    val captureNotes: List<String>,
    val shots: List<ByteArray>,
    val raws: List<ByteArray?>,
    val metas: List<ShotMeta?>,
)

/** État visible d'un travail, pour l'indicateur de la caméra et de la galerie. */
data class JobStatus(
    val id: String,
    val styleTitle: String,
    val state: State,
    val message: String = "",
    val photoId: String? = null,
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }
}

/**
 * File persistante des travaux. Les prises sont écrites immédiatement dans `files/pending/<id>/`,
 * de sorte qu'une fermeture de l'application ne fait jamais perdre une photo.
 */
class PendingJobs(context: Context) {

    private val root = File(context.filesDir, "pending").apply { mkdirs() }

    private val _statuses = MutableStateFlow<List<JobStatus>>(emptyList())
    val statuses: StateFlow<List<JobStatus>> = _statuses.asStateFlow()

    fun write(
        styleId: String,
        styleTitle: String,
        lensLabel: String?,
        captureNotes: List<String>,
        shots: List<ByteArray>,
        raws: List<ByteArray?>,
        metas: List<ShotMeta?>,
    ): String {
        val id = "job-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
        val dir = File(root, id).apply { mkdirs() }
        shots.forEachIndexed { i, bytes -> File(dir, "shot_$i.jpg").writeBytes(bytes) }
        raws.forEachIndexed { i, bytes -> bytes?.let { File(dir, "raw_$i.dng").writeBytes(it) } }
        val metaArray = JSONArray()
        metas.forEach { m ->
            metaArray.put(if (m == null) JSONObject.NULL else JSONObject()
                .put("ts", m.timestampNs)
                .put("iso", m.iso ?: JSONObject.NULL)
                .put("exp", m.exposureNs ?: JSONObject.NULL)
                .put("focus", m.focusDiopters?.toDouble() ?: JSONObject.NULL)
                .put("focal", m.focalLengthMm?.toDouble() ?: JSONObject.NULL))
        }
        val json = JSONObject()
            .put("styleId", styleId)
            .put("styleTitle", styleTitle)
            .put("lensLabel", lensLabel ?: "")
            .put("notes", JSONArray(captureNotes))
            .put("count", shots.size)
            .put("metas", metaArray)
        File(dir, "job.json").writeText(json.toString(2))
        publish(JobStatus(id, styleTitle, JobStatus.State.QUEUED))
        return id
    }

    /** Liste les travaux restant à traiter, du plus ancien au plus récent. */
    fun pendingIds(): List<String> = root.listFiles { f -> f.isDirectory && File(f, "job.json").isFile }
        ?.sortedBy { it.name }
        ?.map { it.name }
        ?: emptyList()

    fun read(id: String): PendingJob {
        val dir = File(root, id)
        val json = JSONObject(File(dir, "job.json").readText())
        val count = json.getInt("count")
        val metas = json.getJSONArray("metas")
        return PendingJob(
            id = id,
            styleId = json.getString("styleId"),
            lensLabel = json.optString("lensLabel").takeIf { it.isNotEmpty() },
            captureNotes = json.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            shots = List(count) { File(dir, "shot_$it.jpg").readBytes() },
            raws = List(count) { File(dir, "raw_$it.dng").takeIf { f -> f.isFile }?.readBytes() },
            metas = List(metas.length()) { i ->
                if (metas.isNull(i)) null else {
                    val m = metas.getJSONObject(i)
                    ShotMeta(
                        timestampNs = m.getLong("ts"),
                        iso = if (m.isNull("iso")) null else m.getInt("iso"),
                        exposureNs = if (m.isNull("exp")) null else m.getLong("exp"),
                        focusDiopters = if (m.isNull("focus")) null else m.getDouble("focus").toFloat(),
                        focalLengthMm = if (m.isNull("focal")) null else m.getDouble("focal").toFloat(),
                    )
                }
            },
        )
    }

    fun styleTitleOf(id: String): String =
        runCatching { JSONObject(File(root, "$id/job.json").readText()).getString("styleTitle") }.getOrDefault("Photo")

    fun complete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** Un échec conserve les prises : elles restent disponibles pour un nouveau traitement. */
    fun markFailed(id: String, message: String) {
        File(root, id).also { it.mkdirs() }.let { File(it, "error.txt").writeText(message) }
        publish(JobStatus(id, styleTitleOf(id), JobStatus.State.FAILED, message))
    }

    fun publish(status: JobStatus) {
        val current = _statuses.value.filterNot { it.id == status.id }
        _statuses.value = (current + status).takeLast(20)
    }
}
