package dev.studiocamera.imaging

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fusion d'expositions de Mertens (Mertens, Kautz, Van Reeth, 2007) : combine plusieurs expositions
 * sans passer par une courbe HDR, en pondérant chaque pixel par contraste, saturation et exposition correcte.
 *
 * Implémentation par pyramides streamées : une seule image intermédiaire est gardée en mémoire à la fois
 * (en plus de l'accumulateur), ce qui limite fortement la consommation de RAM sur mobile.
 */
object ExposureFusion {

    private const val MAX_LEVELS = 6

    fun fuse(frames: List<Frame>): Frame {
        require(frames.isNotEmpty()) { "Aucune image à fusionner" }
        if (frames.size == 1) return frames[0]
        val w = frames[0].width
        val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) { "Les images doivent être alignées et de même taille" }

        val weights = normalizedWeights(frames)
        val levels = pyramidLevels(w, h)

        // Accumulateurs par niveau et par canal.
        val acc = Array(3) { arrayOfNulls<Plane>(levels) }
        val sizes = levelSizes(w, h, levels)
        for (c in 0 until 3) for (l in 0 until levels) {
            acc[c][l] = Plane(sizes[l].first, sizes[l].second)
        }

        for (k in frames.indices) {
            val channels = Array(3) { frames[k].channel(it) }
            val gaussians = Array(3) { gaussianPyramid(channels[it], levels) }
            val weightPyramid = gaussianPyramid(weights[k], levels)
            for (l in 0 until levels) {
                val wl = weightPyramid[l]
                for (c in 0 until 3) {
                    val lap = laplacianLevel(gaussians[c], l, levels)
                    val target = acc[c][l]!!.data
                    for (i in target.indices) target[i] += wl.data[i] * lap.data[i]
                }
            }
        }

        // Reconstruction (collapse) de haut en bas.
        val result = Array(3) { c ->
            var current = acc[c][levels - 1]!!
            for (l in levels - 2 downTo 0) {
                val up = current.resize(sizes[l].first, sizes[l].second)
                val level = acc[c][l]!!
                for (i in level.data.indices) level.data[i] += up.data[i]
                current = level
            }
            current
        }
        return Frame.fromPlanes(result[0], result[1], result[2])
    }

    /** Poids Mertens par image, normalisés pour que leur somme soit 1 en chaque pixel. */
    private fun normalizedWeights(frames: List<Frame>): List<Plane> {
        val raw = frames.map { weightOf(it) }
        val n = frames.size
        val sum = FloatArray(raw[0].size)
        for (p in raw) for (i in sum.indices) sum[i] += p.data[i]
        return raw.map { p ->
            Plane(p.width, p.height, FloatArray(p.size) { i ->
                if (sum[i] > 1e-12f) p.data[i] / sum[i] else 1f / n
            })
        }
    }

    private fun weightOf(frame: Frame): Plane {
        val contrast = frame.luma().absLaplacian()
        val w = frame.width
        val h = frame.height
        val out = FloatArray(w * h)
        val r = frame.channel(0).data
        val g = frame.channel(1).data
        val b = frame.channel(2).data
        for (i in out.indices) {
            val mean = (r[i] + g[i] + b[i]) / 3f
            val sat = sqrt(((r[i] - mean) * (r[i] - mean) + (g[i] - mean) * (g[i] - mean) + (b[i] - mean) * (b[i] - mean)) / 3f)
            val expo = gaussianExposedness(r[i]) * gaussianExposedness(g[i]) * gaussianExposedness(b[i])
            out[i] = (contrast.data[i] + 1e-6f) * (sat + 1e-6f) * (expo + 1e-6f)
        }
        return Plane(w, h, out)
    }

    private fun gaussianExposedness(v: Float): Float {
        val d = v - 0.5f
        return exp(-(d * d) / (2f * 0.2f * 0.2f))
    }

    private fun pyramidLevels(w: Int, h: Int): Int {
        var levels = 1
        var m = min(w, h)
        while (m / 2 >= 16 && levels < MAX_LEVELS) {
            m /= 2
            levels++
        }
        return max(2, levels)
    }

    private fun levelSizes(w: Int, h: Int, levels: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var cw = w
        var ch = h
        for (l in 0 until levels) {
            out.add(cw to ch)
            cw = max(1, (cw + 1) / 2)
            ch = max(1, (ch + 1) / 2)
        }
        return out
    }

    /** Pyramide gaussienne : G0 = image, Gl+1 = réduction(flou(Gl)). */
    internal fun gaussianPyramid(base: Plane, levels: Int): List<Plane> {
        val pyr = ArrayList<Plane>(levels)
        pyr.add(base)
        for (l in 1 until levels) pyr.add(pyr[l - 1].blur(1).downsample())
        return pyr
    }

    /** Niveau laplacien l : Gl - up(Gl+1) ; le dernier niveau est le résidu gaussien. */
    private fun laplacianLevel(pyr: List<Plane>, l: Int, levels: Int): Plane {
        if (l == levels - 1) return pyr[l]
        val cur = pyr[l]
        val up = pyr[l + 1].resize(cur.width, cur.height)
        return Plane(cur.width, cur.height, FloatArray(cur.size) { cur.data[it] - up.data[it] })
    }
}
