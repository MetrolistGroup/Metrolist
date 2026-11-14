package com.metrolist.android.playback

import android.content.Context
import android.os.Bundle
import android.util.LruCache
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.metrolist.android.R
import com.metrolist.android.constants.MediaSessionConstants
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.hasSupportedYouTubeAuthCookie
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.AutomotiveAutoPlaylist
import com.metrolist.shared.settings.AutomotivePinnedPlaylist
import com.metrolist.shared.settings.AutomotivePinnedPlaylistPlaybackMode
import com.metrolist.shared.settings.AutomotivePlaylistSource
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SongBlockSnapshot
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.getAutomotivePinnedPlaylists
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal object AutomotiveMediaIds {
    const val ROOT = "auto:root"
    const val HOME = "auto:home"
    const val COLLECTIONS = "auto:collections"
    const val HISTORY = "auto:history"
    const val LIKED = "auto:liked"
    const val DOWNLOADS = "auto:downloads"
    const val TOP = "auto:top"
    const val CACHED = "auto:cached"
    const val UPLOADED = "auto:uploaded"
    const val SONGS = "auto:songs"
    const val ALBUMS = "auto:albums"
    const val ARTISTS = "auto:artists"
    const val PLAYLISTS = "auto:playlists"
    const val YOUTUBE_PLAYLISTS = "auto:youtube-playlists"
    const val PODCASTS = "auto:podcasts"
    const val PINNED_PLAYLISTS = "auto:pinned-playlists"

    private const val SHUFFLE_SUFFIX = "/__shuffle__"

    fun shuffle(parentId: String): String = parentId + SHUFFLE_SUFFIX

    fun shuffleParent(mediaId: String): String? =
        mediaId.takeIf { it.endsWith(SHUFFLE_SUFFIX) }?.removeSuffix(SHUFFLE_SUFFIX)?.takeIf(::supportsShuffle)

    fun supportsShuffle(parentId: String): Boolean =
        parentId == LIKED || parentId == DOWNLOADS ||
            albumId(parentId) != null || localPlaylistId(parentId) != null || youtubePlaylistId(parentId) != null

    private const val BROWSE_SONG_PREFIX = "auto:browse-song:"
    private const val SONG_PREFIX = "auto:song:"
    private const val ALBUM_PREFIX = "auto:album:"
    private const val ARTIST_PREFIX = "auto:artist:"
    private const val LOCAL_PLAYLIST_PREFIX = "auto:playlist:local:"
    private const val YOUTUBE_PLAYLIST_PREFIX = "auto:playlist:youtube:"
    private const val PINNED_LOCAL_PLAYLIST_PREFIX = "auto:pinned:local:"
    private const val PINNED_YOUTUBE_PLAYLIST_PREFIX = "auto:pinned:youtube:"
    private const val PINNED_AUTO_PLAYLIST_PREFIX = "auto:pinned:auto:"
    private const val QUEUE_SONG_PREFIX = "auto:queue:"

    data class QueueSong(
        val entryKey: Long,
        val songId: String,
    )

    fun song(id: String): String = SONG_PREFIX + encode(id)

    fun album(id: String): String = ALBUM_PREFIX + encode(id)

    fun artist(id: String): String = ARTIST_PREFIX + encode(id)

    fun localPlaylist(id: String): String = LOCAL_PLAYLIST_PREFIX + encode(id)

    fun youtubePlaylist(id: String): String = YOUTUBE_PLAYLIST_PREFIX + encode(id)

    fun pinnedLocalPlaylist(id: String): String = PINNED_LOCAL_PLAYLIST_PREFIX + encode(id)

    fun pinnedYoutubePlaylist(id: String): String = PINNED_YOUTUBE_PLAYLIST_PREFIX + encode(id)

    fun pinnedAutoPlaylist(id: String): String = PINNED_AUTO_PLAYLIST_PREFIX + encode(id)

    fun queueSong(
        entryKey: Long,
        songId: String,
    ): String = "$QUEUE_SONG_PREFIX$entryKey:${encode(songId)}"

    data class BrowseSong(
        val parentId: String,
        val index: Int,
        val songId: String,
    )

    fun browseSong(
        parentId: String,
        index: Int,
        songId: String,
    ): String = "$BROWSE_SONG_PREFIX${encode(parentId)}:$index:${encode(songId)}"

    fun browseSong(mediaId: String): BrowseSong? {
        if (!mediaId.startsWith(BROWSE_SONG_PREFIX)) return null
        val parts = mediaId.removePrefix(BROWSE_SONG_PREFIX).split(':')
        if (parts.size != 3) return null
        val parent = decode(parts[0])?.takeIf(String::isNotEmpty) ?: return null
        val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val song = decode(parts[2])?.takeIf(String::isNotEmpty) ?: return null
        return BrowseSong(parent, index, song)
    }

    fun songId(mediaId: String): String? = browseSong(mediaId)?.songId ?: decodeAfterPrefix(mediaId, SONG_PREFIX)

    fun albumId(mediaId: String): String? = decodeAfterPrefix(mediaId, ALBUM_PREFIX)

    fun artistId(mediaId: String): String? = decodeAfterPrefix(mediaId, ARTIST_PREFIX)

    fun localPlaylistId(mediaId: String): String? = decodeAfterPrefix(mediaId, LOCAL_PLAYLIST_PREFIX)

    fun youtubePlaylistId(mediaId: String): String? = decodeAfterPrefix(mediaId, YOUTUBE_PLAYLIST_PREFIX)

    fun pinnedLocalPlaylistId(mediaId: String): String? = decodeAfterPrefix(mediaId, PINNED_LOCAL_PLAYLIST_PREFIX)

    fun pinnedYoutubePlaylistId(mediaId: String): String? = decodeAfterPrefix(mediaId, PINNED_YOUTUBE_PLAYLIST_PREFIX)

    fun pinnedAutoPlaylistId(mediaId: String): String? = decodeAfterPrefix(mediaId, PINNED_AUTO_PLAYLIST_PREFIX)

    fun queueSong(mediaId: String): QueueSong? {
        if (!mediaId.startsWith(QUEUE_SONG_PREFIX)) return null
        val parts = mediaId.removePrefix(QUEUE_SONG_PREFIX).split(':', limit = 2)
        val entryKey = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val songId = parts.getOrNull(1)?.let(::decode)?.takeIf(String::isNotEmpty) ?: return null
        return QueueSong(entryKey, songId)
    }

    private fun encode(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeAfterPrefix(
        value: String,
        prefix: String,
    ): String? {
        if (!value.startsWith(prefix)) return null
        return decode(value.removePrefix(prefix))
    }

    private fun decode(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrNull()
}

internal fun automotiveStoredPlaylistMediaId(playlist: PlaylistEntity): String =
    playlist.browseId
        ?.takeUnless { playlist.isLocal }
        ?.let(AutomotiveMediaIds::youtubePlaylist)
        ?: AutomotiveMediaIds.localPlaylist(playlist.id)

internal fun automotiveQueueIndex(
    mediaId: String,
    entries: List<MusicQueueEntry>,
): Int? {
    val queued = AutomotiveMediaIds.queueSong(mediaId) ?: return null
    return entries.indexOfFirst { it.key == queued.entryKey && it.song.id == queued.songId }.takeIf { it >= 0 }
}

internal object AutomotivePaging {
    fun <T> page(
        items: List<T>,
        page: Int,
        pageSize: Int,
    ): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = page.toLong() * pageSize.toLong()
        if (from >= items.size || from > Int.MAX_VALUE) return emptyList()
        val start = from.toInt()
        val end = minOf(items.size.toLong(), from + pageSize.toLong()).toInt()
        return items.subList(start, end)
    }
}

internal data class AutomotivePinnedPlaylistQueue(
    val title: String,
    val songs: List<SongItem>,
    val shuffle: Boolean,
    val playlistPage: PlaylistPage? = null,
)

internal data class AutomotiveVoiceQueue(
    val songs: List<SongItem>,
    val playlistPage: PlaylistPage? = null,
)

internal data class AutomotiveSongQueue(
    val songs: List<SongItem>,
    val startIndex: Int,
    val playlistPage: PlaylistPage? = null,
    val shuffle: Boolean = false,
)

internal suspend fun automotiveLikedSongs(
    liked: List<SongItem>,
    signedOut: Boolean,
    loadLegacy: suspend () -> List<SongItem>,
): List<SongItem> = liked.ifEmpty { if (signedOut) loadLegacy() else emptyList() }

internal data class AutomotiveLikedQueueSource(
    val likes: List<SongItem>,
    val account: String,
    val signedOut: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<AutomotiveLikedQueueSource>.withLegacyLikes(loadLegacy: () -> Flow<List<SongItem>>): Flow<AutomotiveLikedQueueSource> =
    flatMapLatest { source ->
        if (source.likes.isEmpty() && source.signedOut) {
            loadLegacy().map { source.copy(likes = it) }
        } else {
            flowOf(source)
        }
    }

internal fun invalidateLikedQueueContexts(
    queues: MutableMap<String, AutomotiveSongQueue>,
    likedContexts: MutableMap<String, AutomotiveSongQueue>,
    previousSource: AutomotiveLikedQueueSource,
    source: AutomotiveLikedQueueSource,
): Boolean {
    if (previousSource == source) return false
    likedContexts.forEach { (id, queue) -> if (queues[id] === queue) queues.remove(id) }
    likedContexts.clear()
    return true
}

internal class AutomotiveLibraryPager(
    private val load: suspend (limit: Int, offset: Int) -> List<SongItem>,
) {
    private val mutex = Mutex()
    private val songs = mutableListOf<SongItem>()
    private var offset = 0
    private var complete = false
    private var identity: List<String>? = null
    private var blocked: SongBlockSnapshot? = null

    suspend fun page(
        page: Int,
        pageSize: Int,
        identity: List<String>,
        blocked: SongBlockSnapshot,
    ): List<SongItem> =
        mutex.withLock {
            if (page < 0 || pageSize <= 0 || page.toLong() * pageSize > Int.MAX_VALUE) return@withLock emptyList()
            if (page == 0 || this.identity != identity || this.blocked != blocked) {
                songs.clear()
                offset = 0
                complete = false
                this.identity = identity
                this.blocked = blocked
            }
            val required = ((page.toLong() + 1) * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            while (!complete && songs.size < required) {
                val limit = maxOf(100, required - songs.size)
                val batch = load(limit, offset)
                songs += BlockedContent.filterPlayableSongs(batch, blocked)
                offset += batch.size
                complete = batch.size < limit
            }
            AutomotivePaging.page(songs, page, pageSize).toList()
        }
}

internal class AutomotiveMediaLibrary(
    private val context: Context,
    private val database: MusicDatabase,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
    private val isNetworkConnected: () -> Boolean = { PlaybackNetworkStateStore.current.connected },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refreshLikes: suspend () -> Boolean = { SongLikeState.refreshLikesFromRemote(force = false) },
) {
    private data class RemotePlaylistState(
        val identity: List<String>,
        var page: PlaylistPage? = null,
    ) {
        val songs: List<SongItem> get() = page?.songs.orEmpty()
        val complete: Boolean get() = page?.hasMore == false
    }

    private val mediaItems =
        object : LruCache<String, MediaItem>(MAX_QUEUE_CONTEXT_SONGS) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: MediaItem,
                newValue: MediaItem?,
            ) {
                if (newValue == null) {
                    songQueuesByMediaId.remove(key)
                    songQueueParents.remove(key)
                    likedQueueContexts.remove(key)
                }
            }
        }
    private val songQueuesByMediaId = ConcurrentHashMap<String, AutomotiveSongQueue>()
    private val remoteQueueParents = ConcurrentHashMap<String, AutomotiveSongQueue>()
    private val songQueueParents = ConcurrentHashMap<String, String>()
    private val likedQueueContexts = ConcurrentHashMap<String, AutomotiveSongQueue>()
    private val signedOutLocalLibrary: Boolean
        get() = Settings.shared.dataSyncId.isBlank() && !hasSupportedYouTubeAuthCookie(Settings.shared.innerTubeCookie)
    private var cachedLikeSource = likedQueueSource()
    private var cachedIdentity = currentHomeIdentity()

    private fun likedQueueSource() =
        AutomotiveLikedQueueSource(
            SongLikeState.likedSongsFlow.value,
            Settings.shared.likedSongsOrderAccount,
            signedOutLocalLibrary,
        )

    suspend fun refreshLikedSongs() {
        if (!signedOutLocalLibrary && isNetworkConnected()) {
            refreshLikes()
        }
    }

    suspend fun observeLikedSongs(onChanged: (Int) -> Unit) {
        combine(
            SongLikeState.likedSongsFlow,
            snapshotFlow { Settings.shared.likedSongsOrderAccount to signedOutLocalLibrary },
        ) { liked, identity -> AutomotiveLikedQueueSource(liked, identity.first, identity.second) }
            .withLegacyLikes {
                database.dao().likedSongsByCreateDateAsc().map { legacy -> legacy.map { it.toSongItem() } }
            }.map { source -> source.copy(likes = filterSongs(source.likes)) }
            .distinctUntilChanged()
            .collect { source ->
                invalidateChangedLikeQueues()
                onChanged(source.likes.size + if (source.likes.isNotEmpty()) 1 else 0)
            }
    }

    private fun invalidateChangedLikeQueues() {
        val identity = currentHomeIdentity()
        if (cachedIdentity != identity) {
            cachedIdentity = identity
            remotePlaylists.clear()
            remoteQueueParents.clear()
            songQueuesByMediaId.clear()
            songQueueParents.clear()
            likedQueueContexts.clear()
            mediaItems.evictAll()
            searchResults.clear()
        }
        val source = likedQueueSource()
        if (invalidateLikedQueueContexts(songQueuesByMediaId, likedQueueContexts, cachedLikeSource, source)) {
            cachedLikeSource = source
        }
    }

    private data class CachedSearch(
        val songs: List<SongItem>,
        val cachedAtMs: Long,
        val remoteSucceeded: Boolean,
    )

    private val searchResults = ConcurrentHashMap<String, CachedSearch>()
    private val remotePlaylists = ConcurrentHashMap<String, RemotePlaylistState>()
    private val remotePlaylistLocks = ConcurrentHashMap<String, Mutex>()
    private val homeLock = Mutex()
    private var homeIdentity: List<String>? = null
    private var homeItems = emptyList<YTItem>()
    private var homeLoadedAtMs = 0L

    private fun currentHomeIdentity() =
        listOf(Settings.shared.dataSyncId, Settings.shared.innerTubeCookie, Settings.shared.contentLanguage, Settings.shared.contentCountry)

    private suspend fun homeChildren(): List<MediaItem> =
        homeLock.withLock {
            val identity = currentHomeIdentity()
            if (homeIdentity != identity) {
                homeIdentity = identity
                homeItems = emptyList()
                homeLoadedAtMs = 0L
            }
            if (isNetworkConnected() && (homeItems.isEmpty() || nowMs() - homeLoadedAtMs >= SEARCH_CACHE_TTL_MS)) {
                try {
                    val items =
                        repository
                            .getHomePageData()
                            .sections
                            .flatMap { it.items }
                            .distinctBy { it.id }
                    if (identity != currentHomeIdentity()) return@withLock emptyList()
                    if (items.isNotEmpty()) {
                        homeItems = items
                        homeLoadedAtMs = nowMs()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (homeItems.isEmpty()) throw error
                }
            }
            if (identity != currentHomeIdentity()) return@withLock emptyList()
            BlockedContent.filterItems(homeItems).mapNotNull { item ->
                when (item) {
                    is SongItem -> {
                        song(item)
                    }

                    is AlbumItem -> {
                        folder(
                            AutomotiveMediaIds.album(item.id),
                            item.title,
                            MediaMetadata.MEDIA_TYPE_ALBUM,
                            item.thumbnail,
                            item.artists.joinToString { it.displayName },
                        )
                    }

                    is ArtistItem -> {
                        folder(AutomotiveMediaIds.artist(item.id), item.title, MediaMetadata.MEDIA_TYPE_ARTIST, item.thumbnail)
                    }

                    is PlaylistItem -> {
                        folder(
                            AutomotiveMediaIds.youtubePlaylist(item.id),
                            item.title,
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            libraryArtwork(item.id, item.thumbnail, item.customThumbnail),
                            item.author,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    val rootItem: MediaItem =
        folder(
            mediaId = AutomotiveMediaIds.ROOT,
            title = context.getString(R.string.app_name),
            childPlayableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            childBrowsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )

    fun rootChildren(
        browsableOnly: Boolean = true,
        childLimit: Int = 4,
    ): List<MediaItem> {
        val items = allRootChildren(browsableOnly)
        if (items.size <= childLimit) return items
        return items.take((childLimit - 1).coerceAtLeast(0)) +
            folder(AutomotiveMediaIds.COLLECTIONS, context.getString(R.string.library))
    }

    private fun allRootChildren(browsableOnly: Boolean): List<MediaItem> {
        val pins = Settings.shared.getAutomotivePinnedPlaylists()
        val pinnedItems =
            if (pins.isEmpty()) {
                emptyList()
            } else if (browsableOnly) {
                listOf(
                    folder(
                        mediaId = AutomotiveMediaIds.PINNED_PLAYLISTS,
                        title = context.getString(R.string.android_auto_driving_playlists),
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                        childPlayableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    ),
                )
            } else {
                pins.map(::pinnedPlaylist)
            }
        return pinnedItems +
            listOf(
                folder(AutomotiveMediaIds.HOME, context.getString(R.string.home)),
                folder(AutomotiveMediaIds.HISTORY, context.getString(R.string.history)),
                folder(AutomotiveMediaIds.LIKED, context.getString(R.string.liked_songs)),
                folder(AutomotiveMediaIds.DOWNLOADS, context.getString(R.string.downloads)),
                folder(AutomotiveMediaIds.TOP, context.getString(R.string.my_top_100)),
                folder(AutomotiveMediaIds.CACHED, context.getString(R.string.cached_playlist)),
                folder(AutomotiveMediaIds.UPLOADED, context.getString(R.string.uploaded_playlist_name)),
                folder(AutomotiveMediaIds.SONGS, context.getString(R.string.songs)),
                folder(AutomotiveMediaIds.ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(AutomotiveMediaIds.ARTISTS, context.getString(R.string.artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(AutomotiveMediaIds.PLAYLISTS, context.getString(R.string.playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(
                    AutomotiveMediaIds.YOUTUBE_PLAYLISTS,
                    context.getString(R.string.youtube_playlists),
                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                ),
                folder(AutomotiveMediaIds.PODCASTS, context.getString(R.string.podcasts)),
            )
    }

    suspend fun children(
        parentId: String,
        page: Int,
        pageSize: Int,
        rootBrowsableOnly: Boolean = true,
        rootChildLimit: Int = 4,
    ): List<MediaItem> {
        invalidateChangedLikeQueues()
        if (page < 0 || pageSize <= 0) return emptyList()
        if (parentId == AutomotiveMediaIds.SONGS) {
            return librarySongChildren(page, pageSize)
        }
        if (parentId == AutomotiveMediaIds.ROOT) {
            return AutomotivePaging.page(rootChildren(rootBrowsableOnly, rootChildLimit), page, pageSize)
        }

        AutomotiveMediaIds.youtubePlaylistId(parentId)?.let { playlistId ->
            return remotePlaylistChildren(playlistId, page, pageSize).also { items ->
                items.forEach { item ->
                    synchronized(mediaItems) {
                        if (mediaItems[item.mediaId] != null) songQueueParents[item.mediaId] = parentId
                    }
                }
            }
        }

        val items =
            when (parentId) {
                AutomotiveMediaIds.COLLECTIONS -> {
                    allRootChildren(browsableOnly = true)
                }

                AutomotiveMediaIds.HOME -> {
                    homeChildren()
                }

                AutomotiveMediaIds.HISTORY -> {
                    database
                        .dao()
                        .events()
                        .first()
                        .distinctBy { it.song.id }
                        .map { it.song.toSongItem() }
                        .let(::filterSongs)
                        .map(::song)
                }

                AutomotiveMediaIds.LIKED -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.LIKED).map(::song)
                }

                AutomotiveMediaIds.DOWNLOADS -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.DOWNLOADED).map(::song)
                }

                AutomotiveMediaIds.TOP -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.TOP).map(::song)
                }

                AutomotiveMediaIds.CACHED -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.CACHED).map(::song)
                }

                AutomotiveMediaIds.UPLOADED -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.UPLOADED).map(::song)
                }

                AutomotiveMediaIds.ALBUMS -> {
                    val blockedArtistIds = BlockedContent.artistIds()
                    database
                        .dao()
                        .albumsByNameAsc(includeBookmarked = true)
                        .first()
                        .filterNot { album -> album.artists.any { it.id.isNotBlank() && it.id in blockedArtistIds } }
                        .map { album ->
                            folder(
                                mediaId = AutomotiveMediaIds.album(album.id),
                                title = album.title,
                                mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                                artwork = album.thumbnailUrl,
                                subtitle = album.artists.joinToString { it.displayName },
                            )
                        }
                }

                AutomotiveMediaIds.ARTISTS -> {
                    val blockedArtistIds = BlockedContent.artistIds()
                    database
                        .dao()
                        .artistsByNameAsc(includeBookmarked = true)
                        .first()
                        .filterNot { it.id.isNotBlank() && it.id in blockedArtistIds }
                        .map { artist ->
                            folder(
                                mediaId = AutomotiveMediaIds.artist(artist.id),
                                title = ArtistNameAliases.resolve(artist.id, artist.title),
                                mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                                artwork = artist.thumbnailUrl,
                            )
                        }
                }

                AutomotiveMediaIds.PLAYLISTS -> {
                    database
                        .dao()
                        .playlistsByNameAsc()
                        .first()
                        .map { playlist ->
                            folder(
                                mediaId = automotiveStoredPlaylistMediaId(playlist.playlist),
                                title = playlist.title,
                                mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                artwork = libraryArtwork(playlist.id, playlist.thumbnailUrl, playlist.playlist.customThumbnailUrl),
                            )
                        }
                }

                AutomotiveMediaIds.YOUTUBE_PLAYLISTS -> {
                    youtubePlaylistFolders()
                }

                AutomotiveMediaIds.PODCASTS -> {
                    autoPlaylistSongs(AutomotiveAutoPlaylist.PODCASTS).map(::song)
                }

                AutomotiveMediaIds.PINNED_PLAYLISTS -> {
                    Settings.shared.getAutomotivePinnedPlaylists().map(::pinnedPlaylist)
                }

                else -> {
                    collectionChildren(
                        parentId,
                        requiredCount = ((page.toLong() + 1L) * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        refresh = page == 0,
                    )
                }
            }
        val contextualItems = cacheSongQueue(items, parentId, liked = parentId == AutomotiveMediaIds.LIKED)
        val children =
            if (contextualItems.isNotEmpty() && AutomotiveMediaIds.supportsShuffle(parentId)) {
                listOf(shuffleItem(parentId)) + contextualItems
            } else {
                contextualItems
            }
        return AutomotivePaging.page(children, page, pageSize)
    }

    private suspend fun youtubePlaylistFolders(): List<MediaItem> {
        if (isNetworkConnected()) {
            val result = repository.getUserPlaylistsResult()
            if (result.complete || result.items.isNotEmpty()) {
                return result.items.filterIsInstance<PlaylistItem>().map { playlist ->
                    folder(
                        mediaId = AutomotiveMediaIds.youtubePlaylist(playlist.id),
                        title = playlist.title,
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        artwork = libraryArtwork(playlist.id, playlist.thumbnail, playlist.customThumbnail),
                        subtitle = playlist.author,
                    )
                }
            }
        }
        return database
            .dao()
            .playlistsByNameAsc()
            .first()
            .filter { !it.playlist.isLocal && !it.playlist.browseId.isNullOrBlank() }
            .map { playlist ->
                folder(
                    mediaId = automotiveStoredPlaylistMediaId(playlist.playlist),
                    title = playlist.title,
                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    artwork = libraryArtwork(playlist.id, playlist.thumbnailUrl, playlist.playlist.customThumbnailUrl),
                )
            }
    }

    private val libraryPager =
        AutomotiveLibraryPager { limit, offset ->
            database
                .dao()
                .songsByNameAsc(limit = limit, offset = offset)
                .first()
                .map { it.toSongItem() }
        }

    private suspend fun librarySongChildren(
        page: Int,
        pageSize: Int,
    ): List<MediaItem> =
        libraryPager.page(page, pageSize, currentHomeIdentity(), BlockedContent.snapshot()).mapIndexed { index, track ->
            val position = (page.toLong() * pageSize + index).toInt()
            val mediaId = AutomotiveMediaIds.browseSong(AutomotiveMediaIds.SONGS, position, track.id)
            song(track).buildUpon().setMediaId(mediaId).build()
        }

    suspend fun item(mediaId: String): MediaItem? {
        invalidateChangedLikeQueues()
        AutomotiveMediaIds.shuffleParent(mediaId)?.let { return shuffleItem(it) }
        if (mediaId == AutomotiveMediaIds.ROOT) return rootItem
        if (mediaId == AutomotiveMediaIds.COLLECTIONS) return folder(mediaId, context.getString(R.string.library))
        mediaItems[mediaId]?.let { item ->
            val artwork = mediaArtworkUri(context, item.mediaMetadata.artworkUri?.toString())
            return item
                .buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata
                        .buildUpon()
                        .setArtworkUri(artwork)
                        .build(),
                ).build()
        }
        allRootChildren(browsableOnly = false).firstOrNull { it.mediaId == mediaId }?.let { return it }
        allRootChildren(browsableOnly = true).firstOrNull { it.mediaId == mediaId }?.let { return it }
        // Browsers can restore a subscription after the service's item cache has been lost.
        val parents =
            when {
                AutomotiveMediaIds.albumId(mediaId) != null -> {
                    listOf(AutomotiveMediaIds.ALBUMS)
                }

                AutomotiveMediaIds.artistId(mediaId) != null -> {
                    listOf(AutomotiveMediaIds.ARTISTS)
                }

                AutomotiveMediaIds.localPlaylistId(mediaId) != null -> {
                    listOf(AutomotiveMediaIds.PLAYLISTS)
                }

                AutomotiveMediaIds.youtubePlaylistId(mediaId) != null -> {
                    listOf(AutomotiveMediaIds.PLAYLISTS, AutomotiveMediaIds.YOUTUBE_PLAYLISTS)
                }

                else -> {
                    emptyList()
                }
            }
        for (parent in parents) {
            children(parent, 0, Int.MAX_VALUE).firstOrNull { it.mediaId == mediaId }?.let { return it }
        }
        return resolveSong(mediaId)?.let(::song)
    }

    suspend fun search(
        query: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> = AutomotivePaging.page(cacheSongQueue(searchSongs(query).map(::song), "auto:search:$query"), page, pageSize)

    suspend fun searchSongs(query: String): List<SongItem> {
        invalidateChangedLikeQueues()
        val key = query.trim().lowercase()
        if (key.isBlank()) return emptyList()
        val connected = isNetworkConnected()
        searchResults[key]
            ?.takeIf { nowMs() - it.cachedAtMs < SEARCH_CACHE_TTL_MS && (!connected || it.remoteSucceeded) }
            ?.let { return filterSongs(it.songs) }

        val (songs, remoteSucceeded) =
            coroutineScope {
                val local =
                    async {
                        database
                            .dao()
                            .searchSongs(query.trim(), SEARCH_LIMIT)
                            .first()
                            .map { it.toSongItem() }
                    }
                val remote =
                    async {
                        if (!connected) return@async null
                        repository.searchFiltered(query.trim(), SearchFilter.SONG)
                    }
                val page = remote.await()
                val combined =
                    filterSongs(
                        local.await() + page?.items.orEmpty().filterIsInstance<SongItem>(),
                    ).distinctBy { it.id }.take(SEARCH_LIMIT)
                combined to (page?.onlineSearchFailed == false)
            }
        songs.forEach { song(it) }
        if (searchResults.size >= MAX_CACHED_SEARCHES) {
            searchResults.keys.firstOrNull()?.let(searchResults::remove)
        }
        searchResults[key] = CachedSearch(songs, nowMs(), remoteSucceeded)
        return songs
    }

    suspend fun resolveSong(mediaItem: MediaItem): SongItem? {
        val query = mediaItem.requestMetadata.searchQuery?.takeIf { it.isNotBlank() }
        if (query != null) return voiceSearchRequest(query, mediaItem.requestMetadata.extras).bestMatch(searchSongs(query))
        return resolveSong(mediaItem.mediaId)
    }

    suspend fun resolveVoiceSearch(request: VoiceSearchRequest): AutomotiveVoiceQueue {
        if (request.matchQuery.isBlank()) {
            return database
                .dao()
                .songsByNameAsc()
                .first()
                .map { it.toSongItem() }
                .let(::filterSongs)
                .shuffled()
                .let(::AutomotiveVoiceQueue)
        }
        if (request.focus == VoiceSearchFocus.ANY || request.focus == VoiceSearchFocus.PLAYLIST) {
            val collections =
                AutomotiveAutoPlaylist.entries.map { type ->
                    val title =
                        context.getString(
                            when (type) {
                                AutomotiveAutoPlaylist.LIKED -> R.string.liked_songs
                                AutomotiveAutoPlaylist.DOWNLOADED -> R.string.downloads
                                AutomotiveAutoPlaylist.TOP -> R.string.my_top_100
                                AutomotiveAutoPlaylist.CACHED -> R.string.cached_playlist
                                AutomotiveAutoPlaylist.UPLOADED -> R.string.uploaded_playlist_name
                                AutomotiveAutoPlaylist.PODCASTS -> R.string.podcasts
                            },
                        )
                    PlaylistItem(type.id, title, null)
                }
            request.bestMatch(collections)?.let { collection ->
                val songs = autoPlaylistSongs(checkNotNull(AutomotiveAutoPlaylist.fromId(collection.id)))
                if (songs.isNotEmpty()) return AutomotiveVoiceQueue(songs)
            }
            val playlists = database.dao().playlistsByNameAsc().first()
            val match = request.bestMatch(playlists.map { PlaylistItem(it.id, it.title, it.thumbnailUrl) })
            val local = playlists.firstOrNull { it.id == match?.id }
            if (local != null) {
                if (!local.playlist.isLocal && local.playlist.browseId != null) {
                    val queue = remoteVoicePlaylist(local.playlist.browseId!!)
                    if (queue.songs.isNotEmpty()) return queue
                }
                val songs =
                    if (local.playlist.isLocal || local.playlist.browseId == null) {
                        database.refreshHistoryPlaylist(local.id)
                        database
                            .dao()
                            .playlistSongs(local.id)
                            .first()
                            .map { it.song.toSongItem() }
                    } else {
                        emptyList()
                    }.let(::filterSongs)
                if (songs.isNotEmpty()) return AutomotiveVoiceQueue(songs)
            }
        }
        if (request.focus == VoiceSearchFocus.SONG || request.focus == VoiceSearchFocus.ANY) {
            val localSongs =
                database
                    .dao()
                    .songsByNameAsc()
                    .first()
                    .map { it.toSongItem() }
                    .let(::filterSongs)
            val candidates = filterSongs(localSongs + searchSongs(request.searchQuery)).distinctBy { it.id }
            request.bestMatch(candidates)?.let { return AutomotiveVoiceQueue(listOf(it)) }
            if (request.focus == VoiceSearchFocus.SONG) return AutomotiveVoiceQueue(emptyList())
        }
        if (request.focus == VoiceSearchFocus.ARTIST || request.focus == VoiceSearchFocus.ANY) {
            val blockedArtistIds = BlockedContent.artistIds()
            val artists =
                database
                    .dao()
                    .artistsByNameAsc(includeBookmarked = true)
                    .first()
                    .filterNot { it.id.isNotBlank() && it.id in blockedArtistIds }
                    .map { ArtistItem(it.id, it.title, it.thumbnailUrl) }
            request.bestMatch(artists)?.let { artist ->
                val songs =
                    collectionChildren(AutomotiveMediaIds.artist(artist.id), Int.MAX_VALUE, false)
                        .mapNotNull(::cachedOrTaggedSong)
                if (songs.isNotEmpty()) return AutomotiveVoiceQueue(songs)
            }
        }
        if (request.focus == VoiceSearchFocus.ALBUM || request.focus == VoiceSearchFocus.ANY) {
            val albums =
                database
                    .dao()
                    .albumsByNameAsc(includeBookmarked = true)
                    .first()
                    .map { AlbumItem(it.id, it.title, it.thumbnailUrl, it.artists.map { artist -> ArtistTiny(artist.id, artist.name) }) }
            request.bestMatch(albums)?.let { album ->
                val songs =
                    collectionChildren(AutomotiveMediaIds.album(album.id), Int.MAX_VALUE, false)
                        .mapNotNull(::cachedOrTaggedSong)
                if (songs.isNotEmpty()) return AutomotiveVoiceQueue(songs)
            }
        }
        if (!isNetworkConnected()) return AutomotiveVoiceQueue(emptyList())
        val filters =
            when (request.focus) {
                VoiceSearchFocus.ARTIST -> listOf(SearchFilter.ARTIST)
                VoiceSearchFocus.ALBUM -> listOf(SearchFilter.ALBUM)
                VoiceSearchFocus.PLAYLIST -> listOf(SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST)
                else -> listOf(SearchFilter.ARTIST, SearchFilter.ALBUM, SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST)
            }
        val candidates = filters.flatMap { repository.searchFiltered(request.searchQuery, it).items }
        return when (val match = request.bestMatch(candidates)) {
            is ArtistItem -> repository.startArtistRadio(match.id).ifEmpty { repository.getArtistSongs(match.id) }
            is AlbumItem -> repository.getAlbum(match.id)?.songs.orEmpty()
            is PlaylistItem -> return remoteVoicePlaylist(match.id)
            else -> emptyList()
        }.let(::filterSongs).let(::AutomotiveVoiceQueue)
    }

    private suspend fun remoteVoicePlaylist(id: String): AutomotiveVoiceQueue {
        if (!isNetworkConnected()) return AutomotiveVoiceQueue(playlistSongs(id, null).let(::filterSongs))
        val page = repository.getPlaylistPages(id, applyContentFilters = false).first { filterSongs(it.songs).isNotEmpty() || !it.hasMore }
        return AutomotiveVoiceQueue(filterSongs(page.songs), page)
    }

    suspend fun resolvePinnedPlaylist(mediaItem: MediaItem): AutomotivePinnedPlaylistQueue? {
        invalidateChangedLikeQueues()
        val sourceAndId =
            AutomotiveMediaIds.pinnedLocalPlaylistId(mediaItem.mediaId)?.let { AutomotivePlaylistSource.LOCAL to it }
                ?: AutomotiveMediaIds.pinnedYoutubePlaylistId(mediaItem.mediaId)?.let { AutomotivePlaylistSource.YOUTUBE to it }
                ?: AutomotiveMediaIds.pinnedAutoPlaylistId(mediaItem.mediaId)?.let { AutomotivePlaylistSource.AUTO to it }
                ?: return null
        val pinned =
            Settings.shared
                .getAutomotivePinnedPlaylists()
                .firstOrNull { it.source == sourceAndId.first && it.id == sourceAndId.second }
                ?: return null
        val shuffle = Settings.shared.androidAutoPinnedPlaylistPlaybackMode == AutomotivePinnedPlaylistPlaybackMode.SHUFFLE
        val songs =
            when (pinned.source) {
                AutomotivePlaylistSource.LOCAL -> {
                    storedPlaylistSongs(pinned.id, PINNED_PLAYLIST_INITIAL_SONGS, refresh = true)
                }

                AutomotivePlaylistSource.YOUTUBE -> {
                    playlistSongs(pinned.id, PINNED_PLAYLIST_INITIAL_SONGS, refresh = true)
                }

                AutomotivePlaylistSource.AUTO -> {
                    AutomotiveAutoPlaylist.fromId(pinned.id)?.let { autoPlaylistSongs(it) }.orEmpty()
                }
            }.let(::filterSongs)
        val remoteId =
            when (pinned.source) {
                AutomotivePlaylistSource.YOUTUBE -> {
                    pinned.id
                }

                AutomotivePlaylistSource.LOCAL -> {
                    database
                        .dao()
                        .playlist(pinned.id)
                        .first()
                        ?.playlist
                        ?.let(::automotiveStoredPlaylistMediaId)
                        ?.let(AutomotiveMediaIds::youtubePlaylistId)
                }

                AutomotivePlaylistSource.AUTO -> {
                    null
                }
            }
        return AutomotivePinnedPlaylistQueue(
            title = pinned.title,
            songs = songs,
            shuffle = shuffle,
            playlistPage =
                remoteId
                    ?.let { remotePlaylists[it.removePrefix("VL")] }
                    ?.takeIf { it.identity == currentHomeIdentity() }
                    ?.page
                    ?.takeIf { isNetworkConnected() && it.hasMore },
        )
    }

    suspend fun resolveAddition(mediaItem: MediaItem): List<SongItem> =
        resolvePinnedPlaylist(mediaItem)?.let { pinned ->
            var page = pinned.playlistPage
            while (page?.hasMore == true) page = repository.continuePlaylistPage(page, applyContentFilters = false)
            page?.songs?.let(::filterSongs) ?: pinned.songs
        } ?: listOfNotNull(resolveSong(mediaItem))

    fun playableItem(song: SongItem): MediaItem = song(song)

    suspend fun resolveSongQueue(
        mediaItem: MediaItem,
        random: Random = Random.Default,
    ): AutomotiveSongQueue? {
        invalidateChangedLikeQueues()
        AutomotiveMediaIds.shuffleParent(mediaItem.mediaId)?.let { parent ->
            val firstSong =
                children(parent, 0, PINNED_PLAYLIST_INITIAL_SONGS)
                    .firstOrNull { AutomotiveMediaIds.songId(it.mediaId) != null } ?: return null
            val queue = resolveSongQueue(firstSong, random) ?: return null
            return queue.copy(startIndex = random.nextInt(queue.songs.size), shuffle = true)
        }
        val selection = AutomotiveMediaIds.browseSong(mediaItem.mediaId)
        if (selection?.parentId == AutomotiveMediaIds.SONGS) {
            val songs =
                database
                    .dao()
                    .songsByNameAsc()
                    .first()
                    .map { it.toSongItem() }
                    .let(::filterSongs)
            val index = songs.indexOfFirst { it.id == selection.songId }.takeIf { it >= 0 } ?: return null
            return AutomotiveSongQueue(songs, index)
        }
        if (selection?.parentId?.startsWith("auto:search:") == true && songQueuesByMediaId[mediaItem.mediaId] == null) {
            val songs = searchSongs(selection.parentId.removePrefix("auto:search:"))
            val index =
                selection.index.takeIf { songs.getOrNull(it)?.id == selection.songId }
                    ?: songs.indexOfFirst { it.id == selection.songId }.takeIf { it >= 0 }
                    ?: return null
            return AutomotiveSongQueue(songs, index)
        }
        val parent = songQueueParents[mediaItem.mediaId] ?: selection?.parentId?.takeUnless { it.startsWith("auto:search:") }
        if (!isNetworkConnected() ||
            (songQueuesByMediaId[mediaItem.mediaId] == null && parent?.let(remoteQueueParents::get) == null)
        ) {
            parent?.let { parent ->
                val remotePlaylist = isNetworkConnected() && AutomotiveMediaIds.youtubePlaylistId(parent) != null
                val pageSize =
                    if (remotePlaylist) {
                        ((selection?.index ?: 0).toLong() + 2)
                            .coerceAtMost(
                                Int.MAX_VALUE.toLong(),
                            ).toInt()
                    } else {
                        Int.MAX_VALUE
                    }
                val selectedId = AutomotiveMediaIds.songId(mediaItem.mediaId)

                suspend fun load(limit: Int): Pair<AutomotiveSongQueue?, List<SongItem>> {
                    val items = children(parent, 0, limit)
                    val remoteQueue = remoteQueueParents[parent].takeIf { remotePlaylist }
                    return remoteQueue to (remoteQueue?.songs ?: items.mapNotNull(::cachedOrTaggedSong))
                }
                var (remoteQueue, songs) = load(pageSize)
                // A changed playlist may move the selected song beyond the original page.
                if (remotePlaylist && songs.none { it.id == selectedId }) {
                    load(Int.MAX_VALUE).let { (queue, loaded) ->
                        remoteQueue = queue
                        songs = loaded
                    }
                }
                if (songs.isEmpty()) return null
                val selectedIndex =
                    AutomotiveMediaIds
                        .browseSong(mediaItem.mediaId)
                        ?.index
                        ?.takeIf { songs.getOrNull(it)?.id == selectedId }
                        ?: songs.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: return null
                return AutomotiveSongQueue(songs, selectedIndex, remoteQueue?.playlistPage)
            }
        }
        parent?.let { parent ->
            remoteQueueParents[parent]?.let { queue ->
                val selection = AutomotiveMediaIds.browseSong(mediaItem.mediaId) ?: return null
                return queue
                    .takeIf { it.songs.getOrNull(selection.index)?.id == AutomotiveMediaIds.songId(mediaItem.mediaId) }
                    ?.copy(startIndex = selection.index)
            }
        }
        return songQueuesByMediaId[mediaItem.mediaId]
    }

    private fun cachedSong(mediaId: String): SongItem? = mediaItems[mediaId]?.localConfiguration?.tag as? SongItem

    fun resolveCachedSong(mediaItem: MediaItem): SongItem? {
        if (AutomotiveMediaIds.queueSong(mediaItem.mediaId) != null) return null
        val videoId = AutomotiveMediaIds.songId(mediaItem.mediaId) ?: mediaItem.mediaId
        // Resolved requests must survive eviction while another browse or search fills the cache.
        (mediaItem.localConfiguration?.tag as? SongItem)?.takeIf { it.id == videoId }?.let { return it }
        cachedSong(mediaItem.mediaId)?.let { return it }
        return cachedSong(AutomotiveMediaIds.song(videoId))
    }

    // Items built in this call retain their songs even when a large page exceeds the session cache.
    private fun cachedOrTaggedSong(item: MediaItem): SongItem? = item.localConfiguration?.tag as? SongItem ?: resolveCachedSong(item)

    private suspend fun resolveSong(mediaId: String): SongItem? {
        cachedSong(mediaId)?.let { return it }
        if (AutomotiveMediaIds.queueSong(mediaId) != null) return null
        val selection = AutomotiveMediaIds.browseSong(mediaId)
        val videoId =
            AutomotiveMediaIds.songId(mediaId)
                ?: mediaId.takeIf { !it.startsWith("auto:") }
                ?: return null
        cachedSong(AutomotiveMediaIds.song(videoId))?.let { return it }
        val local =
            database.dao().getSongById(videoId)?.toSongItem()
                ?: selection
                    ?.parentId
                    ?.takeIf { it.startsWith("auto:search:") }
                    ?.removePrefix("auto:search:")
                    ?.let { query -> searchSongs(query).firstOrNull { it.id == videoId } }
                ?: return null
        return local.takeUnless { it.isUnavailable || BlockedContent.isBlocked(it) }?.also { song(it) }
    }

    private suspend fun collectionChildren(
        parentId: String,
        requiredCount: Int,
        refresh: Boolean,
    ): List<MediaItem> {
        AutomotiveMediaIds.albumId(parentId)?.let { albumId ->
            val stored =
                database
                    .dao()
                    .storedAlbumSongs(albumId)
                    .first()
                    .map { it.toSongItem() }
            val remote =
                homeItems.any { it is AlbumItem && it.id == albumId } ||
                    database
                        .dao()
                        .album(albumId)
                        .first()
                        ?.album
                        ?.bookmarkedAt != null
            return collectionSongs(stored, remote) { repository.getAlbum(albumId)?.songs.orEmpty() }.map(::song)
        }
        AutomotiveMediaIds.artistId(parentId)?.let { artistId ->
            val bookmarked = database.dao().getArtistById(artistId)?.bookmarkedAt != null
            val stored =
                (if (bookmarked) database.dao().storedArtistSongs(artistId) else database.dao().artistSongsByNameAsc(artistId))
                    .first()
                    .map { it.toSongItem() }
            val remote = bookmarked || homeItems.any { it is ArtistItem && it.id == artistId }
            return collectionSongs(stored, remote) { repository.getArtistSongs(artistId) }.map(::song)
        }
        AutomotiveMediaIds.localPlaylistId(parentId)?.let { playlistId ->
            return storedPlaylistSongs(playlistId, requiredCount, refresh)
                .let(::filterSongs)
                .map(::song)
        }
        return emptyList()
    }

    private suspend fun collectionSongs(
        stored: List<SongItem>,
        remote: Boolean,
        load: suspend () -> List<SongItem>,
    ): List<SongItem> {
        if (!remote || !isNetworkConnected()) return filterSongs(stored)
        return try {
            filterSongs(load())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (stored.isEmpty()) throw error
            filterSongs(stored)
        }
    }

    private suspend fun storedPlaylistSongs(
        playlistId: String,
        requiredCount: Int?,
        refresh: Boolean = false,
    ): List<SongItem> {
        database.refreshHistoryPlaylist(playlistId)
        val playlist =
            database
                .dao()
                .playlist(playlistId)
                .first()
                ?.playlist
        val remoteId = playlist?.let(::automotiveStoredPlaylistMediaId)?.let(AutomotiveMediaIds::youtubePlaylistId)
        val stored =
            database
                .dao()
                .playlistSongs(playlistId)
                .first()
                .map { it.song.toSongItem() }
        if (remoteId == null || !isNetworkConnected()) return stored
        return try {
            loadRemotePlaylistSongs(remoteId, requiredCount, refresh)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            remotePlaylists[remoteId.removePrefix("VL")]
                ?.takeIf { it.identity == currentHomeIdentity() }
                ?.songs
                ?.toList()
                .orEmpty()
                .ifEmpty { stored }
        }
    }

    private suspend fun autoPlaylistSongs(type: AutomotiveAutoPlaylist): List<SongItem> =
        when (type) {
            AutomotiveAutoPlaylist.LIKED -> {
                automotiveLikedSongs(
                    liked = SongLikeState.likedSongsFlow.first(),
                    signedOut = signedOutLocalLibrary,
                ) {
                    database
                        .dao()
                        .likedSongsByCreateDateAsc()
                        .first()
                        .map { it.toSongItem() }
                }
            }

            AutomotiveAutoPlaylist.DOWNLOADED -> {
                cachedSongs(KIND_DOWNLOAD)
            }

            AutomotiveAutoPlaylist.TOP -> {
                database
                    .dao()
                    .mostPlayedSongs(0L, limit = 100)
                    .first()
                    .map { it.toSongItem() }
            }

            AutomotiveAutoPlaylist.CACHED -> {
                cachedSongs(KIND_CACHE)
            }

            AutomotiveAutoPlaylist.UPLOADED -> {
                val remote =
                    runCatching { repository.getLibraryUploadSongs(limit = 500) }
                        .getOrDefault(emptyList())
                remote.ifEmpty {
                    database
                        .dao()
                        .uploadedSongsByNameAsc()
                        .first()
                        .map { it.toSongItem() }
                }
            }

            AutomotiveAutoPlaylist.PODCASTS -> {
                database
                    .dao()
                    .savedPodcastEpisodesByCreateDateAsc()
                    .first()
                    .map { it.toSongItem() }
            }
        }.let(::filterSongs)

    private suspend fun cachedSongs(kind: String): List<SongItem> {
        val cached =
            database
                .dao()
                .cachedAudioByKind(kind)
                .first()
                .filter { entry -> File(entry.filePath).let { it.exists() && it.length() > 0L } }
        if (cached.isEmpty()) return emptyList()
        val localSongs = database.dao().getSongsByIds(cached.map { it.songId }).associateBy { it.id }
        return cached.map { entry ->
            localSongs[entry.songId]?.toSongItem()
                ?: SongItem(
                    id = entry.songId,
                    title = entry.title,
                    thumbnail = entry.thumbnailUrl,
                    artists =
                        entry.artist
                            .takeIf { it.isNotBlank() }
                            ?.let { listOf(ArtistTiny(null, it)) }
                            .orEmpty(),
                    album = entry.album?.let { AlbumTiny(null, it) },
                )
        }
    }

    private suspend fun remotePlaylistChildren(
        playlistId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val requiredCount = ((page.toLong() + 1L) * pageSize - 1).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val songs = filterSongs(playlistSongs(playlistId, requiredCount, refresh = page == 0))
        val parent = AutomotiveMediaIds.youtubePlaylist(playlistId)
        val cursor = remotePlaylists[playlistId.removePrefix("VL")]?.page
        remoteQueueParents[parent] = AutomotiveSongQueue(songs, 0, cursor)
        if (songs.isEmpty()) return emptyList()
        val offset = (page.toLong() * pageSize - 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val shuffle = if (page == 0) listOf(shuffleItem(parent)) else emptyList()
        return shuffle +
            songs.drop(offset).take(pageSize - shuffle.size).mapIndexed { index, track ->
                val mediaId = AutomotiveMediaIds.browseSong(parent, offset + index, track.id)
                song(track)
                    .buildUpon()
                    .setMediaId(mediaId)
                    .build()
                    .also { cacheMediaItem(it) }
            }
    }

    private suspend fun playlistSongs(
        playlistId: String,
        requiredCount: Int?,
        refresh: Boolean = false,
    ): List<SongItem> {
        val stored = database.dao().findPlaylistEntity(playlistId)
        return if (stored !=
            null
        ) {
            storedPlaylistSongs(stored.id, requiredCount, refresh)
        } else {
            loadRemotePlaylistSongs(playlistId, requiredCount, refresh)
        }
    }

    private suspend fun loadRemotePlaylistSongs(
        playlistId: String,
        requiredCount: Int?,
        refresh: Boolean = false,
    ): List<SongItem> {
        val key = playlistId.removePrefix("VL")
        val lock = remotePlaylistLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val identity = currentHomeIdentity()
            val previous = remotePlaylists[key]?.takeIf { it.identity == identity }
            if (!isNetworkConnected()) return@withLock previous?.songs.orEmpty()
            val state = if (refresh || previous == null) RemotePlaylistState(identity) else previous
            remotePlaylists[key] = state
            var requests = 0
            while ((requiredCount == null || filterSongs(state.songs).size < requiredCount) && !state.complete &&
                requests < MAX_REMOTE_PLAYLIST_PAGES
            ) {
                val next =
                    state.page?.let { repository.continuePlaylistPage(it, applyContentFilters = false) }
                        ?: repository.getPlaylistPageData(playlistId, applyContentFilters = false)
                if (identity != currentHomeIdentity()) return@withLock emptyList()
                state.page = next
                requests += 1
            }
            state.songs.toList()
        }
    }

    private fun filterSongs(songs: List<SongItem>): List<SongItem> = BlockedContent.filterPlayableSongs(songs)

    private fun cacheMediaItem(item: MediaItem) =
        synchronized(mediaItems) {
            mediaItems.put(item.mediaId, item)
        }

    private fun cacheSongQueue(
        items: List<MediaItem>,
        parentId: String,
        liked: Boolean = false,
    ): List<MediaItem> {
        val songs = items.mapNotNull(::cachedOrTaggedSong)
        if (songs.isEmpty()) return items
        var index = 0
        return items.map { item ->
            val song = cachedOrTaggedSong(item) ?: return@map item
            val mediaId = AutomotiveMediaIds.browseSong(parentId, index, song.id)
            val context = AutomotiveSongQueue(songs, index++)
            item
                .buildUpon()
                .setMediaId(mediaId)
                .build()
                .also { cached ->
                    synchronized(mediaItems) {
                        cacheMediaItem(cached)
                        songQueuesByMediaId[mediaId] = context
                        if (!parentId.startsWith("auto:search:")) songQueueParents[mediaId] = parentId
                        if (liked) likedQueueContexts[mediaId] = context else likedQueueContexts.remove(mediaId)
                    }
                }
        }
    }

    private fun shuffleItem(parentId: String): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(AutomotiveMediaIds.shuffle(parentId))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(context.getString(R.string.shuffle))
                    .setArtworkUri("android.resource://${context.packageName}/${R.drawable.shuffle}".toUri())
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            ).build()

    private fun song(song: SongItem): MediaItem {
        val mediaId = AutomotiveMediaIds.song(song.id)
        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setUri("metrolist://${song.id}")
            .setTag(song)
            .setCustomCacheKey(song.id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(song.title)
                    .setDisplayTitle(song.title)
                    .setSubtitle(song.artists.joinToString { it.displayName })
                    .setArtist(song.artists.joinToString { it.displayName })
                    .setAlbumTitle(song.album?.name)
                    .setArtworkUri(mediaArtworkUri(context, song.thumbnail))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setSupportedCommands(listOf(MediaSessionConstants.ACTION_ADD_TO_QUEUE))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            ).build()
            .also { cacheMediaItem(it) }
    }

    private fun pinnedPlaylist(playlist: AutomotivePinnedPlaylist): MediaItem {
        val mediaId =
            when (playlist.source) {
                AutomotivePlaylistSource.LOCAL -> AutomotiveMediaIds.pinnedLocalPlaylist(playlist.id)
                AutomotivePlaylistSource.YOUTUBE -> AutomotiveMediaIds.pinnedYoutubePlaylist(playlist.id)
                AutomotivePlaylistSource.AUTO -> AutomotiveMediaIds.pinnedAutoPlaylist(playlist.id)
            }
        val subtitle =
            when (Settings.shared.androidAutoPinnedPlaylistPlaybackMode) {
                AutomotivePinnedPlaylistPlaybackMode.IN_ORDER -> context.getString(R.string.android_auto_play_in_order)
                AutomotivePinnedPlaylistPlaybackMode.SHUFFLE -> context.getString(R.string.shuffle)
            }
        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(playlist.title)
                    .setDisplayTitle(playlist.title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(
                        mediaArtworkUri(
                            context,
                            libraryArtwork(
                                playlist.id,
                                playlist.thumbnail,
                                playlist.thumbnail.takeIf {
                                    playlist.source ==
                                        AutomotivePlaylistSource.LOCAL
                                },
                            ),
                        ),
                    ).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                            )
                            putString(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                                context.getString(R.string.android_auto_driving_playlists),
                            )
                        },
                    ).build(),
            ).build()
    }

    private fun folder(
        mediaId: String,
        title: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        artwork: String? = null,
        subtitle: String? = null,
        childPlayableStyle: Int? = null,
        childBrowsableStyle: Int? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(
                        mediaArtworkUri(
                            context,
                            artwork ?: libraryArtwork(
                                when (mediaId) {
                                    AutomotiveMediaIds.DOWNLOADS -> AutomotiveAutoPlaylist.DOWNLOADED.id
                                    AutomotiveMediaIds.TOP -> AutomotiveAutoPlaylist.TOP.id
                                    else -> mediaId
                                },
                            ).takeUnless { mediaId == AutomotiveMediaIds.HISTORY },
                        ),
                    ).setMediaType(mediaType)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(
                        Bundle().apply {
                            childPlayableStyle?.let {
                                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
                            }
                            childBrowsableStyle?.let {
                                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
                            }
                        },
                    ).build(),
            ).build()
            .also { cacheMediaItem(it) }

    private companion object {
        const val SEARCH_LIMIT = 100
        const val MAX_CACHED_SEARCHES = 32
        const val SEARCH_CACHE_TTL_MS = 2 * 60 * 1000L
        const val MAX_REMOTE_PLAYLIST_PAGES = 100
        const val MAX_QUEUE_CONTEXT_SONGS = 2_000
        const val PINNED_PLAYLIST_INITIAL_SONGS = 50
    }
}
