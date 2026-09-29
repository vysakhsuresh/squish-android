package com.squish.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.squish.app.MainActivity
import com.squish.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while an export runs, and says how far
 * it has got in a notification.
 *
 * The encode itself stays where it was - in the view model's scope, on the
 * Transformer's own thread. What this changes is what Android thinks of the
 * process while the screen is locked or the app is behind a call: a process
 * with nothing in front is one it may kill mid-render, and it used to, with
 * the half-written file left behind and nothing to say why. A foreground
 * service is the one thing that tells it not to. If the app is killed anyway,
 * the service goes with it (START_NOT_STICKY): there is no encode to resume.
 *
 * Progress reaches the service through a flow rather than an intent per tick:
 * the encoder reports five times a second, and each would have been a
 * startService call. The notification is refreshed at most every
 * [REFRESH_MS], and at once when the stage changes.
 *
 * No Stop action on the notification: stopping throws away minutes of
 * encoding, and a notification cannot ask first. Tapping it opens the app,
 * where the progress card's Stop does.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var title: String = "your video"
    private var lastShownAt = 0L
    private var lastStage: ExportStage? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // Low importance: a progress bar, not an alert. It neither sounds nor
        // peeks, and it is gone the moment the export is.
        val channel = NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of a video being written"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: title
        // Within the five seconds Android allows after startForegroundService,
        // and with the type each release wants named: media processing where
        // it exists (Android 15), data sync before it. Wrapped because a start
        // refused - the app already in the background by the time this ran -
        // is an export without a notification, not a crashed one.
        val inFront = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(progress.value), foregroundType())
        }.onFailure { android.util.Log.w("SquishExport", "could not go foreground", it) }.isSuccess
        if (!inFront) {
            stopSelf()
            return START_NOT_STICKY
        }
        watching?.cancel()
        watching = scope.launch {
            progress.collectLatest { latest ->
                val now = System.currentTimeMillis()
                val stageChanged = latest.stage != lastStage
                if (!stageChanged && now - lastShownAt < REFRESH_MS) return@collectLatest
                lastShownAt = now
                lastStage = latest.stage
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(latest)) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private fun notification(progress: ExportProgress): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val saving = progress.stage == ExportStage.Saving
        val percent = progress.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val text = when {
            saving -> "Saving to your gallery…"
            progress.stage == ExportStage.Preparing -> "Getting ready…"
            percent == null -> "Starting the encoder…"
            else -> buildString {
                append("$percent%")
                progress.remainingMs?.let { append(" · about ${clockOf(it)} left") }
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_export_notification)
            .setContentTitle("Exporting $title")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null && !saving)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .build()
    }

    /** "4:07" - minutes and seconds, which is the resolution anyone waiting cares about. */
    private fun clockOf(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    companion object {
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.squish.app.export.STOP"
        private const val EXTRA_TITLE = "title"
        private const val REFRESH_MS = 500L

        /** The latest progress, for the notification; the view models write it as the encoder reports. */
        private val progress = MutableStateFlow(ExportProgress())

        /**
         * Starts the service for an export of [title]. Called from the screen
         * that starts the render, while the app is in front - from the
         * background Android refuses, and the export goes on without it.
         */
        fun begin(context: Context, title: String) {
            progress.value = ExportProgress(stage = ExportStage.Preparing)
            val intent = Intent(context, ExportService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { android.util.Log.w("SquishExport", "could not start the export service", it) }
        }

        /** What the encoder just reported. */
        fun update(latest: ExportProgress) {
            progress.value = latest
        }

        /** The export is over - handed over, failed or stopped - and so is the notification. */
        fun end(context: Context) {
            runCatching { context.stopService(Intent(context, ExportService::class.java)) }
        }
    }
}
