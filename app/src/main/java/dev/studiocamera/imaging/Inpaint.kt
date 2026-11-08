package dev.studiocamera.imaging

import kotlin.math.max

/**
 * Reconstruction des zones masquées par la méthode push-pull (pyramides de moyennes pondérées).
 *
 * Utilisée pour compléter les plans d'arrière-plan derrière un sujet déplacé ou agrandi.
 * Les pixels reconstruits sont signalés par [Result.generated] : ce ne sont pas des pixels capturés.
 */
object Inpaint {

    class Result(val frame: Frame, val generated: Plane)

    /**
     * @param known poids 0..1 : 1 = pixel capturé à conserver, 0 = trou à reconstruire.
     */
    fun fill(frame: Frame, known: Plane): Result {
        require(known.width == frame.width && known.height == frame.height)
        val w = frame.width
        val h = frame.height
        val generated = Plane(w, h, FloatArray(w * h) { 1f - known.data[it].coerceIn(0f, 1f) })
        if (generated.mean() < 1e-6f) return Result(frame, generated)

        val channels = Array(3) { c -> frame.channel(c) }
        val filled = Array(3) { c -> fillChannel(channels[c], known) }
        return Result(Frame.fromPlanes(filled[0], filled[1], filled[2]), generated)
    }

    private fun fillChannel(source: Plane, known: Plane): Plane {
        // Descente : niveaux (valeur moyenne pondérée, poids moyen).
        val values = ArrayList<Plane>()
        val weights = ArrayList<Plane>()
        var v = Plane(source.width, source.height, FloatArray(source.size) { source.data[it] * known.data[it] })
        var wgt = known.copy()
        values.add(normalize(v, wgt))
        weights.add(wgt)
        // Descente jusqu'à 1×1 : le niveau le plus grossier moyenne toute l'image, ce qui permet
        // à la couleur connue de se propager jusqu'aux zones éloignées du bord.
        while (v.width > 1 || v.height > 1) {
            val (nv, nw) = weightedDownsample(values.last(), weights.last())
            values.add(nv)
            weights.add(nw)
            v = nv
            wgt = nw
        }

        // Remontée : chaque niveau se complète avec l'estimation du niveau supérieur.
        var current = values.last()
        for (l in values.size - 2 downTo 0) {
            val level = values[l]
            val wl = weights[l]
            val up = current.resize(level.width, level.height)
            current = Plane(level.width, level.height, FloatArray(level.size) { i ->
                val a = wl.data[i].coerceIn(0f, 1f)
                a * level.data[i] + (1f - a) * up.data[i]
            })
        }
        return current
    }

    private fun normalize(sumValues: Plane, sumWeights: Plane): Plane =
        Plane(sumValues.width, sumValues.height, FloatArray(sumValues.size) { i ->
            val wgt = sumWeights.data[i]
            if (wgt > 1e-6f) sumValues.data[i] / wgt else 0f
        })

    /** Réduction : valeur = Σ(v·w)/Σw sur le bloc 2x2, poids = Σw/4. */
    private fun weightedDownsample(values: Plane, weights: Plane): Pair<Plane, Plane> {
        val nw = max(1, (values.width + 1) / 2)
        val nh = max(1, (values.height + 1) / 2)
        val outV = FloatArray(nw * nh)
        val outW = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var sumVW = 0f
            var sumW = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val sx = minOf(2 * x + dx, values.width - 1)
                val sy = minOf(2 * y + dy, values.height - 1)
                val i = sy * values.width + sx
                sumVW += values.data[i] * weights.data[i]
                sumW += weights.data[i]
            }
            val o = y * nw + x
            outV[o] = if (sumW > 1e-6f) sumVW / sumW else 0f
            outW[o] = sumW / 4f
        }
        return Plane(nw, nh, outV) to Plane(nw, nh, outW)
    }
}
