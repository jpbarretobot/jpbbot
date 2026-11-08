package dev.studiocamera.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Correction automatique des verticales (architecture).
 *
 * 1. Détection des contours quasi verticaux (gradient horizontal dominant) ;
 * 2. Estimation de l'inclinaison moyenne (cisaillement) et de la convergence gauche/droite (perspective) ;
 * 3. Application d'une homographie qui redresse les lignes, avec un léger recadrage pour masquer les bords.
 *
 * Si la scène ne contient pas assez de verticales fiables, aucune correction n'est appliquée.
 */
object VerticalCorrection {

    class Estimate(
        val homography: Homography,
        val tiltDegrees: Double,
        val convergence: Double,
        val samples: Int,
    )

    private const val MIN_SAMPLES = 200
    private const val CROP_ZOOM = 1.06

    /** Estimation sur une version réduite de l'image ; l'homographie est exprimée dans ses coordonnées. */
    fun estimate(frame: Frame): Estimate? {
        val small = frame.limitedTo(480)
        val w = small.width
        val h = small.height
        if (w < 64 || h < 64) return null
        val luma = small.luma().blur(1)
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            gx[i] = luma.data[i + 1] - luma.data[i - 1]
            gy[i] = luma.data[i + w] - luma.data[i - w]
        }
        val magnitudes = FloatArray(w * h) { abs(gx[it]) + abs(gy[it]) }
        val sortedMag = magnitudes.copyOf().also { it.sort() }
        val threshold = sortedMag[(sortedMag.size * 0.9f).toInt().coerceIn(0, sortedMag.size - 1)]
        if (threshold <= 1e-4f) return null

        val slopes = ArrayList<Float>()
        val xs = ArrayList<Float>()
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        val leftX = ArrayList<Float>()
        val rightX = ArrayList<Float>()
        val stride = max(1, (w * h) / 60000)
        for (y in 2 until h - 2 step stride.coerceAtMost(3)) for (x in 2 until w - 2) {
            val i = y * w + x
            if (magnitudes[i] < threshold) continue
            if (abs(gx[i]) < 2f * abs(gy[i])) continue // on ne garde que les contours verticaux
            // Direction de la ligne : perpendiculaire au gradient. Pente = dx/dy.
            val slope = -gy[i] / gx[i]
            if (abs(slope) > 0.6f) continue // ignore les lignes trop inclinées (probablement non verticales)
            slopes.add(slope)
            xs.add(x - w / 2f)
            when {
                x < w / 3 -> { left.add(slope); leftX.add(x - w / 2f) }
                x > 2 * w / 3 -> { right.add(slope); rightX.add(x - w / 2f) }
            }
        }
        if (slopes.size < MIN_SAMPLES || left.size < 30 || right.size < 30) return null

        val tilt = median(slopes)
        val tL = median(left)
        val tR = median(right)
        val xL = leftX.average().toFloat()
        val xR = rightX.average().toFloat()
        val convergence = if (abs(xR - xL) > 1f) (tR - tL) / (xR - xL) else 0f

        val tiltCorrection = -tilt.toDouble()
        val perspCorrection = convergence.toDouble()
        val magnitudeTilt = abs(tilt)
        if (magnitudeTilt < 0.004f && abs(convergence) < 2e-4f) return null

        val cy = h / 2.0
        val cx = w / 2.0
        val persp = Homography.verticalPerspective(perspCorrection, cy)
        val shear = Homography.shearX(tiltCorrection, cy)
        // Recadrage léger autour du centre pour éviter les bords vides.
        val zoom = Homography.scale(CROP_ZOOM, cx, cy)
        val h2 = zoom * shear * persp
        return Estimate(
            homography = h2,
            tiltDegrees = Math.toDegrees(kotlin.math.atan(magnitudeTilt.toDouble())) * kotlin.math.sign(tilt.toDouble()),
            convergence = convergence.toDouble(),
            samples = slopes.size,
        )
    }

    /** Applique la correction à l'image pleine résolution ; renvoie l'original si rien n'est détecté. */
    fun correct(frame: Frame): Pair<Frame, Estimate?> {
        val est = estimate(frame) ?: return frame to null
        val small = frame.limitedTo(480)
        val factor = frame.width.toDouble() / small.width
        val full = est.homography.scaledBy(factor)
        val warped = Warp.frame(frame, full, frame.width, frame.height)
        // Les pixels hors source deviennent noirs opaques plutôt que transparents.
        for (i in warped.pixels.indices) warped.pixels[i] = warped.pixels[i] or (0xFF shl 24)
        return warped to est
    }

    private fun median(values: List<Float>): Float {
        val a = values.toFloatArray()
        a.sort()
        return a[a.size / 2]
    }
}
