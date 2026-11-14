package com.metrolist.shared.state

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.PodcastEntity
import com.metrolist.shared.db.entities.SetVideoIdEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.toSongItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

object PodcastLibrary {
    private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val libraryChanges: SharedFlow<Unit> = _libraryChanges.asSharedFlow()

    suspend fun resumePosition(
        database: MusicDatabase,
        song: SongItem,
        requestedPositionMs: Long? = null,
        durationMs: Long = song.duration?.times(1000L) ?: 0L,
    ): Long {
        if (requestedPositionMs != null || !song.isPodcast) return requestedPositionMs?.coerceAtLeast(0L) ?: 0L
        val stored = database.dao().getSongById(song.id)?.song
        return podcastResumePosition(
            song,
            savedPositionMs = stored?.playbackPosition,
            durationMs = durationMs.takeIf { it > 0L } ?: (stored?.duration?.toLong() ?: 0L) * 1000L,
        )
    }

    suspend fun synchronize(
        database: MusicDatabase,
        podcasts: List<PodcastItem>,
        episodes: List<EpisodeItem>,
    ) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        podcasts.chunked(100).forEach { batch ->
            database.withDatabaseTransaction {
                batch.forEach { podcast ->
                    val existing = database.dao().podcast(podcast.id).first()
                    val updated =
                        existing?.copy(
                            title = podcast.title,
                            author = podcast.author,
                            thumbnailUrl = podcast.thumbnail ?: existing.thumbnailUrl,
                            channelId = podcast.channelId ?: existing.channelId,
                            bookmarkedAt = existing.bookmarkedAt ?: now,
                        ) ?: PodcastEntity(
                            id = podcast.id,
                            title = podcast.title,
                            author = podcast.author,
                            thumbnailUrl = podcast.thumbnail,
                            channelId = podcast.channelId,
                            bookmarkedAt = now,
                        )
                    if (updated != existing) database.dao().upsert(updated)
                }
            }
        }
        episodes.chunked(100).forEach { batch ->
            database.withDatabaseTransaction {
                val existing = database.dao().getSongsByIds(batch.map { it.id }).associateBy { it.id }
                val authors =
                    existing.values
                        .flatMap { it.artists }
                        .associateBy { it.id }
                        .toMutableMap()
                batch.forEach { episode ->
                    upsertEpisode(database, episode.copy(saved = true), now, existing[episode.id], authors)
                }
            }
        }
    }

    suspend fun setEpisodeSaved(
        database: MusicDatabase,
        episode: EpisodeItem,
        saved: Boolean,
        repository: YouTubeRepository = GlobalYouTubeRepository.instance,
    ): Result<EpisodeItem> {
        val result =
            repository.setEpisodeSavedWithToken(
                episodeId = episode.id,
                saved = saved,
                playlistSetVideoId = episode.playlistSetVideoId,
            )
        if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)

        val updated =
            episode.copy(
                saved = saved,
                playlistSetVideoId = if (saved) result.getOrNull() ?: episode.playlistSetVideoId else null,
            )
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        database.withDatabaseTransaction {
            upsertEpisode(database, updated, if (saved) now else null, database.dao().getSongById(episode.id))
        }
        _libraryChanges.tryEmit(Unit)
        return Result.success(updated)
    }

    suspend fun setPodcastSaved(
        database: MusicDatabase,
        podcast: PodcastItem,
        saved: Boolean,
        repository: YouTubeRepository = GlobalYouTubeRepository.instance,
    ): Result<PodcastItem> {
        if (!repository.setPodcastSaved(podcast.id, saved)) {
            return Result.failure(IllegalStateException("Unable to update saved podcast"))
        }

        val existing = database.dao().podcast(podcast.id).first()
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        if (existing != null) {
            database.dao().upsert(
                existing.copy(
                    title = podcast.title,
                    author = podcast.author,
                    thumbnailUrl = podcast.thumbnail ?: existing.thumbnailUrl,
                    channelId = podcast.channelId ?: existing.channelId,
                    bookmarkedAt = if (saved) existing.bookmarkedAt ?: now else null,
                    lastUpdateTime = now,
                ),
            )
        } else if (saved) {
            database.dao().upsert(
                PodcastEntity(
                    id = podcast.id,
                    title = podcast.title,
                    author = podcast.author,
                    thumbnailUrl = podcast.thumbnail,
                    channelId = podcast.channelId,
                    bookmarkedAt = now,
                ),
            )
        }
        _libraryChanges.tryEmit(Unit)
        return Result.success(podcast)
    }

    suspend fun updatePlaybackPosition(
        database: MusicDatabase,
        song: SongItem,
        positionMs: Long,
        durationMs: Long = song.duration?.times(1000L) ?: 0L,
        updateMetadata: Boolean = true,
    ) {
        if (!updateMetadata && database.dao().updatePlaybackPosition(song.id, positionMs) > 0) return
        database.withDatabaseTransaction {
            val existing = database.dao().getSongById(song.id)?.song
            database.dao().upsert(
                existing?.copy(
                    title = song.title,
                    duration = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt() ?: existing.duration,
                    thumbnailUrl = song.thumbnail ?: existing.thumbnailUrl,
                    albumId = song.podcastId ?: song.album?.id ?: existing.albumId,
                    albumName = song.album?.name ?: existing.albumName,
                    isEpisode = true,
                    playbackPosition = positionMs,
                ) ?: SongEntity(
                    id = song.id,
                    title = song.title,
                    duration = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt() ?: -1,
                    thumbnailUrl = song.thumbnail,
                    albumId = song.podcastId ?: song.album?.id,
                    albumName = song.album?.name,
                    isEpisode = true,
                    playbackPosition = positionMs,
                ),
            )
        }
    }

    private suspend fun upsertEpisode(
        database: MusicDatabase,
        episode: EpisodeItem,
        inLibrary: LocalDateTime?,
        existingSong: Song?,
        authors: MutableMap<String, ArtistEntity> = mutableMapOf(),
    ) {
        val existing = existingSong?.song
        val duration = episode.duration?.takeIf { it > 0L } ?: existing?.duration?.takeIf { it > 0 }?.toLong()
        val accountPosition =
            if (episode.playbackPositionMs != null || (episode.playbackProgressPercent != null && duration != null)) {
                podcastResumePosition(episode.toSongItem(), null, durationMs = (duration ?: 0L) * 1000L)
            } else {
                null
            }
        val song =
            existing?.copy(
                title = episode.title,
                duration = episode.duration?.toInt() ?: existing.duration,
                thumbnailUrl = episode.thumbnail ?: existing.thumbnailUrl,
                albumId = episode.podcastId ?: existing.albumId,
                albumName = episode.author?.name ?: existing.albumName,
                inLibrary = inLibrary?.let { existing.inLibrary ?: it },
                isEpisode = true,
                playbackPosition = existing.playbackPosition ?: accountPosition,
            ) ?: SongEntity(
                id = episode.id,
                title = episode.title,
                duration = episode.duration?.toInt() ?: -1,
                thumbnailUrl = episode.thumbnail,
                albumId = episode.podcastId,
                albumName = episode.author?.name,
                inLibrary = inLibrary,
                isEpisode = true,
                playbackPosition = accountPosition,
            )
        if (song != existing) database.dao().upsert(song)

        episode.author?.let { author ->
            val artistId = author.id ?: return@let
            val oldArtist = authors[artistId] ?: database.dao().getArtistById(artistId)
            val artist =
                oldArtist?.copy(
                    name = author.name,
                    thumbnailUrl = author.thumbnail ?: oldArtist.thumbnailUrl,
                    isPodcastChannel = true,
                ) ?: ArtistEntity(
                    id = artistId,
                    name = author.name,
                    thumbnailUrl = author.thumbnail,
                    channelId = artistId,
                    isPodcastChannel = true,
                )
            if (artist != oldArtist) database.dao().upsert(artist)
            authors[artistId] = artist
            val relation = SongArtistMap(episode.id, artistId, 0)
            if (existingSong?.artistMaps?.contains(relation) != true) database.dao().upsert(relation)
        }
        episode.playlistSetVideoId?.let { database.dao().insert(SetVideoIdEntity(episode.id, it)) }
    }
}
