/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.playback.mediaArtworkUri
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.ui.components.libraryCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class PlaylistWidgetManager(
    private val context: Context,
    private val database: MusicDatabase,
    private val applicationScope: CoroutineScope,
    private val imageLoader: ImageLoader,
) {
    @Volatile private var cachedArtworkUri: String? = null

    @Volatile private var cachedAlbumArt: Bitmap? = null

    @Volatile private var cachedRoundedAlbumArt: Bitmap? = null

    @Volatile private var cachedRoundedSource: Bitmap? = null
    private val fallbackArtworkCache = LruCache<FallbackArtworkKey, Bitmap>(8)

    @Volatile private var roundedAppIcon: Bitmap? = null

    @Volatile
    private var lastWidgetState =
        WidgetState(
            title = context.getString(R.string.not_playing),
            artist = context.getString(R.string.choose_something_below),
            artworkUri = null,
            isPlaying = false,
            isLiked = false,
            duration = 0,
            currentPosition = 0,
        )

    private val updateMutex = Mutex()
    private val progressLevels = mutableMapOf<Int, Int>()

    private var observation: Job? = null
    private val quickPickCapacity = MutableStateFlow(0)

    @Volatile private var quickPickSnapshot: QuickPickSnapshot? = null

    @Synchronized
    fun updateObservation() {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetReceiver::class.java))
        quickPickCapacity.value =
            widgetIds.maxOfOrNull { id ->
                val options = manager.getAppWidgetOptions(id)
                playlistWidgetQuickPickCapacity(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                )
            } ?: 0
        if (widgetIds.isEmpty()) {
            stopObservation()
        } else if (observation?.isActive != true) {
            observation = observeQuickPickChanges()
        }
    }

    @Synchronized
    fun stopObservation() {
        observation?.cancel()
        observation = null
        quickPickSnapshot = null
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

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
    ) {
        synchronized(this) {
            lastWidgetState = WidgetState(title, artist, artworkUri, isPlaying, isLiked, duration, currentPosition)
        }
        refreshWidgetsFromLastState()
    }

    internal suspend fun updateProgress(
        duration: Long,
        position: Long,
    ) = updateMutex.withLock {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetReceiver::class.java))
        progressLevels.keys.retainAll(ids.toSet())
        synchronized(this) {
            lastWidgetState = lastWidgetState.copy(duration = duration, currentPosition = position)
        }
        val level = progressLevel(duration, position)
        ids.forEach { id ->
            if (progressLevels[id] == null || progressLevels[id] == level) return@forEach
            val options = manager.getAppWidgetOptions(id)
            if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) < PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP) return@forEach
            val views =
                RemoteViews(context.packageName, R.layout.widget_playlist).apply {
                    setInt(R.id.widget_playlist_progress_fill, "setImageLevel", level)
                }
            manager.partiallyUpdateAppWidget(id, views)
            progressLevels[id] = level
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeQuickPickChanges(): Job {
        val dao = database.dao()
        return combine(
            database.speedDialDao().getAll().map { items -> items.map { SpeedDialSnapshot(it.type, it.id, it.title, it.thumbnailUrl) } },
            dao.widgetPlaylists().map { playlists ->
                playlists.map {
                    PlaylistSnapshot(
                        it.id,
                        it.browseId,
                        it.name,
                        libraryArtwork(it.id, it.thumbnailUrl, it.customThumbnailUrl),
                        it.isLocal,
                    )
                }
            },
            dao.likedWidgetSong().map { songs -> songs.map { SongSnapshot(it.id, it.thumbnailUrl) } },
            dao.downloadedWidgetSong().map { songs -> songs.map { SongSnapshot(it.id, it.thumbnailUrl) } },
            quickPickCapacity,
        ) { speedDial, playlists, likedSongs, downloadedSongs, capacity ->
            QuickPickSnapshot(speedDial, playlists, likedSongs, downloadedSongs, emptyList()) to capacity
        }.distinctUntilChanged()
            .flatMapLatest { (snapshot, capacity) ->
                val precedingPicks = buildQuickPicks(snapshot).size
                if (needsTopWidgetSong(precedingPicks, capacity)) {
                    dao.topWidgetSong().map { songs -> snapshot.copy(topSongs = songs.map { SongSnapshot(it.id, it.thumbnailUrl) }) }
                } else {
                    flowOf(snapshot)
                }
            }.distinctUntilChanged()
            .debounce(500L.milliseconds)
            .onEach { snapshot ->
                synchronized(this) { quickPickSnapshot = snapshot }
                refreshWidgetsFromLastState()
            }.launchIn(applicationScope)
    }

    internal suspend fun refreshWidgetsFromLastState() =
        updateMutex.withLock {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, PlaylistWidgetReceiver::class.java))
            updateObservation()
            if (widgetIds.isEmpty()) return@withLock
            val state = lastWidgetState
            val picks = quickPickSnapshot
            val albumArt = getCachedAlbumArt(state.artworkUri)
            val quickPicks = buildQuickPicks()
            widgetIds.forEach { widgetId ->
                val views =
                    createRemoteViews(
                        options = appWidgetManager.getAppWidgetOptions(widgetId),
                        title = state.title,
                        artist = state.artist,
                        albumArt = albumArt,
                        isPlaying = state.isPlaying,
                        isLiked = state.isLiked,
                        duration = state.duration,
                        currentPosition = state.currentPosition,
                        quickPicks = quickPicks,
                    )
                synchronized(this) {
                    if (lastWidgetState === state && quickPickSnapshot === picks) {
                        appWidgetManager.updateAppWidget(widgetId, views)
                        progressLevels[widgetId] = progressLevel(state.duration, state.currentPosition)
                    }
                }
            }
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
        if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) < PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP) {
            return createCompactRemoteViews(title, artist, albumArt, isPlaying, quickPicks.firstOrNull())
        }

        val views = RemoteViews(context.packageName, R.layout.widget_playlist)
        views.setTextViewText(R.id.widget_playlist_song_title, title)
        views.setTextViewText(R.id.widget_playlist_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_playlist_album_art, albumArt?.let { getRoundedAlbumArt(it) } ?: getRoundedAppIcon())
        views.setImageViewResource(
            R.id.widget_playlist_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setInt(R.id.widget_playlist_play_pause_container, "setBackgroundResource", playButtonBackground(isPlaying, inGroup = true))
        views.setInt(R.id.widget_playlist_like_container, "setBackgroundResource", likeButtonBackground(isLiked))
        views.setImageViewResource(
            R.id.widget_playlist_like_button,
            if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav,
        )
        views.setInt(R.id.widget_playlist_progress_fill, "setImageLevel", progressLevel(duration, currentPosition))
        views.setViewVisibility(R.id.widget_playlist_progress_container, if (duration > 0) View.VISIBLE else View.INVISIBLE)
        views.setOnClickPendingIntent(android.R.id.background, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_prev_container, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PREVIOUS, 501))
        views.setOnClickPendingIntent(
            R.id.widget_playlist_play_pause_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 502),
        )
        views.setOnClickPendingIntent(R.id.widget_playlist_next_container, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_NEXT, 503))
        views.setOnClickPendingIntent(R.id.widget_playlist_like_button, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_LIKE, 504))
        bindQuickPicks(views, options, quickPicks)
        return views
    }

    private suspend fun createCompactRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        quickPick: QuickPick?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_playlist_compact)
        views.setTextViewText(R.id.widget_playlist_compact_song_title, title)
        views.setTextViewText(R.id.widget_playlist_compact_artist_name, artist)
        views.setImageViewBitmap(
            R.id.widget_playlist_compact_album_art,
            albumArt?.let { getRoundedAlbumArt(it) } ?: getRoundedAppIcon(),
        )
        views.setImageViewResource(
            R.id.widget_playlist_compact_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setInt(R.id.widget_playlist_compact_play_container, "setBackgroundResource", playButtonBackground(isPlaying, inGroup = false))
        views.setOnClickPendingIntent(android.R.id.background, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_compact_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(
            R.id.widget_playlist_compact_play_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 505),
        )

        if (quickPick == null) {
            views.setViewVisibility(R.id.widget_playlist_compact_quick_pick, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_playlist_compact_quick_pick, View.VISIBLE)
            views.setTextViewText(R.id.widget_playlist_compact_quick_pick_title, quickPick.title)
            views.setImageViewBitmap(
                R.id.widget_playlist_compact_quick_pick_art,
                quickPick.thumbnailUrl
                    ?.let { loadBitmap(it, 180) }
                    ?.let { getRoundedCornerBitmap(it) }
                    ?: getFallbackArtwork(quickPick),
            )
            views.setOnClickPendingIntent(R.id.widget_playlist_compact_quick_pick, getOpenTargetIntent(quickPick))
            views.setOnClickPendingIntent(
                R.id.widget_playlist_compact_quick_pick_play_container,
                getPlayTargetIntent(quickPick),
            )
        }
        return views
    }

    private suspend fun bindQuickPicks(
        views: RemoteViews,
        options: Bundle,
        quickPicks: List<QuickPick>,
    ) {
        val displayCount =
            playlistWidgetDisplayCount(
                quickPicks.size,
                playlistWidgetQuickPickCapacity(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                ),
            )
        val visiblePicks = quickPicks.take(displayCount)
        val visibleSlots = (0 until displayCount).toSet()
        val reservedEmptySlots = reservedEmptySlotsFor(displayCount)

        views.setViewVisibility(R.id.widget_playlist_quick_picks_section, if (displayCount > 0) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_playlist_row_2, if (displayCount > 4) View.VISIBLE else View.GONE)

        cardSlots.forEachIndexed { index, slot ->
            val visibility =
                when {
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
                    ?.let { getRoundedCornerBitmap(it) }
                    ?: getFallbackArtwork(item),
            )
            views.setOnClickPendingIntent(slot.containerId, getOpenTargetIntent(item))
            views.setOnClickPendingIntent(slot.playContainerId, getPlayTargetIntent(item))
        }
    }

    private fun reservedEmptySlotsFor(displayCount: Int): Set<Int> =
        when (displayCount) {
            1 -> setOf(1, 2, 3)
            2 -> setOf(2, 3)
            3 -> setOf(3)
            else -> emptySet()
        }

    private suspend fun buildQuickPicks(snapshot: QuickPickSnapshot? = quickPickSnapshot): List<QuickPick> =
        withContext(Dispatchers.IO) {
            snapshot ?: return@withContext emptyList()
            val savedPlaylists = snapshot.playlists
            val result = mutableListOf<QuickPick>()
            val seen = mutableSetOf<String>()

            fun add(item: QuickPick) {
                if (result.size < 8 && seen.add(item.key)) result += item
            }

            snapshot.speedDial
                .asSequence()
                .filter { it.type == "PLAYLIST" || it.type == "LOCAL_PLAYLIST" }
                .takeWhile { result.size < 8 }
                .forEach { item ->
                    val builtin = BuiltinPlaylist.fromId(item.id)
                    if (builtin != null) {
                        val target = widgetPlaylistTarget(item.id, item.type == "LOCAL_PLAYLIST")
                        add(
                            QuickPick(
                                target.key,
                                target.type,
                                target.id,
                                item.title,
                                libraryArtwork(builtin.collectionType ?: builtin.id, item.thumbnailUrl),
                                if (builtin == BuiltinPlaylist.LIKED) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_play,
                            ),
                        )
                    } else if (item.type == "LOCAL_PLAYLIST") {
                        add(
                            savedPlaylists.firstOrNull { it.id == item.id }?.toQuickPick() ?: QuickPick(
                                "local:${item.id}",
                                PlaylistWidgetReceiver.TARGET_TYPE_LOCAL,
                                item.id,
                                item.title,
                                libraryArtwork(item.id, item.thumbnailUrl),
                                R.drawable.ic_widget_play,
                            ),
                        )
                    } else {
                        val saved = savedPlaylists.firstOrNull { it.browseId == item.id || it.id == item.id }
                        add(
                            saved?.toQuickPick()
                                ?: QuickPick(
                                    "online:${item.id}",
                                    PlaylistWidgetReceiver.TARGET_TYPE_ONLINE,
                                    item.id,
                                    item.title,
                                    libraryArtwork(item.id, item.thumbnailUrl),
                                    R.drawable.ic_widget_play,
                                ),
                        )
                    }
                }

            savedPlaylists.asSequence().filter { it.browseId == null || it.isLocal }.takeWhile { result.size < 8 }.forEach {
                add(
                    it.toQuickPick(),
                )
            }
            savedPlaylists.asSequence().filter { it.browseId != null && !it.isLocal }.takeWhile { result.size < 8 }.forEach {
                add(
                    it.toQuickPick(),
                )
            }

            snapshot.likedSongs.takeIf { it.isNotEmpty() }?.let {
                add(
                    QuickPick(
                        "auto:liked",
                        PlaylistWidgetReceiver.TARGET_TYPE_LIKED,
                        PlaylistWidgetReceiver.TARGET_TYPE_LIKED,
                        context.getString(R.string.liked_songs),
                        libraryArtwork("liked"),
                        R.drawable.ic_widget_heart_nav,
                    ),
                )
            }
            snapshot.downloadedSongs.takeIf { it.isNotEmpty() }?.let {
                add(
                    QuickPick(
                        "auto:downloaded",
                        PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED,
                        PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED,
                        context.getString(R.string.downloaded_songs),
                        libraryArtwork("downloaded"),
                        R.drawable.ic_widget_play,
                    ),
                )
            }
            snapshot.topSongs.takeIf { it.isNotEmpty() }?.let {
                add(
                    QuickPick(
                        "auto:top100",
                        PlaylistWidgetReceiver.TARGET_TYPE_TOP,
                        "100",
                        context.getString(R.string.my_top),
                        libraryArtwork("top100"),
                        R.drawable.ic_widget_play,
                    ),
                )
            }

            result
        }

    private fun PlaylistSnapshot.toQuickPick(): QuickPick {
        val isOnline = browseId != null && !isLocal
        val target = widgetPlaylistTarget(browseId ?: id, !isOnline)
        return QuickPick(
            key = target.key,
            targetType = target.type,
            targetId = target.id,
            title = name,
            thumbnailUrl = thumbnailUrl,
            fallbackIconRes = R.drawable.ic_widget_play,
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
        return getRoundedCornerBitmap(albumArt).also {
            cachedRoundedSource = albumArt
            cachedRoundedAlbumArt = it
        }
    }

    private suspend fun loadBitmap(
        uri: String,
        size: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(if (libraryCover(uri) != null) mediaArtworkUri(context, uri) else uri)
                        .size(size, size)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                imageLoader.execute(request).image?.toBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun getFallbackArtwork(item: QuickPick): Bitmap {
        val (containerColor, contentColor) =
            when (item.targetType) {
                PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> R.color.widget_tertiary_container to R.color.widget_on_tertiary_container
                PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> R.color.widget_secondary_container to R.color.widget_on_secondary_container
                PlaylistWidgetReceiver.TARGET_TYPE_TOP -> R.color.widget_primary_container to R.color.widget_on_primary_container
                else -> R.color.widget_surface_highest to R.color.widget_on_surface_variant
            }.let { (container, content) -> context.getColor(container) to context.getColor(content) }
        val cacheKey = FallbackArtworkKey(item.fallbackIconRes, containerColor, contentColor)
        fallbackArtworkCache[cacheKey]?.let { return it }

        val size = 300
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val background =
            Paint().apply {
                isAntiAlias = true
                color = containerColor
            }
        val cornerRadius = artworkCornerRadius(size)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, background)
        AppCompatResources.getDrawable(context, item.fallbackIconRes)?.mutate()?.let { icon ->
            icon.setTint(contentColor)
            val iconSize = 128
            val iconOffset = (size - iconSize) / 2
            icon.setBounds(iconOffset, iconOffset, iconOffset + iconSize, iconOffset + iconSize)
            icon.draw(canvas)
        }
        fallbackArtworkCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun getRoundedAppIcon(): Bitmap {
        roundedAppIcon?.let { return it }
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = createBitmap(300, 300)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 300, 300)
        drawable.draw(canvas)
        return getRoundedCornerBitmap(bitmap).also { roundedAppIcon = it }
    }

    private fun getOpenAppIntent(): PendingIntent = openAppPendingIntent(context, 500)

    private fun getOpenTargetIntent(item: QuickPick): PendingIntent =
        PendingIntent.getActivity(
            context,
            item.key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_WIDGET_TARGET
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_WIDGET_TARGET_TYPE, item.targetType)
                putExtra(MainActivity.EXTRA_WIDGET_TARGET_ID, item.targetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun getPlayTargetIntent(item: QuickPick): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            item.key.hashCode() xor 0x3522,
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = PlaylistWidgetReceiver.ACTION_PLAY_TARGET
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE, item.targetType)
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID, item.targetId)
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TITLE, item.title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun getMusicWidgetIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent = widgetActionPendingIntent(context, action, requestCode)

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
        val fallbackIconRes: Int,
        val containerColor: Int,
        val contentColor: Int,
    )

    private val cardSlots =
        listOf(
            CardSlot(
                R.id.widget_playlist_card_1,
                R.id.widget_playlist_card_1_art,
                R.id.widget_playlist_card_1_play_container,
                R.id.widget_playlist_card_1_play,
                R.id.widget_playlist_card_1_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_2,
                R.id.widget_playlist_card_2_art,
                R.id.widget_playlist_card_2_play_container,
                R.id.widget_playlist_card_2_play,
                R.id.widget_playlist_card_2_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_3,
                R.id.widget_playlist_card_3_art,
                R.id.widget_playlist_card_3_play_container,
                R.id.widget_playlist_card_3_play,
                R.id.widget_playlist_card_3_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_4,
                R.id.widget_playlist_card_4_art,
                R.id.widget_playlist_card_4_play_container,
                R.id.widget_playlist_card_4_play,
                R.id.widget_playlist_card_4_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_5,
                R.id.widget_playlist_card_5_art,
                R.id.widget_playlist_card_5_play_container,
                R.id.widget_playlist_card_5_play,
                R.id.widget_playlist_card_5_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_6,
                R.id.widget_playlist_card_6_art,
                R.id.widget_playlist_card_6_play_container,
                R.id.widget_playlist_card_6_play,
                R.id.widget_playlist_card_6_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_7,
                R.id.widget_playlist_card_7_art,
                R.id.widget_playlist_card_7_play_container,
                R.id.widget_playlist_card_7_play,
                R.id.widget_playlist_card_7_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_8,
                R.id.widget_playlist_card_8_art,
                R.id.widget_playlist_card_8_play_container,
                R.id.widget_playlist_card_8_play,
                R.id.widget_playlist_card_8_title,
            ),
        )
}
