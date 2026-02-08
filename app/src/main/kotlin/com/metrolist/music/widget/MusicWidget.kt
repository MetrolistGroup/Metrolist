/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    
    override val sizeMode = SizeMode.Responsive(
        setOf(
            COMPACT_SIZE,
            NORMAL_SIZE
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                if (size.height < 80.dp) {
                    CompactMusicWidgetContent(context)
                } else {
                    MusicWidgetContent(context)
                }
            }
        }
    }

    @Composable
    private fun CompactMusicWidgetContent(context: Context) {
        val prefs = currentState<Preferences>()
        val title = prefs[PREF_TITLE] ?: context.getString(R.string.no_song_playing)
        val artist = prefs[PREF_ARTIST] ?: context.getString(R.string.tap_to_open)
        val isPlaying = prefs[PREF_IS_PLAYING] ?: false
        
        val albumArtBitmap = cachedAlbumArtBitmap

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(2.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art - Compact size
                Box(
                    modifier = GlanceModifier
                        .size(40.dp)
                        .cornerRadius(10.dp)
                        .background(GlanceTheme.colors.secondaryContainer)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArtBitmap != null) {
                        Image(
                            provider = ImageProvider(albumArtBitmap),
                            contentDescription = context.getString(R.string.album_art),
                            modifier = GlanceModifier.size(40.dp).cornerRadius(10.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.mipmap.ic_launcher),
                            contentDescription = context.getString(R.string.album_art),
                            modifier = GlanceModifier.size(28.dp).cornerRadius(6.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // Song info - Single line
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                    )
                    Text(
                        text = artist,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                // Compact controls
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Button
                    ControlButton(
                        context = context,
                        iconRes = R.drawable.ic_widget_skip_previous,
                        contentDescription = context.getString(R.string.previous),
                        action = MusicWidgetActions.ACTION_PREV,
                        size = 28
                    )

                    Spacer(modifier = GlanceModifier.width(2.dp))

                    // Play/Pause Button
                    Box(
                        modifier = GlanceModifier
                            .size(36.dp)
                            .cornerRadius(18.dp)
                            .background(GlanceTheme.colors.primary)
                            .clickable(getServiceAction(context, MusicWidgetActions.ACTION_PLAY_PAUSE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(
                                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                            ),
                            contentDescription = context.getString(R.string.play_pause),
                            modifier = GlanceModifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(2.dp))

                    // Next Button
                    ControlButton(
                        context = context,
                        iconRes = R.drawable.ic_widget_skip_next,
                        contentDescription = context.getString(R.string.next),
                        action = MusicWidgetActions.ACTION_NEXT,
                        size = 28
                    )
                }
            }
        }
    }

    @Composable
    private fun MusicWidgetContent(context: Context) {
        val prefs = currentState<Preferences>()
        val title = prefs[PREF_TITLE] ?: context.getString(R.string.no_song_playing)
        val artist = prefs[PREF_ARTIST] ?: context.getString(R.string.tap_to_open)
        val isPlaying = prefs[PREF_IS_PLAYING] ?: false
        val isLiked = prefs[PREF_IS_LIKED] ?: false
        
        val albumArtBitmap = cachedAlbumArtBitmap

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(28.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art - Left side (Square 1:1)
                Box(
                    modifier = GlanceModifier
                        .size(88.dp)
                        .cornerRadius(16.dp)
                        .background(GlanceTheme.colors.secondaryContainer)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArtBitmap != null) {
                        Image(
                            provider = ImageProvider(albumArtBitmap),
                            contentDescription = context.getString(R.string.album_art),
                            modifier = GlanceModifier.size(88.dp).cornerRadius(16.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.mipmap.ic_launcher),
                            contentDescription = context.getString(R.string.album_art),
                            modifier = GlanceModifier.size(56.dp).cornerRadius(12.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(12.dp))

                // Content Column - Song info and controls (centered vertically)
                Column(
                    modifier = GlanceModifier
                        .defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.Start
                ) {
                    // Song Title row with app logo on the right
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Song Title
                        Text(
                            text = title,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(actionStartActivity<MainActivity>())
                        )
                        
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        
                        // App logo aligned with song info
                        Image(
                            provider = ImageProvider(R.mipmap.ic_launcher),
                            contentDescription = context.getString(R.string.app_name),
                            modifier = GlanceModifier.size(20.dp).cornerRadius(3.dp)
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(2.dp))

                    // Artist Name
                    Text(
                        text = artist,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                    )

                    Spacer(modifier = GlanceModifier.height(10.dp))

                    // Control Buttons - aligned to start (left)
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share Button
                        ControlButton(
                            context = context,
                            iconRes = R.drawable.ic_widget_share,
                            contentDescription = context.getString(R.string.share),
                            action = MusicWidgetActions.ACTION_SHARE,
                            size = 32
                        )

                        Spacer(modifier = GlanceModifier.width(4.dp))

                        // Previous Button
                        ControlButton(
                            context = context,
                            iconRes = R.drawable.ic_widget_skip_previous,
                            contentDescription = context.getString(R.string.previous),
                            action = MusicWidgetActions.ACTION_PREV,
                            size = 36
                        )

                        Spacer(modifier = GlanceModifier.width(4.dp))

                        // Play/Pause Button
                        Box(
                            modifier = GlanceModifier
                                .size(44.dp)
                                .cornerRadius(22.dp)
                                .background(GlanceTheme.colors.primary)
                                .clickable(getServiceAction(context, MusicWidgetActions.ACTION_PLAY_PAUSE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(
                                    if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                                ),
                                contentDescription = context.getString(R.string.play_pause),
                                modifier = GlanceModifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(4.dp))

                        // Next Button
                        ControlButton(
                            context = context,
                            iconRes = R.drawable.ic_widget_skip_next,
                            contentDescription = context.getString(R.string.next),
                            action = MusicWidgetActions.ACTION_NEXT,
                            size = 36
                        )

                        Spacer(modifier = GlanceModifier.width(4.dp))

                        // Like Button (smaller size)
                        ControlButton(
                            context = context,
                            iconRes = if (isLiked) R.drawable.ic_widget_heart else R.drawable.ic_widget_heart_outline,
                            contentDescription = context.getString(R.string.like),
                            action = MusicWidgetActions.ACTION_LIKE,
                            size = 32
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlButton(
        context: Context,
        iconRes: Int,
        contentDescription: String,
        action: String,
        size: Int
    ) {
        Box(
            modifier = GlanceModifier
                .size(size.dp)
                .cornerRadius((size / 2).dp)
                .clickable(getServiceAction(context, action)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size((size - 8).dp)
            )
        }
    }

    private fun getServiceAction(context: Context, action: String): Action {
        val intent = Intent(context, MusicService::class.java).apply {
            this.action = action
        }
        return actionStartService(intent)
    }

    companion object {
        private val COMPACT_SIZE = DpSize(280.dp, 56.dp)
        private val NORMAL_SIZE = DpSize(280.dp, 100.dp)
        
        private val PREF_TITLE = stringPreferencesKey("title")
        private val PREF_ARTIST = stringPreferencesKey("artist")
        private val PREF_IS_PLAYING = booleanPreferencesKey("is_playing")
        private val PREF_IS_LIKED = booleanPreferencesKey("is_liked")
        private val PREF_ALBUM_ART_URL = stringPreferencesKey("album_art_url")
        
        private var cachedAlbumArtBitmap: Bitmap? = null
        private var cachedAlbumArtUrl: String? = null

        suspend fun updateWidget(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            albumArtUrl: String? = null,
            isLiked: Boolean = false
        ) {
            try {
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(MusicWidget::class.java)
                
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
                            this[PREF_TITLE] = title
                            this[PREF_ARTIST] = artist
                            this[PREF_IS_PLAYING] = isPlaying
                            this[PREF_IS_LIKED] = isLiked
                            albumArtUrl?.let { this[PREF_ALBUM_ART_URL] = it }
                        }
                    }
                    MusicWidget().update(context, glanceId)
                }
            } catch (e: Exception) {
                // Widget may not be added
            }
        }
        
        fun getCachedAlbumArt(): Bitmap? = cachedAlbumArtBitmap
    }
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()
}
