/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.daos.ImportTrackMatchDao
import com.metrolist.shared.db.daos.PendingScrobbleDao
import com.metrolist.shared.db.daos.SpeedDialDao
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.HistoryImportPolicy
import com.metrolist.shared.db.entities.HistoryProvenance
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.HistoryStatExclusion
import com.metrolist.shared.db.entities.ImportTrackMatch
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.db.entities.PlayCountEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.PlaylistSongMapPreview
import com.metrolist.shared.db.entities.PodcastEntity
import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.db.entities.RelatedSongMap
import com.metrolist.shared.db.entities.SearchHistory
import com.metrolist.shared.db.entities.SetVideoIdEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.SortedSongAlbumMap
import com.metrolist.shared.db.entities.SortedSongArtistMap
import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.scrobble.PendingScrobble

const val DATABASE_VERSION = 65

internal val MIGRATION_64_65_SQL =
    listOf(
        "ALTER TABLE lyrics ADD COLUMN stashedUserLyrics TEXT",
        "ALTER TABLE lyrics ADD COLUMN stashedUserLyricsDraft TEXT",
    )

internal val MIGRATION_63_64_SQL =
    listOf(
        "ALTER TABLE lyrics ADD COLUMN userLyrics TEXT",
        "ALTER TABLE lyrics ADD COLUMN userLyricsDraft TEXT",
        "UPDATE lyrics SET userLyrics = lyrics, lyrics = '', provider = 'Unknown', isUserSelected = 0 WHERE provider IN ('METROLIST_LOCAL', 'Local')",
    )

internal val MIGRATION_62_63_SQL =
    listOf(
        "CREATE INDEX IF NOT EXISTS index_playlist_song_map_playlistId_position ON playlist_song_map(playlistId, position)",
        "CREATE INDEX IF NOT EXISTS index_song_inLibrary ON song(inLibrary)",
    )

internal val MIGRATION_61_62_SQL =
    listOf(
        "ALTER TABLE event ADD COLUMN remoteAccountKey TEXT",
        "CREATE INDEX IF NOT EXISTS index_event_songId_timestamp_remoteOrder_id ON event(songId, timestamp, remoteOrder, id)",
    )

internal val MIGRATION_60_61_SQL =
    listOf("ALTER TABLE song ADD COLUMN isUnavailable INTEGER NOT NULL DEFAULT 0")

internal val MIGRATION_59_60_SQL =
    listOf(
        "ALTER TABLE event ADD COLUMN remoteDateLabel TEXT",
        "ALTER TABLE event ADD COLUMN remoteOrder INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE lyrics ADD COLUMN missLookupKey TEXT",
        "ALTER TABLE lyrics ADD COLUMN missExpiresAt INTEGER",
    )

internal val MIGRATION_57_58_SQL =
    listOf(
        """CREATE TABLE IF NOT EXISTS `pending_scrobble` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `account` TEXT NOT NULL, `track` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `albumArtist` TEXT, `duration` INTEGER, `timestamp` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
    )

internal val MIGRATION_56_57_SQL =
    listOf(
        "ALTER TABLE lyrics ADD COLUMN isUserSelected INTEGER NOT NULL DEFAULT 0",
        "UPDATE lyrics SET isUserSelected = 1 WHERE provider IN ('METROLIST_LOCAL', 'Local')",
    )

// Add indexes in place: Room's table rebuild would cascade-delete playlist membership.
internal val MIGRATION_55_56_SQL =
    listOf(
        "CREATE INDEX IF NOT EXISTS index_playlist_browseId ON playlist(browseId)",
        "CREATE INDEX IF NOT EXISTS index_history_provenance_legacyId_songId ON history_provenance(legacyId, songId)",
    )

// Adds the index in place; a Room-generated rebuild of event would cascade-delete its children.
internal val MIGRATION_54_55_SQL =
    listOf(
        "CREATE INDEX IF NOT EXISTS index_event_timestamp ON event(timestamp)",
        "CREATE TABLE IF NOT EXISTS history_stat_exclusion (kind TEXT NOT NULL, value TEXT NOT NULL, encodedValue TEXT NOT NULL, PRIMARY KEY(kind, value))",
    )

internal val MIGRATION_39_40_SQL =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS cached_audio (
            songId TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT,
            thumbnailUrl TEXT,
            filePath TEXT NOT NULL,
            mimeType TEXT,
            fileSize INTEGER NOT NULL,
            cachedAt INTEGER NOT NULL,
            lastAccessedAt INTEGER NOT NULL,
            cacheLocation TEXT NOT NULL
        )
        """.trimIndent(),
    )

internal val MIGRATION_40_41_SQL =
    listOf("ALTER TABLE cached_audio ADD COLUMN kind TEXT NOT NULL DEFAULT 'CACHE'")

internal val MIGRATION_41_42_SQL =
    listOf(
        "ALTER TABLE event ADD COLUMN source TEXT NOT NULL DEFAULT 'LOCAL'",
        "ALTER TABLE event ADD COLUMN remoteFeedbackToken TEXT",
        "ALTER TABLE event ADD COLUMN remoteSynced INTEGER NOT NULL DEFAULT 0",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_event_remoteFeedbackToken ON event(remoteFeedbackToken)",
    )

internal val MIGRATION_42_43_SQL =
    listOf(
        "ALTER TABLE lyrics ADD COLUMN translationSourceHash TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE lyrics ADD COLUMN translationConfigKey TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE lyrics ADD COLUMN translationNotNeeded INTEGER NOT NULL DEFAULT 0",
    )

internal val MIGRATION_43_44_SQL =
    listOf("ALTER TABLE playlist ADD COLUMN description TEXT")

internal val MIGRATION_44_45_DATA_SQL =
    listOf(
        """
        UPDATE lyrics
        SET offsetMs = COALESCE(
            (SELECT lyricsOffset FROM song WHERE song.id = lyrics.id),
            0
        )
        """.trimIndent(),
        "UPDATE speed_dial SET type = 'PODCAST' WHERE type = 'PLAYLIST' AND id LIKE 'MPSP%'",
    )

internal val MIGRATION_44_45_SQL =
    listOf("ALTER TABLE lyrics ADD COLUMN offsetMs INTEGER NOT NULL DEFAULT 0") + MIGRATION_44_45_DATA_SQL

internal val MIGRATION_45_46_SQL =
    listOf(
        """
        DELETE FROM playlist
        WHERE id GLOB 'LP????????'
          AND browseId IS NULL
          AND name = 'Playlist'
          AND isEditable = 0
          AND isLocal = 0
          AND remoteSongCount = 0
          AND thumbnailUrl IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM playlist_song_map WHERE playlistId = playlist.id
          )
        """.trimIndent(),
    )

internal val MIGRATION_46_47_SQL =
    listOf(
        "ALTER TABLE speed_dial ADD COLUMN musicVideoType TEXT",
    )

internal val MIGRATION_47_48_SQL =
    listOf(
        "ALTER TABLE song ADD COLUMN customTitle TEXT DEFAULT NULL",
        "ALTER TABLE song ADD COLUMN customArtist TEXT DEFAULT NULL",
    )

internal val MIGRATION_48_49_SQL =
    listOf(
        "ALTER TABLE song ADD COLUMN explicitness INTEGER DEFAULT NULL",
        "ALTER TABLE speed_dial ADD COLUMN explicitness INTEGER",
    )

internal val MIGRATION_49_50_SQL =
    listOf(
        """CREATE TABLE IF NOT EXISTS `import_track_match` (`playlistId` TEXT NOT NULL, `provider` TEXT NOT NULL, `trackKey` TEXT NOT NULL, `songId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `provider`, `trackKey`), FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        "CREATE INDEX IF NOT EXISTS `index_import_track_match_songId` ON `import_track_match` (`songId`)",
    )

internal val MIGRATION_50_51_SQL =
    listOf(
        "ALTER TABLE event ADD COLUMN playTimeKnown INTEGER NOT NULL DEFAULT 1",
        "UPDATE event SET playTimeKnown = 0 WHERE source = 'YOUTUBE'",
        """CREATE TABLE IF NOT EXISTS `history_snapshot` (`eventId` INTEGER NOT NULL, `title` TEXT NOT NULL, `artists` TEXT NOT NULL, `album` TEXT, `duration` INTEGER, `thumbnailUrl` TEXT, `remoteFeedbackToken` TEXT, PRIMARY KEY(`eventId`), FOREIGN KEY(`eventId`) REFERENCES `event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_history_snapshot_eventId` ON `history_snapshot` (`eventId`)",
        """CREATE TABLE IF NOT EXISTS `history_provenance` (`source` TEXT NOT NULL, `legacyId` INTEGER NOT NULL, `songId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `remoteFeedbackToken` TEXT, `eventId` INTEGER, PRIMARY KEY(`source`, `legacyId`), FOREIGN KEY(`eventId`) REFERENCES `event`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
        "CREATE INDEX IF NOT EXISTS `index_history_provenance_eventId` ON `history_provenance` (`eventId`)",
        "CREATE TABLE IF NOT EXISTS `history_import_policy` (`id` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )

internal val MIGRATION_51_52_SQL =
    listOf(
        "ALTER TABLE history_snapshot ADD COLUMN artistNamesJson TEXT",
        "ALTER TABLE history_snapshot ADD COLUMN albumId TEXT",
    )

internal val MIGRATION_52_53_SQL =
    listOf(
        "CREATE INDEX IF NOT EXISTS `index_history_snapshot_remoteFeedbackToken` ON `history_snapshot` (`remoteFeedbackToken`)",
    )

internal val MIGRATION_53_54_SQL =
    listOf("ALTER TABLE playlist ADD COLUMN customThumbnailUrl TEXT")

@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SongArtistMap::class,
        SongAlbumMap::class,
        AlbumArtistMap::class,
        PlaylistSongMap::class,
        ImportTrackMatch::class,
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
        HistorySnapshot::class,
        HistoryStatExclusion::class,
        HistoryProvenance::class,
        HistoryImportPolicy::class,
        RelatedSongMap::class,
        SetVideoIdEntity::class,
        PlayCountEntity::class,
        RecognitionHistory::class,
        SpeedDialItem::class,
        PodcastEntity::class,
        CachedAudioEntity::class,
        PendingScrobble::class,
    ],
    views = [
        SortedSongArtistMap::class,
        SortedSongAlbumMap::class,
        PlaylistSongMapPreview::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun pendingScrobbleDao(): PendingScrobbleDao

    abstract fun dao(): DatabaseDao

    abstract fun importTrackMatchDao(): ImportTrackMatchDao

    abstract fun speedDialDao(): SpeedDialDao
}

@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}

expect fun getDatabaseBuilder(context: Any? = null): RoomDatabase.Builder<MusicDatabase>
