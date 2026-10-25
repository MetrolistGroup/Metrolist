/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Android Auto is search-only: the browse tree has an empty root and media is reached through
 * search. Browse items sent to the car deliberately carry no artwork; only the player's own
 * MediaItems (built via [toMediaItem]) do, so the now-playing screen shows the cover.
 */
class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val searchStates = mutableMapOf<MediaSession.ControllerInfo, SearchState>()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        scope.cancel()
        searchStates.clear()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        val isAuto = isAutoController(session, controller)
        val sessionCommands =
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .apply {
                    if (!isAuto) {
                        add(MediaSessionConstants.CommandToggleLike)
                        add(MediaSessionConstants.CommandToggleLibrary)
                        add(MediaSessionConstants.CommandAddToTargetPlaylist)
                    }
                }.build()
        return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
    }

    override fun onPostConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        if (isAutoController(session, controller)) service.refreshLayoutForAutoChange()
    }

    private fun isAutoController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) = session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        searchStates.remove(controller)?.job?.cancel()
        if (isAutoController(session, controller)) service.refreshLayoutForAutoChange(controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET),
        )

    // Media3 itself advertises android.media.browse.SEARCH_SUPPORTED to legacy browsers (Android Auto)
    // whenever the controller holds COMMAND_CODE_LIBRARY_SEARCH, which super.onConnect grants.
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(
            if (parentId == MusicService.ROOT) {
                LibraryResult.ofVoid(params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(
            if (parentId == MusicService.ROOT) {
                LibraryResult.ofItemList(ImmutableList.of(), params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val (query, songId) = parseSearchMediaId(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        val item = searchStates[browser]?.results?.get(query)?.browseItems?.firstOrNull { it.mediaId == mediaId }
        return Futures.immediateFuture(
            if (item != null && songId.isNotBlank()) {
                LibraryResult.ofItem(item, null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            },
        )
    }

    /**
     * Android Auto fires a search for every query it sends and Media3's legacy bridge parks the
     * request until [MediaLibrarySession.notifySearchResultChanged]. Work is therefore debounced
     * and per controller: a newer query supersedes (and answers with an empty result) the older one.
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        val state = searchStates.getOrPut(browser) { SearchState() }
        val cached = state.results[query]
        if (state.latestQuery == query && (cached != null || state.job?.isActive == true)) {
            if (cached != null) session.notifySearchResultChanged(browser, query, cached.size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        state.job?.cancel()
        val supersededQuery = state.pendingQuery
        state.latestQuery = query
        state.pendingQuery = query
        if (supersededQuery != null && supersededQuery != query) {
            session.notifySearchResultChanged(browser, supersededQuery, 0, params)
        }

        state.job =
            scope.launch {
                try {
                    val results =
                        if (query.isBlank()) {
                            SearchResults.EMPTY
                        } else {
                            delay(SEARCH_DEBOUNCE_MS)
                            runSearch(query)
                        }
                    state.results[query] = results
                    state.pendingQuery = null
                    session.notifySearchResultChanged(browser, query, results.size, params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportException(e)
                    state.results[query] = SearchResults.EMPTY
                    state.pendingQuery = null
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
            }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future {
            val state = searchStates[browser]
            val results =
                when {
                    query.isBlank() -> SearchResults.EMPTY
                    state?.results?.get(query) != null -> state.results.getValue(query)
                    // Superseded query whose parked request is being answered.
                    state?.latestQuery != null && state.latestQuery != query -> SearchResults.EMPTY
                    else -> searchAndCache(browser, query)
                }
            LibraryResult.ofItemList(results.browseItems.paginate(page, pageSize), params)
        }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val first = mediaItems.firstOrNull() ?: return@future defaultResult
            val voiceQuery = first.requestMetadata.searchQuery?.takeIf { it.isNotBlank() }

            val (query, songId) =
                if (voiceQuery != null) {
                    voiceQuery to ""
                } else {
                    parseSearchMediaId(first.mediaId) ?: return@future defaultResult
                }
            if (query.isBlank()) return@future defaultResult

            val results =
                searchStates[controller]?.results?.get(query) ?: searchAndCache(controller, query)

            val selected =
                if (songId.isBlank()) {
                    // Voice search: prefer a strong local match, otherwise YouTube's top result.
                    VoiceSearchMatcher.findBest(query, results.local)?.toMediaMetadata()
                        ?: results.online.firstOrNull()?.toMediaMetadata()
                } else {
                    results.local.firstOrNull { it.id == songId }?.toMediaMetadata()
                        ?: results.online.firstOrNull { it.id == songId }?.toMediaMetadata()
                        ?: withContext(Dispatchers.IO) { database.song(songId).first() }?.toMediaMetadata()
                } ?: return@future defaultResult

            if (withContext(Dispatchers.IO) { context.dataStore.get(AutoRadioQueueKey, true) }) {
                val radioQueue = YouTubeQueue.radio(selected)
                val radioStatus =
                    try {
                        withContext(Dispatchers.IO) {
                            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                            val hideVideos = context.dataStore.get(HideVideoSongsKey, false)
                            radioQueue
                                .getInitialStatus()
                                .filterExplicit(hideExplicit)
                                .filterVideoSongs(hideVideos)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }

                if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                    service.adoptQueue(radioQueue, radioStatus.title, radioStatus.items.size)
                    return@future MediaItemsWithStartPosition(
                        radioStatus.items,
                        radioStatus.items.indexOfFirst { it.mediaId == selected.id }.coerceAtLeast(0),
                        C.TIME_UNSET,
                    )
                }
            }

            val selectedItem = selected.toMediaItem()
            val queueItems =
                (results.local.map { it.toMediaItem() } + results.online.map { it.toMediaItem() })
                    .filterNot { it.mediaId == selected.id }
            val items = listOf(selectedItem) + queueItems
            service.adoptQueue(ListQueue(title = selected.title, items = items), title = selected.title)
            MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
        }

    private suspend fun searchAndCache(
        controller: MediaSession.ControllerInfo,
        query: String,
    ): SearchResults {
        val results = runSearch(query)
        searchStates.getOrPut(controller) { SearchState() }.results[query] = results
        return results
    }

    private suspend fun runSearch(query: String): SearchResults =
        withContext(Dispatchers.IO) {
            val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideos = context.dataStore.get(HideVideoSongsKey, false)

            val local = database.searchSongsExtended(query, limit).first()

            val online =
                try {
                    withTimeoutOrNull(ONLINE_SEARCH_TIMEOUT_MS) {
                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    }?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(hideExplicit)
                        ?.filterVideoSongs(hideVideos)
                        ?.filter { onlineSong ->
                            local.none { localSong ->
                                localSong.id == onlineSong.id ||
                                    (
                                        localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                            localSong.artists.any { artist ->
                                                onlineSong.artists.any { it.name.equals(artist.name, ignoreCase = true) }
                                            }
                                    )
                            }
                        }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportException(e)
                    emptyList()
                }

            val separator = getArtistSeparator(context)
            val browseItems =
                local.map { song ->
                    browseItem(
                        query = query,
                        id = song.id,
                        title = song.song.title,
                        artists = song.artists.joinToArtistString(separator) { ArtistNameAliases.resolve(it.id, it.name) },
                        explicit = song.song.explicit,
                    )
                } +
                    online.map { songItem ->
                        browseItem(
                            query = query,
                            id = songItem.id,
                            title = songItem.title,
                            artists = songItem.artists.joinToArtistString(separator) { ArtistNameAliases.resolve(it.id, it.name) },
                            explicit = songItem.explicit,
                        )
                    }
            SearchResults(local, online, browseItems)
        }

    private fun browseItem(
        query: String,
        id: String,
        title: String,
        artists: String,
        explicit: Boolean,
    ) = MediaItem
        .Builder()
        .setMediaId("${MusicService.SEARCH}/$query/$id")
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(artists)
                .setArtist(artists)
                .setExtras(
                    Bundle().apply {
                        if (explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                    },
                ).setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        ).build()

    private class SearchState {
        var latestQuery: String? = null

        /** Query whose parked legacy request has not been answered yet. */
        var pendingQuery: String? = null
        var job: Job? = null
        val results =
            object : LinkedHashMap<String, SearchResults>(MAX_CACHED_QUERIES, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResults>) =
                    size > MAX_CACHED_QUERIES
            }
    }

    private class SearchResults(
        val local: List<Song>,
        val online: List<SongItem>,
        val browseItems: List<MediaItem>,
    ) {
        val size get() = browseItems.size

        companion object {
            val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 700L
        const val ONLINE_SEARCH_TIMEOUT_MS = 10_000L
        const val MAX_CACHED_QUERIES = 3
    }
}

/** Splits `search/<query>/<songId>`; the query may itself contain slashes. */
internal fun parseSearchMediaId(mediaId: String): Pair<String, String>? {
    val prefix = "${MusicService.SEARCH}/"
    if (!mediaId.startsWith(prefix)) return null
    val rest = mediaId.removePrefix(prefix)
    val separator = rest.lastIndexOf('/')
    if (separator < 0) return null
    return rest.substring(0, separator) to rest.substring(separator + 1)
}

internal fun <T> List<T>.paginate(
    page: Int,
    pageSize: Int,
): List<T> {
    val fromIndex = (page.toLong() * pageSize).coerceIn(0L, size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + pageSize).coerceIn(0L, size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}
