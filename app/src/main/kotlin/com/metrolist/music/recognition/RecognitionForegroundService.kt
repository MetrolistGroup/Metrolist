package com.metrolist.music.recognition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.RecognitionHistory
import com.metrolist.music.widget.MusicRecognizerWidgetReceiver
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecognitionEntryPoint {
    fun databaseDao(): DatabaseDao
}

class RecognitionForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var recognitionJob: Job? = null
    private var statusJob: Job? = null
    private var pulseJob: Job? = null
    private var keepNotificationOnStop = false
    private var terminalStateHandled = false

    private val imageLoader by lazy { ImageLoader.Builder(this).build() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Timber.tag(TAG).d("onStartCommand: action=%s", intent?.action)
        when (intent?.action) {
            ACTION_STOP_WIDGET_RECOGNITION -> {
                stopWidgetRecognition()
            }

            ACTION_START_WIDGET_RECOGNITION -> {
                if (recognitionJob?.isActive != true && startInForeground(widgetMode = true)) {
                    startWidgetRecognition()
                }
            }

            else -> {
                if (recognitionJob?.isActive != true && startInForeground(widgetMode = false)) {
                    startRecognition()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recognitionJob?.cancel()
        statusJob?.cancel()
        pulseJob?.cancel()
        serviceScope.cancel()
        if (!keepNotificationOnStop) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startInForeground(widgetMode: Boolean): Boolean {
        val stopIntent =
            if (widgetMode) {
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RecognitionForegroundService::class.java).apply {
                        action = ACTION_STOP_WIDGET_RECOGNITION
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                null
            }
        val openAppIntent =
            if (widgetMode) {
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                null
            }
        val notification =
            buildNotification(
                title = getString(if (widgetMode) R.string.widget_recognizer_listening else R.string.recognize_music),
                contentText =
                    getString(
                        if (widgetMode) {
                            R.string.widget_recognizer_notification_text
                        } else {
                            R.string.recognition_notification_listening
                        },
                    ),
                isTerminal = false,
                contentIntent = openAppIntent,
                largeIcon = null,
                actionIntent = stopIntent,
                actionTitle = stopIntent?.let { getString(android.R.string.cancel) },
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
        } catch (exception: SecurityException) {
            Timber.w(exception, "Unable to start microphone foreground service")
        } catch (exception: RuntimeException) {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                exception::class.java.name != "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                throw exception
            }
            Timber.w(exception, "Unable to start microphone foreground service")
        }
        stopSelf()
        return false
    }

    private fun startRecognition() {
        if (recognitionJob?.isActive == true) return
        keepNotificationOnStop = false
        terminalStateHandled = false
        MusicRecognitionService.reset()

        statusJob?.cancel()
        statusJob =
            serviceScope.launch {
                MusicRecognitionService.recognitionStatus.collect { status ->
                    if (status !is RecognitionStatus.Ready) renderStatus(status)
                }
            }
        recognitionJob =
            serviceScope.launch {
                val result = MusicRecognitionService.recognize(this@RecognitionForegroundService)
                if (
                    result is RecognitionStatus.Error &&
                    MusicRecognitionService.recognitionStatus.value !is RecognitionStatus.Error
                ) {
                    renderStatus(result)
                }
            }
    }

    private fun renderStatus(status: RecognitionStatus) {
        when (status) {
            is RecognitionStatus.Listening -> {
                updateNotification(
                    getString(R.string.recognize_music),
                    getString(R.string.recognition_notification_listening),
                )
            }

            is RecognitionStatus.Processing -> {
                updateNotification(
                    getString(R.string.recognize_music),
                    getString(R.string.recognition_notification_processing),
                )
            }

            is RecognitionStatus.Success -> {
                handleSuccess(status.result)
            }

            is RecognitionStatus.NoMatch -> {
                finishWithMessage(R.string.recognition_notification_no_match)
            }

            is RecognitionStatus.Error -> {
                finishWithMessage(R.string.recognition_notification_failed)
            }

            is RecognitionStatus.Ready -> {
                return
            }
        }
    }

    private fun finishWithMessage(message: Int) {
        if (terminalStateHandled) return
        terminalStateHandled = true
        updateNotification(
            getString(R.string.recognize_music),
            getString(message),
            isTerminal = true,
        )
        finishWithPersistentResult()
    }

    private fun handleSuccess(result: RecognitionResult) {
        if (terminalStateHandled) return
        terminalStateHandled = true
        val pendingIntent = createResultPendingIntent(result)
        updateNotification(
            result.title,
            result.artist,
            isTerminal = true,
            contentIntent = pendingIntent,
            actionIntent = pendingIntent,
            actionTitle = getString(R.string.listen_on_metrolist),
        )

        serviceScope.launch {
            val coverBitmap =
                (result.coverArtHqUrl ?: result.coverArtUrl)?.let { url ->
                    withTimeoutOrNull(1_500L) { loadBitmap(url) }
                }
            if (coverBitmap != null) {
                updateNotification(
                    result.title,
                    result.artist,
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

    private fun startWidgetRecognition() {
        if (recognitionJob?.isActive == true) return
        keepNotificationOnStop = false
        statusJob?.cancel()
        statusJob = null
        MusicRecognitionService.reset()
        saveWidgetState(STATE_LISTENING)
        updateAllWidgets()

        pulseJob?.cancel()
        pulseJob =
            serviceScope.launch {
                var frame = 0
                while (isActive) {
                    widgetPreferences().edit().putInt(PREF_PULSE_FRAME, frame).apply()
                    updateAllWidgets()
                    frame = (frame + 1) % PULSE_FRAME_COUNT
                    delay(PULSE_INTERVAL_MS)
                }
            }

        recognitionJob =
            serviceScope.launch {
                try {
                    when (val status = MusicRecognitionService.recognize(this@RecognitionForegroundService)) {
                        is RecognitionStatus.Success -> saveWidgetSuccess(status.result)
                        is RecognitionStatus.NoMatch -> saveWidgetFailure(STATE_NO_MATCH, status.message)
                        is RecognitionStatus.Error -> saveWidgetFailure(STATE_ERROR, status.message)
                        else -> saveWidgetState(STATE_IDLE)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Timber.e(exception, "Widget recognition failed")
                    saveWidgetFailure(
                        STATE_ERROR,
                        exception.message ?: getString(R.string.widget_recognizer_error),
                    )
                } finally {
                    pulseJob?.cancel()
                    updateAllWidgets()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
    }

    private suspend fun saveWidgetSuccess(result: RecognitionResult) {
        val artPath =
            (result.coverArtHqUrl ?: result.coverArtUrl)
                ?.let { downloadAndCacheAlbumArt(it) }
                ?.absolutePath
                .orEmpty()
        widgetPreferences()
            .edit()
            .putInt(PREF_STATE, STATE_SUCCESS)
            .putString(PREF_SONG_TITLE, result.title)
            .putString(PREF_ARTIST_NAME, result.artist)
            .putString(PREF_COVER_ART_PATH, artPath)
            .putInt(PREF_PULSE_FRAME, 0)
            .apply()

        runCatching {
            EntryPointAccessors
                .fromApplication(
                    applicationContext,
                    RecognitionEntryPoint::class.java,
                ).databaseDao()
                .insert(
                    RecognitionHistory(
                        trackId = result.trackId,
                        title = result.title,
                        artist = result.artist,
                        album = result.album,
                        coverArtUrl = result.coverArtUrl,
                        coverArtHqUrl = result.coverArtHqUrl,
                        genre = result.genre,
                        releaseDate = result.releaseDate,
                        label = result.label,
                        shazamUrl = result.shazamUrl,
                        appleMusicUrl = result.appleMusicUrl,
                        spotifyUrl = result.spotifyUrl,
                        isrc = result.isrc,
                        youtubeVideoId = result.youtubeVideoId,
                        recognizedAt = LocalDateTime.now(),
                    ),
                )
            MusicRecognitionService.resultSavedExternally = true
        }.onFailure { Timber.e(it, "Failed to save recognition result") }
    }

    private fun saveWidgetFailure(
        state: Int,
        message: String,
    ) {
        widgetPreferences()
            .edit()
            .putInt(PREF_STATE, state)
            .putString(PREF_ERROR_MESSAGE, message)
            .putString(PREF_COVER_ART_PATH, "")
            .putInt(PREF_PULSE_FRAME, 0)
            .apply()
    }

    private fun stopWidgetRecognition() {
        recognitionJob?.cancel()
        pulseJob?.cancel()
        saveWidgetState(STATE_IDLE)
        widgetPreferences().edit().putInt(PREF_PULSE_FRAME, 0).apply()
        updateAllWidgets()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun loadBitmap(url: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                imageLoader
                    .execute(
                        ImageRequest
                            .Builder(this@RecognitionForegroundService)
                            .data(url)
                            .size(200, 200)
                            .allowHardware(false)
                            .build(),
                    ).image
                    ?.toBitmap()
            }.getOrNull()
        }

    private suspend fun downloadAndCacheAlbumArt(url: String): File? =
        withContext(Dispatchers.IO) {
            val bitmap = loadBitmap(url) ?: return@withContext null
            runCatching {
                val size = minOf(bitmap.width, bitmap.height)
                val square =
                    Bitmap.createBitmap(
                        bitmap,
                        (bitmap.width - size) / 2,
                        (bitmap.height - size) / 2,
                        size,
                        size,
                    )
                val rounded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    }
                Canvas(rounded).drawRoundRect(
                    RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    24f,
                    24f,
                    paint,
                )
                if (square != bitmap) square.recycle()
                File(cacheDir, ALBUM_ART_CACHE_FILE).also { file ->
                    FileOutputStream(file).use { rounded.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }.onFailure { Timber.e(it, "Failed to cache recognition artwork") }.getOrNull()
        }

    private fun updateNotification(
        title: String,
        contentText: String,
        isTerminal: Boolean = false,
        contentIntent: PendingIntent? = null,
        largeIcon: Bitmap? = null,
        actionIntent: PendingIntent? = null,
        actionTitle: String? = null,
    ) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(
                title,
                contentText,
                isTerminal,
                contentIntent,
                largeIcon,
                actionIntent,
                actionTitle,
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
    ) = NotificationCompat
        .Builder(this, CHANNEL_ID)
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
            if (actionIntent != null && actionTitle != null) addAction(0, actionTitle, actionIntent)
        }.build()

    private fun createResultPendingIntent(result: RecognitionResult) =
        PendingIntent.getActivity(
            this,
            RESULT_PENDING_INTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_RECOGNITION
                putExtra(EXTRA_RECOGNITION_TRACK_ID, result.trackId)
                putExtra(EXTRA_RECOGNITION_TITLE, result.title)
                putExtra(EXTRA_RECOGNITION_ARTIST, result.artist)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun finishWithPersistentResult() {
        keepNotificationOnStop = true
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun widgetPreferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveWidgetState(state: Int) {
        widgetPreferences().edit().putInt(PREF_STATE, state).apply()
    }

    private fun updateAllWidgets() {
        sendBroadcast(
            Intent(this, MusicRecognizerWidgetReceiver::class.java).apply {
                action = MusicRecognizerWidgetReceiver.ACTION_UPDATE_WIDGET
            },
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recognition_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.recognition_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val EXTRA_RECOGNITION_TRACK_ID = "recognition_track_id"
        const val EXTRA_RECOGNITION_TITLE = "recognition_title"
        const val EXTRA_RECOGNITION_ARTIST = "recognition_artist"

        const val ACTION_START_WIDGET_RECOGNITION = "com.metrolist.music.widget.recognizer.START"
        const val ACTION_STOP_WIDGET_RECOGNITION = "com.metrolist.music.widget.recognizer.STOP"
        const val PREFS_NAME = "recognizer_widget_prefs"
        const val PREF_STATE = "state"
        const val PREF_SONG_TITLE = "song_title"
        const val PREF_ARTIST_NAME = "artist_name"
        const val PREF_ERROR_MESSAGE = "error_message"
        const val PREF_PULSE_FRAME = "pulse_frame"
        const val PREF_COVER_ART_PATH = "cover_art_path"
        const val STATE_IDLE = 0
        const val STATE_LISTENING = 1
        const val STATE_PROCESSING = 2
        const val STATE_SUCCESS = 3
        const val STATE_NO_MATCH = 4
        const val STATE_ERROR = 5
        const val ALBUM_ART_CACHE_FILE = "recognizer_widget_art.png"

        private const val PULSE_FRAME_COUNT = 4
        private const val PULSE_INTERVAL_MS = 600L
        private const val CHANNEL_ID = "recognition_channel"
        private const val NOTIFICATION_ID = 9100
        private const val RESULT_PENDING_INTENT_REQUEST_CODE = 9101
        private const val TAG = "RecognitionFgService"
    }
}
