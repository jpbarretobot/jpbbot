package dev.studiocamera.processing

import dev.studiocamera.ai.FaceAnalyzer
import dev.studiocamera.ai.FaceInfo
import dev.studiocamera.imaging.Alignment
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Homography
import dev.studiocamera.imaging.Plane
import dev.studiocamera.imaging.Warp
import kotlin.math.max
import kotlin.math.min

/**
 * Photo de groupe : choisit la prise la plus favorable (yeux ouverts, sourires) puis remplace,
 * dans cette prise, les visages aux yeux fermés par le même visage pris dans une autre prise
 * où les yeux sont ouverts. La zone remplacée est fondue en bordure pour éviter les coutures.
 */
object GroupSelection {

    class Result(val frame: Frame, val notes: List<String>)

    suspend fun select(frames: List<Frame>, faces: FaceAnalyzer): Result {
        if (frames.size == 1) return Result(frames[0], listOf("Une seule prise : sélection inutile."))

        val analyses = frames.map { faces.analyze(it) }
        val scores = analyses.map { list ->
            list.sumOf { f -> (f.eyesOpen + 0.5f * (f.smiling ?: 0.5f)).toDouble() }
        }
        val base = scores.indices.maxByOrNull { scores[it] } ?: 0
        if (analyses[base].isEmpty()) {
            val sharpest = frames.indices.maxByOrNull { sharpness(frames[it]) } ?: 0
            return Result(frames[sharpest], listOf("Aucun visage détecté : prise la plus nette retenue."))
        }

        var out = frames[base]
        var replaced = 0
        for (face in analyses[base].filter { it.eyesOpen < 0.5f }) {
            var bestIndex = -1
            var bestFace: FaceInfo? = null
            var bestOpen = 0.5f
            for (j in frames.indices) {
                if (j == base) continue
                val candidate = analyses[j].maxByOrNull { iou(face, it) } ?: continue
                if (iou(face, candidate) < 0.3f) continue
                if (candidate.eyesOpen > bestOpen + 0.2f) {
                    bestOpen = candidate.eyesOpen
                    bestIndex = j
                    bestFace = candidate
                }
            }
            if (bestIndex >= 0 && bestFace != null) {
                out = replaceRegion(out, frames[bestIndex], face)
                replaced++
            }
        }
        val faceCount = analyses[base].size
        val notes = mutableListOf("Photo de groupe : prise ${base + 1} retenue parmi ${frames.size} ($faceCount visage(s)).")
        if (replaced > 0) notes += "Yeux ouverts restaurés sur $replaced visage(s)."
        return Result(out, notes)
    }

    /** Copie la zone du visage depuis [other] (recalée par translation) avec un bord progressif. */
    private fun replaceRegion(base: Frame, other: Frame, face: FaceInfo): Frame {
        val shift = Alignment.shift(Alignment.edgeMap(base), Alignment.edgeMap(other), maxShift = 64)
        val aligned = Warp.frame(other, Homography.translation(-shift[0].toDouble(), -shift[1].toDouble()), base.width, base.height)
        val w = base.width
        val h = base.height
        val marginX = (face.right - face.left) * 0.15f
        val marginY = (face.bottom - face.top) * 0.15f
        val left = face.left - marginX
        val top = face.top - marginY
        val right = face.right + marginX
        val bottom = face.bottom + marginY
        val feather = max(4f, (face.right - face.left) * 0.12f)
        val weight = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = min(x - left, right - x)
            val dy = min(y - top, bottom - y)
            val d = min(dx, dy)
            weight[y * w + x] = (d / feather).coerceIn(0f, 1f)
        }
        val m = Plane(w, h, weight)
        return Frame.blendWeighted(listOf(base, aligned), listOf(m.map { 1f - it }, m))
    }

    private fun iou(a: FaceInfo, b: FaceInfo): Float {
        val ix = max(0, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix.toFloat() * iy
        val areaA = (a.right - a.left).toFloat() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toFloat() * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun sharpness(frame: Frame): Float = frame.luma().absLaplacian().mean()
}
