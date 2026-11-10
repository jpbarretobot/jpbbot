package dev.studiocamera.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.studiocamera.StudioApp
import dev.studiocamera.ai.AiServices
import dev.studiocamera.ui.camera.CameraScreen
import dev.studiocamera.ui.gallery.GalleryScreen
import dev.studiocamera.ui.gallery.PhotoDetailScreen
import dev.studiocamera.ui.settings.SettingsScreen
import dev.studiocamera.ui.studio.StudioScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Activité unique. Quatre espaces principaux (Appareil photo, Studio IA, Galerie, Paramètres) et des
 * superpositions plein écran pour le détail d'une photo et le Studio.
 */
class MainActivity : Activity() {

    private val app: StudioApp get() = application as StudioApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private val tabButtons = mutableListOf<android.widget.TextView>()

    private var current: Screen? = null
    private var overlay: Screen? = null
    private var currentTab = TAB_CAMERA

    private var cameraScreen: CameraScreen? = null
    private var pendingModel: Pair<AiServices.ModelKind, (String) -> Unit>? = null

    private val tabs = listOf(
        "◉" to "Appareil photo",
        "▦" to "Galerie",
        "⚙" to "Paramètres",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        content = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(0, dp(6), 0, dp(10))
            tabs.forEachIndexed { index, (glyph, label) ->
                val tab = text("$glyph\n$label", size = 11f, color = Palette.MUTED, gravity = Gravity.CENTER).apply {
                    setOnClickListener { selectTab(index) }
                }
                tabButtons += tab
                addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        // Affichage bord à bord (imposé à partir d'Android 15) : on écarte le contenu de la barre d'état
        // et la barre d'onglets de la barre de navigation, pour que rien ne soit masqué ni collé au bord.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = systemBarInsets(insets)
            v.setPadding(0, top, 0, 0)
            tabBar.setPadding(0, dp(6), 0, dp(10) + bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        selectTab(TAB_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        current?.onShow()
    }

    override fun onPause() {
        current?.onHide()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        if (isFinishing) app.camera.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val top = overlay
        when {
            top != null -> closeOverlay()
            current?.onBack() == true -> Unit
            currentTab != TAB_CAMERA -> selectTab(TAB_CAMERA)
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------------

    private fun selectTab(index: Int) {
        closeOverlaySilently()
        currentTab = index
        val screen: Screen = when (index) {
            TAB_CAMERA -> cameraScreen ?: CameraScreen(this, app, scope).also { cameraScreen = it }
            TAB_GALLERY -> GalleryScreen(this, app, scope, GalleryScreen.Filter.ALL) { record -> openPhoto(record.id) }
            else -> SettingsScreen(this, app, scope)
        }
        show(screen)
        tabButtons.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == index) Palette.ACCENT else Palette.MUTED)
        }
    }

    private fun show(screen: Screen) {
        current?.onHide()
        content.removeAllViews()
        content.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        current = screen
        screen.onShow()
    }

    fun openGallery() = selectTab(TAB_GALLERY)

    fun openPhoto(id: String) = showOverlay(PhotoDetailScreen(this, app, scope, id) { studioId -> openStudio(studioId) })

    fun openStudio(id: String) = showOverlay(StudioScreen(this, app, scope, id))

    private fun showOverlay(screen: Screen) {
        overlay?.onHide()
        overlay = screen
        content.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        screen.onShow()
        current?.onHide()
    }

    fun closeOverlay() {
        closeOverlaySilently()
        current?.onShow()
    }

    private fun closeOverlaySilently() {
        overlay?.let { ov ->
            ov.onHide()
            content.removeView(ov.view)
        }
        overlay = null
    }

    // ---------------------------------------------------------------------------------------------
    // Utilitaires partagés par les écrans
    // ---------------------------------------------------------------------------------------------

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun requestCameraPermission() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            cameraScreen?.onPermissionResult(granted)
        }
    }

    /**
     * Importe un modèle TFLite choisi par l'utilisateur (sélecteur système, aucun accès réseau).
     * Le fichier est copié dans le stockage privé de l'application.
     */
    fun pickModel(kind: AiServices.ModelKind, onDone: (String) -> Unit) {
        pendingModel = kind to onDone
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_MODEL)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MODEL) return
        val (kind, done) = pendingModel ?: return
        pendingModel = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            done("Import annulé")
            return
        }
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val target = app.ai.modelFile(kind)
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(target.parentFile, target.name + ".tmp").outputStream().use { out -> input.copyTo(out) }
                    } ?: error("fichier illisible")
                    File(target.parentFile, target.name + ".tmp").renameTo(target)
                    app.ai.reloadModels()
                    "Modèle importé : ${kind.label}"
                }.getOrElse { "Import impossible : ${it.message}" }
            }
            done(message)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars())
            i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            top to bottom
        }

    private fun text(content: String, size: Float, color: Int, gravity: Int): android.widget.TextView =
        android.widget.TextView(this).apply {
            text = content
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            this.gravity = gravity
        }

    companion object {
        private const val TAB_CAMERA = 0
        private const val TAB_GALLERY = 1
        private const val REQ_CAMERA = 10
        private const val REQ_NOTIFICATIONS = 11
        private const val REQ_PICK_MODEL = 12
    }
}
