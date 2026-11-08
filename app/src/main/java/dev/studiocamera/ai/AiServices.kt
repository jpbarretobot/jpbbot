package dev.studiocamera.ai

import android.content.Context
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import java.io.File

/**
 * Point d'entrée unique pour l'IA. Choisit automatiquement le meilleur moteur disponible :
 * un modèle TFLite importé par l'utilisateur, sinon les modules embarqués, sinon l'heuristique.
 *
 * Aucun appel réseau : les modèles ne sont importés que sur demande explicite de l'utilisateur.
 */
class AiServices(context: Context) {

    private val modelsDir = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    private val mlKitSegmenter by lazy { MlKitSubjectSegmenter() }

    @Volatile private var customDepth: TfliteDepthEstimator? = null
    @Volatile private var customSegmenter: TfliteSubjectSegmenter? = null

    fun modelFile(kind: ModelKind): File = File(modelsDir, kind.fileName)

    fun hasImportedModel(kind: ModelKind): Boolean = modelFile(kind).isFile

    /** Relâche les interpréteurs (à appeler après un changement de modèle). */
    @Synchronized
    fun reloadModels() {
        customDepth?.close()
        customSegmenter?.close()
        customDepth = null
        customSegmenter = null
    }

    @Synchronized
    private fun depthEstimator(): DepthEstimator {
        val file = modelFile(ModelKind.DEPTH)
        if (!file.isFile) return HeuristicDepthEstimator
        return customDepth ?: runCatching { TfliteDepthEstimator(file) }.getOrNull()
            ?.also { customDepth = it }
            ?: HeuristicDepthEstimator
    }

    /** Profondeur de l'image ; le nom du moteur utilisé est renvoyé pour l'afficher à l'utilisateur. */
    suspend fun depth(image: Frame): Pair<Plane, String> {
        val engine = depthEstimator()
        return engine.estimate(image) to engine.engineName
    }

    /** Masque du sujet (personne) ou null. Priorité au modèle importé s'il existe. */
    suspend fun subjectMask(image: Frame): Pair<Plane, String>? {
        val custom = customSegmenterOrNull()
        if (custom != null) {
            custom.segment(image)?.let { return it to custom.engineName }
        }
        return mlKitSegmenter.segment(image)?.let { it to mlKitSegmenter.engineName }
    }

    @Synchronized
    private fun customSegmenterOrNull(): SubjectSegmenter? {
        val file = modelFile(ModelKind.SEGMENTATION)
        if (!file.isFile) return null
        return customSegmenter ?: runCatching { TfliteSubjectSegmenter(file) }.getOrNull()
            ?.also { customSegmenter = it }
    }

    enum class ModelKind(val fileName: String, val label: String) {
        DEPTH("depth.tflite", "Profondeur monoculaire"),
        SEGMENTATION("segmentation.tflite", "Segmentation du sujet"),
    }

    companion object {
        const val MODELS_DIR = "models"
    }
}
