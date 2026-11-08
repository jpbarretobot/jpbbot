package dev.studiocamera.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image couleur 8 bits opaque (ARGB empaqueté). Le canal alpha n'est utilisé que pour
 * représenter des pixels non couverts lors d'un warp (voir [Warp]).
 */
class Frame(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {

    init {
        require(pixels.size == width * height) { "Taille de pixels incohérente" }
    }

    fun copy(): Frame = Frame(width, height, pixels.copyOf())

    /** Canal 0=R, 1=G, 2=B, valeurs normalisées entre 0 et 1 (espace gamma d'origine). */
    fun channel(c: Int): Plane {
        val shift = 16 - 8 * c
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) out[i] = ((pixels[i] shr shift) and 0xFF) / 255f
        return Plane(width, height, out)
    }

    /** Luminance Rec.601 normalisée. */
    fun luma(): Plane {
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return Plane(width, height, out)
    }

    fun resize(newWidth: Int, newHeight: Int): Frame {
        if (newWidth == width && newHeight == height) return copy()
        return fromPlanes(
            channel(0).resize(newWidth, newHeight),
            channel(1).resize(newWidth, newHeight),
            channel(2).resize(newWidth, newHeight),
        )
    }

    /** Réduit l'image pour que son plus grand côté ne dépasse pas [maxSide]. */
    fun limitedTo(maxSide: Int): Frame {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val scale = maxSide.toFloat() / longest
        return resize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    /** Applique [transform] à chaque canal (valeurs 0..1) et renvoie une nouvelle image. */
    fun mapChannels(transform: (channel: Int, value: Float) -> Float): Frame {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = transform(0, ((p shr 16) and 0xFF) / 255f)
            val g = transform(1, ((p shr 8) and 0xFF) / 255f)
            val b = transform(2, (p and 0xFF) / 255f)
            out[i] = pack(toByte(r), toByte(g), toByte(b))
        }
        return Frame(width, height, out)
    }

    companion object {
        fun pack(r: Int, g: Int, b: Int, alpha: Int = 0xFF): Int =
            (alpha shl 24) or (r shl 16) or (g shl 8) or b

        fun toByte(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

        /** Reconstruit une image à partir de trois plans R, G, B (valeurs 0..1). */
        fun fromPlanes(r: Plane, g: Plane, b: Plane): Frame {
            require(r.width == g.width && r.width == b.width && r.height == g.height && r.height == b.height)
            val out = IntArray(r.size)
            for (i in out.indices) {
                out[i] = pack(toByte(r.data[i]), toByte(g.data[i]), toByte(b.data[i]))
            }
            return Frame(r.width, r.height, out)
        }

        /** Image unie, utile pour les tests et les fonds. */
        fun solid(width: Int, height: Int, rgb: Int): Frame = Frame(width, height, IntArray(width * height) { rgb })

        /** Moyenne pondérée de plusieurs images de même taille, pixel par pixel. */
        fun blendWeighted(frames: List<Frame>, weights: List<Plane>): Frame {
            require(frames.isNotEmpty() && frames.size == weights.size)
            val w = frames[0].width
            val h = frames[0].height
            val out = FloatArray(w * h * 3)
            val wsum = FloatArray(w * h)
            for (k in frames.indices) {
                val f = frames[k]
                val wk = weights[k].data
                for (i in 0 until w * h) {
                    val p = f.pixels[i]
                    val ww = wk[i]
                    out[3 * i] += ww * ((p shr 16) and 0xFF)
                    out[3 * i + 1] += ww * ((p shr 8) and 0xFF)
                    out[3 * i + 2] += ww * (p and 0xFF)
                    wsum[i] += ww
                }
            }
            val pixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val s = if (wsum[i] > 1e-8f) 1f / wsum[i] else 0f
                pixels[i] = pack(
                    (out[3 * i] * s).roundToInt().coerceIn(0, 255),
                    (out[3 * i + 1] * s).roundToInt().coerceIn(0, 255),
                    (out[3 * i + 2] * s).roundToInt().coerceIn(0, 255),
                )
            }
            return Frame(w, h, pixels)
        }

        internal fun clampInt(v: Int, lo: Int, hi: Int): Int = min(max(v, lo), hi)
    }
}
