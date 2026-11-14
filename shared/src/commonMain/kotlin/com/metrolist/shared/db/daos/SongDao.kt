package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomWarnings
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.metrolist.shared.constants.AlbumSortType
import com.metrolist.shared.constants.ArtistSortType
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.entities.Album
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.Artist
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.EpisodeListeningProgress
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.PodcastEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.extensions.reversed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime
import kotlin.time.Clock

data class SongCustomMetadata(
    val customTitle: String?,
    val customArtist: String?,
)

data class SongDuration(
    val id: String,
    val duration: Long,
)

private const val SQLITE_MAX_BIND_PARAMETERS = 999

@Dao
@Suppress(RoomWarnings.QUERY_MISMATCH)
interface SongDao {
    @Query(
        """
        SELECT id, duration, playbackPosition,
            (totalPlayTime > 0 OR EXISTS (SELECT 1 FROM event WHERE songId = song.id AND timestamp > 0)) AS played
        FROM song WHERE isEpisode = 1
        """,
    )
    fun episodeListeningProgress(): Flow<List<EpisodeListeningProgress>>

    fun songs(
        sortType: SongSortType,
        descending: Boolean,
    ): Flow<List<Song>> =
        when (sortType) {
            SongSortType.CREATE_DATE -> songsByCreateDateAsc()
            SongSortType.NAME -> songsByNameAsc()
            SongSortType.ARTIST -> songsByRowIdAsc()
            SongSortType.PLAY_TIME -> songsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY rowId")
    fun songsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary")
    fun songsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY COALESCE(customTitle, title), id LIMIT :limit OFFSET :offset")
    fun songsByNameAsc(
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY totalPlayTime")
    fun songsByPlayTimeAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY rowId")
    fun likedSongsByRowIdAsc(): Flow<List<Song>>

    @Query("SELECT id, thumbnailUrl FROM song WHERE liked ORDER BY likedDate LIMIT 1")
    fun likedWidgetSong(): Flow<List<WidgetSong>>

    @Query(
        "SELECT id, thumbnailUrl FROM song WHERE isDownloaded = 1 AND (isEpisode = 0 OR isEpisode IS NULL) ORDER BY dateDownload LIMIT 1",
    )
    fun downloadedWidgetSong(): Flow<List<WidgetSong>>

    @Query(
        "SELECT songId FROM event WHERE timestamp > 0 GROUP BY songId ORDER BY SUM(CASE WHEN playTimeKnown THEN playTime ELSE 0 END) DESC LIMIT 1",
    )
    fun topWidgetSongId(): Flow<String?>

    @Query("SELECT id, thumbnailUrl FROM song WHERE id = :songId")
    fun widgetSong(songId: String): Flow<List<WidgetSong>>

    @OptIn(ExperimentalCoroutinesApi::class)
    fun topWidgetSong(): Flow<List<WidgetSong>> =
        topWidgetSongId().distinctUntilChanged().flatMapLatest { songId ->
            if (songId == null) flowOf(emptyList()) else widgetSong(songId)
        }

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate")
    fun likedSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY COALESCE(customTitle, title), id")
    fun likedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY totalPlayTime")
    fun likedSongsByPlayTimeAsc(): Flow<List<Song>>

    fun likedSongs(
        sortType: SongSortType,
        descending: Boolean,
    ): Flow<List<Song>> =
        when (sortType) {
            SongSortType.CREATE_DATE -> likedSongsByCreateDateAsc()
            SongSortType.NAME -> likedSongsByNameAsc()
            SongSortType.ARTIST -> likedSongsByRowIdAsc()
            SongSortType.PLAY_TIME -> likedSongsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT COUNT(1) FROM song WHERE liked")
    fun likedSongsCount(): Flow<Int>

    @Transaction
    @Query(
        "SELECT song.* FROM song JOIN song_album_map ON song.id = song_album_map.songId WHERE song_album_map.albumId = :albumId ORDER BY song_album_map.`index`",
    )
    fun albumSongs(albumId: String): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT song.* FROM (SELECT songId, `index` AS trackIndex FROM song_album_map WHERE albumId = :albumId UNION ALL SELECT id AS songId, NULL AS trackIndex FROM song WHERE albumId = :albumId AND NOT EXISTS (SELECT 1 FROM song_album_map m WHERE m.albumId = :albumId AND m.songId = song.id)) tracks JOIN song ON song.id = tracks.songId ORDER BY tracks.trackIndex IS NULL, tracks.trackIndex, song.rowid",
    )
    fun storedAlbumSongs(albumId: String): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT song.* FROM song JOIN song_artist_map ON song.id = song_artist_map.songId WHERE artistId = :artistId ORDER BY song.rowid",
    )
    fun storedArtistSongs(artistId: String): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT song.* FROM song WHERE title LIKE '%' || :query || '%' OR customTitle LIKE '%' || :query || '%' OR albumName LIKE '%' || :query || '%' OR customArtist LIKE '%' || :query || '%' OR EXISTS (SELECT 1 FROM song_artist_map JOIN artist ON artist.id = song_artist_map.artistId WHERE song_artist_map.songId = song.id AND artist.name LIKE '%' || :query || '%') ORDER BY song.rowid",
    )
    fun storedSearchSongs(query: String): Flow<List<Song>>

    @Query("SELECT * FROM song_album_map WHERE albumId = :albumId ORDER BY `index`")
    suspend fun albumSongMaps(albumId: String): List<SongAlbumMap>

    @Query("DELETE FROM song_album_map WHERE albumId = :albumId")
    suspend fun clearAlbumSongs(albumId: String)

    @Query("DELETE FROM album_artist_map WHERE albumId = :albumId")
    suspend fun clearAlbumArtists(albumId: String)

    @Transaction
    @Query(
        "SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY inLibrary",
    )
    fun artistSongsByCreateDateAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY COALESCE(customTitle, title), song.id",
    )
    fun artistSongsByNameAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT relatedSongId, COUNT(1) AS referredCount
              FROM related_song_map
              WHERE songId IN (SELECT songId
                         FROM (SELECT songId
                               FROM event
                               ORDER BY ROWID DESC
                               LIMIT 5)
                         UNION
                         SELECT songId
                         FROM (SELECT songId
                               FROM event
                               WHERE timestamp > :now - 86400000 * 7
                               GROUP BY songId
                               ORDER BY SUM(CASE WHEN playTimeKnown = 1 THEN playTime ELSE 0 END) DESC
                               LIMIT 5)
                         UNION
                         SELECT id
                         FROM (SELECT id
                               FROM song
                               ORDER BY totalPlayTime DESC
                               LIMIT 10))
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        ORDER BY referredCount DESC
        LIMIT 100
    """,
    )
    fun quickPicks(now: Long = Clock.System.now().toEpochMilliseconds()): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT song.*, plays.songCountListened, plays.timeListened
        FROM (SELECT songId, COUNT(*) AS songCountListened,
                     SUM(CASE WHEN playTimeKnown = 1 THEN playTime ELSE 0 END) AS timeListened
              FROM event
              WHERE timestamp > :fromTimeStamp AND timestamp <= :toTimeStamp
              GROUP BY songId
              ORDER BY timeListened DESC
              LIMIT :limit OFFSET :offset) plays
        JOIN song ON song.id = plays.songId
        ORDER BY plays.timeListened DESC
    """,
    )
    fun mostPlayedSongs(
        fromTimeStamp: Long,
        limit: Int = 100,
        offset: Int = 0,
        toTimeStamp: Long? = Clock.System.now().toEpochMilliseconds(),
    ): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT songId AS eid,
                     SUM(CASE WHEN timestamp < (:now - 86400000 * 30 * 1) AND playTimeKnown = 1 THEN playTime ELSE 0 END) AS oldPlayTime,
                     SUM(CASE WHEN timestamp > (:now - 86400000 * 30 * 1) AND playTimeKnown = 1 THEN playTime ELSE 0 END) AS newPlayTime
              FROM event
              GROUP BY songId
              HAVING SUM(timestamp < (:now - 86400000 * 30 * 1)) > 0
              ORDER BY oldPlayTime) AS t
                 JOIN song on song.id = t.eid
        WHERE 0.2 * t.oldPlayTime > t.newPlayTime
        LIMIT 100
    """,
    )
    fun forgottenFavorites(now: Long = Clock.System.now().toEpochMilliseconds()): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId")
    fun song(songId: String?): Flow<Song?>

    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): Song?

    @Transaction
    @Query("SELECT * FROM song WHERE id IN (:songIds)")
    suspend fun getSongsByIdsChunk(songIds: List<String>): List<Song>

    @Transaction
    suspend fun getSongsByIds(songIds: List<String>): List<Song> =
        songIds.distinct().chunked(SQLITE_MAX_BIND_PARAMETERS).flatMap { getSongsByIdsChunk(it) }

    @Query("SELECT * FROM song WHERE id IN (:ids)")
    suspend fun songEntitiesByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM artist WHERE id IN (:ids)")
    suspend fun artistEntitiesByIds(ids: List<String>): List<ArtistEntity>

    @Query("SELECT * FROM album WHERE id IN (:ids)")
    suspend fun albumEntitiesByIds(ids: List<String>): List<AlbumEntity>

    @Upsert
    suspend fun upsertSongEntities(songs: List<SongEntity>)

    @Upsert
    suspend fun upsertArtistEntities(artists: List<ArtistEntity>)

    @Upsert
    suspend fun upsertAlbumEntities(albums: List<AlbumEntity>)

    @Upsert
    suspend fun upsertSongArtists(maps: List<SongArtistMap>)

    @Upsert
    suspend fun upsertAlbumArtists(maps: List<AlbumArtistMap>)

    @Transaction
    @Query("SELECT * FROM song_artist_map WHERE songId = :songId")
    suspend fun songArtistMap(songId: String): List<SongArtistMap>

    @Transaction
    @Query("SELECT * FROM song")
    fun allSongs(): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE songCount > 0 ORDER BY rowId",
    )
    fun artistsByCreateDateAsc(): Flow<List<Artist>>

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE songCount > 0 OR (:includeBookmarked AND bookmarkedAt IS NOT NULL) ORDER BY name",
    )
    fun artistsByNameAsc(includeBookmarked: Boolean = false): Flow<List<Artist>>

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE songCount > 0 ORDER BY songCount",
    )
    fun artistsBySongCountAsc(): Flow<List<Artist>>

    @Transaction
    @Query(
        """
        SELECT artist.*,
               (SELECT COUNT(1)
                FROM song_artist_map
                         JOIN song ON song_artist_map.songId = song.id
                WHERE artistId = artist.id
                  AND song.inLibrary IS NOT NULL) AS songCount
        FROM artist
                 JOIN(SELECT artistId, SUM(totalPlayTime) AS totalPlayTime
                      FROM song_artist_map
                               JOIN song
                                    ON song_artist_map.songId = song.id
                      GROUP BY artistId
                      ORDER BY totalPlayTime)
                     ON artist.id = artistId
        WHERE songCount > 0
    """,
    )
    fun artistsByPlayTimeAsc(): Flow<List<Artist>>

    @Transaction
    @Query(
        """
        SELECT artist.*,
               (SELECT COUNT(1)
                FROM song_artist_map
                         JOIN song ON song_artist_map.songId = song.id
                WHERE artistId = artist.id
                  AND song.inLibrary IS NOT NULL) AS songCount,
               (SELECT SUM(song.totalPlayTime)
                FROM song_artist_map
                         JOIN song ON song_artist_map.songId = song.id
                WHERE artistId = artist.id) AS timeListened
        FROM artist
        WHERE bookmarkedAt IS NOT NULL
        ORDER BY bookmarkedAt
        """,
    )
    fun artistsBookmarkedByCreateDateAsc(): Flow<List<Artist>>

    fun artists(
        sortType: ArtistSortType,
        descending: Boolean,
    ) = when (sortType) {
        ArtistSortType.CREATE_DATE -> artistsByCreateDateAsc()
        ArtistSortType.NAME -> artistsByNameAsc()
        ArtistSortType.SONG_COUNT -> artistsBySongCountAsc()
        ArtistSortType.PLAY_TIME -> artistsByPlayTimeAsc()
    }.map { artists ->
        artists
            .filter { it.artist.isYouTubeArtist || it.artist.isLocal }
            .reversed(descending)
    }

    @Query(
        "SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE id = :id",
    )
    fun artist(id: String): Flow<Artist?>

    @Query("SELECT EXISTS(SELECT 1 FROM artist WHERE id = :id AND bookmarkedAt IS NOT NULL)")
    fun isArtistBookmarked(id: String): Flow<Boolean>

    @Query("SELECT * FROM artist WHERE id = :id LIMIT 1")
    suspend fun getArtistById(id: String): ArtistEntity?

    @Transaction
    @Query(
        "SELECT * FROM album WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL) ORDER BY rowId",
    )
    fun albumsByCreateDateAsc(): Flow<List<Album>>

    @Transaction
    @Query(
        "SELECT * FROM album WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL) OR (:includeBookmarked AND bookmarkedAt IS NOT NULL) ORDER BY title",
    )
    fun albumsByNameAsc(includeBookmarked: Boolean = false): Flow<List<Album>>

    @Transaction
    @Query(
        "SELECT * FROM album WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL) ORDER BY year",
    )
    fun albumsByYearAsc(): Flow<List<Album>>

    @Transaction
    @Query(
        "SELECT * FROM album WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL) ORDER BY songCount",
    )
    fun albumsBySongCountAsc(): Flow<List<Album>>

    @Transaction
    @Query(
        "SELECT * FROM album WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL) ORDER BY duration",
    )
    fun albumsByLengthAsc(): Flow<List<Album>>

    @Transaction
    @Query(
        """
        SELECT album.*
        FROM album
                 JOIN song
                      ON song.albumId = album.id
        WHERE EXISTS(SELECT * FROM song WHERE song.albumId = album.id AND song.inLibrary IS NOT NULL)
        GROUP BY album.id
        ORDER BY SUM(song.totalPlayTime)
    """,
    )
    fun albumsByPlayTimeAsc(): Flow<List<Album>>

    @Transaction
    @Query(
        """
        SELECT album.*,
               (SELECT SUM(song.totalPlayTime)
                FROM song
                WHERE song.albumId = album.id) AS timeListened
        FROM album
        WHERE bookmarkedAt IS NOT NULL
        ORDER BY rowId
        """,
    )
    fun albumsLikedByCreateDateAsc(): Flow<List<Album>>

    fun albums(
        sortType: AlbumSortType,
        descending: Boolean,
    ): Flow<List<Album>> =
        when (sortType) {
            AlbumSortType.CREATE_DATE -> albumsByCreateDateAsc()
            AlbumSortType.NAME -> albumsByNameAsc()
            AlbumSortType.ARTIST -> albumsByCreateDateAsc()
            AlbumSortType.YEAR -> albumsByYearAsc()
            AlbumSortType.SONG_COUNT -> albumsBySongCountAsc()
            AlbumSortType.LENGTH -> albumsByLengthAsc()
            AlbumSortType.PLAY_TIME -> albumsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<Album?>

    @Transaction
    @Query(
        "SELECT * FROM song WHERE (title LIKE '%' || :query || '%' OR customTitle LIKE '%' || :query || '%' OR customArtist LIKE '%' || :query || '%') AND inLibrary IS NOT NULL LIMIT :previewSize",
    )
    fun searchSongs(
        query: String,
        previewSize: Int = Int.MAX_VALUE,
    ): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT song.* FROM song
        WHERE song.inLibrary IS NOT NULL
          AND (
              song.title LIKE '%' || :query || '%'
              OR song.customTitle LIKE '%' || :query || '%'
              OR song.customArtist LIKE '%' || :query || '%'
              OR song.albumName LIKE '%' || :query || '%'
              OR EXISTS (
                  SELECT 1 FROM song_artist_map sam JOIN artist a ON a.id = sam.artistId
                  WHERE sam.songId = song.id AND a.name LIKE '%' || :query || '%'
              )
          )
        ORDER BY
            CASE WHEN :descending = 0 THEN COALESCE(song.customTitle, song.title) END COLLATE NOCASE ASC,
            CASE WHEN :descending = 1 THEN COALESCE(song.customTitle, song.title) END COLLATE NOCASE DESC,
            song.id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun contentMatchedSongs(
        query: String,
        limit: Int,
        offset: Int,
        descending: Boolean,
    ): List<Song>

    fun downloadedSongs(
        sortType: SongSortType,
        descending: Boolean,
    ): Flow<List<Song>> =
        when (sortType) {
            SongSortType.CREATE_DATE -> downloadedSongsByCreateDateAsc()
            SongSortType.NAME -> downloadedSongsByNameAsc()
            SongSortType.ARTIST -> downloadedSongsByNameAsc()
            SongSortType.PLAY_TIME -> downloadedSongsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query(
        "SELECT * FROM song WHERE isDownloaded = 1 AND (isEpisode = 0 OR isEpisode IS NULL) ORDER BY dateDownload",
    )
    fun downloadedSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT * FROM song WHERE isDownloaded = 1 AND (isEpisode = 0 OR isEpisode IS NULL) ORDER BY COALESCE(customTitle, title), id",
    )
    fun downloadedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query(
        "SELECT * FROM song WHERE isDownloaded = 1 AND (isEpisode = 0 OR isEpisode IS NULL) ORDER BY totalPlayTime",
    )
    fun downloadedSongsByPlayTimeAsc(): Flow<List<Song>>

    @Query("UPDATE song SET isDownloaded = :downloaded, dateDownload = :date WHERE id = :songId")
    suspend fun updateDownloadedInfo(
        songId: String,
        downloaded: Boolean,
        date: LocalDateTime?,
    )

    @Query("UPDATE song SET isCached = :cached WHERE id = :songId")
    suspend fun updateCachedInfo(
        songId: String,
        cached: Boolean,
    )

    @Query("UPDATE song SET playbackPosition = :position WHERE id = :songId")
    suspend fun updatePlaybackPosition(
        songId: String,
        position: Long?,
    ): Int

    @Transaction
    @Query("SELECT * FROM song WHERE isUploaded = 1 AND inLibrary IS NOT NULL ORDER BY COALESCE(customTitle, title), id")
    fun uploadedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isEpisode = 1 AND isDownloaded = 1 ORDER BY dateDownload")
    fun downloadedPodcastEpisodesByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isEpisode = 1 AND inLibrary IS NOT NULL ORDER BY inLibrary DESC")
    fun savedPodcastEpisodesByCreateDateAsc(): Flow<List<Song>>

    @Query("SELECT * FROM podcast WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    fun subscribedPodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcast WHERE id = :id")
    fun podcast(id: String): Flow<PodcastEntity?>

    @Transaction
    @Query(
        """
        SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount
        FROM artist
        WHERE artist.bookmarkedAt IS NOT NULL
        AND artist.isPodcastChannel = 1
        ORDER BY artist.name COLLATE NOCASE ASC
    """,
    )
    fun bookmarkedPodcastChannels(): Flow<List<Artist>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Upsert
    suspend fun upsert(podcast: PodcastEntity)

    @Delete
    suspend fun delete(podcast: PodcastEntity)

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + :playTime WHERE id = :songId")
    suspend fun incrementTotalPlayTime(
        songId: String,
        playTime: Long,
    )

    @Transaction
    @Query("UPDATE song SET inLibrary = :inLibrary WHERE id = :songId")
    suspend fun inLibrary(
        songId: String,
        inLibrary: LocalDateTime?,
    )

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT *
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN
             song
             ON song.id = map.relatedSongId
        WHERE songId = :songId
        """,
    )
    suspend fun relatedSongs(songId: String): List<Song>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: SongEntity): Long

    @Update
    suspend fun update(song: SongEntity)

    @Update
    suspend fun update(artist: ArtistEntity)

    @Update
    suspend fun update(album: AlbumEntity)

    @Update
    suspend fun update(map: PlaylistSongMap)

    @Upsert
    suspend fun upsertSongEntity(song: SongEntity)

    @Query("SELECT customTitle, customArtist FROM song WHERE id = :songId")
    suspend fun songCustomMetadata(songId: String): SongCustomMetadata?

    @Transaction
    @Query("SELECT * FROM song WHERE customTitle IS NOT NULL OR customArtist IS NOT NULL")
    fun allSongsWithCustomMetadata(): Flow<List<Song>>

    @Query("SELECT id, duration FROM song WHERE duration > 0")
    fun knownSongDurations(): Flow<List<SongDuration>>

    @Transaction
    suspend fun upsert(song: SongEntity) {
        val existing = songCustomMetadata(song.id)
        upsertSongEntity(
            song.copy(
                customTitle = song.customTitle ?: existing?.customTitle,
                customArtist = song.customArtist ?: existing?.customArtist,
            ),
        )
    }

    @Upsert
    suspend fun upsert(artist: ArtistEntity)

    @Upsert
    suspend fun upsert(album: AlbumEntity)

    @Upsert
    suspend fun upsert(map: SongArtistMap)

    @Upsert
    suspend fun upsert(map: SongAlbumMap)

    @Upsert
    suspend fun upsert(map: AlbumArtistMap)

    @Delete
    suspend fun delete(song: SongEntity)

    @Delete
    suspend fun delete(songArtistMap: SongArtistMap)

    @Delete
    suspend fun delete(artist: ArtistEntity)

    @Delete
    suspend fun delete(album: AlbumEntity)

    @Delete
    suspend fun delete(albumArtistMap: AlbumArtistMap)
}

data class WidgetSong(
    val id: String,
    val thumbnailUrl: String?,
)
