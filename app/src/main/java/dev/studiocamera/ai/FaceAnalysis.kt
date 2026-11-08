package dev.studiocamera.ai

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dev.studiocamera.imaging.Frame
import dev.studiocamera.io.Bitmaps
import dev.studiocamera.util.await

/** Visage détecté : boîte en pixels de l'image analysée et probabilités ML Kit (0..1, ou null). */
data class FaceInfo(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
) {
    val eyesOpen: Float
        get() {
            val values = listOfNotNull(leftEyeOpen, rightEyeOpen)
            return if (values.isEmpty()) 0.5f else values.average().toFloat()
        }
}

/** Détection de visages et d'expressions, embarquée (ML Kit). */
class FaceAnalyzer {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
        FaceDetection.getClient(options)
    }

    suspend fun analyze(image: Frame): List<FaceInfo> {
        val bitmap = Bitmaps.toBitmap(image)
        try {
            val faces: List<Face> = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            return faces.map { f ->
                FaceInfo(
                    left = f.boundingBox.left,
                    top = f.boundingBox.top,
                    right = f.boundingBox.right,
                    bottom = f.boundingBox.bottom,
                    leftEyeOpen = f.leftEyeOpenProbability,
                    rightEyeOpen = f.rightEyeOpenProbability,
                    smiling = f.smilingProbability,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }
}
