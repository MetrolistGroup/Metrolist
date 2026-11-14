/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.HistoryProvenance
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.foreign_import_failed
import com.metrolist.shared.generated.resources.foreign_import_success
import com.metrolist.shared.generated.resources.foreign_import_unsupported
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Instant

enum class ForeignLibraryFamily {
    INNERTUNE,

    VIMUSIC,

    KREATE,

    SIMPMUSIC,

    PRISM,
}

data class ForeignArtist(
    val id: String?,
    val name: String,
)

data class ForeignSong(
    val id: String,
    val title: String,
    val artists: List<ForeignArtist> = emptyList(),
    val albumId: String? = null,
    val albumName: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedAt: Long? = null,
    val inLibraryAt: Long? = null,
    val totalPlayTimeMs: Long = 0,
    val isLocal: Boolean = false,
    val isVideo: Boolean = false,
    val isUploaded: Boolean = false,
    val isEpisode: Boolean = false,
    val isLive: Boolean = false,
)

data class ForeignPlaylist(
    val name: String,
    val browseId: String?,
    val songIds: List<String>,
    val isEditable: Boolean = false,
    val description: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val thumbnailUrl: String? = null,
)

data class ForeignPlay(
    val songId: String,
    val playedAt: Long,
    val playTimeMs: Long,
)

data class ForeignBookmark(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val bookmarkedAt: Long,
    val year: Int? = null,
)

data class ForeignLibrary(
    val family: ForeignLibraryFamily,
    val songs: List<ForeignSong>,
    val playlists: List<ForeignPlaylist>,
    val plays: List<ForeignPlay>,
    val artists: List<ForeignBookmark>,
    val albums: List<ForeignBookmark>,
)

data class ForeignImportSummary(
    val family: ForeignLibraryFamily,
    val songs: Int,
    val likes: Int,
    val playlists: Int,
    val plays: Int,
    val skippedSongs: Int,
)

class UnsupportedForeignLibraryException : IllegalArgumentException("The selected file is not a supported music app backup")

private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private const val FOREIGN_HISTORY_SOURCE = "FOREIGN_IMPORT"

fun isYouTubeVideoId(id: String): Boolean = YOUTUBE_VIDEO_ID.matches(id)

fun SQLiteConnection.detectForeignLibraryFamily(): ForeignLibraryFamily? {
    val tables = foreignTableColumns()

    fun has(
        table: String,
        vararg columns: String,
    ) = tables[table.lowercase()]?.containsAll(columns.map(String::lowercase)) == true
    return when {
        has("song", "id", "title", "liked") && has("playlist", "id", "name") &&
            has("playlist_song_map", "playlistId", "songId", "position") -> ForeignLibraryFamily.INNERTUNE

        has("song", "id", "title", "likedAt", "totalPlayTimeMs") && has("playlist", "id", "name") &&
            has("songplaylistmap", "songId", "playlistId", "position") -> ForeignLibraryFamily.VIMUSIC

        has("songs", "id", "title", "liked_at", "total_playtime") && has("playlists", "id", "name") &&
            has("song_playlist_map", "song_id", "playlist_id", "position") -> ForeignLibraryFamily.KREATE

        has("song", "videoId", "title", "liked") && has("local_playlist", "id", "title") &&
            has("pair_song_local_playlist", "playlistId", "songId", "position") -> ForeignLibraryFamily.SIMPMUSIC

        has("songs", "id", "title", "artistName", "durationSec", "liked", "likedAt", "lastPlayed") &&
            has("play_events", "id", "songId", "timestamp", "playedMs") -> ForeignLibraryFamily.PRISM

        else -> null
    }
}

fun SQLiteConnection.readForeignLibrary(): ForeignLibrary {
    execSQL("PRAGMA query_only = ON")
    val family = detectForeignLibraryFamily() ?: throw UnsupportedForeignLibraryException()
    val tables = foreignTableColumns()
    return when (family) {
        ForeignLibraryFamily.INNERTUNE -> readInnerTune(tables)
        ForeignLibraryFamily.VIMUSIC -> readViMusic(ViMusicNames.VIMUSIC, tables)
        ForeignLibraryFamily.KREATE -> readViMusic(ViMusicNames.KREATE, tables)
        ForeignLibraryFamily.SIMPMUSIC -> readSimpMusic(tables)
        ForeignLibraryFamily.PRISM -> readPrism()
    }
}

private fun SQLiteConnection.readInnerTune(tables: Map<String, Set<String>>): ForeignLibrary {
    val artists = mutableMapOf<String, MutableList<ForeignArtist>>()
    if ("song_artist_map" in tables && "artist" in tables) {
        rows(
            "SELECT m.songId AS songId, a.id AS id, a.name AS name FROM song_artist_map m " +
                "JOIN artist a ON a.id = m.artistId ORDER BY m.songId, m.position",
        ) { row ->
            val name = row.text("name") ?: return@rows
            artists.getOrPut(row.text("songId").orEmpty()) { mutableListOf() } += ForeignArtist(row.text("id")?.youtubeChannelId(), name)
        }
    }
    val songs =
        rows("SELECT * FROM song") { row ->
            val id = row.text("id") ?: return@rows null
            ForeignSong(
                id = id,
                title = row.text("title").orEmpty(),
                artists = artists[id].orEmpty(),
                albumId = row.text("albumId"),
                albumName = row.text("albumName"),
                durationSeconds = row.long("duration")?.toInt()?.takeIf { it > 0 },
                thumbnailUrl = row.text("thumbnailUrl"),
                explicit = row.long("explicit") == 1L,
                liked = row.long("liked") == 1L,
                likedAt = row.long("likedDate"),
                inLibraryAt = row.long("inLibrary"),
                totalPlayTimeMs = row.long("totalPlayTime") ?: 0,
                isLocal = row.long("isLocal") == 1L,
            )
        }.filterNotNull()
    val members = mutableMapOf<String, MutableList<String>>()
    rows("SELECT playlistId, songId FROM playlist_song_map ORDER BY playlistId, position, id") { row ->
        members.getOrPut(row.text("playlistId").orEmpty()) { mutableListOf() } += row.text("songId").orEmpty()
    }
    val playlists =
        rows("SELECT * FROM playlist") { row ->
            ForeignPlaylist(
                name = row.text("name").orEmpty(),
                browseId = row.text("browseId")?.takeIf { row.long("isLocal") != 1L },
                songIds = members[row.text("id")].orEmpty(),
                isEditable = row.long("isEditable") == 1L,
            )
        }
    val plays =
        if ("event" in tables) {
            rows("SELECT songId, timestamp, playTime FROM event ORDER BY timestamp, id") { row ->
                ForeignPlay(row.text("songId").orEmpty(), row.long("timestamp") ?: 0, row.long("playTime") ?: 0)
            }
        } else {
            emptyList()
        }
    return ForeignLibrary(
        family = ForeignLibraryFamily.INNERTUNE,
        songs = songs,
        playlists = playlists,
        plays = plays,
        artists = bookmarks(tables, "artist", "name", "thumbnailUrl", "bookmarkedAt"),
        albums = bookmarks(tables, "album", "title", "thumbnailUrl", "bookmarkedAt"),
    )
}

private enum class ViMusicNames(
    val song: String,
    val artistsText: String,
    val duration: String,
    val thumbnail: String,
    val likedAt: String,
    val totalPlayTime: String,
    val explicit: String,
    val songArtistMap: String,
    val artist: String,
    val songAlbumMap: String,
    val album: String,
    val playlist: String,
    val browseId: String,
    val songPlaylistMap: String,
    val songId: String,
    val artistId: String,
    val albumId: String,
    val playlistId: String,
    val event: String,
    val eventTimestamp: String,
    val eventPlayTime: String,
    val bookmarkedAt: String,
) {
    VIMUSIC(
        "Song",
        "artistsText",
        "durationText",
        "thumbnailUrl",
        "likedAt",
        "totalPlayTimeMs",
        "explicit",
        "SongArtistMap",
        "Artist",
        "SongAlbumMap",
        "Album",
        "Playlist",
        "browseId",
        "SongPlaylistMap",
        "songId",
        "artistId",
        "albumId",
        "playlistId",
        "Event",
        "timestamp",
        "playTime",
        "bookmarkedAt",
    ),
    KREATE(
        "songs",
        "artists",
        "duration",
        "thumbnail_url",
        "liked_at",
        "total_playtime",
        "is_explicit",
        "song_artist_map",
        "artists",
        "song_album_map",
        "albums",
        "playlists",
        "browse_id",
        "song_playlist_map",
        "song_id",
        "artist_id",
        "album_id",
        "playlist_id",
        "playback_history",
        "created_at",
        "time_spent",
        "bookmarked_at",
    ),
}

private fun SQLiteConnection.readViMusic(
    n: ViMusicNames,
    tables: Map<String, Set<String>>,
): ForeignLibrary {
    val family = if (n == ViMusicNames.KREATE) ForeignLibraryFamily.KREATE else ForeignLibraryFamily.VIMUSIC
    val artists = mutableMapOf<String, MutableList<ForeignArtist>>()
    if (n.songArtistMap.lowercase() in tables && n.artist.lowercase() in tables) {
        // The map has no position column; insertion order is the source's display order.
        rows(
            "SELECT m.${n.songId} AS songId, a.id AS id, a.name AS name FROM ${n.songArtistMap} m " +
                "JOIN ${n.artist} a ON a.id = m.${n.artistId} ORDER BY m.rowid",
        ) { row ->
            val name = row.text("name")?.takeIf(String::isNotBlank) ?: return@rows
            artists.getOrPut(row.text("songId").orEmpty()) { mutableListOf() } += ForeignArtist(row.text("id")?.youtubeChannelId(), name)
        }
    }
    val albums = mutableMapOf<String, Pair<String, String?>>()
    if (n.songAlbumMap.lowercase() in tables && n.album.lowercase() in tables) {
        rows(
            "SELECT m.${n.songId} AS songId, a.id AS id, a.title AS title FROM ${n.songAlbumMap} m " +
                "JOIN ${n.album} a ON a.id = m.${n.albumId}",
        ) { row -> albums[row.text("songId").orEmpty()] = row.text("id").orEmpty() to row.text("title") }
    }
    val songs =
        rows("SELECT * FROM ${n.song}") { row ->
            val id = row.text("id") ?: return@rows null
            // RiMusic and Kreate store -1 as a dislike marker; only positive timestamps are likes.
            val likedAt = row.long(n.likedAt)?.takeIf { it > 0 }
            ForeignSong(
                id = id,
                title = row.text("title").orEmpty(),
                artists =
                    artists[id] ?: row
                        .text(n.artistsText)
                        ?.takeIf(String::isNotBlank)
                        ?.let { listOf(ForeignArtist(null, it)) }
                        .orEmpty(),
                albumId = albums[id]?.first,
                albumName = albums[id]?.second,
                durationSeconds = row.text(n.duration)?.let(::parseDurationText),
                thumbnailUrl = row.text(n.thumbnail),
                explicit = row.long(n.explicit) == 1L,
                liked = likedAt != null,
                likedAt = likedAt,
                totalPlayTimeMs = row.long(n.totalPlayTime) ?: 0,
                isLocal = row.long("is_local") == 1L,
            )
        }.filterNotNull()
    val members = mutableMapOf<Long, MutableList<String>>()
    rows(
        "SELECT ${n.playlistId} AS playlistId, ${n.songId} AS songId FROM ${n.songPlaylistMap} ORDER BY ${n.playlistId}, position",
    ) { row ->
        members.getOrPut(row.long("playlistId") ?: 0) { mutableListOf() } += row.text("songId").orEmpty()
    }
    val playlists =
        rows("SELECT * FROM ${n.playlist}") { row ->
            ForeignPlaylist(
                name = row.text("name").orEmpty(),
                browseId = row.text(n.browseId)?.removePrefix("VL")?.takeIf(String::isNotBlank),
                songIds = members[row.long("id")].orEmpty(),
                isEditable = (row.long("isEditable") ?: row.long("editable")) == 1L,
            )
        }
    val plays =
        if (n.event.lowercase() in tables) {
            rows(
                "SELECT ${n.songId} AS songId, ${n.eventTimestamp} AS ts, ${n.eventPlayTime} AS playTime FROM ${n.event} ORDER BY ts, id",
            ) {
                ForeignPlay(it.text("songId").orEmpty(), it.long("ts") ?: 0, it.long("playTime") ?: 0)
            }
        } else {
            emptyList()
        }
    return ForeignLibrary(
        family = family,
        songs = songs,
        playlists = playlists,
        plays = plays,
        artists = bookmarks(tables, n.artist, "name", n.thumbnail, n.bookmarkedAt),
        albums = bookmarks(tables, n.album, "title", n.thumbnail, n.bookmarkedAt),
    )
}

private fun SQLiteConnection.readSimpMusic(tables: Map<String, Set<String>>): ForeignLibrary {
    val songs =
        rows("SELECT * FROM song") { row ->
            val id = row.text("videoId") ?: return@rows null
            val artistIds = jsonStrings(row.text("artistId"))
            val artistNames = jsonStrings(row.text("artistName"))
            val liked = row.long("liked") == 1L
            ForeignSong(
                id = id,
                title = row.text("title").orEmpty(),
                artists =
                    artistNames.mapIndexedNotNull { index, name ->
                        name?.takeIf(String::isNotBlank)?.let { ForeignArtist(artistIds.getOrNull(index)?.youtubeChannelId(), it) }
                    },
                albumId = row.text("albumId"),
                albumName = row.text("albumName"),
                durationSeconds = row.long("durationSeconds")?.toInt()?.takeIf { it > 0 },
                thumbnailUrl = row.text("thumbnails"),
                explicit = row.long("isExplicit") == 1L,
                liked = liked,
                likedAt = row.long("favoriteAt").takeIf { liked },
                // song.totalPlayTime is a play counter, not milliseconds; history comes from playback_event instead.
            )
        }.filterNotNull()
    val members = mutableMapOf<Long, MutableList<String>>()
    rows("SELECT playlistId, songId FROM pair_song_local_playlist ORDER BY playlistId, position, id") { row ->
        members.getOrPut(row.long("playlistId") ?: 0) { mutableListOf() } += row.text("songId").orEmpty()
    }
    val localPlaylists =
        rows("SELECT * FROM local_playlist") { row ->
            ForeignPlaylist(
                name = row.text("title").orEmpty(),
                browseId = row.text("youtubePlaylistId")?.removePrefix("VL")?.takeIf(String::isNotBlank),
                songIds = members[row.long("id")].orEmpty(),
                isEditable = true,
            )
        }
    val savedPlaylists =
        if ("playlist" in tables) {
            rows("SELECT id, title, tracks FROM playlist WHERE liked = 1") { row ->
                ForeignPlaylist(
                    name = row.text("title").orEmpty(),
                    browseId = row.text("id")?.removePrefix("VL"),
                    songIds = jsonStrings(row.text("tracks")).filterNotNull(),
                )
            }
        } else {
            emptyList()
        }
    val plays =
        if ("playback_event" in tables) {
            rows("SELECT videoId, timestamp, listenedSecond FROM playback_event ORDER BY timestamp, eventId") { row ->
                ForeignPlay(row.text("videoId").orEmpty(), row.long("timestamp") ?: 0, (row.long("listenedSecond") ?: 0) * 1000)
            }
        } else {
            emptyList()
        }
    val now = Clock.System.now().toEpochMilliseconds()
    val artists =
        if ("artist" in tables) {
            rows("SELECT * FROM artist WHERE followed = 1") { row ->
                ForeignBookmark(
                    row.text("channelId").orEmpty(),
                    row.text("name").orEmpty(),
                    row.text("thumbnails"),
                    row.long("followedAt") ?: now,
                )
            }
        } else {
            emptyList()
        }
    val albums =
        if ("album" in tables) {
            rows("SELECT * FROM album WHERE liked = 1") { row ->
                ForeignBookmark(
                    row.text("browseId").orEmpty(),
                    row.text("title").orEmpty(),
                    row.text("thumbnails"),
                    row.long("favoriteAt") ?: now,
                    row.text("year")?.toIntOrNull(),
                )
            }
        } else {
            emptyList()
        }
    return ForeignLibrary(ForeignLibraryFamily.SIMPMUSIC, songs, localPlaylists + savedPlaylists, plays, artists, albums)
}

private fun SQLiteConnection.readPrism(): ForeignLibrary {
    val plays =
        rows("SELECT songId, timestamp, playedMs FROM play_events ORDER BY timestamp, id") { row ->
            ForeignPlay(row.text("songId").orEmpty(), row.long("timestamp") ?: 0, row.long("playedMs") ?: 0)
        }
    val playTime =
        plays.filter { it.playedAt > 0 }.groupBy(ForeignPlay::songId).mapValues { (_, events) ->
            events.sumOf { it.playTimeMs.coerceAtLeast(0) }
        }
    val songs =
        rows("SELECT * FROM songs") { row ->
            val id = row.text("id").orEmpty()
            val artists = row.text("artistsJson")?.let { runCatching { prismArtists(it) }.getOrNull() }
            ForeignSong(
                id = id,
                title = row.text("title").orEmpty(),
                artists =
                    artists ?: row
                        .text("artistName")
                        .orEmpty()
                        .takeUnless { it == "Unknown artist" && row.text("artistId") == null }
                        .orEmpty()
                        .split(", ")
                        .filter(String::isNotBlank)
                        .mapIndexed { index, name ->
                            ForeignArtist(row.text("artistId")?.youtubeChannelId().takeIf { index == 0 }, name)
                        },
                albumId = row.text("albumId"),
                albumName = row.text("albumTitle"),
                durationSeconds = row.long("durationSec")?.toInt()?.takeIf { it > 0 },
                thumbnailUrl = row.text("thumbnail"),
                liked = row.long("liked") == 1L,
                likedAt = row.long("likedAt")?.takeIf { it > 0 },
                totalPlayTimeMs = playTime[id] ?: 0,
                isLocal = id.startsWith("local:"),
                isVideo = row.long("isVideo") == 1L,
            )
        }
    return ForeignLibrary(ForeignLibraryFamily.PRISM, songs, emptyList(), plays, emptyList(), emptyList())
}

internal fun prismArtists(json: String): List<ForeignArtist> =
    Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val artist = element as? JsonObject ?: return@mapNotNull null
        val name = (artist["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        ForeignArtist((artist["id"] as? JsonPrimitive)?.contentOrNull?.youtubeChannelId(), name)
    }

private fun SQLiteConnection.bookmarks(
    tables: Map<String, Set<String>>,
    table: String,
    nameColumn: String,
    thumbnailColumn: String,
    bookmarkColumn: String,
): List<ForeignBookmark> {
    if (tables[table.lowercase()]?.contains(bookmarkColumn.lowercase()) != true) return emptyList()
    return rows("SELECT * FROM $table WHERE $bookmarkColumn IS NOT NULL") { row ->
        ForeignBookmark(
            id = row.text("id").orEmpty(),
            name = row.text(nameColumn).orEmpty(),
            thumbnailUrl = row.text(thumbnailColumn),
            bookmarkedAt = row.long(bookmarkColumn) ?: 0,
            year = row.text("year")?.toIntOrNull(),
        )
    }
}

suspend fun MusicDatabase.importForeignLibrary(
    library: ForeignLibrary,
    onNewLikes: (List<SongItem>) -> Unit = SongLikeState::appendLikes,
): ForeignImportSummary {
    val now = Clock.System.now().toEpochMilliseconds()
    val sourceSongs = library.songs.filter { !it.isLocal && !it.isLive && isYouTubeVideoId(it.id) }.associateBy(ForeignSong::id)
    val wanted =
        buildSet {
            library.songs.forEach { if (it.liked || it.inLibraryAt != null || it.totalPlayTimeMs > 0) add(it.id) }
            library.playlists.forEach { addAll(it.songIds) }
            library.plays.forEach { add(it.songId) }
        }
    val imported = wanted.filter(sourceSongs::containsKey).toSet()
    val newLikes = mutableListOf<Pair<Long, SongItem>>()
    var playCount = 0
    var playlistCount = 0
    withDatabaseTransaction {
        val dao = dao()
        imported.forEach { id ->
            val source = sourceSongs.getValue(id)
            if (dao.getSongById(id) == null) dao.upsertHistorySong(source.toSongItem())
            val stored = checkNotNull(dao.getSongById(id)).song
            val likedAt = source.likedAt?.takeIf { it > 0 } ?: now
            val merged =
                stored.copy(
                    liked = stored.liked || source.liked,
                    likedDate = stored.likedDate ?: likedAt.takeIf { source.liked }?.timestamp(),
                    inLibrary = stored.inLibrary ?: (source.inLibraryAt ?: likedAt.takeIf { source.liked })?.timestamp(),
                    totalPlayTime = maxOf(stored.totalPlayTime, source.totalPlayTimeMs),
                )
            if (merged != stored) dao.update(merged)
            if (source.liked && !stored.liked) newLikes += likedAt to checkNotNull(dao.getSongById(id)).toSongItem()
        }
        library.plays.forEach { play ->
            if (play.songId !in imported || play.playedAt <= 0) return@forEach
            val key = stableHistoryKey(play)
            if (dao.historyProvenance(FOREIGN_HISTORY_SOURCE, key) != null) return@forEach
            val eventId =
                dao.insert(
                    Event(songId = play.songId, timestamp = play.playedAt.timestamp(), playTime = play.playTimeMs.coerceAtLeast(0)),
                )
            check(eventId > 0) { "Failed to import play history" }
            dao.insert(HistoryProvenance(FOREIGN_HISTORY_SOURCE, key, play.songId, play.playedAt, null, eventId))
            playCount++
        }
        library.playlists.forEach { source ->
            val name = source.name.trim()
            if (name.isEmpty() && source.browseId == null) return@forEach
            val songIds = source.songIds.filter(imported::contains)
            val existing =
                if (source.browseId != null) {
                    dao.findPlaylistEntity(source.browseId)
                } else {
                    dao.findPlaylistByName(name)?.takeIf { it.browseId == null && it.isEditable }
                }
            if (existing != null && existing.browseId != null) return@forEach
            val target =
                existing ?: PlaylistEntity(
                    name = name.ifEmpty { checkNotNull(source.browseId) },
                    browseId = source.browseId,
                    isEditable = source.browseId == null || source.isEditable,
                    isLocal = source.browseId == null,
                    remoteSongCount = songIds.size.takeIf { source.browseId != null },
                    thumbnailUrl = source.thumbnailUrl ?: songIds.firstNotNullOfOrNull { sourceSongs[it]?.thumbnailUrl },
                    description = source.description,
                    createdAt = (source.createdAt ?: now).timestamp(),
                    lastUpdateTime = (source.updatedAt ?: now).timestamp(),
                ).also { dao.insert(it) }
            var position = dao.nextPlaylistPosition(target.id)
            val existingCounts = dao.playlistSongMaps(target.id, 0).groupingBy { it.songId }.eachCount()
            val sourceCounts = mutableMapOf<String, Int>()
            songIds.forEach { songId ->
                val occurrence = (sourceCounts[songId] ?: 0) + 1
                sourceCounts[songId] = occurrence
                if (occurrence > (existingCounts[songId] ?: 0)) {
                    dao.insert(PlaylistSongMap(playlistId = target.id, songId = songId, position = position++))
                }
            }
            playlistCount++
        }
        library.artists.forEach { artist ->
            if (!artist.id.startsWith("UC")) return@forEach
            val stored = dao.getArtistById(artist.id)
            if (stored == null) {
                dao.upsert(
                    ArtistEntity(
                        id = artist.id,
                        name = artist.name,
                        thumbnailUrl = artist.thumbnailUrl,
                        bookmarkedAt = artist.bookmarkedAt.timestamp(),
                    ),
                )
            } else if (stored.bookmarkedAt == null) {
                dao.upsert(stored.copy(bookmarkedAt = artist.bookmarkedAt.timestamp()))
            }
        }
        library.albums.forEach { album ->
            if (!album.id.startsWith("MPRE")) return@forEach
            val stored = dao.album(album.id).first()?.album
            if (stored == null) {
                dao.upsert(
                    AlbumEntity(
                        id = album.id,
                        title = album.name,
                        year = album.year,
                        thumbnailUrl = album.thumbnailUrl,
                        songCount = 0,
                        duration = 0,
                        bookmarkedAt = album.bookmarkedAt.timestamp(),
                    ),
                )
            } else if (stored.bookmarkedAt == null) {
                dao.upsert(stored.copy(bookmarkedAt = album.bookmarkedAt.timestamp()))
            }
        }
    }
    if (newLikes.isNotEmpty()) onNewLikes(newLikes.sortedByDescending { it.first }.map { it.second })
    return ForeignImportSummary(
        family = library.family,
        songs = imported.size,
        likes = newLikes.size,
        playlists = playlistCount,
        plays = playCount,
        skippedSongs = (wanted - imported).size,
    )
}

suspend fun Result<ForeignImportSummary>.foreignImportMessage(): String =
    fold(
        { getString(Res.string.foreign_import_success, it.songs, it.likes, it.playlists, it.plays) },
        {
            if (it is UnsupportedForeignLibraryException) {
                getString(Res.string.foreign_import_unsupported)
            } else {
                getString(Res.string.foreign_import_failed, it.message.orEmpty())
            }
        },
    )

private fun ForeignSong.toSongItem() =
    SongItem(
        id = id,
        title = title,
        thumbnail = thumbnailUrl,
        artists = artists.map { ArtistTiny(it.id, it.name) },
        album = albumName?.let { AlbumTiny(albumId, it) },
        duration = durationSeconds?.toLong(),
        isExplicit = explicit,
        isUploaded = isUploaded,
        musicVideoType =
            when {
                isEpisode -> "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
                isVideo -> "MUSIC_VIDEO_TYPE_OMV"
                else -> null
            },
    )

private fun Long.timestamp(): LocalDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC)

private fun stableHistoryKey(play: ForeignPlay): Long {
    var hash = -0x340d631b7bdddcdbL
    "${play.songId}:${play.playedAt}".encodeToByteArray().forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001b3L
    }
    return hash
}

internal fun parseDurationText(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size !in 1..3) return null
    return parts
        .fold(0) { total, part -> total * 60 + (part.toIntOrNull()?.takeIf { it >= 0 } ?: return null) }
        .takeIf { it > 0 }
}

private fun String.youtubeChannelId(): String? = takeIf { it.startsWith("UC") }

private fun jsonStrings(text: String?): List<String?> =
    text
        ?.let {
            runCatching {
                Json.parseToJsonElement(it).jsonArray.map { element ->
                    (element as? JsonPrimitive)?.contentOrNull
                }
            }.getOrNull()
        }.orEmpty()

private fun SQLiteConnection.foreignTableColumns(): Map<String, Set<String>> {
    val names =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { it.text("name").orEmpty() }
    return names.associate { name ->
        name.lowercase() to
            rows("PRAGMA table_info(\"${name.replace("\"", "\"\"")}\")") { it.text("name").orEmpty().lowercase() }.toSet()
    }
}

private class ForeignRow(
    private val statement: SQLiteStatement,
    private val columns: Map<String, Int>,
) {
    private fun index(column: String) = columns[column.lowercase()]?.takeUnless(statement::isNull)

    fun text(column: String): String? = index(column)?.let(statement::getText)

    fun long(column: String): Long? = index(column)?.let(statement::getLong)
}

private fun <T> SQLiteConnection.rows(
    sql: String,
    map: (ForeignRow) -> T,
): List<T> =
    prepare(sql).use { statement ->
        val columns = (0 until statement.getColumnCount()).associateBy { statement.getColumnName(it).lowercase() }
        val row = ForeignRow(statement, columns)
        buildList { while (statement.step()) add(map(row)) }
    }
