package dev.studiocamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryTest {

    private fun apply(m: FloatArray, x: Float, y: Float) =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    @Test
    fun `preview covers the whole view for every rotation and format`() {
        val views = listOf(1080f to 2340f, 1440f to 3088f)
        val buffers = listOf(1920f to 1080f, 1440f to 1080f, 1280f to 720f)
        for ((vw, vh) in views) for ((bw, bh) in buffers) for (rot in listOf(0, 90, 180, 270)) {
            val m = PreviewGeometry.coverMatrix(vw, vh, bw, bh, rot)
            val corners = listOf(0f to 0f, bw to 0f, 0f to bh, bw to bh).map { apply(m, it.first, it.second) }
            val minX = corners.minOf { it.first }
            val maxX = corners.maxOf { it.first }
            val minY = corners.minOf { it.second }
            val maxY = corners.maxOf { it.second }
            assertTrue("rot=$rot ${bw}x$bh -> ${vw}x$vh : ne couvre pas en largeur", minX <= 0.01f && maxX >= vw - 0.01f)
            assertTrue("rot=$rot ${bw}x$bh -> ${vw}x$vh : ne couvre pas en hauteur", minY <= 0.01f && maxY >= vh - 0.01f)
            // Recadrage minimal : l'une des deux dimensions touche exactement le bord de la vue.
            val slack = minOf(maxX - minX - vw, maxY - minY - vh)
            assertEquals("rot=$rot : recadrage non minimal", 0f, slack, 0.5f)
            // Le centre du capteur tombe au centre de la vue.
            val (cx, cy) = apply(m, bw / 2f, bh / 2f)
            assertEquals(vw / 2f, cx, 0.01f)
            assertEquals(vh / 2f, cy, 0.01f)
        }
    }

    @Test
    fun `scaling keeps the aspect ratio of the sensor image`() {
        val m = PreviewGeometry.coverMatrix(1080f, 2340f, 1920f, 1080f, 270)
        val (x0, y0) = apply(m, 0f, 0f)
        val (x1, y1) = apply(m, 1920f, 0f)
        val (x2, y2) = apply(m, 0f, 1080f)
        val lenLong = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        val lenShort = Math.hypot((x2 - x0).toDouble(), (y2 - y0).toDouble())
        assertEquals(1920.0 / 1080.0, lenLong / lenShort, 0.001)
    }
}
