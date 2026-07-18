package com.metrolist.music.recognition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

class RecognitionForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var recognitionJob: Job? = null
    private var statusJob: Job? = null
    private var keepNotificationOnStop = false
    private var terminalStateHandled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: flags=%d, startId=%d", flags, startId)
        if (!startInForeground()) return START_NOT_STICKY
        startRecognitionIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("Service destroyed (keepNotification=%b)", keepNotificationOnStop)
        recognitionJob?.cancel()
        statusJob?.cancel()
        serviceScope.cancel()
        if (!keepNotificationOnStop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        val notification =
            buildNotification(
                title = getString(R.string.recognize_music),
                contentText = getString(R.string.recognition_notification_listening),
                isTerminal = false,
                contentIntent = null,
                largeIcon = null,
                actionIntent = null,
                actionTitle = null,
            )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (foregroundTypeException: SecurityException) {
            Timber.w(foregroundTypeException, "Unable to start microphone foreground service")
            stopSelf()
            return false
        } catch (runtimeException: RuntimeException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    runtimeException::class.java.name ==
                    "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                Timber.w(runtimeException, "Unable to start microphone foreground service")
                stopSelf()
                return false
            }
            throw runtimeException
        }
    }

    private fun startRecognitionIfNeeded() {
        if (recognitionJob?.isActive == true) return
        Timber.tag(TAG).d("Starting recognition flow")

        keepNotificationOnStop = false
        terminalStateHandled = false
        MusicRecognitionService.reset()
        Timber.tag(TAG).d("MusicRecognitionService reset")

        statusJob?.cancel()
        statusJob =
            serviceScope.launch {
                MusicRecognitionService.recognitionStatus.collect { status ->
                    when (status) {
                        is RecognitionStatus.Ready -> Unit
                        else -> renderStatus(status)
                    }
                }
            }

        recognitionJob =
            serviceScope.launch {
                val result = MusicRecognitionService.recognize(this@RecognitionForegroundService)
                if (result is RecognitionStatus.Error &&
                    MusicRecognitionService.recognitionStatus.value !is RecognitionStatus.Error
                ) {
                    renderStatus(result)
                }
            }
    }

    private fun renderStatus(status: RecognitionStatus) {
        when (status) {
            is RecognitionStatus.Listening -> {
                Timber.tag(TAG).d("Status: Listening")
                updateNotification(
                    title = getString(R.string.recognize_music),
                    contentText = getString(R.string.recognition_notification_listening),
                    isTerminal = false,
                    contentIntent = null,
                    largeIcon = null,
                    actionIntent = null,
                    actionTitle = null,
                )
            }

            is RecognitionStatus.Processing -> {
                Timber.tag(TAG).d("Status: Processing")
                updateNotification(
                    title = getString(R.string.recognize_music),
                    contentText = getString(R.string.recognition_notification_processing),
                    isTerminal = false,
                    contentIntent = null,
                    largeIcon = null,
                    actionIntent = null,
                    actionTitle = null,
                )
            }

            is RecognitionStatus.Success -> {
                Timber.tag(TAG).i("Status: Success — '%s' by %s", status.result.title, status.result.artist)
                handleSuccess(status.result)
            }

            is RecognitionStatus.NoMatch -> {
                if (terminalStateHandled) return
                terminalStateHandled = true
                Timber.tag(TAG).i("Status: No match")
                updateNotification(
                    title = getString(R.string.recognize_music),
                    contentText = getString(R.string.recognition_notification_no_match),
                    isTerminal = true,
                    contentIntent = null,
                    largeIcon = null,
                    actionIntent = null,
                    actionTitle = null,
                )
                finishWithPersistentResult()
            }

            is RecognitionStatus.Error -> {
                if (terminalStateHandled) return
                terminalStateHandled = true
                Timber.tag(TAG).w("Status: Error — %s", status.message)
                updateNotification(
                    title = getString(R.string.recognize_music),
                    contentText = getString(R.string.recognition_notification_failed),
                    isTerminal = true,
                    contentIntent = null,
                    largeIcon = null,
                    actionIntent = null,
                    actionTitle = null,
                )
                finishWithPersistentResult()
            }

            is RecognitionStatus.Ready -> Unit
        }
    }

    private fun updateNotification(
        title: String,
        contentText: String,
        isTerminal: Boolean,
        contentIntent: PendingIntent?,
        largeIcon: Bitmap?,
        actionIntent: PendingIntent?,
        actionTitle: String?,
    ) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                contentText = contentText,
                isTerminal = isTerminal,
                contentIntent = contentIntent,
                largeIcon = largeIcon,
                actionIntent = actionIntent,
                actionTitle = actionTitle,
            ),
        )
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        isTerminal: Boolean,
        contentIntent: PendingIntent?,
        largeIcon: Bitmap?,
        actionIntent: PendingIntent?,
        actionTitle: String?,
    ) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(!isTerminal)
            .setAutoCancel(isTerminal)
            .setContentIntent(contentIntent)
            .setLargeIcon(largeIcon)
            .apply {
                if (actionIntent != null && actionTitle != null) {
                    addAction(0, actionTitle, actionIntent)
                }
            }
            .build()

    private fun handleSuccess(result: RecognitionResult) {
        if (terminalStateHandled) return
        terminalStateHandled = true

        val pendingIntent = createResultPendingIntent(result)
        updateNotification(
            title = result.title,
            contentText = result.artist,
            isTerminal = true,
            contentIntent = pendingIntent,
            largeIcon = null,
            actionIntent = pendingIntent,
            actionTitle = getString(R.string.listen_on_metrolist),
        )

        serviceScope.launch {
            val coverUrl = result.coverArtHqUrl ?: result.coverArtUrl
            val coverBitmap =
                if (coverUrl == null) {
                    null
                } else {
                    withTimeoutOrNull(1_500L) {
                        loadBitmap(coverUrl)
                    }
                }

            if (coverBitmap != null) {
                updateNotification(
                    title = result.title,
                    contentText = result.artist,
                    isTerminal = true,
                    contentIntent = pendingIntent,
                    largeIcon = coverBitmap,
                    actionIntent = pendingIntent,
                    actionTitle = getString(R.string.listen_on_metrolist),
                )
            }
            finishWithPersistentResult()
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG).d("Loading cover art bitmap from %s", url)
            runCatching {
                val connection = (URL(url).openConnection() as? HttpURLConnection)
                    ?: return@runCatching null
                try {
                    connection.connectTimeout = BITMAP_CONNECT_TIMEOUT_MS
                    connection.readTimeout = BITMAP_READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = true
                    connection.doInput = true
                    connection.connect()
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }

    private fun createResultPendingIntent(result: RecognitionResult): PendingIntent {
        val launchIntent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_RECOGNITION
                putExtra(EXTRA_RECOGNITION_TRACK_ID, result.trackId)
                putExtra(EXTRA_RECOGNITION_TITLE, result.title)
                putExtra(EXTRA_RECOGNITION_ARTIST, result.artist)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        return PendingIntent.getActivity(
            this,
            RESULT_PENDING_INTENT_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun finishWithPersistentResult() {
        Timber.tag(TAG).d("Finishing with persistent notification")
        keepNotificationOnStop = true
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recognition_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.recognition_notification_channel_desc)
                setShowBadge(false)
            }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_RECOGNITION_TRACK_ID = "recognition_track_id"
        const val EXTRA_RECOGNITION_TITLE = "recognition_title"
        const val EXTRA_RECOGNITION_ARTIST = "recognition_artist"

        private const val CHANNEL_ID = "recognition_channel"
        private const val NOTIFICATION_ID = 9100
        private const val RESULT_PENDING_INTENT_REQUEST_CODE = 9101
        private const val TAG = "RecognitionFgService"
        private const val BITMAP_CONNECT_TIMEOUT_MS = 1_200
        private const val BITMAP_READ_TIMEOUT_MS = 1_200
    }
}
