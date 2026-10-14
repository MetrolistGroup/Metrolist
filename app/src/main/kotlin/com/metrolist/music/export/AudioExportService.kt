/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.metrolist.music.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Foreground service that keeps an export alive when the app goes to the background and shows its progress.
 * Exports run one at a time; every request can be cancelled from the notification.
 */
@OptIn(FlowPreview::class)
@AndroidEntryPoint
class AudioExportService : Service() {
    @Inject
    lateinit var exporter: AudioExporter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val titles = ConcurrentHashMap<String, String>()
    private val oneAtATime = Semaphore(1)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            exporter.states.sample(PROGRESS_INTERVAL_MS).collect { updateProgressNotification() }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_CANCEL -> cancel(intent.getStringExtra(EXTRA_SONG_ID))
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val songId = intent.getStringExtra(EXTRA_SONG_ID)
        if (songId == null) {
            stopIfIdle()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val request =
            ExportRequest(
                songId = songId,
                format = enumValueOrNull<ExportFormat>(intent.getStringExtra(EXTRA_FORMAT)) ?: ExportFormat.ORIGINAL,
                destination = ExportDestination(intent.getStringExtra(EXTRA_TREE_URI)),
                conflictPolicy =
                    enumValueOrNull<ConflictPolicy>(intent.getStringExtra(EXTRA_CONFLICT_POLICY)) ?: ConflictPolicy.KEEP_BOTH,
            )

        titles[songId] = title
        startInForeground(buildProgressNotification())
        if (jobs.containsKey(songId)) return

        jobs[songId] =
            scope.launch {
                try {
                    oneAtATime.withPermit {
                        val result = exporter.export(request)
                        notifyResult(songId, title, success = true, text = result.message(this@AudioExportService), uri = result.uri)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ExportException) {
                    notifyResult(songId, title, success = false, text = failure.error.message(this@AudioExportService))
                } finally {
                    jobs.remove(songId)
                    titles.remove(songId)
                    stopIfIdle()
                }
            }
    }

    private fun cancel(songId: String?) {
        if (songId == null) jobs.values.forEach { it.cancel() } else jobs[songId]?.cancel()
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        // The system caps data-sync services; stop cleanly instead of being killed mid-write.
        jobs.values.forEach { it.cancel() }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification() {
        if (jobs.isEmpty()) return
        notify(NOTIFICATION_ID, buildProgressNotification())
    }

    private fun buildProgressNotification(): Notification {
        val active =
            exporter.states.value.entries.firstOrNull { jobs.containsKey(it.key) && it.value.isActive }
        val title = active?.key?.let { titles[it] }?.takeIf { it.isNotBlank() }
        val queued = jobs.size

        val progress: Float? =
            when (val state = active?.value) {
                is ExportState.Downloading -> state.progress?.let { it * 0.5f }
                is ExportState.Converting -> state.progress?.let { 0.5f + it * 0.5f }
                else -> null
            }
        val phase =
            when (active?.value) {
                is ExportState.Converting -> getString(R.string.export_phase_converting)
                is ExportState.Writing -> getString(R.string.export_phase_writing)
                else -> getString(R.string.export_phase_downloading)
            }

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.download)
                .setContentTitle(getString(R.string.export_notification_title))
                .setContentText(listOfNotNull(title, phase).joinToString(" · "))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    R.drawable.close,
                    getString(android.R.string.cancel),
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, AudioExportService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
        if (queued > 1) builder.setSubText(resources.getQuantityString(R.plurals.n_song, queued, queued))
        if (progress != null) {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notifyResult(
        songId: String,
        title: String,
        success: Boolean,
        text: String,
        uri: String? = null,
    ) {
        mainHandler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.download)
                .setContentTitle(
                    getString(if (success) R.string.export_notification_done else R.string.export_notification_failed, title),
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        if (success && uri != null) {
            val view =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri.toUri(), "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(this, songId.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        notify(RESULT_NOTIFICATION_BASE + (songId.hashCode() and 0xFFFF), builder.build())
    }

    private fun notify(
        id: Int,
        notification: Notification,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.export_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "audio_export"
        private const val NOTIFICATION_ID = 7410
        private const val RESULT_NOTIFICATION_BASE = 7500
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_INTERVAL_MS = 400L

        const val ACTION_START = "com.metrolist.music.export.START"
        const val ACTION_CANCEL = "com.metrolist.music.export.CANCEL"
        private const val EXTRA_SONG_ID = "song_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_TREE_URI = "tree_uri"
        private const val EXTRA_CONFLICT_POLICY = "conflict_policy"

        /** Returns false if the system refused to start the service (e.g. app not in the foreground). */
        fun start(
            context: Context,
            request: ExportRequest,
            title: String,
        ): Boolean {
            val intent =
                Intent(context, AudioExportService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SONG_ID, request.songId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_FORMAT, request.format.name)
                    .putExtra(EXTRA_TREE_URI, request.destination.treeUri)
                    .putExtra(EXTRA_CONFLICT_POLICY, request.conflictPolicy.name)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: RuntimeException) {
                false
            }
        }

        fun cancel(
            context: Context,
            songId: String,
        ) {
            context.startService(
                Intent(context, AudioExportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_SONG_ID, songId),
            )
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
            name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
    }
}
