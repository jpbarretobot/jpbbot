package dev.studiocamera.layers

import dev.studiocamera.capture.Step
import dev.studiocamera.capture.StyleCatalog
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LayerEngineTest {

    /** Scène : texture + profondeur qui croît du bas (proche) vers le haut (lointain). */
    private fun scene(w: Int = 96, h: Int = 72): Pair<Frame, Plane> {
        val rnd = Random(7)
        val noise = Plane(w, h, FloatArray(w * h) { rnd.nextFloat() }).blur(1)
        val image = Frame.fromPlanes(
            noise.map { 0.3f + it * 0.5f },
            noise.map { 0.4f + it * 0.3f },
            noise.map { 0.6f - it * 0.2f },
        )
        val depth = Plane(w, h, FloatArray(w * h) { i -> 1f - (i / w).toFloat() / (h - 1) })
        return image to depth
    }

    @Test
    fun `layers form a partition of unity`() {
        val (image, depth) = scene()
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        assertTrue("au moins deux couches", project.layers.size >= 2)
        for (i in 0 until image.width * image.height step 37) {
            var sum = 0f
            project.layers.forEach { sum += it.alpha.data[i] }
            assertEquals(1f, sum, 2e-3f)
        }
    }

    @Test
    fun `neutral edit state returns the captured image exactly`() {
        val (image, depth) = scene()
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        val out = LayerRenderer(project).render(EditState(), image.width, image.height)
        assertTrue(out.frame.pixels.contentEquals(image.pixels))
        assertEquals(0f, out.generated.mean(), 1e-6f)
    }

    @Test
    fun `moving a layer reveals generated pixels and keeps the frame size`() {
        val (image, depth) = scene()
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        val nearest = project.layers.minByOrNull { it.depthMean }!!
        val state = EditState(adjusts = mapOf(nearest.id to LayerAdjust(zoom = 0.7f, offsetX = 0.1f)))
        val out = LayerRenderer(project).render(state, image.width, image.height)
        assertEquals(image.width, out.frame.width)
        assertEquals(image.height, out.frame.height)
        assertTrue("pixels reconstruits attendus", out.generated.mean() > 0.001f)
    }

    @Test
    fun `hiding a layer keeps the image complete through the farthest layer`() {
        val (image, depth) = scene()
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        val nearest = project.layers.minByOrNull { it.depthMean }!!
        val state = EditState(visible = mapOf(nearest.id to false))
        val out = LayerRenderer(project).render(state, image.width, image.height)
        // Aucun pixel ne doit rester noir : la couche lointaine couvre la zone masquée.
        var black = 0
        for (p in out.frame.pixels) if ((p and 0xFFFFFF) == 0) black++
        assertEquals(0, black)
    }

    @Test
    fun `zoom of the background does not shrink the subject plane`() {
        val (image, depth) = scene()
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        val farthest = project.layers.maxByOrNull { it.depthMean }!!
        val state = EditState(adjusts = mapOf(farthest.id to LayerAdjust(zoom = 1.3f)))
        val out = LayerRenderer(project).render(state, image.width, image.height)
        val diff = meanAbs(out.frame, image)
        assertTrue("le rendu doit changer : $diff", diff > 0.5f)
    }

    @Test
    fun `every style declaring preset layers provides a preset`() {
        StyleCatalog.all().forEach { s ->
            if (Step.PRESET_LAYERS in s.steps) assertTrue("preset manquant : ${s.id}", s.layerPreset != null)
            if (s.producesLayers) assertTrue(s.id, Step.PRESET_LAYERS !in s.steps || s.layerPreset != null)
        }
    }

    @Test
    fun `catalog ids are unique and every family is populated`() {
        val ids = StyleCatalog.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(20, ids.size)
        assertTrue(ids.all { id -> id.all { it.isLetterOrDigit() || it == '-' } })
        dev.studiocamera.capture.Family.values().forEach { f ->
            assertTrue("famille vide : $f", StyleCatalog.byFamily(f).size >= 3)
        }
    }

    private fun meanAbs(a: Frame, b: Frame): Float {
        var s = 0.0
        for (i in a.pixels.indices) {
            s += abs(((a.pixels[i] shr 16) and 0xFF) - ((b.pixels[i] shr 16) and 0xFF))
        }
        return (s / a.pixels.size).toFloat()
    }
}
