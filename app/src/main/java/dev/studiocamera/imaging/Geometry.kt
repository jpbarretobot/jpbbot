package dev.studiocamera.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Homographie 3x3 (row-major, [DoubleArray] de 9 éléments).
 * Convention : `forward` transforme les coordonnées source en coordonnées de destination.
 */
class Homography(val m: DoubleArray) {

    init {
        require(m.size == 9) { "Une homographie a 9 coefficients" }
    }

    /** Composition : `this ∘ other` (applique [other] puis [this]). */
    operator fun times(other: Homography): Homography {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += m[i * 3 + k] * other.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Homography(r)
    }

    fun determinant(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Inverse par la méthode des cofacteurs ; identité si la matrice est singulière. */
    fun inverse(): Homography {
        val det = determinant()
        if (abs(det) < 1e-12) return IDENTITY
        val inv = 1.0 / det
        return Homography(doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * inv,
            (m[2] * m[7] - m[1] * m[8]) * inv,
            (m[1] * m[5] - m[2] * m[4]) * inv,
            (m[5] * m[6] - m[3] * m[8]) * inv,
            (m[0] * m[8] - m[2] * m[6]) * inv,
            (m[2] * m[3] - m[0] * m[5]) * inv,
            (m[3] * m[7] - m[4] * m[6]) * inv,
            (m[1] * m[6] - m[0] * m[7]) * inv,
            (m[0] * m[4] - m[1] * m[3]) * inv,
        ))
    }

    /** Applique la transformation à un point (division projective). */
    fun apply(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        val d = if (abs(w) < 1e-12) 1e-12 else w
        return ((m[0] * x + m[1] * y + m[2]) / d) to ((m[3] * x + m[4] * y + m[5]) / d)
    }

    /** Mise à l'échelle des coordonnées : utile pour passer d'une résolution de travail à une autre. */
    fun scaledBy(factor: Double): Homography = Homography(doubleArrayOf(
        m[0], m[1], m[2] * factor,
        m[3], m[4], m[5] * factor,
        m[6] / factor, m[7] / factor, m[8],
    ))

    fun isIdentity(epsilon: Double = 1e-9): Boolean =
        IDENTITY.m.indices.all { abs(m[it] - IDENTITY.m[it]) < epsilon }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translation(dx: Double, dy: Double) =
            Homography(doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0))

        /** Échelle uniforme autour du point (cx, cy). */
        fun scale(s: Double, cx: Double, cy: Double): Homography =
            translation(cx, cy) * Homography(doubleArrayOf(s, 0.0, 0.0, 0.0, s, 0.0, 0.0, 0.0, 1.0)) *
                translation(-cx, -cy)

        /** Rotation (radians) autour du point (cx, cy). */
        fun rotation(theta: Double, cx: Double, cy: Double): Homography {
            val c = cos(theta)
            val s = sin(theta)
            return translation(cx, cy) *
                Homography(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)) *
                translation(-cx, -cy)
        }

        /** Cisaillement horizontal : x' = x + k·(y - cy). Redresse une inclinaison commune des verticales. */
        fun shearX(k: Double, cy: Double): Homography =
            Homography(doubleArrayOf(1.0, k, -k * cy, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Perspective verticale : w = 1 + c·(y - cy). Corrige la convergence des verticales. */
        fun verticalPerspective(c: Double, cy: Double): Homography =
            Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, c, 1.0 - c * cy))

        /** Facteur de tangente utilisé pour les angles d'inclinaison. */
        fun slopeFromAngle(theta: Double): Double = tan(theta)
    }
}
