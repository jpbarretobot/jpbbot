package dev.studiocamera.ui.settings

import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import dev.studiocamera.StudioApp
import dev.studiocamera.ai.AiServices
import dev.studiocamera.camera.CapabilityProbe
import dev.studiocamera.camera.CaptureFormat
import dev.studiocamera.camera.DeviceCapabilities
import dev.studiocamera.camera.ResolutionPreset
import dev.studiocamera.settings.ProcessingQuality
import dev.studiocamera.ui.MainActivity
import dev.studiocamera.ui.Palette
import dev.studiocamera.ui.Screen
import dev.studiocamera.ui.button
import dev.studiocamera.ui.column
import dev.studiocamera.ui.dp
import dev.studiocamera.ui.row
import dev.studiocamera.ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Réglages, capacités détectées, modèles d'IA optionnels et confidentialité. */
class SettingsScreen(
    private val activity: MainActivity,
    private val app: StudioApp,
    private val scope: CoroutineScope,
) : Screen {

    private val caps: DeviceCapabilities by lazy { CapabilityProbe.probe(activity) }
    private val content = activity.column { setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(20)) }

    override val view: ScrollView = ScrollView(activity).apply {
        setBackgroundColor(Palette.BG)
        addView(content)
    }

    override fun onShow() = render()

    private fun render() {
        content.removeAllViews()
        content.addView(title("Paramètres"))

        content.addView(section("Capture"))
        content.addView(valueRow("Format de capture", app.settings.format.value.label) {
            val all = CaptureFormat.values()
            val next = all[(app.settings.format.value.ordinal + 1) % all.size]
            if (next == CaptureFormat.JPEG_DNG && !caps.supportsRaw) {
                activity.toast("Cet appareil ne fournit pas de RAW : format JPEG conservé.")
            } else {
                app.settings.setFormat(next)
            }
            render()
        })
        content.addView(valueRow("Résolution", app.settings.resolution.value.label) {
            val all = ResolutionPreset.values()
            app.settings.setResolution(all[(app.settings.resolution.value.ordinal + 1) % all.size])
            render()
        })
        content.addView(valueRow("Grille de composition", if (app.settings.gridVisible.value) "Affichée" else "Masquée") {
            app.settings.setGridVisible(!app.settings.gridVisible.value)
            render()
        })

        content.addView(section("Traitement"))
        content.addView(valueRow("Qualité de traitement", app.settings.quality.value.label) {
            val all = ProcessingQuality.values()
            app.settings.setQuality(all[(app.settings.quality.value.ordinal + 1) % all.size])
            render()
        })
        content.addView(valueRow("Mode avancé (valeurs détaillées)", if (app.settings.advancedMode.value) "Activé" else "Désactivé") {
            app.settings.setAdvancedMode(!app.settings.advancedMode.value)
            render()
        })
        content.addView(hint("Le traitement haute qualité demande davantage de mémoire vive. Les photos capturées restent intactes si un traitement échoue."))

        content.addView(section("Modèles d'IA optionnels"))
        content.addView(hint("Par défaut, l'application utilise les modèles embarqués (ML Kit, sur l'appareil) et une estimation heuristique de profondeur. Importer un modèle TFLite améliore la profondeur. Aucun téléchargement automatique n'est effectué."))
        AiServices.ModelKind.values().forEach { kind ->
            val installed = app.ai.hasImportedModel(kind)
            content.addView(valueRow(kind.label, if (installed) "Importé : ${app.ai.modelFile(kind).name}" else "Non importé") {
                activity.pickModel(kind) { message ->
                    activity.toast(message)
                    render()
                }
            })
            if (installed) {
                content.addView(activity.row {
                    addView(activity.button("Retirer ce modèle") {
                        scope.launch {
                            app.ai.modelFile(kind).delete()
                            app.ai.reloadModels()
                            activity.toast("Modèle retiré")
                            render()
                        }
                    })
                })
            }
        }

        content.addView(section("Appareil"))
        content.addView(hint(caps.summary()))
        content.addView(hint("Modèle : ${caps.deviceModel} · Android API ${caps.androidApi}"))
        caps.lenses.forEach { l ->
            val flags = listOfNotNull(
                if (l.rawSupported) "RAW" else null,
                if (l.manualSensor) "manuel" else null,
                if (l.hasOis) "OIS" else null,
                if (l.minFocusDiopters > 0f) "mise au point" else "focale fixe",
            ).joinToString(" · ")
            content.addView(hint("• ${if (l.isBack) "Arrière" else "Avant"} — ${l.label}${l.physicalId?.let { " (physique $it)" } ?: ""} — $flags"))
        }

        content.addView(section("Confidentialité"))
        content.addView(hint(
            "Studio Caméra ne demande pas la permission Internet. Vos photos, projets et traitements restent sur cet appareil. " +
                "Les modèles d'IA ne sont importés que lorsque vous le décidez, depuis un fichier de votre choix.",
        ))
        content.addView(hint("Stockage privé de l'application : ${File(activity.filesDir, "library").path}"))
        val versionName = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "inconnue"
        content.addView(hint("Version $versionName (compilation n° ${versionName.substringAfterLast('.')})"))
    }

    private fun title(value: String): TextView = activity.text(value, size = 24f, bold = true)
        .apply { setPadding(0, activity.dp(4), 0, activity.dp(6)) }

    private fun section(value: String): TextView = activity.text(value, size = 15f, color = Palette.ACCENT, bold = true)
        .apply { setPadding(0, activity.dp(18), 0, activity.dp(6)) }

    private fun hint(value: String): TextView = activity.text(value, size = 12f, color = Palette.MUTED)
        .apply { setPadding(0, activity.dp(2), 0, activity.dp(4)) }

    private fun valueRow(label: String, value: String, onClick: () -> Unit): View = activity.row {
        setPadding(0, activity.dp(6), 0, activity.dp(6))
        addView(activity.column {
            addView(activity.text(label, size = 14f))
            addView(activity.text(value, size = 12f, color = Palette.ACCENT, gravity = Gravity.START))
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        addView(activity.button("Modifier", onClick = onClick))
    }
}
