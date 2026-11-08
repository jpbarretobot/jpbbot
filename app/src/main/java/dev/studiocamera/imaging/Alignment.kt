package dev.studiocamera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Résultat d'un alignement : images recadrées sur la zone commune à toutes les prises. */
class AlignedSet(val frames: List<Frame>, val shifts: List<IntArray>)

/**
 * Alignement translationnel par pyramide multi-échelle.
 * Compare les cartes de contours : robuste aux écarts d'exposition entre prises (HDR, nuit).
 */
object Alignment {

    /** Carte de contours normalisée, utilisée pour la comparaison. */
    fun edgeMap(frame: Frame): Plane = frame.luma().blur(1).gradientMagnitude()

    /**
     * Décalage entier (dx, dy) tel que `ref(p) ≈ src(p + d)`.
     * Renvoie [0, 0] si les images ne sont pas comparables.
     */
    fun shift(ref: Plane, src: Plane, maxShift: Int = 48): IntArray {
        require(ref.width == src.width && ref.height == src.height) { "Tailles différentes" }
        val refLevels = ArrayList<Plane>().apply { add(ref) }
        val srcLevels = ArrayList<Plane>().apply { add(src) }
        while (min(refLevels.last().width, refLevels.last().height) > 80 && refLevels.size < 6) {
            refLevels.add(refLevels.last().blur(1).downsample())
            srcLevels.add(srcLevels.last().blur(1).downsample())
        }
        val top = refLevels.size - 1
        var dx = 0
        var dy = 0
        for (level in top downTo 0) {
            val r = refLevels[level]
            val s = srcLevels[level]
            val range = if (level == top) max(2, maxShift shr top) else 1
            val baseX = if (level == top) 0 else dx * 2
            val baseY = if (level == top) 0 else dy * 2
            val step = max(1, min(r.width, r.height) / 160)
            var bestCost = Float.MAX_VALUE
            var bestX = baseX
            var bestY = baseY
            for (oy in -range..range) for (ox in -range..range) {
                val cx = baseX + ox
                val cy = baseY + oy
                val c = cost(r, s, cx, cy, step)
                if (c < bestCost) {
                    bestCost = c
                    bestX = cx
                    bestY = cy
                }
            }
            dx = bestX
            dy = bestY
        }
        return intArrayOf(dx, dy)
    }

    /** Erreur absolue moyenne sur le recouvrement ; infini si le recouvrement est insuffisant. */
    private fun cost(ref: Plane, src: Plane, dx: Int, dy: Int, step: Int): Float {
        var sum = 0f
        var count = 0
        var total = 0
        var y = 0
        while (y < ref.height) {
            var x = 0
            while (x < ref.width) {
                total++
                val sx = x + dx
                val sy = y + dy
                if (sx >= 0 && sy >= 0 && sx < src.width && sy < src.height) {
                    sum += abs(ref.data[y * ref.width + x] - src.data[sy * src.width + sx])
                    count++
                }
                x += step
            }
            y += step
        }
        if (count < total * 0.3f || count == 0) return Float.MAX_VALUE
        return sum / count
    }

    /**
     * Aligne toutes les images sur la première (référence), puis recadre la zone commune.
     * Le décalage d'une image est appliqué par translation inverse, sans interpolation sur les bords.
     */
    fun alignAll(frames: List<Frame>): AlignedSet {
        require(frames.isNotEmpty())
        val ref = frames[0]
        val refEdges = edgeMap(ref.limitedTo(480))
        val scale = ref.width.toFloat() / refEdges.width
        val shifts = frames.mapIndexed { index, frame ->
            if (index == 0) {
                intArrayOf(0, 0)
            } else {
                val small = frame.limitedTo(480)
                val s = shift(refEdges, edgeMap(small))
                intArrayOf(Math.round(s[0] * scale), Math.round(s[1] * scale))
            }
        }
        // Zone commune : un point p de la référence correspond au point p + s dans chaque image.
        // Il doit donc rester dans [0, w) après décalage, soit p ∈ [max(-s), min(w - s)).
        var x0 = 0
        var y0 = 0
        var x1 = ref.width
        var y1 = ref.height
        for (s in shifts) {
            x0 = max(x0, -s[0])
            y0 = max(y0, -s[1])
            x1 = min(x1, ref.width - s[0])
            y1 = min(y1, ref.height - s[1])
        }
        val w = max(1, x1 - x0)
        val h = max(1, y1 - y0)
        val aligned = frames.mapIndexed { index, frame ->
            val s = shifts[index]
            // Pixel de sortie (x, y) ↔ référence (x + x0, y + y0) ↔ source (x + x0 + s).
            val forward = Homography.translation(-(x0 + s[0]).toDouble(), -(y0 + s[1]).toDouble())
            Warp.frame(frame, forward, w, h).opaque()
        }
        return AlignedSet(aligned, shifts)
    }

    /** Rend une image entièrement opaque (alpha = 255) après un warp recadré. */
    private fun Frame.opaque(): Frame {
        for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
        return this
    }
}
