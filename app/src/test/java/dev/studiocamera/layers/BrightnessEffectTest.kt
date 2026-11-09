package dev.studiocamera.layers

import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Vérifie que la luminosité d'une couche modifie réellement le rendu (curseurs de la barre). */
class BrightnessEffectTest {

    @Test
    fun `brightness of a layer changes the rendered image`() {
        val w = 96
        val h = 72
        val rnd = Random(3)
        val noise = Plane(w, h, FloatArray(w * h) { rnd.nextFloat() }).blur(1)
        val image = Frame.fromPlanes(
            noise.map { 0.3f + it * 0.5f },
            noise.map { 0.4f + it * 0.3f },
            noise.map { 0.6f - it * 0.2f },
        )
        val depth = Plane(w, h, FloatArray(w * h) { i -> 1f - (i / w).toFloat() / (h - 1) })
        val project = LayerBuilder.build(image, depth, subjectMask = null)
        val renderer = LayerRenderer(project)
        val target = project.layers.maxByOrNull { it.coverage }!!

        val neutral = renderer.render(EditState(), w, h).frame
        val brighter = renderer.render(
            EditState(adjusts = mapOf(target.id to LayerAdjust(brightness = 0.5f))), w, h,
        ).frame
        val darker = renderer.render(
            EditState(adjusts = mapOf(target.id to LayerAdjust(brightness = -0.5f))), w, h,
        ).frame

        fun mean(f: Frame) = f.pixels.sumOf { (it shr 16) and 0xFF }.toDouble() / f.pixels.size
        val mNeutral = mean(neutral)
        val mBright = mean(brighter)
        val mDark = mean(darker)
        println("mean neutral=$mNeutral bright=$mBright dark=$mDark")
        assertTrue("la luminosité positive doit éclaircir", mBright > mNeutral + 1.0)
        assertTrue("la luminosité négative doit assombrir", mDark < mNeutral - 1.0)
    }
}
