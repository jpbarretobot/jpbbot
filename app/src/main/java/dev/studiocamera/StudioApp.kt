package dev.studiocamera

import android.app.Application
import dev.studiocamera.ai.AiServices
import dev.studiocamera.ai.FaceAnalyzer
import dev.studiocamera.camera.CameraEngine
import dev.studiocamera.capture.StyleCatalog
import dev.studiocamera.processing.ProcessingPipeline
import dev.studiocamera.settings.AppSettings
import dev.studiocamera.storage.PhotoLibrary
import dev.studiocamera.work.PendingJobs

/**
 * Conteneur de dépendances manuel : une seule instance de chaque service pour toute l'application.
 */
class StudioApp : Application() {

    val settings: AppSettings by lazy { AppSettings(this) }
    val catalog: StyleCatalog get() = StyleCatalog
    val library: PhotoLibrary by lazy { PhotoLibrary(this) }
    val jobs: PendingJobs by lazy { PendingJobs(this) }
    val ai: AiServices by lazy { AiServices(this) }
    val faces: FaceAnalyzer by lazy { FaceAnalyzer() }
    val pipeline: ProcessingPipeline by lazy { ProcessingPipeline(ai, faces) }

    /** Moteur caméra partagé : le thread caméra reste vivant tant que l'application l'est. */
    val camera: CameraEngine by lazy { CameraEngine(this) }
}
