package dev.studiocamera.imaging

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Plan de valeurs flottantes (luminance, masque de netteté, profondeur, poids de fusion...).
 * Stockage row-major, sans dépendance Android : toute la chaîne de traitement est testable sur JVM.
 */
class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {

    init {
        require(width >= 0 && height >= 0) { "Dimensions négatives" }
        require(data.size == width * height) { "Taille de tableau incohérente" }
    }

    val size: Int get() = data.size

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }

    fun copy(): Plane = Plane(width, height, data.copyOf())

    /** Interpolation bilinéaire, coordonnées bornées aux bords. */
    fun sampleBilinear(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = data[y0 * width + x0] * (1 - fx) + data[y0 * width + x1] * fx
        val bottom = data[y1 * width + x0] * (1 - fx) + data[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Flou gaussien approché par deux passes de flou boîte séparables. */
    fun blur(radius: Int): Plane {
        if (radius <= 0 || width == 0 || height == 0) return copy()
        val tmp = FloatArray(data.size)
        val out = FloatArray(data.size)
        boxBlurHorizontal(data, tmp, width, height, radius)
        boxBlurVertical(tmp, out, width, height, radius)
        boxBlurHorizontal(out, tmp, width, height, radius)
        boxBlurVertical(tmp, out, width, height, radius)
        return Plane(width, height, out)
    }

    /** Réduction par 2 avec moyenne de blocs 2x2 (bords répliqués). */
    fun downsample(): Plane {
        val nw = max(1, (width + 1) / 2)
        val nh = max(1, (height + 1) / 2)
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = min(2 * y, height - 1)
            val y1 = min(2 * y + 1, height - 1)
            for (x in 0 until nw) {
                val x0 = min(2 * x, width - 1)
                val x1 = min(2 * x + 1, width - 1)
                out[y * nw + x] = 0.25f * (
                    data[y0 * width + x0] + data[y0 * width + x1] +
                        data[y1 * width + x0] + data[y1 * width + x1]
                    )
            }
        }
        return Plane(nw, nh, out)
    }

    /** Redimensionnement bilinéaire vers une taille arbitraire. */
    fun resize(newWidth: Int, newHeight: Int): Plane {
        if (newWidth == width && newHeight == height) return copy()
        val out = FloatArray(newWidth * newHeight)
        val sx = width.toFloat() / newWidth
        val sy = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val fy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until newWidth) {
                val fx = (x + 0.5f) * sx - 0.5f
                out[y * newWidth + x] = sampleBilinear(fx, fy)
            }
        }
        return Plane(newWidth, newHeight, out)
    }

    /** Magnitude du laplacien (mesure de netteté locale). */
    fun absLaplacian(): Plane {
        val out = FloatArray(data.size)
        for (y in 0 until height) {
            val ym = max(0, y - 1) * width
            val yp = min(height - 1, y + 1) * width
            val yc = y * width
            for (x in 0 until width) {
                val xm = max(0, x - 1)
                val xp = min(width - 1, x + 1)
                val c = data[yc + x]
                val lap = 4f * c - data[yc + xm] - data[yc + xp] - data[ym + x] - data[yp + x]
                out[yc + x] = if (lap < 0f) -lap else lap
            }
        }
        return Plane(width, height, out)
    }

    /** Magnitude du gradient (|Gx| + |Gy|) par différences centrées. */
    fun gradientMagnitude(): Plane {
        val out = FloatArray(data.size)
        for (y in 0 until height) {
            val ym = max(0, y - 1) * width
            val yp = min(height - 1, y + 1) * width
            val yc = y * width
            for (x in 0 until width) {
                val xm = max(0, x - 1)
                val xp = min(width - 1, x + 1)
                val gx = data[yc + xp] - data[yc + xm]
                val gy = data[yp + x] - data[ym + x]
                out[yc + x] = (if (gx < 0f) -gx else gx) + (if (gy < 0f) -gy else gy)
            }
        }
        return Plane(width, height, out)
    }

    fun map(transform: (Float) -> Float): Plane {
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = transform(data[i])
        return Plane(width, height, out)
    }

    fun mean(): Float {
        if (data.isEmpty()) return 0f
        var sum = 0.0
        for (v in data) sum += v
        return (sum / data.size).toFloat()
    }

    fun minMax(): Pair<Float, Float> {
        if (data.isEmpty()) return 0f to 0f
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in data) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return lo to hi
    }

    companion object {
        fun filled(width: Int, height: Int, value: Float): Plane =
            Plane(width, height, FloatArray(width * height) { value })
    }
}

private fun boxBlurHorizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
    val inv = 1f / (2 * r + 1)
    for (y in 0 until h) {
        val row = y * w
        var acc = 0f
        for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            dst[row + x] = acc * inv
            acc += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
        }
    }
}

private fun boxBlurVertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
    val inv = 1f / (2 * r + 1)
    for (x in 0 until w) {
        var acc = 0f
        for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            dst[y * w + x] = acc * inv
            acc += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
        }
    }
}
