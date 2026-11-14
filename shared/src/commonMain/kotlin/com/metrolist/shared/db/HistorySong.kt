package com.metrolist.shared.db

import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.EventWithSong
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.knownPlayTimeMs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LEGACY_UNKNOWN_ARTIST_ID = "unknown"

internal data class HistoryAlbumStat(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val artists: List<ArtistEntity>,
    val playCount: Int,
    val playTime: Long,
)

internal fun Flow<List<EventWithSong>>.visibleHistoryEvents(): Flow<List<EventWithSong>> =
    combine(this, BlockedContent.version) { events, _ ->
        val songIds = BlockedContent.songIds()
        val artistIds = BlockedContent.artistIds()
        val artistNames = BlockedContent.artists().map { it.name.trim() }.toSet()
        events.map(EventWithSong::withHistoricalMetadata).filterNot { row ->
            row.event.songId in songIds ||
                row.song.artists.any { artist ->
                    artist.id in artistIds || (row.snapshot != null && artistNames.any { it.equals(artist.name.trim(), ignoreCase = true) })
                }
        }
    }

internal fun EventWithSong.withHistoricalMetadata(): EventWithSong {
    val snapshot = snapshot ?: return this
    val names =
        snapshot.artistNamesJson?.let { Json.decodeFromString<List<String>>(it) }
            ?: listOf(snapshot.artists).filter(String::isNotBlank)
    val artists =
        names.map { name ->
            song.artists.firstOrNull { it.name == name }
                ?: ArtistEntity(id = "history-artist:$name", name = name, isLocal = true)
        }
    return copy(
        song =
            song.copy(
                song =
                    song.song.copy(
                        title = snapshot.title,
                        customTitle = null,
                        customArtist = null,
                        albumId = snapshot.albumId,
                        albumName = snapshot.album,
                        duration = snapshot.duration?.toInt() ?: -1,
                        thumbnailUrl = snapshot.thumbnailUrl,
                    ),
                artists = artists,
                artistMaps = artists.mapIndexed { index, artist -> SongArtistMap(event.songId, artist.id, index) },
                album = song.album?.takeIf { it.id == snapshot.albumId },
            ),
    )
}

internal fun historySongStats(events: List<EventWithSong>): List<Pair<Song, Int>> =
    events
        .groupBy { it.song.id }
        .values
        .sortedByDescending { rows -> rows.sumOf { it.event.knownPlayTimeMs } }
        .map { rows -> rows.first().song to rows.size }

internal fun historyArtistStats(events: List<EventWithSong>): List<Pair<ArtistEntity, Int>> =
    events
        .flatMap { row ->
            row.song.artists
                .distinctBy { it.id }
                .map { it to row.event }
        }.groupBy { it.first.id }
        .values
        .sortedByDescending { rows -> rows.sumOf { it.second.knownPlayTimeMs } }
        .map { rows -> rows.first().first to rows.size }

internal fun historyAlbumStats(events: List<EventWithSong>): List<HistoryAlbumStat> =
    events
        .groupBy {
            it.song.song.albumId ?: it.song.album?.id ?: it.song.song.albumName
                ?.takeIf(String::isNotBlank)
        }.mapNotNull { (id, rows) ->
            if (id == null) return@mapNotNull null
            val title =
                rows.firstNotNullOfOrNull {
                    it.song.song.albumName
                        ?.takeIf(String::isNotBlank) ?: it.song.album?.title
                } ?: return@mapNotNull null
            HistoryAlbumStat(
                id = id,
                title = title,
                thumbnailUrl = rows.firstNotNullOfOrNull { it.song.album?.thumbnailUrl ?: it.song.song.thumbnailUrl },
                artists = rows.flatMap { it.song.orderedArtists }.distinctBy { it.id },
                playCount = rows.size,
                playTime = rows.sumOf { it.event.knownPlayTimeMs },
            )
        }.sortedByDescending { it.playTime }

internal fun mergeHistorySong(
    existing: SongEntity?,
    played: SongItem,
): SongEntity {
    val canonicalTitle = played.canonicalTitle ?: played.title
    val base =
        existing ?: SongEntity(
            id = played.id,
            title = canonicalTitle,
            customTitle = played.customTitle,
            customArtist = played.customArtist,
        )
    return base.copy(
        title = base.title.ifBlank { canonicalTitle },
        duration = base.duration.takeIf { it > 0 } ?: played.duration?.toInt()?.takeIf { it > 0 } ?: -1,
        thumbnailUrl = base.thumbnailUrl?.takeIf { it.isNotBlank() } ?: played.thumbnail,
        albumId = base.albumId ?: played.album?.id,
        albumName = base.albumName?.takeIf { it.isNotBlank() } ?: played.album?.name,
        explicit = base.explicit || played.isExplicit,
        explicitness = if (base.explicit || played.isExplicit) true else base.explicitness ?: played.artworkExplicitness,
        year = base.year ?: played.year,
        libraryAddToken = base.libraryAddToken ?: played.libraryAddToken,
        libraryRemoveToken = base.libraryRemoveToken ?: played.libraryRemoveToken,
        isLocal = base.isLocal || played.isLocal,
        isUploaded = base.isUploaded || played.isUploaded,
        isVideo = base.isVideo || played.isVideoSong,
        isEpisode = base.isEpisode || played.isPodcast,
        isUnavailable = played.isUnavailable,
        uploadEntityId = base.uploadEntityId ?: played.uploadEntityId,
    )
}

internal fun historyArtistId(
    songId: String,
    position: Int,
    artistId: String?,
): String = artistId?.takeIf { it.isNotBlank() } ?: "LH:$songId:$position"

internal fun resolveHistoryArtistId(
    songId: String,
    position: Int,
    incomingId: String?,
    incomingName: String,
    existingMaps: List<SongArtistMap>,
    existingArtists: Map<String, ArtistEntity>,
): String {
    incomingId?.takeIf { it.isNotBlank() }?.let { return it }
    val syntheticPrefix = "LH:$songId:"
    return existingMaps
        .filter { it.position == position && it.artistId != LEGACY_UNKNOWN_ARTIST_ID }
        .mapNotNull { existingArtists[it.artistId] }
        .filter { incomingName.isNotBlank() && it.name.trim().equals(incomingName.trim(), ignoreCase = true) }
        .minByOrNull { if (it.id.startsWith(syntheticPrefix)) 1 else 0 }
        ?.id ?: historyArtistId(songId, position, null)
}

internal fun obsoleteHistoryArtistMaps(
    songId: String,
    replacementIds: Set<String>,
    existingMaps: List<SongArtistMap>,
): List<SongArtistMap> {
    if (replacementIds.isEmpty()) return emptyList()
    val syntheticPrefix = "LH:$songId:"
    return existingMaps.filter { map ->
        map.artistId == LEGACY_UNKNOWN_ARTIST_ID ||
            (map.artistId.startsWith(syntheticPrefix) && map.artistId !in replacementIds)
    }
}

suspend fun MusicDatabase.updateHistoryEntry(
    id: Long,
    title: String,
    artists: String,
    album: String?,
) {
    if (dao().updateHistorySnapshot(id, title, artists, album, Json.encodeToString(listOf(artists.trim()).filter(String::isNotBlank))) ==
        0
    ) {
        dao().historySongId(id)?.let { updateHistorySong(it, title, artists, album) }
    }
}

suspend fun MusicDatabase.updateHistorySong(
    songId: String,
    title: String,
    artistName: String,
    albumName: String?,
) {
    withDatabaseTransaction {
        val dao = dao()
        val song = dao.getSongById(songId) ?: return@withDatabaseTransaction
        dao.update(song.song.copy(title = title, albumName = albumName))
        if (artistName.trim() == song.orderedArtists.joinToString(", ") { it.name }.trim()) return@withDatabaseTransaction
        song.artistMaps.forEach { dao.delete(it) }
        artistName.trim().takeIf(String::isNotEmpty)?.let { name ->
            val artistId = "LH:$songId"
            dao.upsert(ArtistEntity(id = artistId, name = name, isLocal = true))
            dao.upsert(SongArtistMap(songId = songId, artistId = artistId, position = 0))
        }
    }
}

suspend fun DatabaseDao.upsertHistorySong(song: SongItem) {
    val existing = getSongById(song.id)
    upsert(mergeHistorySong(existing?.song, song))
    val existingMaps = existing?.artistMaps.orEmpty()
    val existingArtists = existing?.artists.orEmpty().associateBy { it.id }
    val canonicalArtists = song.canonicalArtists ?: song.artists
    val resolvedArtistIds =
        canonicalArtists.mapIndexed { index, artist ->
            resolveHistoryArtistId(
                songId = song.id,
                position = index,
                incomingId = artist.id,
                incomingName = artist.name,
                existingMaps = existingMaps,
                existingArtists = existingArtists,
            )
        }
    obsoleteHistoryArtistMaps(
        songId = song.id,
        replacementIds = resolvedArtistIds.toSet(),
        existingMaps = existingMaps,
    ).forEach { delete(it) }
    canonicalArtists.forEachIndexed { artistIndex, artist ->
        val artistId = resolvedArtistIds[artistIndex]
        val stored = getArtistById(artistId)
        upsert(
            stored?.copy(
                name = artist.name.ifBlank { stored.name },
                thumbnailUrl = artist.thumbnail ?: stored.thumbnailUrl,
            ) ?: ArtistEntity(
                id = artistId,
                name = artist.name,
                thumbnailUrl = artist.thumbnail,
                isLocal = artist.id.isNullOrBlank(),
            ),
        )
        upsert(SongArtistMap(songId = song.id, artistId = artistId, position = artistIndex))
    }
}
