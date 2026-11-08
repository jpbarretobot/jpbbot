package dev.studiocamera.imaging

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Réglages colorimétriques et lumineux. Utilisés à l'échelle de l'image entière ou par couche.
 * Toutes les valeurs sont centrées sur 0 (neutre), entre -1 et 1 sauf indication contraire.
 */
data class Grade(
    val exposureEv: Float = 0f,   // -2 à +2 IL
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val shadows: Float = 0f,      // relève les ombres
    val highlights: Float = 0f,   // relève les hautes lumières
    val fade: Float = 0f,         // rendu pellicule : noirs relevés, couleurs atténuées
    val vignette: Float = 0f,
) {
    fun isNeutral(): Boolean = this == NEUTRAL

    companion object {
        val NEUTRAL = Grade()
    }
}

object Grading {

    private const val GAMMA = 2.2f

    /**
     * Applique [grade] à l'image. Si [mask] est fourni (0..1), l'effet est pondéré pixel par pixel :
     * une couche peut ainsi être étalonnée sans toucher aux autres.
     */
    fun apply(frame: Frame, grade: Grade, mask: Plane? = null): Frame {
        if (grade.isNeutral()) return frame
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * h)
        val gain = 2f.pow(grade.exposureEv)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxR = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = frame.pixels[i]
            var r = ((p shr 16) and 0xFF) / 255f
            var g = ((p shr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f

            if (grade.exposureEv != 0f) {
                r = toLinearThenBack(r, gain)
                g = toLinearThenBack(g, gain)
                b = toLinearThenBack(b, gain)
            }
            if (grade.contrast != 0f) {
                val k = 1f + grade.contrast * 0.8f
                r = 0.5f + (r - 0.5f) * k
                g = 0.5f + (g - 0.5f) * k
                b = 0.5f + (b - 0.5f) * k
            }
            var lum = 0.299f * r + 0.587f * g + 0.114f * b
            if (grade.saturation != 0f || grade.fade != 0f) {
                val sat = 1f + grade.saturation - grade.fade * 0.15f
                r = lum + (r - lum) * sat
                g = lum + (g - lum) * sat
                b = lum + (b - lum) * sat
            }
            if (grade.warmth != 0f) {
                r += grade.warmth * 0.06f
                b -= grade.warmth * 0.06f
            }
            lum = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            if (grade.shadows != 0f) {
                val wgt = (1f - lum) * (1f - lum) * (1f - lum)
                val d = grade.shadows * 0.15f * wgt
                r += d; g += d; b += d
            }
            if (grade.highlights != 0f) {
                val wgt = lum * lum * lum
                val d = grade.highlights * 0.15f * wgt
                r += d; g += d; b += d
            }
            if (grade.fade != 0f) {
                r = r * (1f - 0.08f * grade.fade) + 0.06f * grade.fade
                g = g * (1f - 0.08f * grade.fade) + 0.06f * grade.fade
                b = b * (1f - 0.08f * grade.fade) + 0.06f * grade.fade
            }
            if (grade.vignette != 0f) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) / maxR
                val v = 1f - grade.vignette * 0.5f * d * d
                r *= v; g *= v; b *= v
            }

            var nr = Frame.toByte(r.coerceIn(0f, 1f)) / 255f
            var ng = Frame.toByte(g.coerceIn(0f, 1f)) / 255f
            var nb = Frame.toByte(b.coerceIn(0f, 1f)) / 255f
            if (mask != null) {
                val m = mask.data[i].coerceIn(0f, 1f)
                nr = ((p shr 16) and 0xFF) / 255f * (1f - m) + nr * m
                ng = ((p shr 8) and 0xFF) / 255f * (1f - m) + ng * m
                nb = (p and 0xFF) / 255f * (1f - m) + nb * m
            }
            out[i] = Frame.pack(Frame.toByte(nr), Frame.toByte(ng), Frame.toByte(nb))
        }
        return Frame(w, h, out)
    }

    /** Exposition appliquée en espace linéaire (gamma approché), puis ré-encodage. */
    private fun toLinearThenBack(v: Float, gain: Float): Float {
        val lin = v.coerceAtLeast(0f).pow(GAMMA) * gain
        return lin.coerceAtLeast(0f).pow(1f / GAMMA)
    }
}
