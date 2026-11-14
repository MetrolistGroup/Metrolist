/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.state

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.storeAlbumArtists
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryBookmarkMutations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

object AlbumLikeState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun albumLiked(albumId: String): Flow<Boolean> =
        KoinPlatform
            .getKoin()
            .get<MusicDatabase>()
            .dao()
            .album(albumId)
            .map { it?.album?.bookmarkedAt != null }

    fun toggleAlbumLike(
        album: AlbumItem,
        songs: List<SongItem>,
        onResult: (Result<Boolean>) -> Unit,
    ) {
        scope.launch {
            val result =
                withContext(Dispatchers.Default) {
                    try {
                        val db = KoinPlatform.getKoin().get<MusicDatabase>()
                        Result.success(db.toggleAlbumLiked(album, songs, GlobalYouTubeRepository.instance))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            onResult(result)
        }
    }
}

private val albumBookmarkGuard = Mutex()
private val albumBookmarkLocks = mutableMapOf<String, AlbumBookmarkLock>()

private class AlbumBookmarkLock {
    val mutex = Mutex()
    var users = 0
}

internal suspend fun <T> withAlbumBookmarkLock(
    albumId: String,
    block: suspend () -> T,
): T {
    val lock =
        albumBookmarkGuard.withLock {
            albumBookmarkLocks.getOrPut(albumId) { AlbumBookmarkLock() }.also { it.users++ }
        }
    try {
        return lock.mutex.withLock { block() }
    } finally {
        withContext(NonCancellable) {
            albumBookmarkGuard.withLock {
                if (--lock.users == 0) albumBookmarkLocks.remove(albumId)
            }
        }
    }
}

internal suspend fun MusicDatabase.toggleAlbumLiked(
    album: AlbumItem,
    songs: List<SongItem>,
    repository: YouTubeRepository,
): Boolean =
    withAlbumBookmarkLock(album.id) {
        val accountKey = LibraryBookmarkMutations.capture().accountKey
        val existing = dao().album(album.id).first()?.album
        val liked = existing?.bookmarkedAt == null
        var playlistId = album.playlistId?.takeIf { it.isNotBlank() } ?: existing?.playlistId?.takeIf { it.isNotBlank() }
        if (repository.canManagePlaylists()) {
            if (playlistId == null) {
                playlistId =
                    repository
                        .getAlbum(album.id, loadAllPages = false)
                        ?.album
                        ?.playlistId
                        ?.takeIf { it.isNotBlank() }
            }
            repository.setPlaylistSaved(checkNotNull(playlistId), liked).getOrThrow()
        }
        LibraryBookmarkMutations.withLock {
            withDatabaseTransaction {
                val dao = dao()
                val current = dao.album(album.id).first()?.album
                val duration =
                    songs
                        .sumOf { song -> song.duration?.coerceIn(0L, Int.MAX_VALUE.toLong()) ?: 0L }
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                val entity =
                    current ?: AlbumEntity(
                        id = album.id,
                        playlistId = playlistId,
                        title = album.title,
                        year = album.year?.toIntOrNull(),
                        thumbnailUrl = album.thumbnail,
                        songCount = songs.size,
                        duration = duration,
                        explicit = album.isExplicit,
                    )
                dao.upsert(
                    entity.copy(
                        playlistId = playlistId ?: entity.playlistId,
                        songCount = entity.songCount.takeIf { it > 0 } ?: songs.size,
                        duration = entity.duration.takeIf { it > 0 } ?: duration,
                        bookmarkedAt = if (liked) Clock.System.now().toLocalDateTime(TimeZone.UTC) else null,
                    ),
                )
                if (album.artists.isNotEmpty()) {
                    dao.clearAlbumArtists(album.id)
                    album.artists.distinctBy { it.id?.takeIf(String::isNotBlank) ?: it.name }.forEachIndexed { index, artist ->
                        val artistId = artist.id?.takeIf { it.isNotBlank() } ?: "LA:${album.id}:$index"
                        val stored = dao.getArtistById(artistId)
                        dao.upsert(
                            stored?.copy(name = artist.name, thumbnailUrl = artist.thumbnail ?: stored.thumbnailUrl)
                                ?: ArtistEntity(
                                    id = artistId,
                                    name = artist.name,
                                    thumbnailUrl = artist.thumbnail,
                                    isLocal = artist.id.isNullOrBlank(),
                                ),
                        )
                        dao.upsert(AlbumArtistMap(album.id, artistId, index))
                    }
                }
            }
            LibraryBookmarkMutations.record(accountKey, "album:${album.id}", liked)
        }
        liked
    }

internal suspend fun MusicDatabase.saveBookmarkedAlbum(
    albumId: String,
    repository: YouTubeRepository,
) = withAlbumBookmarkLock(albumId) {
    checkNotNull(
        dao()
            .album(albumId)
            .first()
            ?.album
            ?.bookmarkedAt,
    )
    val album = repository.saveAlbumToLibrary(albumId)
    withDatabaseTransaction {
        val existing = checkNotNull(dao().album(albumId).first()?.album)
        dao().upsert(
            existing.copy(
                title = album.title,
                playlistId = album.playlistId,
                thumbnailUrl = album.thumbnail ?: existing.thumbnailUrl,
                year = album.year?.toIntOrNull() ?: existing.year,
                explicit = album.isExplicit,
            ),
        )
        storeAlbumArtists(album)
    }
}
