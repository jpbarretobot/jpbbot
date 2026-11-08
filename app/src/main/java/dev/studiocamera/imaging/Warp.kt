package dev.studiocamera.imaging

import kotlin.math.abs
import kotlin.math.floor

/**
 * Transformations géométriques par rétro-projection (inverse mapping) avec interpolation bilinéaire.
 * Les pixels situés hors de la source sont laissés transparents (alpha = 0) ou à 0 pour les plans.
 */
object Warp {

    /** Transforme une image. [forward] va de la source vers la destination. */
    fun frame(src: Frame, forward: Homography, outWidth: Int, outHeight: Int): Frame {
        val m = forward.inverse().m
        val out = IntArray(outWidth * outHeight)
        val maxX = src.width - 0.5
        val maxY = src.height - 0.5
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val w = m[6] * x + m[7] * y + m[8]
                if (abs(w) < 1e-9) continue
                val sx = (m[0] * x + m[1] * y + m[2]) / w
                val sy = (m[3] * x + m[4] * y + m[5]) / w
                if (sx < -0.5 || sy < -0.5 || sx > maxX || sy > maxY) continue
                out[y * outWidth + x] = sampleArgb(src, sx.toFloat(), sy.toFloat())
            }
        }
        return Frame(outWidth, outHeight, out)
    }

    /** Transforme un plan (masque, profondeur...). Hors source = 0. */
    fun plane(src: Plane, forward: Homography, outWidth: Int, outHeight: Int): Plane {
        val m = forward.inverse().m
        val out = FloatArray(outWidth * outHeight)
        val maxX = src.width - 0.5
        val maxY = src.height - 0.5
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val w = m[6] * x + m[7] * y + m[8]
                if (abs(w) < 1e-9) continue
                val sx = (m[0] * x + m[1] * y + m[2]) / w
                val sy = (m[3] * x + m[4] * y + m[5]) / w
                if (sx < -0.5 || sy < -0.5 || sx > maxX || sy > maxY) continue
                out[y * outWidth + x] = src.sampleBilinear(sx.toFloat(), sy.toFloat())
            }
        }
        return Plane(outWidth, outHeight, out)
    }

    private fun sampleArgb(src: Frame, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, src.width - 1f)
        val cy = y.coerceIn(0f, src.height - 1f)
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = minOf(x0 + 1, src.width - 1)
        val y1 = minOf(y0 + 1, src.height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val p00 = src.pixels[y0 * src.width + x0]
        val p10 = src.pixels[y0 * src.width + x1]
        val p01 = src.pixels[y1 * src.width + x0]
        val p11 = src.pixels[y1 * src.width + x1]
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        var r = 0f
        var g = 0f
        var b = 0f
        r += w00 * ((p00 shr 16) and 0xFF) + w10 * ((p10 shr 16) and 0xFF) +
            w01 * ((p01 shr 16) and 0xFF) + w11 * ((p11 shr 16) and 0xFF)
        g += w00 * ((p00 shr 8) and 0xFF) + w10 * ((p10 shr 8) and 0xFF) +
            w01 * ((p01 shr 8) and 0xFF) + w11 * ((p11 shr 8) and 0xFF)
        b += w00 * (p00 and 0xFF) + w10 * (p10 and 0xFF) + w01 * (p01 and 0xFF) + w11 * (p11 and 0xFF)
        return Frame.pack(
            (r + 0.5f).toInt().coerceIn(0, 255),
            (g + 0.5f).toInt().coerceIn(0, 255),
            (b + 0.5f).toInt().coerceIn(0, 255),
        )
    }
}
