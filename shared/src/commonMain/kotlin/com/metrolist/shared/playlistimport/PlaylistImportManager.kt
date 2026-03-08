package com.metrolist.shared.playlistimport

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongOccurrences
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.ImportTrackMatch
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.favorites
import com.metrolist.shared.generated.resources.imported_playlist
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isVideoType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private fun uriScheme(value: String): String? = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)

class PlaylistImportManager(
    private val database: MusicDatabase,
    private val repository: YouTubeRepository,
) {
    suspend fun resolveImportTracks(
        tracks: List<CsvTrack>,
        destination: Destination?,
        provider: ImportSourceType?,
        onProgress: (resolved: Int, total: Int, currentTrack: CsvTrack?) -> Unit = { _, _, _ -> },
    ): List<Pair<CsvTrack, SongItem>> =
        coroutineScope {
            if (tracks.size > MAX_PLAYLIST_TRACKS) throw ImportException(ImportErrorKey.TooManyTracks)
            val importTracks = tracks
            val playlist = (destination as? Destination.EXISTING)?.playlist
            val cached =
                if (playlist != null && provider != null) {
                    val matches = database.importTrackMatchDao().importTrackMatches(playlist.id, provider.name)
                    database
                        .dao()
                        .getSongsByIds(matches.map(ImportTrackMatch::songId))
                        .associateBy { it.id }
                        .let { songs ->
                            matches
                                .mapNotNull { match ->
                                    songs[match.songId]?.let { match.trackKey to it.toSongItem() }
                                }.toMap()
                        }
                } else {
                    emptyMap()
                }
            val progressMutex = Mutex()
            var completed = 0
            var nextIndex = 0
            val results = arrayOfNulls<Pair<CsvTrack, SongItem>>(importTracks.size)
            List(minOf(RESOLVE_CONCURRENCY, importTracks.size)) {
                async {
                    while (true) {
                        ensureActive()
                        val index = progressMutex.withLock { nextIndex++ }
                        if (index >= importTracks.size) break
                        val track = importTracks[index]
                        val cachedSong =
                            cached[track.matchKey()]?.takeIf {
                                track.isResolvedLocalReference || track.videoId == it.id || bestTrackMatch(track, listOf(it)) != null
                            }
                        val song = cachedSong ?: resolveTrack(track)
                        results[index] =
                            song?.let {
                                val customTitle = track.customTitle?.takeIf(String::isNotBlank)
                                track to
                                    if (customTitle == null) {
                                        it
                                    } else {
                                        it.copy(
                                            title = customTitle,
                                            customTitle = customTitle,
                                            canonicalTitle = it.canonicalTitle ?: it.title,
                                        )
                                    }
                            }
                        progressMutex.withLock {
                            completed++
                            onProgress(completed, importTracks.size, track)
                        }
                    }
                }
            }.awaitAll()
            results.filterNotNull()
        }

    suspend fun importResolvedTracks(
        name: String,
        matches: List<Pair<CsvTrack, SongItem>>,
        destination: Destination,
        provider: ImportSourceType?,
        sourceYouTubePlaylistId: String? = null,
        coverUrl: String? = null,
        onProgress: (imported: Int, total: Int, currentSong: SongItem?) -> Unit = { _, _, _ -> },
    ): Result<ImportResult> {
        val songs = matches.map { it.second }
        val result = importVerbose(name, songs, destination, sourceYouTubePlaylistId, coverUrl, onProgress)
        val playlist = result.getOrNull()?.playlist ?: (result.exceptionOrNull() as? IncompleteImportException)?.playlist
        if (provider != null && playlist != null) {
            val importedIds = database.dao().playlistSongMaps(playlist.id, 0).mapTo(mutableSetOf()) { it.songId }
            database.importTrackMatchDao().upsert(
                matches
                    .filter { it.second.id in importedIds }
                    .map { (track, song) ->
                        ImportTrackMatch(playlist.id, provider.name, track.matchKey(), song.id)
                    }.distinctBy(ImportTrackMatch::trackKey),
            )
        }
        return result
    }

    private fun CsvTrack.matchKey(): String =
        listOf(title.trim().lowercase(), artist.trim().lowercase(), directReference.orEmpty()).joinToString("\u0000")

    suspend fun loadYouTubePlaylist(
        input: String,
        fallbackTitle: String? = null,
    ): Result<Triple<String, List<SongItem>, String?>> =
        try {
            val playlistId = parsePlaylistId(input) ?: error("Enter a valid YouTube or YouTube Music playlist URL")
            val sections = repository.getPlaylist(playlistId, applyContentFilters = false)
            val items = sections.values.flatten()
            val title =
                items.filterIsInstance<PlaylistItem>().firstOrNull()?.title ?: fallbackTitle ?: getString(Res.string.imported_playlist)
            val coverUrl = items.filterIsInstance<PlaylistItem>().firstOrNull()?.thumbnail
            val songs = sections.playlistSongOccurrences()
            check(songs.isNotEmpty()) { "The playlist has no importable songs" }
            Result.success(Triple(title, songs.take(MAX_PLAYLIST_TRACKS), coverUrl))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    suspend fun import(
        name: String,
        songs: List<SongItem>,
        destination: Destination,
        onProgress: (imported: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<ImportResult> = importVerbose(name, songs, destination) { imp, tot, _ -> onProgress(imp, tot) }

    suspend fun importVerbose(
        name: String,
        songs: List<SongItem>,
        destination: Destination,
        sourceYouTubePlaylistId: String? = null,
        coverUrl: String? = null,
        onProgress: (imported: Int, total: Int, currentSong: SongItem?) -> Unit = { _, _, _ -> },
    ): Result<ImportResult> {
        val importSongs = if (destination == Destination.Favorites) songs.distinctBy { it.id } else songs
        var createdRemotePlaylistId: String? = null
        var importedSongCount = 0
        var resumablePlaylist: PlaylistEntity? = null
        return try {
            if (name.isBlank()) throw ImportException(ImportErrorKey.EmptyInput)
            if (songs.isEmpty()) throw ImportException(ImportErrorKey.NoTracksToImport)
            if (destination is Destination.EXISTING && !destination.playlist.isEditable) {
                throw ImportException(ImportErrorKey.Generic)
            }
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val remoteDestination =
                destination == Destination.NewSynced ||
                    (destination is Destination.EXISTING && !destination.playlist.isLocal)
            if (remoteDestination && importSongs.any(SongItem::isLocal)) {
                throw ImportException(ImportErrorKey.LocalFilesRequireLocalPlaylist)
            }

            if (destination == Destination.Favorites) {
                val playlist =
                    PlaylistEntity(
                        id = "favorites",
                        name = name.ifBlank { getString(Res.string.favorites) },
                        isLocal = true,
                    )
                resumablePlaylist = playlist
                SongLikeState.activateCurrentAccount()
                val account = Settings.shared.likedSongsOrderAccount
                return SongLikeState.withImportVoteSync {
                    SongLikeState.activateCurrentAccount()
                    if (Settings.shared.likedSongsOrderAccount != account) throw CancellationException("YouTube account changed")
                    val songsToLike = importSongs.filterNot { SongLikeState.isLiked(it.id) }

                    val persistedLikes = mutableListOf<SongItem>()
                    try {
                        suspend fun persistLikedSong(song: SongItem) {
                            database.useWriterConnection { connection ->
                                connection.immediateTransaction { persistSongToFavorites(song, now, isNewLike = true) }
                            }
                            importedSongCount++
                            persistedLikes += song
                            onProgress(importedSongCount, importSongs.size, song)
                        }

                        val pendingIds = songsToLike.mapTo(mutableSetOf(), SongItem::id)
                        importSongs.filter { it.id !in pendingIds }.forEach { song ->
                            database.useWriterConnection { connection ->
                                connection.immediateTransaction { persistSongToFavorites(song, now, isNewLike = false) }
                            }
                        }
                        val remoteSongs = songsToLike.filterNot(SongItem::isLocal)
                        val remoteById = remoteSongs.associateBy(SongItem::id)
                        SongLikeState.activateCurrentAccount()
                        if (Settings.shared.likedSongsOrderAccount != account) throw CancellationException("YouTube account changed")
                        val voteResult =
                            repository.likeVideos(remoteSongs.map(SongItem::id)) { id ->
                                remoteById[id]?.let { persistLikedSong(it) }
                            }
                        if (!voteResult.authenticated) remoteSongs.forEach { persistLikedSong(it) }
                        songsToLike.filter(SongItem::isLocal).forEach { persistLikedSong(it) }
                        val failedSongCount = if (voteResult.authenticated) remoteSongs.size - voteResult.succeededIds.size else 0
                        if (failedSongCount > 0) {
                            return@withImportVoteSync Result.failure(
                                IncompleteImportException(playlist, importedSongCount, failedSongCount, null),
                            )
                        }
                        Result.success(ImportResult(playlist, importedSongCount))
                    } finally {
                        SongLikeState.appendLikes(persistedLikes, account)
                    }
                }
            }

            val firstThumbnail = importSongs.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
            val playlist =
                when (destination) {
                    Destination.NewLocal -> {
                        PlaylistEntity(
                            name = name.trim(),
                            isLocal = true,
                            bookmarkedAt = now,
                            createdAt = now,
                            lastUpdateTime = now,
                            thumbnailUrl = coverUrl?.takeIf(String::isNotBlank) ?: firstThumbnail,
                            customThumbnailUrl = coverUrl?.takeIf(String::isNotBlank),
                            remoteSongCount = importSongs.size,
                        )
                    }

                    Destination.NewSynced -> {
                        val remoteId = repository.createPlaylist(name).getOrThrow().removePrefix("VL")
                        createdRemotePlaylistId = remoteId
                        PlaylistEntity(
                            name = name.trim(),
                            browseId = remoteId,
                            bookmarkedAt = now,
                            createdAt = now,
                            lastUpdateTime = now,
                            isEditable = true,
                            isAutoSync = true,
                            isLocal = false,
                            thumbnailUrl = coverUrl?.takeIf(String::isNotBlank) ?: firstThumbnail,
                            customThumbnailUrl = coverUrl?.takeIf(String::isNotBlank),
                            remoteSongCount = importSongs.size,
                        )
                    }

                    is Destination.EXISTING -> {
                        destination.playlist
                    }

                    Destination.Favorites -> {
                        error("Favorites handled above")
                    }
                }
            val remoteId = playlist.browseId?.removePrefix("VL")?.takeUnless { playlist.isLocal }
            resumablePlaylist = playlist.takeIf { remoteId != null || destination is Destination.EXISTING }
            val remoteCounts =
                if (shouldLoadRemotePlaylistSongs(remoteId, createdRemotePlaylistId)) {
                    remoteSongCounts(requireNotNull(remoteId)).toMutableMap()
                } else {
                    mutableMapOf()
                }
            val requestedCounts = mutableMapOf<String, Int>()

            fun nextOccurrence(song: SongItem): Int = (requestedCounts.getOrElse(song.id) { 0 } + 1).also { requestedCounts[song.id] = it }
            if (remoteId == null) {
                val batchSize = 250
                importSongs.chunked(batchSize).forEachIndexed { batchIndex, chunk ->
                    var committedCount = 0
                    database.useWriterConnection { connection ->
                        connection.immediateTransaction {
                            if (batchIndex == 0) {
                                database.dao().upsert(playlist)
                            }
                            var nextPosition = database.dao().nextPlaylistPosition(playlist.id)
                            chunk.forEach { song ->
                                if (persistSong(playlist, song, nextPosition, nextOccurrence(song))) {
                                    committedCount++
                                    nextPosition++
                                }
                            }
                        }
                    }
                    importedSongCount += committedCount
                    resumablePlaylist = playlist
                    onProgress(importedSongCount, importSongs.size, chunk.lastOrNull())
                }
            } else {
                database.dao().upsert(playlist)
                val originalRemoteCounts = remoteCounts.toMap()
                val selectedIds = importSongs.map { it.id }
                if (sourceYouTubePlaylistId != null && selectedIds.none { it in remoteCounts }) {
                    val sourceSongs = mutableListOf<SongItem>()
                    try {
                        repository.getPlaylistPages(sourceYouTubePlaylistId, applyContentFilters = false).collect { page ->
                            sourceSongs.addAll(page.sections.playlistSongOccurrences())
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        sourceSongs.clear()
                    }
                    if (sourceSongs.map { it.id } == selectedIds) {
                        val copied = repository.addPlaylistToPlaylist(remoteId, sourceYouTubePlaylistId)
                        if (copied.isSuccess) {
                            remoteCounts.putAll(selectedIds.groupingBy { it }.eachCount())
                        } else {
                            remoteCounts.clear()
                            remoteCounts.putAll(remoteSongCounts(remoteId))
                        }
                    }
                }
                var failedSongCount = 0
                var firstFailure: Throwable? = null
                importSongs.forEach { song ->
                    val occurrence = nextOccurrence(song)
                    val addedRemotely = remoteCounts.getOrElse(song.id) { 0 } < occurrence
                    if (addedRemotely) {
                        val addResult = addRemoteSong(remoteId, song.id, occurrence)
                        if (addResult.isFailure) {
                            failedSongCount++
                            if (firstFailure == null) firstFailure = addResult.exceptionOrNull()
                            onProgress(importedSongCount, importSongs.size, song)
                            return@forEach
                        }
                        remoteCounts[song.id] = occurrence
                    }
                    val persisted =
                        database.useWriterConnection { connection ->
                            connection.immediateTransaction {
                                persistSong(
                                    playlist,
                                    song,
                                    occurrence = occurrence,
                                )
                            }
                        }
                    if (persisted || originalRemoteCounts.getOrElse(song.id) { 0 } < occurrence) importedSongCount++
                    onProgress(importedSongCount, importSongs.size, song)
                }
                if (failedSongCount > 0) {
                    return Result.failure(
                        IncompleteImportException(
                            playlist = playlist,
                            importedSongCount = importedSongCount,
                            failedSongCount = failedSongCount,
                            cause = firstFailure,
                        ),
                    )
                }
            }
            Result.success(ImportResult(playlist, importedSongCount))
        } catch (error: CancellationException) {
            val partial = resumablePlaylist
            if (partial != null) throw CancelledImportException(partial, importedSongCount, error)
            throw error
        } catch (error: Exception) {
            val partial = resumablePlaylist
            Result.failure(
                if (partial != null && error !is IncompleteImportException) {
                    IncompleteImportException(
                        playlist = partial,
                        importedSongCount = importedSongCount,
                        failedSongCount = (importSongs.size - importedSongCount).coerceAtLeast(1),
                        cause = error,
                    )
                } else {
                    error
                },
            )
        }
    }

    private suspend fun resolveTrack(track: CsvTrack): SongItem? {
        if (track.isResolvedLocalReference) {
            val uri = track.directReference ?: return null
            return SongItem(
                id = uri,
                title = track.title,
                thumbnail = null,
                artists =
                    track.artist
                        .takeIf(String::isNotBlank)
                        ?.let { listOf(ArtistTiny(null, it)) }
                        .orEmpty(),
                album = null,
                endpointParams = uri,
                isLocal = true,
            )
        }
        val searchQuery = resolutionSearchQuery(track) ?: return null
        repeat(RESOLVE_RETRIES) { attempt ->
            try {
                return track.videoId?.let { repository.getSongMetadata(it) }
                    ?: bestTrackMatch(
                        track,
                        repository
                            .searchFiltered(
                                searchQuery,
                                SearchFilter.SONG,
                                allowOfflineFallback = false,
                            ).items
                            .filterIsInstance<SongItem>(),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!shouldRetryTrackResolution(attempt)) throw ImportException(ImportErrorKey.ResolutionFailed, error)
                delay((RESOLVE_RETRY_DELAY_MILLIS * (attempt + 1)).milliseconds)
            }
        }
        return null
    }

    private suspend fun remoteSongCounts(playlistId: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        repository.getPlaylistPages(playlistId, applyContentFilters = false).collect { page ->
            page.sections.playlistSongOccurrences().forEach { song -> counts[song.id] = counts.getOrElse(song.id) { 0 } + 1 }
        }
        return counts
    }

    private suspend fun addRemoteSong(
        playlistId: String,
        songId: String,
        occurrence: Int,
    ): Result<Unit> {
        var failure: Result<Unit> = repository.addToPlaylist(playlistId, songId)
        repeat(REMOTE_ADD_RETRIES) { attempt ->
            if (failure.isSuccess) return failure
            val remoteCounts =
                try {
                    Result.success(remoteSongCounts(playlistId))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            if ((remoteCounts.getOrNull()?.get(songId) ?: 0) >= occurrence) return Result.success(Unit)
            if (!shouldRetryRemoteAdd(attempt, remoteCounts.isSuccess)) return failure
            delay((REMOTE_ADD_RETRY_DELAY_MILLIS * (attempt + 1)).milliseconds)
            failure = repository.addToPlaylist(playlistId, songId)
        }
        return failure
    }

    private suspend fun persistSong(
        playlist: PlaylistEntity,
        song: SongItem,
        nextPosition: Int? = null,
        occurrence: Int,
    ): Boolean {
        val dao = database.dao()
        val existing = persistImportedTitle(song)
        if (dao.checkInPlaylist(playlist.id, song.id) >= occurrence) return false
        if (existing == null) {
            dao.upsert(song.toEntity())
            song.artists.forEachIndexed { artistIndex, artist ->
                val artistId = artist.id ?: ArtistEntity.generateArtistId()
                if (dao.getArtistById(artistId) == null) {
                    dao.upsert(
                        ArtistEntity(id = artistId, name = artist.name, thumbnailUrl = artist.thumbnail, isLocal = artist.id == null),
                    )
                }
                dao.upsert(SongArtistMap(song.id, artistId, artistIndex))
            }
        }
        dao.insert(
            PlaylistSongMap(
                playlistId = playlist.id,
                songId = song.id,
                position =
                    nextPosition ?: dao.nextPlaylistPosition(playlist.id),
            ),
        )
        dao.updatePlaylistLastUpdated(playlist.id)
        return true
    }

    private suspend fun persistImportedTitle(song: SongItem): SongEntity? {
        val dao = database.dao()
        val existing = dao.getSongById(song.id)?.song ?: return null
        val customTitle = song.customTitle?.takeIf(String::isNotBlank)
        return if (existing.customTitle == null && customTitle != null) {
            existing.copy(customTitle = customTitle).also { dao.upsert(it) }
        } else {
            existing
        }
    }

    private suspend fun persistSongToFavorites(
        song: SongItem,
        now: LocalDateTime,
        isNewLike: Boolean,
    ) {
        val dao = database.dao()
        val existing = persistImportedTitle(song)
        val entity =
            existing?.copy(
                title = song.canonicalTitle ?: song.title,
                duration = song.duration?.toInt() ?: existing.duration,
                thumbnailUrl = song.thumbnail ?: existing.thumbnailUrl,
                albumId = song.album?.id ?: existing.albumId,
                albumName = song.album?.name ?: existing.albumName,
                explicit = song.isExplicit,
                explicitness = song.artworkExplicitness,
                inLibrary = if (isNewLike) now else existing.inLibrary ?: now,
                liked = true,
                likedDate = if (isNewLike) now else existing.likedDate ?: now,
                isLocal = song.isLocal,
            ) ?: song.toEntity().copy(
                inLibrary = now,
                liked = true,
                likedDate = now,
            )
        dao.upsert(entity)
        song.artists.forEachIndexed { artistIndex, artist ->
            val artistId = artist.id ?: ArtistEntity.generateArtistId()
            if (dao.getArtistById(artistId) == null) {
                dao.upsert(
                    ArtistEntity(id = artistId, name = artist.name, thumbnailUrl = artist.thumbnail, isLocal = artist.id == null),
                )
            }
            dao.upsert(SongArtistMap(song.id, artistId, artistIndex))
        }
    }

    sealed interface Destination {
        data object NewLocal : Destination

        data object NewSynced : Destination

        data object Favorites : Destination

        data class EXISTING(
            val playlist: PlaylistEntity,
        ) : Destination
    }

    data class ImportResult(
        val playlist: PlaylistEntity,
        val importedSongCount: Int,
    )

    class CancelledImportException(
        val playlist: PlaylistEntity,
        val importedSongCount: Int,
        override val cause: CancellationException,
    ) : CancellationException("Playlist import cancelled")

    class IncompleteImportException(
        val playlist: PlaylistEntity,
        val importedSongCount: Int,
        val failedSongCount: Int,
        cause: Throwable?,
    ) : Exception(cause?.message, cause)

    data class CsvTrack(
        val title: String,
        val artist: String,
        val videoId: String?,
        val directReference: String? = null,
        val isResolvedLocalReference: Boolean = false,
        val album: String = "",
        val durationSeconds: Long? = null,
        val customTitle: String? = null,
    )

    companion object {
        const val MAX_CSV_CHARACTERS = 50 * 1024 * 1024
        const val MAX_PLAYLIST_TRACKS = 100_000
        private const val RESOLVE_CONCURRENCY = 4
        private const val RESOLVE_RETRIES = 3
        private const val RESOLVE_RETRY_DELAY_MILLIS = 500L
        private const val REMOTE_ADD_RETRIES = 2
        private const val REMOTE_ADD_RETRY_DELAY_MILLIS = 500L

        internal fun shouldRetryTrackResolution(attempt: Int): Boolean = attempt < RESOLVE_RETRIES - 1

        internal fun shouldRetryRemoteAdd(
            attempt: Int,
            verificationSucceeded: Boolean,
        ): Boolean = verificationSucceeded && attempt < REMOTE_ADD_RETRIES

        internal fun shouldLoadRemotePlaylistSongs(
            remoteId: String?,
            createdRemotePlaylistId: String?,
        ): Boolean = remoteId != null && createdRemotePlaylistId == null

        fun parsePlaylistId(input: String): String? {
            val trimmed = input.trim()
            val fromQuery = Regex("[?&]list=([^&#]+)").find(trimmed)?.groupValues?.get(1)
            return (fromQuery ?: trimmed.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,}")) })?.removePrefix("VL")
        }

        fun isLocalReference(input: String): Boolean {
            val value = input.trim()
            if (value.startsWith("//") || value.startsWith("\\\\") || value.startsWith("/") || value.startsWith("\\")) {
                return true
            }
            if (value.length >= 3 && value[0].isLetter() && value[1] == ':' && value[2] in setOf('\\', '/')) return true
            val scheme = uriScheme(value)
            return scheme == null || scheme.equals("file", ignoreCase = true)
        }

        internal fun bestTrackMatch(
            track: CsvTrack,
            candidates: List<SongItem>,
        ): SongItem? {
            fun normalized(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
            val title = normalized(track.title)
            if (title.isEmpty()) return null
            val artist = normalized(track.artist)
            val album = normalized(track.album)
            return candidates
                .filter { song ->
                    normalized(song.title) == title &&
                        (
                            artist.isEmpty() || normalized(song.artists.joinToString { it.name }) == artist ||
                                track.artist.split(',').any { sourceArtist ->
                                    song.artists.any { normalized(it.name) == normalized(sourceArtist) }
                                }
                        ) &&
                        (
                            track.durationSeconds == null || song.duration == null ||
                                abs(track.durationSeconds - song.duration) <= 5
                        )
                }.sortedWith(
                    compareByDescending<SongItem> { album.isNotEmpty() && normalized(it.album?.name.orEmpty()) == album }
                        .thenBy { song ->
                            track.durationSeconds?.let { duration -> song.duration?.let { abs(duration - it) } } ?: Long.MAX_VALUE
                        },
                ).firstOrNull()
        }

        internal fun resolutionSearchQuery(track: CsvTrack): String? {
            if (
                track.directReference != null &&
                (isLocalReference(track.directReference) || track.title.isBlank() || track.artist.isBlank())
            ) {
                return null
            }
            return listOf(track.title, track.artist, track.album).filter(String::isNotBlank).joinToString(" ")
        }

        fun parseCsv(
            text: String,
            resolveLocalReference: (String) -> String? = { null },
        ): List<CsvTrack> {
            val csv = CsvPlaylist.parse(text)
            return if (csv.rows.isEmpty()) emptyList() else csv.tracks(csv.guessMapping(), resolveLocalReference)
        }
    }
}

private fun SongItem.toEntity() =
    SongEntity(
        id = id,
        title = canonicalTitle ?: title,
        customTitle = customTitle,
        duration = duration?.toInt() ?: -1,
        thumbnailUrl = thumbnail,
        albumId = album?.id,
        albumName = album?.name,
        explicit = isExplicit,
        explicitness = artworkExplicitness,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
        isUploaded = isUploaded,
        isLocal = isLocal,
        uploadEntityId = uploadEntityId,
        isVideo = isVideoType(musicVideoType),
        isEpisode = musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
    )
