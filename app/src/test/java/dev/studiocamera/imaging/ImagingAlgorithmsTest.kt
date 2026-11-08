package dev.studiocamera.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Vérifie le comportement mesurable des algorithmes de traitement sur des scènes synthétiques. */
class ImagingAlgorithmsTest {

    /** Texture aléatoire lissée : riche en contours, adaptée à l'alignement et à la netteté. */
    private fun texture(w: Int, h: Int, seed: Int): Frame {
        val rnd = Random(seed)
        val base = Plane(w, h, FloatArray(w * h) { rnd.nextFloat() })
        val smooth = base.blur(1)
        return Frame.fromPlanes(smooth, smooth.map { (it * 0.8f + 0.1f) }, smooth.map { 1f - it * 0.6f })
    }

    private fun meanAbsDiff(a: Frame, b: Frame): Float {
        var s = 0.0
        for (i in a.pixels.indices) {
            val pa = a.pixels[i]
            val pb = b.pixels[i]
            s += abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                abs((pa and 0xFF) - (pb and 0xFF))
        }
        return (s / (a.pixels.size * 3)).toFloat()
    }

    @Test
    fun `alignment recovers a known translation`() {
        val w = 320
        val h = 240
        val ref = texture(w, h, 1)
        // Warp.frame(ref, T(7, -5)) produit shifted(x, y) = ref(x - 7, y + 5).
        // Alignment.shift renvoie s tel que ref(p) ≈ shifted(p + s) : ici s = (7, -5).
        val shifted = Warp.frame(ref, Homography.translation(7.0, -5.0), w, h)
        val s = Alignment.shift(Alignment.edgeMap(ref), Alignment.edgeMap(shifted), maxShift = 32)
        assertTrue("dx=${s[0]}", abs(s[0] - 7) <= 1)
        assertTrue("dy=${s[1]}", abs(s[1] + 5) <= 1)
    }

    @Test
    fun `alignAll produces frames of identical size that match the reference`() {
        val ref = texture(256, 192, 2)
        val shifted = Warp.frame(ref, Homography.translation(4.0, 3.0), 256, 192)
        val set = Alignment.alignAll(listOf(ref, shifted))
        assertEquals(set.frames[0].width, set.frames[1].width)
        assertEquals(set.frames[0].height, set.frames[1].height)
        assertTrue(meanAbsDiff(set.frames[0], set.frames[1]) < 25f)
    }

    @Test
    fun `exposure fusion stays between its inputs and is finite`() {
        val scene = texture(128, 96, 3)
        val dark = scene.mapChannels { _, v -> v * 0.35f }
        val bright = scene.mapChannels { _, v -> minOf(1f, v * 1.9f) }
        val fused = ExposureFusion.fuse(listOf(dark, bright))
        assertEquals(128, fused.width)
        val meanOf = { f: Frame -> f.channel(1).mean() }
        val m = meanOf(fused)
        assertTrue("mean=$m", m > meanOf(dark) && m < meanOf(bright) + 0.01f)
    }

    @Test
    fun `focus stack picks the sharp region of each plane`() {
        val w = 160
        val h = 120
        val sharp = texture(w, h, 4)
        val blurred = Frame.fromPlanes(sharp.channel(0).blur(4), sharp.channel(1).blur(4), sharp.channel(2).blur(4))
        // Plan A : net à gauche, flou à droite. Plan B : l'inverse.
        val a = Frame(w, h, IntArray(w * h) { i -> if (i % w < w / 2) sharp.pixels[i] else blurred.pixels[i] })
        val b = Frame(w, h, IntArray(w * h) { i -> if (i % w < w / 2) blurred.pixels[i] else sharp.pixels[i] })
        val result = FocusStack.stack(listOf(a, b))
        // Côté gauche : la fusion doit ressembler à la partie nette de A.
        val left = Frame(w / 2, h, IntArray(w / 2 * h) { k -> result.frame.pixels[(k / (w / 2)) * w + k % (w / 2)] })
        val leftSharp = Frame(w / 2, h, IntArray(w / 2 * h) { k -> sharp.pixels[(k / (w / 2)) * w + k % (w / 2)] })
        assertTrue("diff=${meanAbsDiff(left, leftSharp)}", meanAbsDiff(left, leftSharp) < 12f)
        // Profondeur relative : gauche proche de 0 (plan A), droite proche de 1 (plan B).
        assertTrue(result.depth[10, h / 2] < 0.5f)
        assertTrue(result.depth[w - 10, h / 2] > 0.5f)
    }

    @Test
    fun `robust mean rejects a moving outlier`() {
        val frames = (0 until 5).map { k ->
            Frame.solid(4, 4, if (k == 2) Frame.pack(255, 255, 255) else Frame.pack(100, 100, 100))
        }
        val out = Stacking.robustMean(frames)
        assertEquals(100, (out.pixels[0] shr 16) and 0xFF)
    }

    @Test
    fun `mean stack averages values`() {
        val a = Frame.solid(2, 2, Frame.pack(0, 0, 0))
        val b = Frame.solid(2, 2, Frame.pack(200, 200, 200))
        val out = Stacking.meanStack(listOf(a, b))
        assertEquals(100, (out.pixels[0] shr 16) and 0xFF)
    }

    @Test
    fun `push pull fills the unknown area with the surrounding colour`() {
        val w = 40
        val h = 30
        val frame = Frame.solid(w, h, Frame.pack(200, 80, 40))
        val known = Plane(w, h, FloatArray(w * h) { i -> if (i % w < w / 2) 1f else 0f })
        // Les pixels inconnus contiennent du noir ; le comblement doit les remplacer par la couleur connue.
        val holed = Frame(w, h, IntArray(w * h) { i -> if (i % w < w / 2) frame.pixels[i] else Frame.pack(0, 0, 0) })
        val result = Inpaint.fill(holed, known)
        val p = result.frame.pixels[(h / 2) * w + w - 3]
        assertTrue("r=${(p shr 16) and 0xFF}", abs(((p shr 16) and 0xFF) - 200) < 6)
        assertTrue(result.generated[w - 3, h / 2] > 0.99f)
        assertEquals(0f, result.generated[2, h / 2], 1e-6f)
    }

    @Test
    fun `homography inverse composes to identity`() {
        val h = Homography.translation(12.0, -4.0) * Homography.scale(1.3, 50.0, 40.0) * Homography.rotation(0.1, 20.0, 30.0)
        val id = h * h.inverse()
        assertTrue(id.isIdentity(1e-6))
    }

    @Test
    fun `warp translation moves content`() {
        val src = Frame(4, 1, intArrayOf(Frame.pack(10, 0, 0), Frame.pack(20, 0, 0), Frame.pack(30, 0, 0), Frame.pack(40, 0, 0)))
        val out = Warp.frame(src, Homography.translation(1.0, 0.0), 4, 1)
        assertEquals(10, (out.pixels[1] shr 16) and 0xFF)
        assertEquals(0, out.pixels[0] ushr 24) // bord découvert : transparent
    }

    @Test
    fun `neutral grade returns the same image and exposure brightens it`() {
        val f = texture(32, 32, 5)
        assertTrue(Grading.apply(f, Grade.NEUTRAL) === f)
        val brighter = Grading.apply(f, Grade(exposureEv = 1f))
        assertTrue(brighter.channel(0).mean() > f.channel(0).mean())
    }

    @Test
    fun `vertical correction detects slanted verticals`() {
        val w = 360
        val h = 480
        val slope = 0.12f
        // Lignes verticales inclinées : x = x0 + slope * y (contours nets, fond uniforme).
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var v = 220
            for (x0 in 30 until w step 60) {
                val lineX = x0 + slope * y
                if (abs(x - lineX) < 2.5f) v = 20
            }
            Frame.pack(v, v, v)
        }
        val est = VerticalCorrection.estimate(Frame(w, h, pixels))
        assertNotNull("aucune verticale détectée", est)
        assertTrue("inclinaison=${est!!.tiltDegrees}", abs(est.tiltDegrees) > 3.0)
        assertFalse(est.homography.isIdentity())
    }
}
