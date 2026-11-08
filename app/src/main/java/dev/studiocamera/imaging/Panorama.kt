package dev.studiocamera.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Panorama par balayage : chaque image est positionnée par translation estimée sur la précédente,
 * puis fusionnée avec une pondération en plume (les bords de chaque image s'atténuent).
 *
 * Limite assumée : pas de projection cylindrique ; convient aux balayages horizontaux à main levée.
 */
object Panorama {

    fun stitch(frames: List<Frame>): Frame {
        require(frames.isNotEmpty()) { "Aucune image" }
        if (frames.size == 1) return frames[0]
        val w = frames[0].width
        val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) { "Images de tailles différentes" }

        // Positions cumulées : pos_k = pos_{k-1} - s, où s est le décalage (précédente → courante).
        val posX = FloatArray(frames.size)
        val posY = FloatArray(frames.size)
        for (k in 1 until frames.size) {
            val prev = frames[k - 1].limitedTo(360)
            val cur = frames[k].limitedTo(360)
            val scale = w.toFloat() / prev.width
            val s = Alignment.shift(Alignment.edgeMap(prev), Alignment.edgeMap(cur), maxShift = 64)
            posX[k] = posX[k - 1] - s[0] * scale
            posY[k] = posY[k - 1] - s[1] * scale
        }
        val minX = posX.min()
        val minY = posY.min()
        val canvasW = (posX.indices.maxOf { posX[it] - minX } + w).roundToInt()
        val canvasH = (posY.indices.maxOf { posY[it] - minY } + h).roundToInt()

        val sumR = FloatArray(canvasW * canvasH)
        val sumG = FloatArray(canvasW * canvasH)
        val sumB = FloatArray(canvasW * canvasH)
        val sumW = FloatArray(canvasW * canvasH)
        val feather = featherWeights(w, h)

        for (k in frames.indices) {
            val ox = (posX[k] - minX).roundToInt()
            val oy = (posY[k] - minY).roundToInt()
            val f = frames[k]
            for (y in 0 until h) {
                val cy = oy + y
                if (cy < 0 || cy >= canvasH) continue
                for (x in 0 until w) {
                    val cx = ox + x
                    if (cx < 0 || cx >= canvasW) continue
                    val wgt = feather[y * w + x]
                    val p = f.pixels[y * w + x]
                    val idx = cy * canvasW + cx
                    sumR[idx] += wgt * ((p shr 16) and 0xFF)
                    sumG[idx] += wgt * ((p shr 8) and 0xFF)
                    sumB[idx] += wgt * (p and 0xFF)
                    sumW[idx] += wgt
                }
            }
        }

        val out = IntArray(canvasW * canvasH)
        for (i in out.indices) {
            val s = if (sumW[i] > 1e-6f) 1f / sumW[i] else 0f
            out[i] = Frame.pack(
                (sumR[i] * s + 0.5f).toInt().coerceIn(0, 255),
                (sumG[i] * s + 0.5f).toInt().coerceIn(0, 255),
                (sumB[i] * s + 0.5f).toInt().coerceIn(0, 255),
            )
        }
        return Frame(canvasW, canvasH, out)
    }

    /** Poids en plume : distance minimale au bord, normalisée. */
    private fun featherWeights(w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val ramp = max(1f, min(w, h) * 0.25f)
        for (y in 0 until h) for (x in 0 until w) {
            val d = min(min(x + 1, w - x), min(y + 1, h - y)).toFloat()
            out[y * w + x] = min(1f, d / ramp)
        }
        return out
    }
}
