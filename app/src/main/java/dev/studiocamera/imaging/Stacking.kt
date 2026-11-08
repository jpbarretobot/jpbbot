package dev.studiocamera.imaging

import kotlin.math.abs

/**
 * Empilements pour la nuit, la pose longue et les scènes à faible lumière.
 * Les images doivent être alignées (voir [Alignment.alignAll]).
 */
object Stacking {

    /**
     * Moyenne écrêtée autour de la médiane (rejet MAD) : réduit le bruit d'un facteur √N
     * tout en éliminant les éléments mobiles (passants, feuillage, voitures).
     */
    fun robustMean(frames: List<Frame>, clipSigma: Float = 2.5f): Frame {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        val n = frames.size
        val w = frames[0].width
        val h = frames[0].height
        val out = IntArray(w * h)
        val values = FloatArray(n)
        val sorted = FloatArray(n)
        val dev = FloatArray(n)
        for (i in out.indices) {
            var packed = 0
            for (shift in intArrayOf(16, 8, 0)) {
                for (k in 0 until n) values[k] = ((frames[k].pixels[i] shr shift) and 0xFF).toFloat()
                System.arraycopy(values, 0, sorted, 0, n)
                insertionSort(sorted)
                val median = sorted[n / 2]
                for (k in 0 until n) dev[k] = abs(values[k] - median)
                insertionSort(dev)
                val mad = dev[n / 2] * 1.4826f
                val limit = clipSigma * max(mad, 1f)
                var sum = 0f
                var count = 0
                for (k in 0 until n) {
                    if (abs(values[k] - median) <= limit) {
                        sum += values[k]
                        count++
                    }
                }
                val v = if (count > 0) sum / count else median
                packed = packed or ((v + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
            out[i] = packed or (0xFF shl 24)
        }
        return Frame(w, h, out)
    }

    /**
     * Pose longue simulée : moyenne de toutes les images alignées.
     * Les trajectoires lumineuses (eau, nuages, phares) deviennent continues ; à utiliser lorsque
     * le capteur ne permet pas une vraie pose longue.
     */
    fun meanStack(frames: List<Frame>): Frame {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        val n = frames.size
        val w = frames[0].width
        val h = frames[0].height
        val sums = FloatArray(w * h * 3)
        for (f in frames) {
            for (i in 0 until w * h) {
                val p = f.pixels[i]
                sums[3 * i] += (p shr 16) and 0xFF
                sums[3 * i + 1] += (p shr 8) and 0xFF
                sums[3 * i + 2] += p and 0xFF
            }
        }
        val out = IntArray(w * h)
        val inv = 1f / n
        for (i in out.indices) {
            out[i] = Frame.pack(
                (sums[3 * i] * inv + 0.5f).toInt().coerceIn(0, 255),
                (sums[3 * i + 1] * inv + 0.5f).toInt().coerceIn(0, 255),
                (sums[3 * i + 2] * inv + 0.5f).toInt().coerceIn(0, 255),
            )
        }
        return Frame(w, h, out)
    }

    private fun max(a: Float, b: Float): Float = if (a > b) a else b

    private fun insertionSort(a: FloatArray) {
        for (i in 1 until a.size) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = v
        }
    }
}
