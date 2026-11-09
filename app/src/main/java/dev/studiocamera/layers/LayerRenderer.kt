package dev.studiocamera.layers

import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Grade
import dev.studiocamera.imaging.Grading
import dev.studiocamera.imaging.Homography
import dev.studiocamera.imaging.Inpaint
import dev.studiocamera.imaging.Plane
import dev.studiocamera.imaging.Warp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Moteur de rendu multicouche.
 *
 * Principe :
 * - chaque couche est transformée indépendamment (zoom autour de son centre, perspective, décalage) ;
 * - chaque couche a son propre étalonnage (luminosité, contraste, saturation, netteté, flou) ;
 * - les zones que révèle un déplacement sont comblées par la couche la plus lointaine, elle-même complétée
 *   par push-pull : ces pixels sont signalés comme « générés » dans la carte retournée ;
 * - sans aucune modification, le rendu renvoie exactement l'image capturée.
 *
 * Les plans complétés (« plates ») sont mis en cache par résolution de rendu.
 */
class LayerRenderer(private val project: LayerProject) {

    /** Image rendue et carte des pixels reconstruits (1 = généré, 0 = capturé). */
    class Output(val frame: Frame, val generated: Plane)

    private class Prepared(
        val width: Int,
        val height: Int,
        val base: Frame,
        val alphas: List<Plane>,
        val plates: List<Frame>,
        val centroids: List<Pair<Float, Float>>,
    )

    private var prepared: Prepared? = null

    @Synchronized
    fun render(state: EditState, width: Int, height: Int): Output {
        val p = prepare(width, height)
        val layers = project.layers
        val visible = layers.indices.filter { state.isVisible(layers[it].id) }

        if (visible.isEmpty()) {
            val black = Frame.solid(width, height, Frame.pack(0, 0, 0))
            return Output(black, Plane.filled(width, height, 0f))
        }
        if (state.isIdentity(layers)) {
            return Output(Grading.apply(p.base, state.grade), Plane.filled(width, height, 0f))
        }

        // Ordre de composition : du plus lointain au plus proche.
        val order = visible.sortedByDescending { layers[it].depthMean }
        val transforms = order.map { i ->
            transformFor(state.adjustFor(layers[i].id), p.centroids[i], width, height)
        }

        // Passe 1 : somme des contributions géométriques, pour calculer le résidu à combler.
        val coverage = FloatArray(width * height)
        for (k in order.indices) {
            val a = Warp.plane(p.alphas[order[k]], transforms[k], width, height)
            for (idx in coverage.indices) coverage[idx] += a.data[idx]
        }

        // Passe 2 : moyenne pondérée des couches (les masques forment une partition de l'unité).
        // Une composition « par-dessus » assombrirait l'image : le noir du fond remonterait sur les bords doux.
        // Les zones révélées sont affectées à la couche lointaine.
        val canvasR = FloatArray(width * height)
        val canvasG = FloatArray(width * height)
        val canvasB = FloatArray(width * height)
        val generated = FloatArray(width * height)
        val weightSum = FloatArray(width * height)
        for (k in order.indices) {
            val i = order[k]
            val layer = layers[i]
            val adjust = state.adjustFor(layer.id)
            val alphaRaw = Warp.plane(p.alphas[i], transforms[k], width, height)
            val content = applyLook(Warp.frame(p.plates[i], transforms[k], width, height), adjust)
            val residual = if (k == 0) FloatArray(width * height) { idx -> max(0f, 1f - coverage[idx]) } else null

            for (idx in canvasR.indices) {
                val pix = content.pixels[idx]
                var a = alphaRaw.data[idx]
                if (residual != null) a += residual[idx]
                a = a.coerceIn(0f, 1f)
                if (a <= 0f) continue
                canvasR[idx] += ((pix shr 16) and 0xFF) / 255f * a
                canvasG[idx] += ((pix shr 8) and 0xFF) / 255f * a
                canvasB[idx] += (pix and 0xFF) / 255f * a
                weightSum[idx] += a
                // Pixel généré = la source n'était pas capturée pour cette couche.
                generated[idx] += (1f - alphaRaw.data[idx]).coerceIn(0f, 1f) * a
            }
        }

        for (idx in weightSum.indices) {
            val w = weightSum[idx]
            if (w > 1e-6f) {
                canvasR[idx] /= w
                canvasG[idx] /= w
                canvasB[idx] /= w
                generated[idx] /= w
            }
        }
        val frame = Grading.apply(Frame.fromPlanes(
            Plane(width, height, canvasR),
            Plane(width, height, canvasG),
            Plane(width, height, canvasB),
        ), state.grade)
        return Output(frame, Plane(width, height, generated))
    }

    private fun prepare(width: Int, height: Int): Prepared {
        prepared?.takeIf { it.width == width && it.height == height }?.let { return it }
        val base = project.base.resize(width, height)
        val alphas = project.layers.map { it.alpha.resize(width, height) }
        val plates = alphas.map { Inpaint.fill(base, it).frame }
        val centroids = alphas.map { centroid(it) }
        return Prepared(width, height, base, alphas, plates, centroids).also { prepared = it }
    }

    /** Transformation d'une couche : zoom, puis perspective, puis décalage (en pixels de sortie). */
    private fun transformFor(adj: LayerAdjust, centroid: Pair<Float, Float>, w: Int, h: Int): Homography {
        val cx = centroid.first * w
        val cy = centroid.second * h
        val zoom = Homography.scale(adj.zoom.toDouble(), cx.toDouble(), cy.toDouble())
        // Perspective : w = 1 + k·(y - cy). Un k négatif agrandit le bas de la couche.
        val k = -adj.perspective.toDouble() / h
        val persp = Homography.translation(cx.toDouble(), cy.toDouble()) *
            Homography.verticalPerspective(k, 0.0) *
            Homography.translation(-cx.toDouble(), -cy.toDouble())
        val offset = Homography.translation(adj.offsetX.toDouble() * w, adj.offsetY.toDouble() * h)
        return offset * persp * zoom
    }

    /** Barycentre pondéré par l'alpha, en coordonnées normalisées (0..1). */
    private fun centroid(alpha: Plane): Pair<Float, Float> {
        var sx = 0.0
        var sy = 0.0
        var mass = 0.0
        for (y in 0 until alpha.height) for (x in 0 until alpha.width) {
            val a = alpha.data[y * alpha.width + x].toDouble()
            sx += a * x
            sy += a * y
            mass += a
        }
        if (mass < 1e-6) return 0.5f to 0.5f
        return (sx / mass / max(1, alpha.width - 1)).toFloat() to (sy / mass / max(1, alpha.height - 1)).toFloat()
    }

    /** Réglages propres à la couche : netteté / flou par convolution, puis exposition / contraste / saturation. */
    private fun applyLook(frame: Frame, adj: LayerAdjust): Frame {
        if (!adj.hasLook()) return frame
        var out = frame
        if (adj.sharpness != 0f || adj.blur != 0f) {
            val blurRadius = (2 + 8 * adj.blur).roundToInt()
            val planes = Array(3) { c ->
                val src = frame.channel(c)
                val small = src.blur(1)
                val sharpened = if (adj.sharpness > 0f) {
                    Plane(src.width, src.height, FloatArray(src.size) { i ->
                        src.data[i] + adj.sharpness * 1.2f * (src.data[i] - small.data[i])
                    })
                } else src
                if (adj.blur > 0f) {
                    val soft = src.blur(blurRadius)
                    Plane(src.width, src.height, FloatArray(src.size) { i ->
                        sharpened.data[i] * (1f - adj.blur) + soft.data[i] * adj.blur
                    })
                } else sharpened
            }
            out = Frame.fromPlanes(planes[0], planes[1], planes[2])
        }
        if (adj.brightness != 0f || adj.contrast != 0f || adj.saturation != 0f) {
            out = Grading.apply(out, Grade(
                exposureEv = adj.brightness * 1.5f,
                contrast = adj.contrast,
                saturation = adj.saturation,
            ))
        }
        return out
    }

    companion object {
        /** Taille de rendu d'aperçu : compromis fluidité / qualité pour les curseurs. */
        const val PREVIEW_SIDE = 720

        /** Dimensions de rendu pour un plus grand côté donné, en conservant le format. */
        fun fitSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val longest = max(width, height)
            if (longest <= maxSide) return width to height
            val s = maxSide.toFloat() / longest
            return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
        }
    }
}
