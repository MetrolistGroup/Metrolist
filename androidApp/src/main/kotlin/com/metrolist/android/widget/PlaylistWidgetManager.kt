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
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.playback.mediaArtworkUri
import com.metrolist.android.ui.theme.extractThemeColor
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

    @Volatile private var cachedCookieAlbumArt: Bitmap? = null

    @Volatile private var cachedCookieSource: Bitmap? = null

    @Volatile private var cachedArtworkSeed: Color? = null
    private val fallbackArtworkCache = LruCache<FallbackArtworkKey, Bitmap>(8)

    @Volatile private var cookieAppIcon: Bitmap? = null

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

    private var lastControls = WidgetControls()

    private val updateMutex = Mutex()
    private val progressBuckets = mutableMapOf<Int, Long>()

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

    internal suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        controls: WidgetControls = WidgetControls(playbackRequested = isPlaying),
    ) {
        synchronized(this) {
            lastWidgetState = WidgetState(title, artist, artworkUri, isPlaying, isLiked, duration, currentPosition)
            lastControls = controls
        }
        refreshWidgetsFromLastState()
    }

    internal suspend fun updateProgress(
        duration: Long,
        position: Long,
    ) = updateMutex.withLock {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetReceiver::class.java))
        progressBuckets.keys.retainAll(ids.toSet())
        synchronized(this) {
            lastWidgetState = lastWidgetState.copy(duration = duration, currentPosition = position)
        }
        val bucket = position.coerceAtLeast(0L) / 1000L
        ids.forEach { id ->
            if (progressBuckets[id] == null || progressBuckets[id] == bucket) return@forEach
            val options = manager.getAppWidgetOptions(id)
            if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) < PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP) return@forEach
            val views =
                RemoteViews(context.packageName, R.layout.widget_playlist).apply {
                    setWidgetProgress(playlistWidgetProgressIds, duration, position)
                }
            manager.partiallyUpdateAppWidget(id, views)
            progressBuckets[id] = bucket
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
            val (state, controls) = synchronized(this) { lastWidgetState to lastControls }
            val picks = quickPickSnapshot
            val albumArt = getCachedAlbumArt(state.artworkUri)
            val config = WidgetThemeConfig.fromSettings()
            val seed =
                if (config.materialYouEnabled && config.dynamicTheme) {
                    cachedArtworkSeed ?: albumArt?.extractThemeColor()?.also { cachedArtworkSeed = it }
                } else {
                    null
                }
            val schemes = resolveWidgetColorSchemes(context, seed, config)
            val quickPicks = buildQuickPicks()
            widgetIds.forEach { widgetId ->
                val views =
                    createRemoteViews(
                        options = appWidgetManager.getAppWidgetOptions(widgetId),
                        title = state.title,
                        artist = state.artist,
                        albumArt = albumArt,
                        controls = controls,
                        isLiked = state.isLiked,
                        duration = state.duration,
                        currentPosition = state.currentPosition,
                        quickPicks = quickPicks,
                        schemes = schemes,
                    )
                synchronized(this) {
                    if (lastWidgetState === state && quickPickSnapshot === picks) {
                        appWidgetManager.updateAppWidget(widgetId, views)
                        if (state.duration > 0) {
                            progressBuckets[widgetId] = state.currentPosition.coerceAtLeast(0L) / 1000L
                        } else {
                            progressBuckets.remove(widgetId)
                        }
                    }
                }
            }
        }

    private suspend fun createRemoteViews(
        options: Bundle,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        controls: WidgetControls,
        isLiked: Boolean,
        duration: Long,
        currentPosition: Long,
        quickPicks: List<QuickPick>,
        schemes: WidgetColorSchemes,
    ): RemoteViews {
        if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) < PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP) {
            return createCompactRemoteViews(title, artist, albumArt, controls, quickPicks.firstOrNull(), schemes)
        }

        val views = RemoteViews(context.packageName, R.layout.widget_playlist)
        views.setTextViewText(R.id.widget_playlist_song_title, title)
        views.setTextViewText(R.id.widget_playlist_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_playlist_album_art, albumArt?.let { getCookieAlbumArt(it) } ?: getCookieAppIcon())
        views.setImageViewBitmap(R.id.widget_playlist_play_bg, cookie12MaskBitmap())
        if (isLiked) {
            views.setImageViewBitmap(R.id.widget_playlist_like_bg, cookie9MaskBitmap())
        } else {
            views.setImageViewResource(R.id.widget_playlist_like_bg, R.drawable.widget_circle_bg)
        }
        views.setImageViewResource(
            R.id.widget_playlist_play_pause,
            if (controls.playbackRequested) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setImageViewResource(
            R.id.widget_playlist_like_button,
            if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav,
        )
        if (duration > 0) {
            views.setViewVisibility(R.id.widget_playlist_progress_container, View.VISIBLE)
            views.setWidgetProgress(playlistWidgetProgressIds, duration, currentPosition)
        } else {
            views.setViewVisibility(R.id.widget_playlist_progress_container, View.INVISIBLE)
        }

        views.setWidgetTint(context, R.id.widget_playlist_surface_background, schemes, ::widgetSurfaceRole)
        views.setWidgetTextColor(context, R.id.widget_playlist_song_title, schemes) { it.onSurface }
        views.setWidgetTextColor(context, R.id.widget_playlist_artist_name, schemes) { it.onSurfaceVariant }
        views.setWidgetTint(context, R.id.widget_playlist_prev_bg, schemes) { it.secondaryContainer }
        views.setWidgetTint(context, R.id.widget_playlist_next_bg, schemes) { it.secondaryContainer }
        views.setWidgetTint(context, R.id.widget_playlist_prev_icon, schemes) { it.onSecondaryContainer }
        views.setWidgetTint(context, R.id.widget_playlist_next_icon, schemes) { it.onSecondaryContainer }
        views.setWidgetTint(context, R.id.widget_playlist_play_bg, schemes) { it.primary }
        views.setWidgetTint(context, R.id.widget_playlist_play_pause, schemes) { it.onPrimary }
        views.setWidgetTint(context, R.id.widget_playlist_like_bg, schemes) {
            if (isLiked) it.tertiary else it.tertiaryContainer
        }
        views.setWidgetTint(context, R.id.widget_playlist_like_button, schemes) {
            if (isLiked) it.onTertiary else it.onTertiaryContainer
        }
        views.setWidgetProgressTint(context, playlistWidgetProgressIds, schemes)

        views.setOnClickPendingIntent(android.R.id.background, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_prev_container, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PREVIOUS, 501))
        views.setOnClickPendingIntent(
            R.id.widget_playlist_play_pause_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 502),
        )
        views.setOnClickPendingIntent(R.id.widget_playlist_next_container, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_NEXT, 503))
        views.setOnClickPendingIntent(R.id.widget_playlist_like_container, getMusicWidgetIntent(MusicWidgetReceiver.ACTION_LIKE, 504))
        views.setContentDescription(
            R.id.widget_playlist_play_pause_container,
            context.getString(if (controls.playbackRequested) R.string.pause else R.string.play),
        )
        views.setContentDescription(
            R.id.widget_playlist_like_container,
            context.getString(if (isLiked) R.string.unlike else R.string.action_like),
        )
        views.setBoolean(R.id.widget_playlist_play_pause_container, "setEnabled", controls.canPlayPause)
        views.setBoolean(R.id.widget_playlist_prev_container, "setEnabled", controls.canPrevious)
        views.setBoolean(R.id.widget_playlist_next_container, "setEnabled", controls.canNext)
        views.setBoolean(R.id.widget_playlist_like_container, "setEnabled", controls.canLike)
        views.setInt(R.id.widget_playlist_play_pause, "setImageAlpha", if (controls.canPlayPause) 255 else 97)
        views.setInt(R.id.widget_playlist_prev_icon, "setImageAlpha", if (controls.canPrevious) 255 else 97)
        views.setInt(R.id.widget_playlist_next_icon, "setImageAlpha", if (controls.canNext) 255 else 97)
        views.setInt(R.id.widget_playlist_like_button, "setImageAlpha", if (controls.canLike) 255 else 97)
        bindQuickPicks(views, options, quickPicks, schemes)
        return views
    }

    private suspend fun createCompactRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        controls: WidgetControls,
        quickPick: QuickPick?,
        schemes: WidgetColorSchemes,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_playlist_compact)
        views.setTextViewText(R.id.widget_playlist_compact_song_title, title)
        views.setTextViewText(R.id.widget_playlist_compact_artist_name, artist)
        views.setImageViewBitmap(
            R.id.widget_playlist_compact_album_art,
            albumArt?.let { getCookieAlbumArt(it) } ?: getCookieAppIcon(),
        )
        views.setImageViewBitmap(R.id.widget_playlist_compact_play_bg, cookie12MaskBitmap())
        views.setImageViewResource(
            R.id.widget_playlist_compact_play_pause,
            if (controls.playbackRequested) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        views.setWidgetTint(context, R.id.widget_playlist_surface_background, schemes, ::widgetSurfaceRole)
        views.setWidgetTextColor(context, R.id.widget_playlist_compact_song_title, schemes) { it.onSurface }
        views.setWidgetTextColor(context, R.id.widget_playlist_compact_artist_name, schemes) { it.onSurfaceVariant }
        views.setWidgetTint(context, R.id.widget_playlist_compact_play_bg, schemes) { it.primary }
        views.setWidgetTint(context, R.id.widget_playlist_compact_play_pause, schemes) { it.onPrimary }

        views.setOnClickPendingIntent(android.R.id.background, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_playlist_compact_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(
            R.id.widget_playlist_compact_play_container,
            getMusicWidgetIntent(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 505),
        )

        views.setContentDescription(
            R.id.widget_playlist_compact_play_container,
            context.getString(if (controls.playbackRequested) R.string.pause else R.string.play),
        )
        views.setBoolean(R.id.widget_playlist_compact_play_container, "setEnabled", controls.canPlayPause)
        views.setInt(R.id.widget_playlist_compact_play_pause, "setImageAlpha", if (controls.canPlayPause) 255 else 97)

        if (quickPick == null) {
            views.setViewVisibility(R.id.widget_playlist_compact_quick_pick, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_playlist_compact_quick_pick, View.VISIBLE)
            views.setTextViewText(R.id.widget_playlist_compact_quick_pick_title, quickPick.title)
            views.setWidgetTextColor(context, R.id.widget_playlist_compact_quick_pick_title, schemes) { it.onSurface }
            views.setWidgetTint(context, R.id.widget_playlist_compact_quick_pick_play_bg, schemes) { it.primaryContainer }
            views.setWidgetTint(context, R.id.widget_playlist_compact_quick_pick_play, schemes) { it.onPrimaryContainer }
            views.setImageViewBitmap(
                R.id.widget_playlist_compact_quick_pick_art,
                quickPick.thumbnailUrl
                    ?.let { loadBitmap(it, 180) }
                    ?.let { getRoundedCornerBitmap(it) }
                    ?: getFallbackArtwork(quickPick, schemes.current(context)),
            )
            views.setContentDescription(
                R.id.widget_playlist_compact_quick_pick_play_container,
                context.getString(R.string.widget_play_item, quickPick.title),
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
        schemes: WidgetColorSchemes,
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

        val currentScheme = schemes.current(context)
        visiblePicks.forEachIndexed { index, item ->
            val slot = cardSlots[index]
            views.setTextViewText(slot.titleId, item.title)
            views.setWidgetTextColor(context, slot.titleId, schemes) { it.onSurface }
            views.setImageViewResource(slot.playIconId, R.drawable.ic_widget_play_low)
            views.setWidgetTint(context, slot.playBgId, schemes) { it.primaryContainer }
            views.setWidgetTint(context, slot.playIconId, schemes) { it.onPrimaryContainer }
            views.setImageViewBitmap(
                slot.artworkId,
                item.thumbnailUrl
                    ?.let { loadBitmap(it, 180) }
                    ?.let { getRoundedCornerBitmap(it) }
                    ?: getFallbackArtwork(item, currentScheme),
            )
            views.setOnClickPendingIntent(slot.containerId, getOpenTargetIntent(item))
            views.setContentDescription(slot.playContainerId, context.getString(R.string.widget_play_item, item.title))
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
                val remoteId = item.targetId.takeIf { item.targetType == PlaylistWidgetReceiver.TARGET_TYPE_ONLINE }
                if (result.size < 8 && seen.add(playlistWidgetPickKey(item.key, remoteId))) result += item
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
            cachedCookieAlbumArt = null
            cachedCookieSource = null
            cachedArtworkSeed = null
            return null
        }
        if (artworkUri != cachedArtworkUri) {
            val loaded = loadBitmap(artworkUri, 240)
            cachedAlbumArt = loaded
            cachedCookieAlbumArt = null
            cachedCookieSource = null
            cachedArtworkSeed = null
            cachedArtworkUri = artworkUri.takeIf { loaded != null }
        }
        return cachedAlbumArt
    }

    private fun getCookieAlbumArt(albumArt: Bitmap): Bitmap {
        val cached = cachedCookieAlbumArt
        if (cached != null && cachedCookieSource === albumArt) return cached
        return getCookie9Bitmap(albumArt).also {
            cachedCookieSource = albumArt
            cachedCookieAlbumArt = it
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

    private fun getFallbackArtwork(
        item: QuickPick,
        scheme: ColorScheme,
    ): Bitmap {
        val (containerColor, contentColor) =
            when (item.targetType) {
                PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> scheme.tertiaryContainer.toArgb() to scheme.onTertiaryContainer.toArgb()
                PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> scheme.secondaryContainer.toArgb() to scheme.onSecondaryContainer.toArgb()
                PlaylistWidgetReceiver.TARGET_TYPE_TOP -> scheme.primaryContainer.toArgb() to scheme.onPrimaryContainer.toArgb()
                else -> scheme.surfaceContainerHighest.toArgb() to scheme.onSurfaceVariant.toArgb()
            }
        val cacheKey = FallbackArtworkKey(item.fallbackIconRes, containerColor, contentColor)
        fallbackArtworkCache[cacheKey]?.let { return it }

        val size = 240
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val background =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = containerColor
            }
        val cornerRadius = artworkCornerRadius(size)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, background)
        AppCompatResources.getDrawable(context, item.fallbackIconRes)?.mutate()?.let { icon ->
            icon.setTint(contentColor)
            val iconSize = 104
            val iconOffset = (size - iconSize) / 2
            icon.setBounds(iconOffset, iconOffset, iconOffset + iconSize, iconOffset + iconSize)
            icon.draw(canvas)
        }
        fallbackArtworkCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun getCookieAppIcon(): Bitmap {
        cookieAppIcon?.let { return it }
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = createBitmap(240, 240)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 240, 240)
        drawable.draw(canvas)
        return getCookie9Bitmap(bitmap).also { cookieAppIcon = it }
    }

    private fun getOpenAppIntent(): PendingIntent = openAppPendingIntent(context, 500)

    private fun getOpenTargetIntent(item: QuickPick): PendingIntent =
        PendingIntent.getActivity(
            context,
            item.key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_WIDGET_TARGET
                data = item.pendingIntentData()
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
                data = item.pendingIntentData()
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE, item.targetType)
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID, item.targetId)
                putExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TITLE, item.title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun QuickPick.pendingIntentData(): Uri =
        Uri
            .Builder()
            .scheme("metrolist-widget")
            .authority("playlist-target")
            .appendPath(targetType)
            .appendPath(targetId)
            .build()

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
        val playBgId: Int,
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
                R.id.widget_playlist_card_1_play_bg,
                R.id.widget_playlist_card_1_play,
                R.id.widget_playlist_card_1_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_2,
                R.id.widget_playlist_card_2_art,
                R.id.widget_playlist_card_2_play_container,
                R.id.widget_playlist_card_2_play_bg,
                R.id.widget_playlist_card_2_play,
                R.id.widget_playlist_card_2_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_3,
                R.id.widget_playlist_card_3_art,
                R.id.widget_playlist_card_3_play_container,
                R.id.widget_playlist_card_3_play_bg,
                R.id.widget_playlist_card_3_play,
                R.id.widget_playlist_card_3_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_4,
                R.id.widget_playlist_card_4_art,
                R.id.widget_playlist_card_4_play_container,
                R.id.widget_playlist_card_4_play_bg,
                R.id.widget_playlist_card_4_play,
                R.id.widget_playlist_card_4_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_5,
                R.id.widget_playlist_card_5_art,
                R.id.widget_playlist_card_5_play_container,
                R.id.widget_playlist_card_5_play_bg,
                R.id.widget_playlist_card_5_play,
                R.id.widget_playlist_card_5_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_6,
                R.id.widget_playlist_card_6_art,
                R.id.widget_playlist_card_6_play_container,
                R.id.widget_playlist_card_6_play_bg,
                R.id.widget_playlist_card_6_play,
                R.id.widget_playlist_card_6_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_7,
                R.id.widget_playlist_card_7_art,
                R.id.widget_playlist_card_7_play_container,
                R.id.widget_playlist_card_7_play_bg,
                R.id.widget_playlist_card_7_play,
                R.id.widget_playlist_card_7_title,
            ),
            CardSlot(
                R.id.widget_playlist_card_8,
                R.id.widget_playlist_card_8_art,
                R.id.widget_playlist_card_8_play_container,
                R.id.widget_playlist_card_8_play_bg,
                R.id.widget_playlist_card_8_play,
                R.id.widget_playlist_card_8_title,
            ),
        )
}
