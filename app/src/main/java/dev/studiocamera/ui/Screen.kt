package dev.studiocamera.ui

import android.view.View

/** Un écran de l'application : une vue racine et son cycle de vie simple. */
interface Screen {
    val view: View

    /** Appelé quand l'écran devient visible. */
    fun onShow() {}

    /** Appelé quand l'écran est masqué (libère la caméra, arrête les animations...). */
    fun onHide() {}

    /** Retourne true si l'écran a consommé le retour arrière. */
    fun onBack(): Boolean = false
}
