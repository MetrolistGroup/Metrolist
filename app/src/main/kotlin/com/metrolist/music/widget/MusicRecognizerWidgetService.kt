/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.RecognitionHistory
import com.metrolist.music.recognition.MusicRecognitionService
import com.metrolist.shazamkit.models.RecognitionStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecognizerWidgetEntryPoint {
    fun databaseDao(): DatabaseDao
}

/**
 * Foreground service that handles music recognition for the widget.
 * Runs recognition in the foreground to allow microphone access,
 * downloads & caches the album art, then broadcasts results back to
 * the widget receiver.
 */
class MusicRecognizerWidgetService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recognitionJob: Job? = null
    private var pulseJob: Job? = null

    private val imageLoader by lazy {
        ImageLoader.Builder(this).crossfade(false).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: action=%s", intent?.action)
        when (intent?.action) {
            ACTION_START_RECOGNITION -> {
                startForegroundNotification()
                startRecognition()
            }
            ACTION_STOP_RECOGNITION -> stopRecognitionAndService()
        }
        return START_NOT_STICKY
    }

    // ─── Foreground notification ──────────────────────────────────────────────

    private fun startForegroundNotification() {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicRecognizerWidgetService::class.java).apply {
                action = ACTION_STOP_RECOGNITION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(getString(R.string.widget_recognizer_listening))
            .setContentText(getString(R.string.widget_recognizer_notification_text))
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(android.R.string.cancel),
                stopIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ─── Recognition flow ─────────────────────────────────────────────────────

    private fun startRecognition() {
        Timber.tag(TAG).d("Starting widget music recognition")
        saveState(STATE_LISTENING)
        updateAllWidgets()

        // Animate pulse rings while active
        pulseJob = serviceScope.launch {
            var frame = 0
            while (isActive) {
                savePulseFrame(frame)
                updateAllWidgets()
                frame = (frame + 1) % PULSE_FRAME_COUNT
                delay(PULSE_INTERVAL_MS)
            }
        }

        recognitionJob = serviceScope.launch {
            try {
                val result = MusicRecognitionService.recognize(this@MusicRecognizerWidgetService)
                pulseJob?.cancel()

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                when (result) {
                    is RecognitionStatus.Success -> {
                        Timber.tag(TAG).i("Widget recognition success: '%s' by %s", result.result.title, result.result.artist)
                        val artPath = downloadAndCacheAlbumArt(
                            result.result.coverArtHqUrl ?: result.result.coverArtUrl
                        )?.absolutePath ?: ""
                        prefs.edit()
                            .putInt(PREF_STATE, STATE_SUCCESS)
                            .putString(PREF_SONG_TITLE, result.result.title)
                            .putString(PREF_ARTIST_NAME, result.result.artist)
                            .putString(PREF_COVER_ART_PATH, artPath)
                            .putInt(PREF_PULSE_FRAME, 0)
                            .apply()
                        // Save to history so the result is persisted even if the user
                        // never opens the recognition screen after seeing the widget result.
                        try {
                            val dao = EntryPointAccessors.fromApplication(
                                applicationContext,
                                RecognizerWidgetEntryPoint::class.java
                            ).databaseDao()
                            dao.insert(
                                RecognitionHistory(
                                    trackId = result.result.trackId,
                                    title = result.result.title,
                                    artist = result.result.artist,
                                    album = result.result.album,
                                    coverArtUrl = result.result.coverArtUrl,
                                    coverArtHqUrl = result.result.coverArtHqUrl,
                                    genre = result.result.genre,
                                    releaseDate = result.result.releaseDate,
                                    label = result.result.label,
                                    shazamUrl = result.result.shazamUrl,
                                    appleMusicUrl = result.result.appleMusicUrl,
                                    spotifyUrl = result.result.spotifyUrl,
                                    isrc = result.result.isrc,
                                    youtubeVideoId = result.result.youtubeVideoId,
                                    recognizedAt = LocalDateTime.now()
                                )
                            )
                            // Tell RecognitionScreen not to save again (avoid duplicate entry)
                            MusicRecognitionService.resultSavedExternally = true
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to save recognition result to database")
                        }
                    }
                    is RecognitionStatus.NoMatch -> {
                        Timber.tag(TAG).i("Widget recognition: no match — %s", result.message)
                        prefs.edit()
                            .putInt(PREF_STATE, STATE_NO_MATCH)
                            .putString(PREF_ERROR_MESSAGE, result.message)
                            .putString(PREF_COVER_ART_PATH, "")
                            .putInt(PREF_PULSE_FRAME, 0)
                            .apply()
                    }
                    is RecognitionStatus.Error -> {
                        Timber.tag(TAG).w("Widget recognition error: %s", result.message)
                        prefs.edit()
                            .putInt(PREF_STATE, STATE_ERROR)
                            .putString(PREF_ERROR_MESSAGE, result.message)
                            .putString(PREF_COVER_ART_PATH, "")
                            .putInt(PREF_PULSE_FRAME, 0)
                            .apply()
                    }
                    else -> {
                        prefs.edit()
                            .putInt(PREF_STATE, STATE_IDLE)
                            .putString(PREF_COVER_ART_PATH, "")
                            .putInt(PREF_PULSE_FRAME, 0)
                            .apply()
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Widget recognition crashed")
                pulseJob?.cancel()
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_STATE, STATE_ERROR)
                    .putString(PREF_ERROR_MESSAGE, e.message ?: getString(R.string.widget_recognizer_error))
                    .putString(PREF_COVER_ART_PATH, "")
                    .putInt(PREF_PULSE_FRAME, 0)
                    .apply()
            } finally {
                updateAllWidgets()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Downloads [url], clips it to a rounded square (corner 24dp), and writes
     * the result to [ALBUM_ART_CACHE_FILE] inside the app cache directory.
     * Returns the file on success or null on any failure.
     */
    private suspend fun downloadAndCacheAlbumArt(url: String?): File? {
        if (url.isNullOrBlank()) {
            Timber.tag(TAG).d("No album art URL provided, skipping download")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@MusicRecognizerWidgetService)
                    .data(url)
                    .size(200, 200)
                    .allowHardware(false)
                    .build()
                val bitmap = imageLoader.execute(request).image?.toBitmap() ?: return@withContext null
                val rounded = getRoundedCornerBitmap(bitmap, 24f)
                val file = File(cacheDir, ALBUM_ART_CACHE_FILE)
                FileOutputStream(file).use { rounded.compress(Bitmap.CompressFormat.PNG, 90, it) }
                Timber.tag(TAG).d("Album art downloaded and cached from %s", url)
                file
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to download album art from %s", url)
                null
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val xOff = (bitmap.width - size) / 2
        val yOff = (bitmap.height - size) / 2
        val square = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, paint)
        if (square != bitmap) square.recycle()
        return output
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun stopRecognitionAndService() {
        Timber.tag(TAG).d("Stopping recognition and service")
        recognitionJob?.cancel()
        pulseJob?.cancel()
        saveState(STATE_IDLE)
        savePulseFrame(0)
        updateAllWidgets()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveState(state: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_STATE, state)
            .apply()
    }

    private fun savePulseFrame(frame: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_PULSE_FRAME, frame)
            .apply()
    }

    private fun updateAllWidgets() {
        sendBroadcast(
            Intent(this, MusicRecognizerWidgetReceiver::class.java).apply {
                action = MusicRecognizerWidgetReceiver.ACTION_UPDATE_WIDGET
            }
        )
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("Service destroyed")
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.widget_recognizer_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.widget_recognizer_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START_RECOGNITION = "com.metrolist.music.widget.recognizer.START"
        const val ACTION_STOP_RECOGNITION = "com.metrolist.music.widget.recognizer.STOP"

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
        private const val CHANNEL_ID = "music_recognizer_widget"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "WidgetRecognizer"
    }
}
