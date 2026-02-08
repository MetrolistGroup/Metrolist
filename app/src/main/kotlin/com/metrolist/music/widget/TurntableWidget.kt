/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TurntableWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                TurntableWidgetContent(context)
            }
        }
    }

    @Composable
    private fun TurntableWidgetContent(context: Context) {
        val prefs = currentState<Preferences>()
        val isPlaying = prefs[PREF_IS_PLAYING] ?: false
        val isLiked = prefs[PREF_IS_LIKED] ?: false
        
        val albumArtBitmap = cachedAlbumArtBitmap

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Main circular album art
            Box(
                modifier = GlanceModifier
                    .size(120.dp)
                    .cornerRadius(60.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                if (albumArtBitmap != null) {
                    Image(
                        provider = ImageProvider(albumArtBitmap),
                        contentDescription = context.getString(R.string.album_art),
                        modifier = GlanceModifier.size(120.dp).cornerRadius(60.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.mipmap.ic_launcher),
                        contentDescription = context.getString(R.string.album_art),
                        modifier = GlanceModifier.size(120.dp).cornerRadius(60.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Play button - bottom left of the circle
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(start = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .cornerRadius(16.dp)
                        .background(GlanceTheme.colors.primaryContainer)
                        .clickable(getServiceAction(context, MusicWidgetActions.ACTION_PLAY_PAUSE)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isPlaying) R.drawable.ic_turntable_pause else R.drawable.ic_turntable_play
                        ),
                        contentDescription = context.getString(R.string.play_pause),
                        modifier = GlanceModifier.size(24.dp)
                    )
                }
            }

            // Like button - top right of the circle
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(end = 8.dp, top = 8.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(40.dp)
                        .cornerRadius(20.dp)
                        .background(GlanceTheme.colors.tertiaryContainer)
                        .clickable(getServiceAction(context, MusicWidgetActions.ACTION_LIKE)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isLiked) R.drawable.ic_widget_heart else R.drawable.ic_widget_heart_outline
                        ),
                        contentDescription = context.getString(R.string.like),
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
            }
        }
    }

    private fun getServiceAction(context: Context, action: String): Action {
        val intent = Intent(context, MusicService::class.java).apply {
            this.action = action
        }
        return actionStartService(intent)
    }

    companion object {
        private val PREF_IS_PLAYING = booleanPreferencesKey("is_playing")
        private val PREF_IS_LIKED = booleanPreferencesKey("is_liked")
        private val PREF_ALBUM_ART_URL = stringPreferencesKey("album_art_url")
        
        private var cachedAlbumArtBitmap: Bitmap? = null
        private var cachedAlbumArtUrl: String? = null

        suspend fun updateWidget(
            context: Context,
            isPlaying: Boolean,
            albumArtUrl: String? = null,
            isLiked: Boolean = false
        ) {
            try {
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(TurntableWidget::class.java)
                
                // Load album art bitmap if URL changed
                if (albumArtUrl != null && albumArtUrl != cachedAlbumArtUrl) {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            val loader = ImageLoader(context)
                            val request = ImageRequest.Builder(context)
                                .data(albumArtUrl)
                                .size(300, 300)
                                .build()
                            val result = loader.execute(request)
                            if (result is SuccessResult) {
                                val originalBitmap = result.image.toBitmap()
                                if (originalBitmap.config == Bitmap.Config.HARDWARE) {
                                    originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                } else {
                                    originalBitmap
                                }
                            } else null
                        }
                        if (bitmap != null) {
                            cachedAlbumArtBitmap = bitmap
                            cachedAlbumArtUrl = albumArtUrl
                        }
                    } catch (e: Exception) {
                        // Failed to load album art
                    }
                }

                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[PREF_IS_PLAYING] = isPlaying
                            this[PREF_IS_LIKED] = isLiked
                            albumArtUrl?.let { this[PREF_ALBUM_ART_URL] = it }
                        }
                    }
                    TurntableWidget().update(context, glanceId)
                }
            } catch (e: Exception) {
                // Widget may not be added
            }
        }
    }
}

class TurntableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TurntableWidget()
}
