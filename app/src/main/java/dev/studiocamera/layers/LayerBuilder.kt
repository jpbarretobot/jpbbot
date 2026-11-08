package dev.studiocamera.layers

import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Construit les couches à partir d'une image, d'une carte de profondeur et, si disponible, d'un masque sujet.
 *
 * Convention : la carte de profondeur va de 0 (le plus proche) à 1 (le plus lointain).
 *
 * Étapes :
 * 1. normalisation percentile de la profondeur ;
 * 2. masque ciel : zones lointaines, claires et en haut de l'image ;
 * 3. regroupement 1D (k-moyennes) des profondeurs restantes en trois plans ;
 * 4. appartenance douce (gaussienne) à chaque plan, pour des transitions sans halo dur ;
 * 5. combinaison : sujet, puis ciel, puis plans : la somme reste égale à 1 en chaque pixel.
 */
object LayerBuilder {

    private const val BANDS = 3
    private const val MIN_COVERAGE = 0.01f

    fun build(image: Frame, depthRaw: Plane, subjectMask: Plane?): LayerProject {
        val w = image.width
        val h = image.height
        require(depthRaw.width == w && depthRaw.height == h) { "Profondeur et image de tailles différentes" }

        val depth = normalizePercentile(depthRaw)
        val luma = image.luma()

        // Ciel : profondeur lointaine, luminosité élevée, partie haute de l'image.
        val sky = Plane(w, h, FloatArray(w * h) { i ->
            val y = i / w
            val topPrior = smoothStep(0.55f, 0.25f, y.toFloat() / h)
            val far = smoothStep(0.7f, 0.9f, depth.data[i])
            val bright = smoothStep(0.55f, 0.8f, luma.data[i])
            topPrior * far * bright
        }).blur(3)
        val skyCoverage = sky.mean()
        val hasSky = skyCoverage in 0.04f..0.6f

        val subject = subjectMask?.blur(2)?.takeIf { it.mean() in 0.01f..0.9f }

        // Profondeurs des pixels non sujet / non ciel pour le regroupement.
        val centers = kMeans1D(depth, subject, if (hasSky) sky else null, BANDS)
        val spacing = if (centers.size > 1) (centers.last() - centers.first()) / (centers.size - 1) else 0.3f
        val sigma = max(0.08f, 0.5f * spacing)

        val memberships = Array(centers.size) { k ->
            Plane(w, h, FloatArray(w * h) { i ->
                val d = depth.data[i] - centers[k]
                exp(-(d * d) / (2f * sigma * sigma))
            })
        }

        // Partition : chaque pixel distribue 1 entre sujet, ciel et plans.
        val total = FloatArray(w * h)
        val bandAlpha = Array(centers.size) { FloatArray(w * h) }
        val subjectAlpha = FloatArray(w * h)
        val skyAlpha = FloatArray(w * h)
        for (i in 0 until w * h) {
            val s = subject?.data?.get(i)?.coerceIn(0f, 1f) ?: 0f
            val sk = if (hasSky) sky.data[i].coerceIn(0f, 1f) else 0f
            var norm = 0f
            for (k in centers.indices) norm += memberships[k].data[i]
            val rest = 1f - s
            for (k in centers.indices) {
                val share = if (norm > 1e-9f) memberships[k].data[i] / norm else 1f / centers.size
                bandAlpha[k][i] = rest * (1f - sk) * share
            }
            subjectAlpha[i] = s
            skyAlpha[i] = rest * sk
            total[i] = s + rest * sk + rest * (1f - sk)
        }

        val named = ArrayList<Pair<LayerRole, FloatArray>>()
        val roles = bandRoles(centers.size)
        if (subject != null) named.add(LayerRole.SUBJECT to subjectAlpha)
        if (hasSky) named.add(LayerRole.SKY to skyAlpha)
        for (k in centers.indices) named.add(roles[k] to bandAlpha[k])

        // Filtre les couches trop petites, puis renormalise la partition.
        val kept = named.filter { (_, a) -> a.average().toFloat() >= MIN_COVERAGE }
        val sums = FloatArray(w * h)
        for ((_, a) in kept) for (i in sums.indices) sums[i] += a[i]

        val layers = kept.mapIndexed { index, (role, a) ->
            val alpha = Plane(w, h, FloatArray(w * h) { i -> if (sums[i] > 1e-9f) a[i] / sums[i] else 0f })
            var weighted = 0.0
            var mass = 0.0
            for (i in alpha.data.indices) {
                weighted += alpha.data[i].toDouble() * depth.data[i]
                mass += alpha.data[i]
            }
            val mean = if (mass > 0) (weighted / mass).toFloat() else 0.5f
            Layer(id = index, role = role, alpha = alpha, depthMean = mean)
        }
        return LayerProject(base = image, layers = layers, depth = depth, subjectDetected = subject != null)
    }

    private fun bandRoles(count: Int): List<LayerRole> = when (count) {
        1 -> listOf(LayerRole.MIDGROUND)
        2 -> listOf(LayerRole.FOREGROUND, LayerRole.BACKGROUND)
        else -> listOf(LayerRole.FOREGROUND, LayerRole.MIDGROUND, LayerRole.BACKGROUND)
    }

    /** Normalisation entre les 1er et 99e centiles : robuste aux valeurs aberrantes du modèle. */
    private fun normalizePercentile(p: Plane): Plane {
        val sorted = p.data.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.01f).toInt().coerceIn(0, sorted.size - 1)]
        val hi = sorted[(sorted.size * 0.99f).toInt().coerceIn(0, sorted.size - 1)]
        val span = if (hi - lo > 1e-6f) hi - lo else 1f
        return Plane(p.width, p.height, FloatArray(p.size) { ((p.data[it] - lo) / span).coerceIn(0f, 1f) })
    }

    /** Regroupement 1D par k-moyennes sur un histogramme, initialisé par quantiles. */
    private fun kMeans1D(depth: Plane, subject: Plane?, sky: Plane?, k: Int): List<Float> {
        val bins = 128
        val hist = DoubleArray(bins)
        for (i in depth.data.indices) {
            if (subject != null && subject.data[i] > 0.5f) continue
            if (sky != null && sky.data[i] > 0.5f) continue
            hist[(depth.data[i] * (bins - 1)).toInt().coerceIn(0, bins - 1)] += 1.0
        }
        val total = hist.sum()
        if (total < 1.0) return listOf(0.25f, 0.5f, 0.75f).take(k)

        // Initialisation par quantiles.
        val centers = DoubleArray(k) { c ->
            val target = total * (c + 0.5) / k
            var acc = 0.0
            var idx = 0
            while (idx < bins - 1 && acc + hist[idx] < target) {
                acc += hist[idx]
                idx++
            }
            idx.toDouble() / (bins - 1)
        }
        repeat(20) {
            val sums = DoubleArray(k)
            val counts = DoubleArray(k)
            for (b in 0 until bins) {
                if (hist[b] == 0.0) continue
                val v = b.toDouble() / (bins - 1)
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = kotlin.math.abs(v - centers[c])
                    if (d < bestD) {
                        bestD = d
                        best = c
                    }
                }
                sums[best] += v * hist[b]
                counts[best] += hist[b]
            }
            for (c in 0 until k) if (counts[c] > 0) centers[c] = sums[c] / counts[c]
        }
        return centers.map { it.toFloat() }.sorted().distinct()
            .let { if (it.size < k) it + List(k - it.size) { 0.5f } else it }
            .sorted()
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
