package dev.studiocamera.camera

import kotlin.math.max

/**
 * Géométrie de l'aperçu, sans dépendance Android : vérifiable sur ordinateur.
 *
 * Renvoie les 9 valeurs d'une matrice `android.graphics.Matrix` (ordre MSCALE_X, MSKEW_X, MTRANS_X,
 * MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1). Le tampon du capteur est tourné de [rotationDeg] (sens horaire),
 * puis agrandi juste assez pour couvrir la vue : l'image remplit l'écran, sans déformation, recadrée au centre.
 */
object PreviewGeometry {

    fun coverMatrix(viewW: Float, viewH: Float, bufW: Float, bufH: Float, rotationDeg: Int): FloatArray {
        val rot = ((rotationDeg % 360) + 360) % 360
        // Valeurs exactes pour les quatre quarts de tour : pas d'erreur d'arrondi sur les angles droits.
        val cos = when (rot) { 0 -> 1f; 180 -> -1f; else -> 0f }
        val sin = when (rot) { 90 -> 1f; 270 -> -1f; else -> 0f }
        val swapped = rot == 90 || rot == 270
        val rw = if (swapped) bufH else bufW
        val rh = if (swapped) bufW else bufH
        val s = max(viewW / rw, viewH / rh)
        val a = s * cos
        val b = s * sin
        val cx = bufW / 2f
        val cy = bufH / 2f
        // x' = a(x - cx) - b(y - cy) + vcx ; y' = b(x - cx) + a(y - cy) + vcy
        val tx = viewW / 2f - (a * cx - b * cy)
        val ty = viewH / 2f - (b * cx + a * cy)
        return floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)
    }
}
