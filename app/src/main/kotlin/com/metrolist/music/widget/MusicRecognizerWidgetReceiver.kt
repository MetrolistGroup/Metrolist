/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.recognition.MusicRecognitionService
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.ALBUM_ART_CACHE_FILE
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_ARTIST_NAME
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_COVER_ART_PATH
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_ERROR_MESSAGE
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_PULSE_FRAME
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_SONG_TITLE
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREF_STATE
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.PREFS_NAME
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_ERROR
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_IDLE
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_LISTENING
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_NO_MATCH
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_PROCESSING
import com.metrolist.music.widget.MusicRecognizerWidgetService.Companion.STATE_SUCCESS
import java.io.File

/**
 * AppWidgetProvider for the Music Recognizer Widget.
 *
 * Sizes:
 *  - 1×1 (minWidth < 110dp): Only the animated mic circle
 *  - 1×3 (minWidth 110–229dp): Album art + song info + mic button (compact)
 *  - 1×4 (minWidth ≥ 230dp): Album art + song info + mic button (wide, default)
 *
 * Click behaviour:
 *  - Mic button  → start / stop recognition
 *  - Album art / text area (SUCCESS) → open app on recognition history screen
 *  - Album art / text area (other)   → open app on recognition screen
 */
class MusicRecognizerWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAllWidgets(context, appWidgetManager)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_START_RECOGNITION -> handleStartRecognition(context)
            ACTION_UPDATE_WIDGET -> updateAllWidgets(context, AppWidgetManager.getInstance(context))
            ACTION_RESET_STATE -> {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_STATE, STATE_IDLE)
                    .putString(PREF_SONG_TITLE, "")
                    .putString(PREF_ARTIST_NAME, "")
                    .putString(PREF_ERROR_MESSAGE, "")
                    .putString(PREF_COVER_ART_PATH, "")
                    .putInt(PREF_PULSE_FRAME, 0)
                    .apply()
                File(context.cacheDir, ALBUM_ART_CACHE_FILE).delete()
                updateAllWidgets(context, AppWidgetManager.getInstance(context))
            }
        }
    }

    // ─── Recognition start / stop ─────────────────────────────────────────────

    private fun handleStartRecognition(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentState = prefs.getInt(PREF_STATE, STATE_IDLE)

        // If active → stop
        if (currentState == STATE_LISTENING || currentState == STATE_PROCESSING) {
            context.startService(
                Intent(context, MusicRecognizerWidgetService::class.java).apply {
                    action = MusicRecognizerWidgetService.ACTION_STOP_RECOGNITION
                }
            )
            return
        }

        // Showing a result/error → clear it before starting a new search
        if (currentState == STATE_SUCCESS || currentState == STATE_NO_MATCH || currentState == STATE_ERROR) {
            prefs.edit().putInt(PREF_STATE, STATE_IDLE).apply()
        }

        // No mic permission → open the app so the user can grant it
        if (!MusicRecognitionService.hasRecordPermission(context)) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_RECOGNITION
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            return
        }

        // Start recognition foreground service
        val serviceIntent = Intent(context, MusicRecognizerWidgetService::class.java).apply {
            action = MusicRecognizerWidgetService.ACTION_START_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    // ─── Widget update ────────────────────────────────────────────────────────

    private fun updateAllWidgets(context: Context, appWidgetManager: AppWidgetManager) {
        val componentName = ComponentName(context, MusicRecognizerWidgetReceiver::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = prefs.getInt(PREF_STATE, STATE_IDLE)
        val songTitle = prefs.getString(PREF_SONG_TITLE, "") ?: ""
        val artistName = prefs.getString(PREF_ARTIST_NAME, "") ?: ""
        val errorMessage = prefs.getString(PREF_ERROR_MESSAGE, "") ?: ""
        val coverArtPath = prefs.getString(PREF_COVER_ART_PATH, "") ?: ""
        val pulseFrame = prefs.getInt(PREF_PULSE_FRAME, 0)

        // Load album art bitmap from the cached file (synchronous, already on disk)
        val albumArtBitmap = if (state == STATE_SUCCESS && coverArtPath.isNotEmpty()) {
            try { BitmapFactory.decodeFile(coverArtPath) } catch (_: Exception) { null }
        } else null

        widgetIds.forEach { widgetId ->
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)

            val views = when {
                minWidth < 110 -> createTinyViews(context, state, pulseFrame)
                minWidth < 230 -> createCompactViews(context, state, songTitle, artistName, errorMessage, albumArtBitmap, pulseFrame)
                else -> createWideViews(context, state, songTitle, artistName, errorMessage, albumArtBitmap, pulseFrame)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    // ─── Layout builders ──────────────────────────────────────────────────────

    private fun createWideViews(
        context: Context,
        state: Int,
        songTitle: String,
        artistName: String,
        errorMessage: String,
        albumArt: android.graphics.Bitmap?,
        pulseFrame: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recognizer_wide)
        applyAlbumArt(views, state, albumArt)
        applyTextState(context, views, state, songTitle, artistName, errorMessage)
        applyMicState(views, state, pulseFrame, R.id.widget_recognizer_mic_container, R.id.widget_recognizer_pulse)
        views.setOnClickPendingIntent(R.id.widget_recognizer_mic_container, getMicIntent(context))
        val infoIntent = getInfoAreaIntent(context)
        views.setOnClickPendingIntent(R.id.widget_recognizer_text_area, infoIntent)
        views.setOnClickPendingIntent(R.id.widget_recognizer_album_art, infoIntent)
        return views
    }

    private fun createCompactViews(
        context: Context,
        state: Int,
        songTitle: String,
        artistName: String,
        errorMessage: String,
        albumArt: android.graphics.Bitmap?,
        pulseFrame: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recognizer_compact)
        applyAlbumArt(views, state, albumArt)
        applyTextState(context, views, state, songTitle, artistName, errorMessage)
        applyMicState(views, state, pulseFrame, R.id.widget_recognizer_mic_container, R.id.widget_recognizer_pulse)
        views.setOnClickPendingIntent(R.id.widget_recognizer_mic_container, getMicIntent(context))
        val infoIntent = getInfoAreaIntent(context)
        views.setOnClickPendingIntent(R.id.widget_recognizer_text_area, infoIntent)
        views.setOnClickPendingIntent(R.id.widget_recognizer_album_art, infoIntent)
        return views
    }

    private fun createTinyViews(
        context: Context,
        state: Int,
        pulseFrame: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recognizer_tiny)
        applyMicState(views, state, pulseFrame, R.id.widget_recognizer_tiny_mic_container, R.id.widget_recognizer_tiny_pulse)
        views.setOnClickPendingIntent(R.id.widget_recognizer_tiny_root, getMicIntent(context))
        return views
    }

    // ─── State helpers ────────────────────────────────────────────────────────

    private fun applyAlbumArt(
        views: RemoteViews,
        state: Int,
        albumArt: android.graphics.Bitmap?
    ) {
        if (state == STATE_SUCCESS && albumArt != null) {
            views.setImageViewBitmap(R.id.widget_recognizer_album_art, albumArt)
            views.setViewVisibility(R.id.widget_recognizer_album_art, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_recognizer_album_art, View.GONE)
        }
    }

    private fun applyTextState(
        context: Context,
        views: RemoteViews,
        state: Int,
        songTitle: String,
        artistName: String,
        errorMessage: String
    ) {
        when (state) {
            STATE_IDLE -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    context.getString(R.string.widget_recognizer_tap_to_search))
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.GONE)
            }
            STATE_LISTENING -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    context.getString(R.string.widget_recognizer_listening))
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.GONE)
            }
            STATE_PROCESSING -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    context.getString(R.string.widget_recognizer_processing))
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.GONE)
            }
            STATE_SUCCESS -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    songTitle.ifEmpty { context.getString(R.string.widget_recognizer_unknown_song) })
                views.setTextViewText(R.id.widget_recognizer_artist_name,
                    artistName.ifEmpty { context.getString(R.string.widget_recognizer_unknown_artist) })
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.VISIBLE)
            }
            STATE_NO_MATCH -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    context.getString(R.string.widget_recognizer_no_match))
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.GONE)
            }
            STATE_ERROR -> {
                views.setTextViewText(R.id.widget_recognizer_song_title,
                    context.getString(R.string.widget_recognizer_error))
                views.setTextViewText(R.id.widget_recognizer_artist_name,
                    errorMessage.ifEmpty { context.getString(R.string.widget_recognizer_error_generic) })
                views.setViewVisibility(R.id.widget_recognizer_artist_name, View.VISIBLE)
            }
        }
    }

    private fun applyMicState(
        views: RemoteViews,
        state: Int,
        pulseFrame: Int,
        micContainerId: Int,
        pulseViewId: Int
    ) {
        val isActive = state == STATE_LISTENING || state == STATE_PROCESSING

        views.setInt(
            micContainerId, "setBackgroundResource",
            if (isActive) R.drawable.widget_mic_button_bg_active else R.drawable.widget_mic_button_bg
        )

        val pulseDrawable = if (isActive) {
            when (pulseFrame % 4) {
                0 -> R.drawable.widget_mic_pulse_1
                1 -> R.drawable.widget_mic_pulse_2
                2 -> R.drawable.widget_mic_pulse_3
                else -> R.drawable.widget_mic_pulse_4
            }
        } else R.drawable.widget_mic_pulse_idle

        views.setImageViewResource(pulseViewId, pulseDrawable)
    }

    // ─── PendingIntents ───────────────────────────────────────────────────────

    /** Tap on mic button → start or stop recognition */
    private fun getMicIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 20,
            Intent(context, MusicRecognizerWidgetReceiver::class.java).apply {
                action = ACTION_START_RECOGNITION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Tap on song info area / album art → always open the recognition screen.
     * On SUCCESS the screen will show the result that the widget service already
     * set on [MusicRecognitionService.recognitionStatus].
     */
    private fun getInfoAreaIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 21,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_RECOGNITION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START_RECOGNITION = "com.metrolist.music.widget.recognizer.TAP_MIC"
        const val ACTION_UPDATE_WIDGET = "com.metrolist.music.widget.recognizer.UPDATE"
        const val ACTION_RESET_STATE = "com.metrolist.music.widget.recognizer.RESET"
    }
}
