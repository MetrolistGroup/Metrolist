/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.os.BundleCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.android.R
import com.metrolist.android.ui.theme.extractThemeColor
import com.metrolist.android.ui.theme.systemDynamicColorScheme
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.ui.theme.orIfIllegible
import com.metrolist.shared.ui.theme.themeColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MetrolistWidgetManager(
    private val context: Context,
    private val playlistWidgetManager: PlaylistWidgetManager,
    private val imageLoader: ImageLoader,
) {
    private val updateMutex = Mutex()
    private var cachedArtworkUri: String? = null
    private var cachedAlbumArt: Bitmap? = null
    private var cachedCircularAlbumArt: Bitmap? = null
    private var cachedArtworkSeed: Color? = null

    private val progressDemand = MutableStateFlow(false)
    internal val hasProgressWidgets = progressDemand.asStateFlow()
    private var publishedPlayback: Pair<String?, Long>? = null
    private val progressLevels = mutableMapOf<Int, Int>()
    private val musicWidgetViews = mutableMapOf<Int, MusicWidgetRemoteViews>()

    internal fun refreshProgressDemand() {
        val manager = AppWidgetManager.getInstance(context)
        val music = manager.getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))
        val playlists = manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetReceiver::class.java))
        progressDemand.value = music.any { id ->
            val options = manager.getAppWidgetOptions(id)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width =
                options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                )
            val height =
                options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                )
            musicWidgetSizePolicy(width, height, context.resources.configuration.fontScale).layout == MusicWidgetLayout.PLAYER
        } ||
            playlists.any { id ->
                manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP
            }
    }

    internal suspend fun updateProgress(
        songId: String,
        playbackInstanceId: Long,
        duration: Long,
        position: Long,
    ) = updateMutex.withLock {
        if (publishedPlayback != (songId to playbackInstanceId)) return@withLock
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))
        progressLevels.keys.retainAll(ids.toSet())
        musicWidgetViews.keys.retainAll(ids.toSet())
        val level = progressLevel(duration, position)
        ids.forEach { id ->
            val previous = progressLevels[id] ?: return@forEach
            if (previous == level) return@forEach
            val snapshot = musicWidgetViews[id] ?: return@forEach
            if (snapshot.sizes != widgetSizes(manager.getAppWidgetOptions(id))) return@forEach
            // Android merges only top-level actions, so wrapped layouts need their complete tree.
            manager.updateAppWidget(id, snapshot.create(level))
            progressLevels[id] = level
        }
        playlistWidgetManager.updateProgress(duration, position)
        refreshProgressDemand()
    }

    @Volatile
    private var lastWidgetState =
        WidgetState(
            title = context.getString(R.string.not_playing),
            artist = context.getString(R.string.tap_to_play),
            artworkUri = null,
            isPlaying = false,
            isLiked = false,
            duration = 0,
            currentPosition = 0,
        )

    internal suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        controls: WidgetControls = WidgetControls(),
        songId: String? = null,
        playbackInstanceId: Long = 0,
    ) = updateMutex.withLock {
        publishedPlayback = null
        withContext(Dispatchers.IO) {
            lastWidgetState = WidgetState(title, artist, artworkUri, isPlaying, isLiked, duration, currentPosition)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val musicWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))
            val turntableWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TurntableWidgetReceiver::class.java))
            musicWidgetViews.keys.retainAll(musicWidgetIds.toSet())
            if (musicWidgetIds.isNotEmpty() || turntableWidgetIds.isNotEmpty()) {
                val albumArt = getCachedAlbumArt(artworkUri)
                progressLevels.clear()
                musicWidgetIds.forEach { widgetId ->
                    val options = appWidgetManager.getAppWidgetOptions(widgetId)
                    val snapshot =
                        createRemoteViewsForSize(
                            options,
                            title,
                            artist,
                            albumArt,
                            isLiked,
                            duration,
                            currentPosition,
                            controls,
                        )
                    appWidgetManager.updateAppWidget(widgetId, snapshot.create())
                    musicWidgetViews[widgetId] = snapshot
                    if (widgetSizes(options).any { (width, height) ->
                            musicWidgetSizePolicy(width, height, context.resources.configuration.fontScale).layout ==
                                MusicWidgetLayout.PLAYER
                        }
                    ) {
                        progressLevels[widgetId] = progressLevel(duration, currentPosition)
                    }
                }

                if (turntableWidgetIds.isNotEmpty()) {
                    val artwork = getCachedCircularAlbumArt(albumArt)
                    turntableWidgetIds.forEach { widgetId ->
                        appWidgetManager.updateAppWidget(
                            widgetId,
                            createTurntableRemoteViewsForSize(appWidgetManager.getAppWidgetOptions(widgetId), artwork, isPlaying, isLiked),
                        )
                    }
                }
            }

            playlistWidgetManager.updateWidgets(title, artist, artworkUri, isPlaying, isLiked, duration, currentPosition)
            publishedPlayback = songId to playbackInstanceId
            refreshProgressDemand()
        }
    }

    suspend fun updateIdleMusicWidgets(appWidgetIds: IntArray) =
        updateMutex.withLock {
            if (appWidgetIds.isEmpty()) return@withLock
            publishedPlayback = null
            withContext(Dispatchers.IO) {
                updateMusicWidgets(appWidgetIds, idleWidgetState())
            }
        }

    private suspend fun updateMusicWidgets(
        appWidgetIds: IntArray,
        state: WidgetState,
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val albumArt = getCachedAlbumArt(state.artworkUri)
        appWidgetIds.forEach { widgetId ->
            val snapshot =
                createRemoteViewsForSize(
                    appWidgetManager.getAppWidgetOptions(widgetId),
                    state.title,
                    state.artist,
                    albumArt,
                    state.isLiked,
                    state.duration,
                    state.currentPosition,
                )
            appWidgetManager.updateAppWidget(widgetId, snapshot.create())
            musicWidgetViews[widgetId] = snapshot
        }
    }

    suspend fun updateIdleTurntableWidgets(appWidgetIds: IntArray) =
        updateMutex.withLock {
            if (appWidgetIds.isEmpty()) return@withLock
            withContext(Dispatchers.IO) {
                updateTurntableWidgets(appWidgetIds, idleWidgetState())
            }
        }

    private suspend fun updateTurntableWidgets(
        appWidgetIds: IntArray,
        state: WidgetState,
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val albumArt = getCachedAlbumArt(state.artworkUri)
        val artwork = getCachedCircularAlbumArt(albumArt)
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(
                widgetId,
                createTurntableRemoteViewsForSize(appWidgetManager.getAppWidgetOptions(widgetId), artwork, state.isPlaying, state.isLiked),
            )
        }
    }

    private fun idleWidgetState() =
        WidgetState(
            title = context.getString(R.string.not_playing),
            artist = context.getString(R.string.tap_to_play),
            artworkUri = null,
            isPlaying = false,
            isLiked = false,
            duration = 0,
            currentPosition = 0,
        )

    private fun createRemoteViewsForSize(
        options: Bundle,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isLiked: Boolean,
        duration: Long,
        currentPosition: Long,
        controls: WidgetControls = WidgetControls(),
    ): MusicWidgetRemoteViews {
        val seed = cachedArtworkSeed ?: albumArt?.extractThemeColor()?.also { cachedArtworkSeed = it }

        fun scheme(dark: Boolean) =
            musicWidgetColorScheme(seed, dark) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    systemDynamicColorScheme(context, dark).orIfIllegible(dark) {
                        themeColorScheme(Color(context.getColor(android.R.color.system_accent1_500)), dark, ThemePaletteStyle.TONAL_SPOT)
                    }
                } else {
                    themeColorScheme(DefaultThemeColor, dark, ThemePaletteStyle.TONAL_SPOT)
                }
            }
        val usePalette = albumArt != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val lightColors = if (usePalette) scheme(false) else null
        val darkColors = if (usePalette) scheme(true) else null

        fun create(
            width: Int,
            height: Int,
        ): RemoteViews {
            val policy = musicWidgetSizePolicy(width, height, context.resources.configuration.fontScale)
            val views =
                when (policy.layout) {
                    MusicWidgetLayout.CONTROLS -> {
                        RemoteViews(context.packageName, R.layout.widget_compact_controls)
                    }

                    MusicWidgetLayout.WIDE -> {
                        createCompactWideRemoteViews(title, artist, albumArt).apply {
                            setViewVisibility(R.id.widget_wide_artwork, if (policy.showArtwork) View.VISIBLE else View.GONE)
                        }
                    }

                    MusicWidgetLayout.SQUARE -> {
                        createCompactSquareRemoteViews(title, artist, albumArt).apply {
                            setViewVisibility(R.id.widget_square_album_art, if (policy.showArtwork) View.VISIBLE else View.GONE)
                        }
                    }

                    MusicWidgetLayout.PLAYER -> {
                        createRemoteViews(title, artist, albumArt, duration, currentPosition)
                    }
                }
            bindMusicControls(views, controls, isLiked, policy.layout != MusicWidgetLayout.PLAYER)
            if (lightColors != null && darkColors != null) {
                bindMusicColors(views, policy.layout, isLiked, lightColors, darkColors)
            }
            return views
        }
        return remoteViewsForSizes(options, ::create)
    }

    private fun widgetSizes(options: Bundle): List<Pair<Int, Int>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            if (!sizes.isNullOrEmpty()) return sizes.map { it.width.toInt() to it.height.toInt() }.distinct()
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
        return listOf(maxWidth to minHeight, minWidth to maxHeight)
    }

    private fun remoteViewsForSizes(
        options: Bundle,
        create: (Int, Int) -> RemoteViews,
    ): MusicWidgetRemoteViews {
        val sizes = widgetSizes(options)
        val useSizeMap =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java).isNullOrEmpty()
        return MusicWidgetRemoteViews(sizes, sizes.map { (width, height) -> create(width, height) }, useSizeMap)
    }

    private fun bindMusicControls(
        views: RemoteViews,
        controls: WidgetControls,
        isLiked: Boolean,
        inGroup: Boolean,
    ) {
        fun bind(
            id: Int,
            label: Int,
            enabled: Boolean,
            action: String,
            requestCode: Int,
        ) {
            views.setContentDescription(id, context.getString(label))
            views.setBoolean(id, "setEnabled", enabled)
            views.setOnClickPendingIntent(id, getMusicWidgetIntent(action, requestCode))
        }
        val playbackRequested = controls.playbackRequested
        val label = if (playbackRequested) R.string.pause else R.string.play
        bind(R.id.widget_prev_container, R.string.previous, controls.canPrevious, MusicWidgetReceiver.ACTION_PREVIOUS, 1)
        bind(R.id.widget_play_pause_container, label, controls.canPlayPause, MusicWidgetReceiver.ACTION_PLAY_PAUSE, 2)
        bind(R.id.widget_next_container, R.string.next, controls.canNext, MusicWidgetReceiver.ACTION_NEXT, 3)
        bind(
            R.id.widget_like_container,
            if (isLiked) R.string.unlike else R.string.action_like,
            controls.canLike,
            MusicWidgetReceiver.ACTION_LIKE,
            4,
        )
        views.setTextViewText(R.id.widget_play_pause_label, context.getString(label))
        views.setImageViewResource(R.id.widget_play_pause, if (playbackRequested) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setImageViewResource(R.id.widget_play_background, playButtonBackground(playbackRequested, inGroup))
        views.setImageViewResource(R.id.widget_like_background, likeButtonBackground(isLiked))
        views.setImageViewResource(
            R.id.widget_like_button,
            if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav,
        )
        views.setOnClickPendingIntent(android.R.id.background, getOpenAppIntent())
    }

    private fun bindMusicColors(
        views: RemoteViews,
        layout: MusicWidgetLayout,
        isLiked: Boolean,
        light: ColorScheme,
        dark: ColorScheme,
    ) {
        fun color(
            id: Int,
            method: String,
            role: (ColorScheme) -> Color,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setColorInt(id, method, role(light).toArgb(), role(dark).toArgb())
            } else {
                val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                views.setInt(id, method, role(if (isDark) dark else light).toArgb())
            }
        }

        fun tint(
            id: Int,
            role: (ColorScheme) -> Color,
        ) = color(id, "setColorFilter", role)

        fun text(
            id: Int,
            role: (ColorScheme) -> Color,
        ) = color(id, "setTextColor", role)

        text(R.id.widget_play_pause_label) { it.onPrimary }
        tint(R.id.widget_surface_background) { it.surface }
        tint(R.id.widget_play_background) { it.primary }
        tint(R.id.widget_play_pause) { it.onPrimary }
        for (id in intArrayOf(R.id.widget_prev_background, R.id.widget_next_background)) {
            tint(id) { it.secondary }
        }
        for (id in intArrayOf(R.id.widget_prev, R.id.widget_next)) {
            tint(id) { it.onSecondary }
        }
        tint(R.id.widget_like_background) { if (isLiked) it.tertiary else it.secondary }
        tint(R.id.widget_like_button) { if (isLiked) it.onTertiary else it.onSecondary }
        val textIds =
            when (layout) {
                MusicWidgetLayout.PLAYER -> R.id.widget_song_title to R.id.widget_artist_name
                MusicWidgetLayout.WIDE -> R.id.widget_wide_song_title to R.id.widget_wide_artist_name
                MusicWidgetLayout.SQUARE -> R.id.widget_square_song_title to R.id.widget_square_artist_name
                MusicWidgetLayout.CONTROLS -> null
            }
        textIds?.let { (titleId, artistId) ->
            text(titleId) { it.onSurface }
            text(artistId) { it.onSurfaceVariant }
        }
        if (layout == MusicWidgetLayout.PLAYER) {
            tint(R.id.widget_progress_track) { it.surfaceContainerHighest }
            tint(R.id.widget_progress_fill) { it.primary }
        }
    }

    private fun createRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        duration: Long,
        currentPosition: Long,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_music_player)
        views.setTextViewText(R.id.widget_song_title, title)
        views.setTextViewText(R.id.widget_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_album_art, albumArt?.let { getRoundedCornerBitmap(it) } ?: getRoundedDefaultIcon())
        views.setInt(R.id.widget_progress_fill, "setImageLevel", progressLevel(duration, currentPosition))
        views.setViewVisibility(R.id.widget_progress_container, if (duration > 0) View.VISIBLE else View.INVISIBLE)
        views.setOnClickPendingIntent(R.id.widget_album_art, getOpenAppIntent())
        return views
    }

    private fun createCompactSquareRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_square)
        views.setTextViewText(R.id.widget_square_song_title, title)
        views.setTextViewText(R.id.widget_square_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_square_album_art, albumArt?.let { getRoundedCornerBitmap(it) } ?: getRoundedDefaultIcon())
        views.setOnClickPendingIntent(R.id.widget_square_album_art, getOpenAppIntent())
        return views
    }

    private fun createCompactWideRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_wide)
        views.setTextViewText(R.id.widget_wide_song_title, title)
        views.setTextViewText(R.id.widget_wide_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_wide_album_art, albumArt?.let { getRoundedCornerBitmap(it) } ?: getRoundedDefaultIcon())
        views.setOnClickPendingIntent(R.id.widget_wide_album_art, getOpenAppIntent())
        return views
    }

    private fun createTurntableRemoteViewsForSize(
        options: Bundle,
        circularAlbumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
    ): RemoteViews {
        val artwork = circularAlbumArt ?: getCircularDefaultIcon()
        return remoteViewsForSizes(options) { width, height ->
            createTurntableRemoteViews(artwork, isPlaying, isLiked).apply {
                val (horizontal, vertical) = turntableWidgetPadding(width, height)
                val density = context.resources.displayMetrics.density
                val horizontalPx = (horizontal * density).roundToInt()
                val verticalPx = (vertical * density).roundToInt()
                setViewPadding(android.R.id.background, horizontalPx, verticalPx, horizontalPx, verticalPx)
            }
        }.create()
    }

    private fun createTurntableRemoteViews(
        circularAlbumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_turntable)
        views.setImageViewBitmap(R.id.widget_turntable_album_art, circularAlbumArt ?: getCircularDefaultIcon())
        views.setImageViewResource(
            R.id.widget_turntable_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setInt(R.id.widget_turntable_play_container, "setBackgroundResource", playButtonBackground(isPlaying, inGroup = false))
        views.setInt(R.id.widget_turntable_like_button, "setBackgroundResource", likeButtonBackground(isLiked))
        views.setImageViewResource(
            R.id.widget_turntable_like_icon,
            if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav,
        )
        views.setOnClickPendingIntent(R.id.widget_turntable_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(
            R.id.widget_turntable_play_container,
            getMusicWidgetIntent(TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE, 6),
        )
        views.setOnClickPendingIntent(
            R.id.widget_turntable_prev_button,
            getMusicWidgetIntent(TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS, 7),
        )
        views.setOnClickPendingIntent(
            R.id.widget_turntable_next_button,
            getMusicWidgetIntent(TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT, 8),
        )
        views.setOnClickPendingIntent(
            R.id.widget_turntable_like_button,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_LIKE, 9),
        )
        return views
    }

    private suspend fun getCachedAlbumArt(artworkUri: String?): Bitmap? {
        if (artworkUri != null && artworkUri == cachedArtworkUri && cachedAlbumArt != null) return cachedAlbumArt
        val albumArt = artworkUri?.let { loadAlbumArt(it, 300) }
        cachedArtworkUri = artworkUri
        cachedAlbumArt = albumArt
        cachedCircularAlbumArt = null
        cachedArtworkSeed = null
        return albumArt
    }

    private fun getCachedCircularAlbumArt(albumArt: Bitmap?): Bitmap? =
        cachedCircularAlbumArt ?: albumArt?.let(::getCookieBitmap)?.also { cachedCircularAlbumArt = it }

    private suspend fun loadAlbumArt(
        artworkUri: String,
        size: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(artworkUri)
                        .size(size, size)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                imageLoader.execute(request).image?.toBitmap()
            }.getOrNull()
        }

    private fun getRoundedDefaultIcon(): Bitmap {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = createBitmap(300, 300)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 300, 300)
        drawable.draw(canvas)
        return getRoundedCornerBitmap(bitmap)
    }

    private fun getCircularDefaultIcon(): Bitmap {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = createBitmap(300, 300)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 300, 300)
        drawable.draw(canvas)
        return getCookieBitmap(bitmap)
    }

    private fun getOpenAppIntent(): PendingIntent = openAppPendingIntent(context, 0)

    private fun getMusicWidgetIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent = widgetActionPendingIntent(context, action, requestCode)
}
