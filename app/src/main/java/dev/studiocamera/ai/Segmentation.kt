package dev.studiocamera.ai

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import dev.studiocamera.io.Bitmaps
import dev.studiocamera.util.await
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Segmentation du sujet principal : renvoie un masque de probabilité 0..1 à la résolution de l'image,
 * ou null si aucun sujet fiable n'est détecté.
 */
interface SubjectSegmenter {
    val engineName: String
    suspend fun segment(image: Frame): Plane?
}

/**
 * Segmentation des personnes embarquée (ML Kit, modèle inclus dans l'APK, exécution locale).
 */
class MlKitSubjectSegmenter : SubjectSegmenter {
    override val engineName: String = "ML Kit (personne)"

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    override suspend fun segment(image: Frame): Plane? {
        val bitmap = Bitmaps.toBitmap(image)
        try {
            val mask = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            val buffer = mask.buffer.asFloatBuffer()
            val w = mask.width
            val h = mask.height
            val values = FloatArray(w * h)
            buffer.get(values, 0, min(values.size, buffer.remaining()))
            val plane = Plane(w, h, values)
            return if (plane.mean() < 0.005f) null else plane.resize(image.width, image.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}

/**
 * Segmentation par modèle TFLite fourni par l'utilisateur (entrée 256×256 RGB normalisée 0..1).
 * La sortie peut être [1,H,W,1] (probabilité sujet) ou [1,H,W,C] (on retient [personClass]).
 */
class TfliteSubjectSegmenter(modelFile: File, private val personClass: Int = -1) : SubjectSegmenter, AutoCloseable {
    override val engineName: String = "TFLite (${modelFile.nameWithoutExtension})"

    private val interpreter = TfliteRunner.open(modelFile)

    override suspend fun segment(image: Frame): Plane? {
        val input = TfliteRunner.toInputBuffer(image, INPUT, normalize = TfliteRunner.Norm.UNIT)
        val outShape = interpreter.getOutputTensor(0).shape()
        val h = outShape[1]
        val w = outShape[2]
        val channels = outShape[3]
        val output = Array(1) { Array(h) { Array(w) { FloatArray(channels) } } }
        interpreter.run(input, output)
        val values = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val cell = output[0][y][x]
            values[y * w + x] = if (channels == 1 || personClass < 0) cell[0] else cell[personClass]
        }
        val plane = Plane(w, h, values)
        return if (plane.mean() < 0.005f) null else plane.resize(image.width, image.height)
    }

    override fun close() = interpreter.close()

    companion object {
        const val INPUT = 256
    }
}

/** Utilitaires communs aux modèles TFLite. */
internal object TfliteRunner {
    enum class Norm { UNIT, IMAGENET }

    fun open(file: File): org.tensorflow.lite.Interpreter {
        val channel = java.io.FileInputStream(file).channel
        val mapped: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        val options = org.tensorflow.lite.Interpreter.Options().setNumThreads(4)
        return org.tensorflow.lite.Interpreter(mapped, options)
    }

    /** Tampon d'entrée [1, size, size, 3] en float32, redimensionné depuis l'image. */
    fun toInputBuffer(image: Frame, size: Int, normalize: Norm): ByteBuffer {
        val resized = image.resize(size, size)
        val buf = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (p in resized.pixels) {
            val rgb = floatArrayOf(
                ((p shr 16) and 0xFF) / 255f,
                ((p shr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f,
            )
            for (c in 0 until 3) {
                val v = when (normalize) {
                    Norm.UNIT -> rgb[c]
                    Norm.IMAGENET -> (rgb[c] - mean[c]) / std[c]
                }
                buf.putFloat(v)
            }
        }
        buf.rewind()
        return buf
    }
}
