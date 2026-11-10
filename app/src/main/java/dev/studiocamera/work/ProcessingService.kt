package dev.studiocamera.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.studiocamera.StudioApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui traite les travaux en attente, l'un après l'autre.
 * Le viseur reste utilisable pendant le traitement : la capture et le calcul sont découplés.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var draining = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startInForeground(buildNotification(this, "Traitement photo en cours…", ongoing = true))
        synchronized(this) {
            if (!draining) {
                draining = true
                scope.launch { drain() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        val app = application as StudioApp
        try {
            while (true) {
                val next = app.jobs.pendingIds().firstOrNull() ?: break
                process(app, next)
            }
        } finally {
            synchronized(this) { draining = false }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun process(app: StudioApp, jobId: String) {
        val title = app.jobs.styleTitleOf(jobId)
        app.jobs.publish(JobStatus(jobId, title, JobStatus.State.RUNNING, "Analyse…"))
        try {
            val job = app.jobs.read(jobId)
            val style = app.catalog.byId(job.styleId) ?: error("Style inconnu : ${job.styleId}")
            val output = app.pipeline.run(
                style,
                job.shots,
                job.metas.map { it ?: dummyMeta },
                app.settings.workingSide(),
            ) { step -> app.jobs.publish(JobStatus(jobId, title, JobStatus.State.RUNNING, step)) }

            val record = app.library.create(
                styleId = style.id,
                styleTitle = style.title,
                family = style.family.name,
                original = job.shots.first(),
                raw = job.raws.firstOrNull(),
                notes = job.captureNotes + output.notes,
                generatedRatio = output.generatedRatio,
                hasProject = output.project != null,
                meta = job.metas.firstOrNull(),
                shotCount = job.shots.size,
                lensLabel = job.lensLabel,
            )
            val rendered = dev.studiocamera.io.Bitmaps.encodeJpeg(output.rendered, 94)
            app.library.addVersion(record.id, rendered, if (output.project != null) "Rendu multicouche" else "Rendu automatique", "auto")
            output.project?.let {
                app.library.saveProject(record.id, it)
                app.library.saveEditState(record.id, output.state)
            }
            app.jobs.complete(jobId)
            app.jobs.publish(JobStatus(jobId, title, JobStatus.State.DONE, "Prête dans la galerie", record.id))
            notifyDone(this, title)
        } catch (e: Throwable) {
            app.jobs.markFailed(jobId, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 42
        private const val DONE_ID = 43
        private val dummyMeta = dev.studiocamera.camera.ShotMeta(0L, null, null, null, null)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProcessingService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Traitements photo", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun buildNotification(context: Context, text: String, ongoing: Boolean): Notification {
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
            return builder
                .setContentTitle("Studio Caméra")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(ongoing)
                .build()
        }

        private fun notifyDone(context: Context, title: String) {
            ensureChannel(context)
            val n = buildNotification(context, "$title : photo prête dans la galerie", ongoing = false)
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        }
    }
}
