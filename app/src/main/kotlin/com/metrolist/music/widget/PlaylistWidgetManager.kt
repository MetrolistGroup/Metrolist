/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val imageLoader
        get() = context.imageLoader
    @Volatile
    private var cachedArtworkUri: String? = null
    @Volatile
    private var cachedAlbumArt: Bitmap? = null
    @Volatile
    private var cachedRoundedAlbumArt: Bitmap? = null
    @Volatile
    private var cachedRoundedSource: Bitmap? = null
    private val fallbackArtworkCache = ConcurrentHashMap<FallbackArtworkKey, Bitmap>()
    private val roundedAppIconCache = ConcurrentHashMap<Int, Bitmap>()

    @Volatile
    private var lastWidgetState = WidgetState(
        title = context.getString(R.string.not_playing),
        artist = context.getString(R.string.choose_something_below),
        artworkUri = null,
        isPlaying = false,
        isLiked = false,
        duration = 0,
        currentPosition = 0,
    )

    init {
        observeQuickPickChanges()
    }

    suspend fun updateIdleWidgets() {
        updateWidgets(
            title = context.getString(R.string.not_playing),
            artist = context.getString(R.string.choose_something_below),
            artworkUri = null,
            isPlaying = false,
            isLiked = false,
            duration = 0,
            currentPosition = 0,
        )
    }

    suspend fun updateIdleWidget(
        appWidgetId: Int,
        options: Bundle,
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val state = lastWidgetState
        val albumArt = getCachedAlbumArt(state.artworkUri)
        val quickPicks = buildQuickPicks()
        val views = createRemoteViews(
            options = options,
            title = state.title,
            artist = state.artist,
            albumArt = albumArt,
            isPlaying = state.isPlaying,
            isLiked = state.isLiked,
            duration = state.duration,
            currentPosition = state.currentPosition,
            quickPicks = quickPicks,
        )
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private suspend fun refreshWidgetsFromLastState() {
        val state = lastWidgetState
        updateWidgets(
            title = state.title,
            artist = state.artist,
            artworkUri = state.artworkUri,
            isPlaying = state.isPlaying,
            isLiked = state.isLiked,
            duration = state.duration,
            currentPosition = state.currentPosition,
        )
    }

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
    ) {
        lastWidgetState = WidgetState(
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            isPlaying = isPlaying,
            isLiked = isLiked,
            duration = duration,
            currentPosition = currentPosition,
        )

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, PlaylistWidgetReceiver::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (widgetIds.isEmpty()) return

        val albumArt = getCachedAlbumArt(artworkUri)
        val quickPicks = buildQuickPicks()

        widgetIds.forEach { widgetId ->
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val views = createRemoteViews(
                options = options,
                title = title,
                artist = artist,
                albumArt = albumArt,
                isPlaying = isPlaying,
                isLiked = isLiked,
                duration = duration,
                currentPosition = currentPosition,
                quickPicks = quickPicks,
            )
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQuickPickChanges() {
        combine(
            database.speedDialDao.getAll().map { items ->
                items.map { item ->
                    SpeedDialSnapshot(
                        type = item.type,
                        id = item.id,
                        title = item.title,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            },
            database.playlistsByCreateDateAsc().map { playlists ->
                playlists.map { playlist ->
                    PlaylistSnapshot(
                        id = playlist.playlist.id,
                        browseId = playlist.playlist.browseId,
                        name = playlist.playlist.name,
                        thumbnailUrl = playlist.thumbnails.firstOrNull(),
                        isLocal = playlist.playlist.isLocal,
                    )
                }
            },
            database.likedSongsByCreateDateAsc().map { songs ->
                songs.map { song ->
                    SongSnapshot(
                        id = song.id,
                        thumbnailUrl = song.thumbnailUrl,
                    )
                }
            },
            database.downloadedSongsByCreateDateAsc().map { songs ->
                songs.map { song ->
                    SongSnapshot(
                        id = song.id,
                        thumbnailUrl = song.thumbnailUrl,
                    )
                }
            },
            database.mostPlayedSongs(fromTimeStamp = LocalDateTime.of(1970, 1, 1, 0, 0), limit = 50, offset = 0, toTimeStamp = LocalDateTime.now()).map { songs ->
                songs.map { song ->
                    SongSnapshot(
                        id = song.id,
                        thumbnailUrl = song.thumbnailUrl,
                    )
                }
            },
        ) { speedDial, playlists, likedSongs, downloadedSongs, topSongs ->
            QuickPickSnapshot(
                speedDial = speedDial,
                playlists = playlists,
                likedSongs = likedSongs,
                downloadedSongs = downloadedSongs,
                topSongs = topSongs,
            )
        }
            .distinctUntilChanged()
            .debounce(500L)
            .onEach { refreshWidgetsFromLastState() }
            .launchIn(applicationScope)
    }

    private suspend fun createRemoteViews(
        options: Bundle,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long,
        currentPosition: Long,
        quickPicks: List<QuickPick>,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_playlist)

        views.setTextViewText(R.id.widget_playlist_song_title, title)
        views.setTextViewText(R.id.widget_playlist_artist_name, artist)

        if (albumArt != null) {
            views.setImageViewBitmap(R.id.widget_playlist_album_art, getRoundedAlbumArt(albumArt))
        } else {
            views.setImageViewBitmap(R.id.widget_playlist_album_art, getRoundedAppIcon(36f))
        }

        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_playlist_play_pause, playPauseIcon)
        views.setImageViewResource(
            R.id.widget_playlist_like_button,
            if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav,
        )

        val progressLevel = if (duration > 0) {
            ((currentPosition.toDouble() / duration.toDouble()) * 10000).toInt().coerceIn(0, 10000)
        } else {
            0
        }
        views.setInt(R.id.widget_playlist_progress_fill, "setImageLevel", progressLevel)

        views.setOnClickPendingIntent(R.id.widget_playlist_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(
            R.id.widget_playlist_prev_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PREVIOUS, 501),
        )
        views.setOnClickPendingIntent(
            R.id.widget_playlist_play_pause_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 502),
        )
        views.setOnClickPendingIntent(
            R.id.widget_playlist_next_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_NEXT, 503),
        )
        views.setOnClickPendingIntent(
            R.id.widget_playlist_like_button,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_LIKE, 504),
        )

        bindQuickPicks(views, options, quickPicks)
        return views
    }

    private suspend fun bindQuickPicks(
        views: RemoteViews,
        options: Bundle,
        quickPicks: List<QuickPick>,
    ) {
        val maxItems = maxQuickPicksForSize(options)
        val displayCount = balancedDisplayCount(quickPicks.size, maxItems)
        val visiblePicks = quickPicks.take(displayCount)
        val visibleSlots = (0 until displayCount).toSet()
        val reservedEmptySlots = reservedEmptySlotsFor(displayCount)

        views.setViewVisibility(
            R.id.widget_playlist_quick_picks_section,
            if (displayCount > 0) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_playlist_row_2,
            if (displayCount > 4) View.VISIBLE else View.GONE,
        )

        cardSlots.forEachIndexed { index, slot ->
            val visibility = when {
                index in visibleSlots -> View.VISIBLE
                index in reservedEmptySlots -> View.INVISIBLE
                else -> View.GONE
            }
            views.setViewVisibility(slot.containerId, visibility)
        }

        visiblePicks.forEachIndexed { index, item ->
            val slot = cardSlots[index]
            views.setTextViewText(slot.titleId, item.title)
            views.setImageViewResource(slot.playIconId, R.drawable.ic_widget_play_low)
            views.setImageViewBitmap(
                slot.artworkId,
                item.thumbnailUrl
                    ?.let { loadBitmap(it, 180) }
                    ?.let { getRoundedCornerBitmap(it, 30f) }
                    ?: getFallbackArtwork(item, 30f),
            )
            views.setOnClickPendingIntent(slot.containerId, getOpenTargetIntent(item))
            views.setOnClickPendingIntent(slot.playContainerId, getPlayTargetIntent(item))
        }
    }
    // Pick the widget size bucket for the shortcut grid
    private fun maxQuickPicksForSize(options: Bundle): Int {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

        return when {
            minWidth < 210 -> 0
            minHeight < 330 -> 4
            minWidth >= 360 -> 8
            else -> 4
        }
    }
    // The widget intentionally avoids an incomplete second row:
    // show up to 4 playlists in one row, collapse 5-7 to 4, and only use two rows when 8 are available
    private fun balancedDisplayCount(available: Int, maxItems: Int): Int {
        val capped = minOf(available, maxItems, 8)
        return when {
            capped >= 8 -> 8
            capped >= 4 -> 4
            else -> capped
        }
    }


    private fun reservedEmptySlotsFor(displayCount: Int): Set<Int> = when (displayCount) {
        1 -> setOf(1, 2, 3)
        2 -> setOf(2, 3)
        3 -> setOf(3)
        else -> emptySet()
    }

    private suspend fun buildQuickPicks(): List<QuickPick> = withContext(Dispatchers.IO) {
        val speedDialItemsDeferred = async { database.speedDialDao.getAll().first() }
        val savedPlaylistsDeferred = async { database.playlistsByCreateDateAsc().first() }
        val likedSongsDeferred = async { database.likedSongsByCreateDateAsc().first() }
        val downloadedSongsDeferred = async { database.downloadedSongsByCreateDateAsc().first() }
        val topSongsDeferred = async { database.mostPlayedSongs(fromTimeStamp = LocalDateTime.of(1970, 1, 1, 0, 0), limit = 50, offset = 0, toTimeStamp = LocalDateTime.now()).first() }

        val result = mutableListOf<QuickPick>()
        val seen = mutableSetOf<String>()
        val speedDialItems = speedDialItemsDeferred.await()
        val savedPlaylists = savedPlaylistsDeferred.await()
        val likedSongs = likedSongsDeferred.await()
        val downloadedSongs = downloadedSongsDeferred.await()
        val topSongs = topSongsDeferred.await()

        fun add(item: QuickPick) {
            if (seen.add(item.key)) result += item
        }

        speedDialItems
            .filter { it.type == "PLAYLIST" || it.type == "LOCAL_PLAYLIST" }
            .forEach { item ->
                if (item.type == "LOCAL_PLAYLIST") {
                    add(
                        QuickPick(
                            key = "local:${item.id}",
                            targetType = PlaylistWidgetReceiver.TARGET_TYPE_LOCAL,
                            targetId = item.id,
                            title = item.title,
                            thumbnailUrl = item.thumbnailUrl,
                            fallbackIconRes = R.drawable.playlist_play,
                        ),
                    )
                } else {
                    val saved = savedPlaylists.firstOrNull {
                        it.playlist.browseId == item.id || it.playlist.id == item.id
                    }
                    if (saved != null) {
                        add(saved.toQuickPick())
                    } else {
                        add(
                            QuickPick(
                                key = "online:${item.id}",
                                targetType = PlaylistWidgetReceiver.TARGET_TYPE_ONLINE,
                                targetId = item.id,
                                title = item.title,
                                thumbnailUrl = item.thumbnailUrl,
                                fallbackIconRes = R.drawable.playlist_play,
                            ),
                        )
                    }
                }
            }

        savedPlaylists
            .filter { it.playlist.browseId == null || it.playlist.isLocal }
            .forEach { add(it.toQuickPick()) }

        savedPlaylists
            .filter { it.playlist.browseId != null && !it.playlist.isLocal }
            .forEach { add(it.toQuickPick()) }

        if (likedSongs.isNotEmpty()) {
            add(
                QuickPick(
                    key = "auto:liked",
                    targetType = PlaylistWidgetReceiver.TARGET_TYPE_LIKED,
                    targetId = PlaylistWidgetReceiver.TARGET_TYPE_LIKED,
                    title = context.getString(R.string.liked_songs),
                    thumbnailUrl = likedSongs.firstOrNull()?.thumbnailUrl,
                    fallbackIconRes = R.drawable.ic_widget_heart_nav,
                ),
            )
        }

        if (downloadedSongs.isNotEmpty()) {
            add(
                QuickPick(
                    key = "auto:downloaded",
                    targetType = PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED,
                    targetId = PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED,
                    title = context.getString(R.string.downloaded_songs),
                    thumbnailUrl = downloadedSongs.firstOrNull()?.thumbnailUrl,
                    fallbackIconRes = R.drawable.cached,
                ),
            )
        }

        if (topSongs.isNotEmpty()) {
            add(
                QuickPick(
                    key = "auto:top50",
                    targetType = PlaylistWidgetReceiver.TARGET_TYPE_TOP,
                    targetId = "50",
                    title = context.getString(R.string.my_top),
                    thumbnailUrl = topSongs.firstOrNull()?.thumbnailUrl,
                    fallbackIconRes = R.drawable.trending_up,
                ),
            )
        }

        result
    }

    private fun Playlist.toQuickPick(): QuickPick {
        val browseId = playlist.browseId
        val isOnline = browseId != null && !playlist.isLocal
        return QuickPick(
            key = if (isOnline) "online:$browseId" else "local:${playlist.id}",
            targetType = if (isOnline) {
                PlaylistWidgetReceiver.TARGET_TYPE_ONLINE
            } else {
                PlaylistWidgetReceiver.TARGET_TYPE_LOCAL
            },
            targetId = browseId ?: playlist.id,
            title = playlist.name,
            thumbnailUrl = thumbnails.firstOrNull(),
            fallbackIconRes = R.drawable.playlist_play,
        )
    }

    private suspend fun getCachedAlbumArt(artworkUri: String?): Bitmap? {
        if (artworkUri == null) {
            cachedArtworkUri = null
            cachedAlbumArt = null
            cachedRoundedAlbumArt = null
            cachedRoundedSource = null
            return null
        }

        if (artworkUri != cachedArtworkUri) {
            val loaded = loadBitmap(artworkUri, 240)
            cachedAlbumArt = loaded
            cachedRoundedAlbumArt = null
            cachedRoundedSource = null
            cachedArtworkUri = artworkUri.takeIf { loaded != null }
        }

        return cachedAlbumArt
    }

    private fun getRoundedAlbumArt(albumArt: Bitmap): Bitmap {
        val cached = cachedRoundedAlbumArt
        if (cached != null && cachedRoundedSource === albumArt) return cached

        return getRoundedCornerBitmap(albumArt, 36f).also {
            cachedRoundedSource = albumArt
            cachedRoundedAlbumArt = it
        }
    }

    private suspend fun loadBitmap(uri: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(size, size)
                .allowHardware(false)
                .crossfade(false)
                .build()
            imageLoader.execute(request).image?.toBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        if (squareBitmap != bitmap) squareBitmap.recycle()
        return output
    }

    private fun getFallbackArtwork(item: QuickPick, cornerRadius: Float): Bitmap {
        val cacheKey = FallbackArtworkKey(
            targetType = item.targetType,
            fallbackIconRes = item.fallbackIconRes,
            cornerRadius = cornerRadius.toInt(),
        )

        fallbackArtworkCache[cacheKey]?.let { return it }

        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint().apply {
            isAntiAlias = true
            color = when (item.targetType) {
                PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> Color.rgb(198, 40, 86)
                PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> Color.rgb(38, 128, 118)
                PlaylistWidgetReceiver.TARGET_TYPE_TOP -> Color.rgb(122, 86, 178)
                else -> Color.rgb(66, 72, 86)
            }
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, background)

        val icon = context.getDrawable(item.fallbackIconRes)?.mutate()
        icon?.setTint(Color.WHITE)
        val iconSize = 128
        val iconOffset = (size - iconSize) / 2
        icon?.setBounds(iconOffset, iconOffset, iconOffset + iconSize, iconOffset + iconSize)
        icon?.draw(canvas)

        fallbackArtworkCache[cacheKey] = bitmap
        return bitmap
    }

    private fun getRoundedAppIcon(cornerRadius: Float): Bitmap {
        val cacheKey = cornerRadius.toInt()
        roundedAppIconCache[cacheKey]?.let { return it }

        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        return getRoundedCornerBitmap(bitmap, cornerRadius).also {
            roundedAppIconCache[cacheKey] = it
        }
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            500,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getOpenTargetIntent(item: QuickPick): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_WIDGET_TARGET
            putExtra(MainActivity.EXTRA_WIDGET_TARGET_TYPE, item.targetType)
            putExtra(MainActivity.EXTRA_WIDGET_TARGET_ID, item.targetId)
        }
        return PendingIntent.getActivity(
            context,
            item.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getPlayTargetIntent(item: QuickPick): PendingIntent {
        val intent = Intent(context, PlaylistWidgetReceiver::class.java).apply {
            action = PlaylistWidgetReceiver.ACTION_PLAY_TARGET
            putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE, item.targetType)
            putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID, item.targetId)
            putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TITLE, item.title)
        }
        return PendingIntent.getBroadcast(
            context,
            item.key.hashCode() xor 0x3522,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getMusicWidgetIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class WidgetState(
        val title: String,
        val artist: String,
        val artworkUri: String?,
        val isPlaying: Boolean,
        val isLiked: Boolean,
        val duration: Long,
        val currentPosition: Long,
    )

    private data class QuickPickSnapshot(
        val speedDial: List<SpeedDialSnapshot>,
        val playlists: List<PlaylistSnapshot>,
        val likedSongs: List<SongSnapshot>,
        val downloadedSongs: List<SongSnapshot>,
        val topSongs: List<SongSnapshot>,
    )

    private data class SpeedDialSnapshot(
        val type: String,
        val id: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    private data class PlaylistSnapshot(
        val id: String,
        val browseId: String?,
        val name: String,
        val thumbnailUrl: String?,
        val isLocal: Boolean,
    )

    private data class SongSnapshot(
        val id: String,
        val thumbnailUrl: String?,
    )

    private data class QuickPick(
        val key: String,
        val targetType: String,
        val targetId: String,
        val title: String,
        val thumbnailUrl: String?,
        val fallbackIconRes: Int,
    )

    private data class CardSlot(
        val containerId: Int,
        val artworkId: Int,
        val playContainerId: Int,
        val playIconId: Int,
        val titleId: Int,
    )

    private data class FallbackArtworkKey(
        val targetType: String,
        val fallbackIconRes: Int,
        val cornerRadius: Int,
    )

    private val cardSlots = listOf(
        CardSlot(R.id.widget_playlist_card_1, R.id.widget_playlist_card_1_art, R.id.widget_playlist_card_1_play_container, R.id.widget_playlist_card_1_play, R.id.widget_playlist_card_1_title),
        CardSlot(R.id.widget_playlist_card_2, R.id.widget_playlist_card_2_art, R.id.widget_playlist_card_2_play_container, R.id.widget_playlist_card_2_play, R.id.widget_playlist_card_2_title),
        CardSlot(R.id.widget_playlist_card_3, R.id.widget_playlist_card_3_art, R.id.widget_playlist_card_3_play_container, R.id.widget_playlist_card_3_play, R.id.widget_playlist_card_3_title),
        CardSlot(R.id.widget_playlist_card_4, R.id.widget_playlist_card_4_art, R.id.widget_playlist_card_4_play_container, R.id.widget_playlist_card_4_play, R.id.widget_playlist_card_4_title),
        CardSlot(R.id.widget_playlist_card_5, R.id.widget_playlist_card_5_art, R.id.widget_playlist_card_5_play_container, R.id.widget_playlist_card_5_play, R.id.widget_playlist_card_5_title),
        CardSlot(R.id.widget_playlist_card_6, R.id.widget_playlist_card_6_art, R.id.widget_playlist_card_6_play_container, R.id.widget_playlist_card_6_play, R.id.widget_playlist_card_6_title),
        CardSlot(R.id.widget_playlist_card_7, R.id.widget_playlist_card_7_art, R.id.widget_playlist_card_7_play_container, R.id.widget_playlist_card_7_play, R.id.widget_playlist_card_7_title),
        CardSlot(R.id.widget_playlist_card_8, R.id.widget_playlist_card_8_art, R.id.widget_playlist_card_8_play_container, R.id.widget_playlist_card_8_play, R.id.widget_playlist_card_8_title),
    )
}
