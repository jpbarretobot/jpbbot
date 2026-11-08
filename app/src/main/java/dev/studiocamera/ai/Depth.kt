package dev.studiocamera.ai

import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Estimation de profondeur relative. Convention de sortie : 0 = le plus proche, 1 = le plus lointain.
 */
interface DepthEstimator {
    /** Nom affiché à l'utilisateur : indique clairement si un modèle réel a été utilisé. */
    val engineName: String
    val isLearned: Boolean
    suspend fun estimate(image: Frame): Plane
}

/**
 * Profondeur par modèle monoculaire (type MiDaS / Depth Anything en TFLite, entrée 256×256 ImageNet).
 * Le modèle renvoie une profondeur inverse : grande valeur = proche. Elle est inversée ici.
 */
class TfliteDepthEstimator(modelFile: File) : DepthEstimator, AutoCloseable {
    override val engineName: String = "Modèle de profondeur (${modelFile.nameWithoutExtension})"
    override val isLearned: Boolean = true

    private val interpreter = TfliteRunner.open(modelFile)

    override suspend fun estimate(image: Frame): Plane {
        val input = TfliteRunner.toInputBuffer(image, INPUT, TfliteRunner.Norm.IMAGENET)
        val shape = interpreter.getOutputTensor(0).shape()
        val h = shape[1]
        val w = shape[2]
        val output = Array(1) { Array(h) { Array(w) { FloatArray(shape.getOrElse(3) { 1 }) } } }
        interpreter.run(input, output)
        val values = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) values[y * w + x] = output[0][y][x][0]
        val raw = Plane(w, h, values)
        val (lo, hi) = raw.minMax()
        val span = if (hi - lo > 1e-6f) hi - lo else 1f
        val inverted = raw.map { 1f - (it - lo) / span }
        return inverted.resize(image.width, image.height)
    }

    override fun close() = interpreter.close()

    companion object {
        const val INPUT = 256
    }
}

/**
 * Repli sans modèle : combine la netteté locale (ce qui est net est souvent proche du point de mise au point)
 * et la position verticale (le sol et le premier plan sont dans la partie basse de l'image).
 *
 * C'est une heuristique : elle est signalée comme telle dans l'interface et ne doit pas être présentée
 * comme une mesure de distance réelle.
 */
object HeuristicDepthEstimator : DepthEstimator {
    override val engineName: String = "Heuristique (netteté + position, sans modèle)"
    override val isLearned: Boolean = false

    override suspend fun estimate(image: Frame): Plane {
        val small = image.limitedTo(512)
        val w = small.width
        val h = small.height
        val luma = small.luma()
        val sharp = luma.absLaplacian().blur(3)
        val (lo, hi) = sharp.minMax()
        val span = if (hi - lo > 1e-6f) hi - lo else 1f
        val values = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val sharpNorm = ((sharp.data[i] - lo) / span).coerceIn(0f, 1f)
            val vertical = 1f - y.toFloat() / max(1, h - 1)
            values[i] = min(1f, max(0f, 0.6f * (1f - sharpNorm) + 0.4f * (1f - vertical)))
        }
        return Plane(w, h, values).resize(image.width, image.height)
    }
}
