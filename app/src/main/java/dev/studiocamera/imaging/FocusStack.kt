package dev.studiocamera.imaging

/**
 * Netteté intégrale / macro : fusion d'une série prise à différentes distances de mise au point.
 *
 * Pour chaque pixel, le poids de chaque image dépend de sa netteté locale (magnitude du laplacien
 * lissée). La carte de poids moyenne fournit aussi une estimation de profondeur relative.
 */
object FocusStack {

    /** Image fusionnée et carte de profondeur relative (0 = premier plan, 1 = arrière-plan). */
    class Result(val frame: Frame, val depth: Plane)

    /** [frames] doivent être ordonnées du plan le plus proche au plus lointain et alignées. */
    fun stack(frames: List<Frame>): Result {
        require(frames.isNotEmpty()) { "Aucune image à empiler" }
        val w = frames[0].width
        val h = frames[0].height
        if (frames.size == 1) return Result(frames[0], Plane.filled(w, h, 0.5f))

        val focus = frames.map { it.luma().absLaplacian().blur(2) }
        val weights = focus.map { fm ->
            val eps = 1e-4f * (fm.mean() + 1e-6f)
            fm.map { v -> (v + eps) * (v + eps) }.blur(4)
        }
        val sums = FloatArray(w * h)
        for (wk in weights) for (i in sums.indices) sums[i] += wk.data[i]
        val normalized = weights.map { wk ->
            Plane(w, h, FloatArray(wk.size) { i -> if (sums[i] > 0f) wk.data[i] / sums[i] else 1f / frames.size })
        }

        val fused = Frame.blendWeighted(frames, normalized)

        val depth = FloatArray(w * h)
        val denom = (frames.size - 1).coerceAtLeast(1).toFloat()
        for (k in normalized.indices) {
            val share = k / denom
            val wk = normalized[k].data
            for (i in depth.indices) depth[i] += share * wk[i]
        }
        return Result(fused, Plane(w, h, depth))
    }
}
