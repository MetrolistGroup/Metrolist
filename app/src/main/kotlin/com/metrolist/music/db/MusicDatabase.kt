/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.PlayCountEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.PlaylistSongMapPreview
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.db.entities.SetVideoIdEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.db.entities.SortedSongAlbumMap
import com.metrolist.music.db.entities.SortedSongArtistMap
import com.metrolist.music.extensions.toSQLiteQuery
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date

class MusicDatabase(
    private val delegate: InternalDatabase,
) : DatabaseDao by delegate.dao {
    val openHelper: SupportSQLiteOpenHelper
        get() = delegate.openHelper

    fun query(block: MusicDatabase.() -> Unit) =
        with(delegate) {
            queryExecutor.execute {
                block(this@MusicDatabase)
            }
        }

    fun transaction(block: MusicDatabase.() -> Unit) =
        with(delegate) {
            transactionExecutor.execute {
                runInTransaction {
                    block(this@MusicDatabase)
                }
            }
        }

    suspend fun withTransaction(block: suspend MusicDatabase.() -> Unit) =
        with(delegate) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runInTransaction {
                    kotlinx.coroutines.runBlocking {
                        block(this@MusicDatabase)
                    }
                }
            }
        }

    fun close() = delegate.close()
}

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
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
        RelatedSongMap::class,
        SetVideoIdEntity::class,
        PlayCountEntity::class
    ],
    views = [
        SortedSongArtistMap::class,
        SortedSongAlbumMap::class,
        PlaylistSongMapPreview::class,
    ],
    version = 30,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = Migration5To6::class),
        AutoMigration(from = 6, to = 7, spec = Migration6To7::class),
        AutoMigration(from = 7, to = 8, spec = Migration7To8::class),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, spec = Migration9To10::class),
        AutoMigration(from = 10, to = 11, spec = Migration10To11::class),
        AutoMigration(from = 11, to = 12, spec = Migration11To12::class),
        AutoMigration(from = 12, to = 13, spec = Migration12To13::class),
        AutoMigration(from = 13, to = 14, spec = Migration13To14::class),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17, spec = Migration16To17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19, spec = Migration18To19::class),
        AutoMigration(from = 19, to = 20, spec = Migration19To20::class),
        AutoMigration(from = 20, to = 21, spec = Migration20To21::class),
        AutoMigration(from = 21, to = 22, spec = Migration21To22::class),
        AutoMigration(from = 22, to = 23, spec = Migration22To23::class),
        AutoMigration(from = 23, to = 24, spec = Migration23To24::class),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30, spec = Migration29To30::class),
    ],
)
@TypeConverters(Converters::class)
abstract class InternalDatabase : RoomDatabase() {
    abstract val dao: DatabaseDao

    companion object {
        const val DB_NAME = "song.db"

        fun newInstance(context: Context): MusicDatabase =
            MusicDatabase(
                delegate =
                Room
                    .databaseBuilder(context, InternalDatabase::class.java, DB_NAME)
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_21_24,
                        MIGRATION_22_24,
                        MIGRATION_24_25,
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setTransactionExecutor(java.util.concurrent.Executors.newFixedThreadPool(4))
                    .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(4))
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            try {
                                db.query("PRAGMA busy_timeout = 60000").close()
                                db.query("PRAGMA cache_size = -16000").close()
                                db.query("PRAGMA wal_autocheckpoint = 1000").close()
                                db.query("PRAGMA synchronous = NORMAL").close()
                            } catch (e: Exception) {
                                Timber.tag("MusicDatabase").e(e, "Failed to set PRAGMA settings")
                            }
                        }
                    })
                    .build(),
            )
    }
}

// ===== Migrations =====

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            data class OldSongEntity(
                val id: String,
                val title: String,
                val duration: Int = -1,
                val thumbnailUrl: String? = null,
                val albumId: String? = null,
                val albumName: String? = null,
                val liked: Boolean = false,
                val totalPlayTime: Long = 0,
                val downloadState: Int = 0,
                val createDate: LocalDateTime = LocalDateTime.now(),
                val modifyDate: LocalDateTime = LocalDateTime.now(),
            )

            val converters = Converters()
            val artistMap = mutableMapOf<Int, String>()
            val artists = mutableListOf<ArtistEntity>()
            db.query("SELECT * FROM artist".toSQLiteQuery()).use { cursor ->
                while (cursor.moveToNext()) {
                    val oldId = cursor.getInt(0)
                    val newId = ArtistEntity.generateArtistId()
                    artistMap[oldId] = newId
                    artists.add(
                        ArtistEntity(
                            id = newId,
                            name = cursor.getString(1),
                        ),
                    )
                }
            }

            val playlistMap = mutableMapOf<Int, String>()
            val playlists = mutableListOf<PlaylistEntity>()
            db.query("SELECT * FROM playlist".toSQLiteQuery()).use { cursor ->
                while (cursor.moveToNext()) {
                    val oldId = cursor.getInt(0)
                    val newId = PlaylistEntity.generatePlaylistId()
                    playlistMap[oldId] = newId
                    playlists.add(
                        PlaylistEntity(
                            id = newId,
                            name = cursor.getString(1),
                        ),
                    )
                }
            }
            val playlistSongMaps = mutableListOf<PlaylistSongMap>()
            db.query("SELECT * FROM playlist_song".toSQLiteQuery()).use { cursor ->
                while (cursor.moveToNext()) {
                    playlistSongMaps.add(
                        PlaylistSongMap(
                            playlistId = playlistMap[cursor.getInt(1)]!!,
                            songId = cursor.getString(2),
                            position = cursor.getInt(3),
                        ),
                    )
                }
            }
            playlistSongMaps.sortBy { it.position }
            val playlistSongCount = mutableMapOf<String, Int>()
            playlistSongMaps.map { map ->
                if (map.playlistId !in playlistSongCount) playlistSongCount[map.playlistId] = 0
                map.copy(position = playlistSongCount[map.playlistId]!!).also {
                    playlistSongCount[map.playlistId] = playlistSongCount[map.playlistId]!! + 1
                }
            }
            val songs = mutableListOf<OldSongEntity>()
            val songArtistMaps = mutableListOf<SongArtistMap>()
            db.query("SELECT * FROM song".toSQLiteQuery()).use { cursor ->
                while (cursor.moveToNext()) {
                    val songId = cursor.getString(0)
                    songs.add(
                        OldSongEntity(
                            id = songId,
                            title = cursor.getString(1),
                            duration = cursor.getInt(3),
                            liked = cursor.getInt(4) == 1,
                            createDate = Instant.ofEpochMilli(Date(cursor.getLong(8)).time)
                                .atZone(ZoneOffset.UTC).toLocalDateTime(),
                            modifyDate = Instant.ofEpochMilli(Date(cursor.getLong(9)).time)
                                .atZone(ZoneOffset.UTC).toLocalDateTime(),
                        ),
                    )
                    songArtistMaps.add(
                        SongArtistMap(
                            songId = songId,
                            artistId = artistMap[cursor.getInt(2)]!!,
                            position = 0,
                        ),
                    )
                }
            }
            db.execSQL("DROP TABLE IF EXISTS song")
            db.execSQL("DROP TABLE IF EXISTS artist")
            db.execSQL("DROP TABLE IF EXISTS playlist")
            db.execSQL("DROP TABLE IF EXISTS playlist_song")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `albumId` TEXT, `albumName` TEXT, `liked` INTEGER NOT NULL, `totalPlayTime` INTEGER NOT NULL, `isTrash` INTEGER NOT NULL, `download_state` INTEGER NOT NULL, `create_date` INTEGER NOT NULL, `modify_date` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `artist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnailUrl` TEXT, `bannerUrl` TEXT, `description` TEXT, `createDate` INTEGER NOT NULL, `lastUpdateTime` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `album` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `year` INTEGER, `thumbnailUrl` TEXT, `songCount` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `createDate` INTEGER NOT NULL, `lastUpdateTime` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT, `authorId` TEXT, `year` INTEGER, `thumbnailUrl` TEXT, `createDate` INTEGER NOT NULL, `lastUpdateTime` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `song_artist_map` (`songId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`songId`, `artistId`), FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artistId`) REFERENCES `artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_artist_map_songId` ON `song_artist_map` (`songId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_artist_map_artistId` ON `song_artist_map` (`artistId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `song_album_map` (`songId` TEXT NOT NULL, `albumId` TEXT NOT NULL, `index` INTEGER, PRIMARY KEY(`songId`, `albumId`), FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`albumId`) REFERENCES `album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_album_map_songId` ON `song_album_map` (`songId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_album_map_albumId` ON `song_album_map` (`albumId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `album_artist_map` (`albumId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `order` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `artistId`), FOREIGN KEY(`albumId`) REFERENCES `album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artistId`) REFERENCES `artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_artist_map_albumId` ON `album_artist_map` (`albumId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_artist_map_artistId` ON `album_artist_map` (`artistId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_song_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_song_map_playlistId` ON `playlist_song_map` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_song_map_songId` ON `playlist_song_map` (`songId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `download` (`id` INTEGER NOT NULL, `songId` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_query` ON `search_history` (`query`)")
            db.execSQL("CREATE VIEW `sorted_song_artist_map` AS SELECT * FROM song_artist_map ORDER BY position")
            db.execSQL(
                "CREATE VIEW `playlist_song_map_preview` AS SELECT * FROM playlist_song_map WHERE position <= 3 ORDER BY position",
            )
            artists.forEach { artist ->
                db.insert(
                    "artist",
                    SQLiteDatabase.CONFLICT_ABORT,
                    contentValuesOf(
                        "id" to artist.id,
                        "name" to artist.name,
                        "createDate" to converters.dateToTimestamp(artist.lastUpdateTime),
                        "lastUpdateTime" to converters.dateToTimestamp(artist.lastUpdateTime),
                    ),
                )
            }
            songs.forEach { song ->
                db.insert(
                    "song",
                    SQLiteDatabase.CONFLICT_ABORT,
                    contentValuesOf(
                        "id" to song.id,
                        "title" to song.title,
                        "duration" to song.duration,
                        "liked" to song.liked,
                        "totalPlayTime" to song.totalPlayTime,
                        "isTrash" to false,
                        "download_state" to song.downloadState,
                        "create_date" to converters.dateToTimestamp(song.createDate),
                        "modify_date" to converters.dateToTimestamp(song.modifyDate),
                    ),
                )
            }
            songArtistMaps.forEach { songArtistMap ->
                db.insert(
                    "song_artist_map",
                    SQLiteDatabase.CONFLICT_ABORT,
                    contentValuesOf(
                        "songId" to songArtistMap.songId,
                        "artistId" to songArtistMap.artistId,
                        "position" to songArtistMap.position,
                    ),
                )
            }
            playlists.forEach { playlist ->
                db.insert(
                    "playlist",
                    SQLiteDatabase.CONFLICT_ABORT,
                    contentValuesOf(
                        "id" to playlist.id,
                        "name" to playlist.name,
                        "createDate" to converters.dateToTimestamp(LocalDateTime.now()),
                        "lastUpdateTime" to converters.dateToTimestamp(LocalDateTime.now()),
                    ),
                )
            }
            playlistSongMaps.forEach { playlistSongMap ->
                db.insert(
                    "playlist_song_map",
                    SQLiteDatabase.CONFLICT_ABORT,
                    contentValuesOf(
                        "playlistId" to playlistSongMap.playlistId,
                        "songId" to playlistSongMap.songId,
                        "position" to playlistSongMap.position,
                    ),
                )
            }
        }
    }

val MIGRATION_21_24 =
    object : Migration(21, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Combine all changes from 21→22→23→24
            
            // From 21→22: Add columns
            try {
                db.execSQL("ALTER TABLE song ADD COLUMN libraryAddToken TEXT DEFAULT ''")
            } catch (e: Exception) {
                Timber.tag("Migration").w("Column libraryAddToken may already exist")
            }
            try {
                db.execSQL("ALTER TABLE song ADD COLUMN libraryRemoveToken TEXT DEFAULT ''")
            } catch (e: Exception) {
                Timber.tag("Migration").w("Column libraryRemoveToken may already exist")
            }
            try {
                db.execSQL("ALTER TABLE song ADD COLUMN romanizeLyrics INTEGER NOT NULL DEFAULT 1")
            } catch (e: Exception) {
                Timber.tag("Migration").w("Column romanizeLyrics may already exist")
            }
            try {
                db.execSQL("ALTER TABLE song ADD COLUMN isDownloaded INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                Timber.tag("Migration").w("Column isDownloaded may already exist")
            }

            // From 23→24: Add isUploaded
            var hasIsUploaded = false
            db.query("PRAGMA table_info('song')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    if (colName == "isUploaded") {
                        hasIsUploaded = true
                        break
                    }
                }
            }
            
            if (!hasIsUploaded) {
                db.execSQL("ALTER TABLE `song` ADD COLUMN `isUploaded` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

val MIGRATION_22_24 =
    object : Migration(22, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // From 23→24: Add isUploaded
            var hasIsUploaded = false
            db.query("PRAGMA table_info('song')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    if (colName == "isUploaded") {
                        hasIsUploaded = true
                        break
                    }
                }
            }
            
            if (!hasIsUploaded) {
                db.execSQL("ALTER TABLE `song` ADD COLUMN `isUploaded` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

// ===== AutoMigration Specs =====

@DeleteColumn.Entries(
    DeleteColumn(tableName = "song", columnName = "isTrash"),
    DeleteColumn(tableName = "playlist", columnName = "author"),
    DeleteColumn(tableName = "playlist", columnName = "authorId"),
    DeleteColumn(tableName = "playlist", columnName = "year"),
    DeleteColumn(tableName = "playlist", columnName = "thumbnailUrl"),
    DeleteColumn(tableName = "playlist", columnName = "createDate"),
    DeleteColumn(tableName = "playlist", columnName = "lastUpdateTime"),
)
@RenameColumn.Entries(
    RenameColumn(
        tableName = "song",
        fromColumnName = "download_state",
        toColumnName = "downloadState"
    ),
    RenameColumn(tableName = "song", fromColumnName = "create_date", toColumnName = "createDate"),
    RenameColumn(tableName = "song", fromColumnName = "modify_date", toColumnName = "modifyDate"),
)
class Migration5To6 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id FROM playlist WHERE id NOT LIKE 'LP%'").use { cursor ->
            while (cursor.moveToNext()) {
                db.execSQL(
                    "UPDATE playlist SET browseId = '${cursor.getString(0)}' WHERE id = '${cursor.getString(0)}'"
                )
            }
        }
    }
}

class Migration6To7 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, createDate FROM song").use { cursor ->
            while (cursor.moveToNext()) {
                db.execSQL(
                    "UPDATE song SET inLibrary = ${cursor.getLong(1)} WHERE id = '${cursor.getString(0)}'"
                )
            }
        }
    }
}

@DeleteColumn.Entries(
    DeleteColumn(tableName = "song", columnName = "createDate"),
    DeleteColumn(tableName = "song", columnName = "modifyDate"),
)
class Migration7To8 : AutoMigrationSpec

@DeleteTable.Entries(
    DeleteTable(tableName = "download"),
)
class Migration9To10 : AutoMigrationSpec

@DeleteColumn.Entries(
    DeleteColumn(tableName = "song", columnName = "downloadState"),
    DeleteColumn(tableName = "artist", columnName = "bannerUrl"),
    DeleteColumn(tableName = "artist", columnName = "description"),
    DeleteColumn(tableName = "artist", columnName = "createDate"),
)
class Migration10To11 : AutoMigrationSpec

@DeleteColumn.Entries(
    DeleteColumn(tableName = "album", columnName = "createDate"),
)
class Migration11To12 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE album SET bookmarkedAt = lastUpdateTime")
        db.query("SELECT DISTINCT albumId, albumName FROM song").use { cursor ->
            while (cursor.moveToNext()) {
                val albumId = cursor.getString(0)
                val albumName = cursor.getString(1)
                db.insert(
                    table = "album",
                    conflictAlgorithm = SQLiteDatabase.CONFLICT_IGNORE,
                    values =
                    contentValuesOf(
                        "id" to albumId,
                        "title" to albumName,
                        "songCount" to 0,
                        "duration" to 0,
                        "lastUpdateTime" to 0,
                    ),
                )
            }
        }
        db.query("CREATE INDEX IF NOT EXISTS `index_song_albumId` ON `song` (`albumId`)")
    }
}

class Migration12To13 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
    }
}

class Migration13To14 : AutoMigrationSpec {
    @SuppressLint("Range")
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE playlist SET createdAt = '${Converters().dateToTimestamp(LocalDateTime.now())}'")
        db.execSQL(
            "UPDATE playlist SET lastUpdateTime = '${Converters().dateToTimestamp(LocalDateTime.now())}'"
        )
    }
}

@DeleteColumn.Entries(
    DeleteColumn(tableName = "song", columnName = "isLocal"),
    DeleteColumn(tableName = "song", columnName = "localPath"),
    DeleteColumn(tableName = "artist", columnName = "isLocal"),
    DeleteColumn(tableName = "playlist", columnName = "isLocal"),
)
class Migration16To17 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE playlist SET bookmarkedAt = lastUpdateTime")
        db.execSQL("UPDATE playlist SET isEditable = 1 WHERE browseId IS NOT NULL")
    }
}

class Migration18To19 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE song SET explicit = 0 WHERE explicit IS NULL")
    }
}

class Migration19To20 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE song SET explicit = 0 WHERE explicit IS NULL")
    }
}

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "song",
        columnName = "artistName"
    )
)
class Migration20To21 : AutoMigrationSpec

class Migration21To22 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE song ADD COLUMN libraryAddToken TEXT DEFAULT ''")
        } catch (e: Exception) {
            Timber.tag("Migration21To22").w(e, "Column may already exist")
        }
        try {
            db.execSQL("ALTER TABLE song ADD COLUMN libraryRemoveToken TEXT DEFAULT ''")
        } catch (e: Exception) {
            Timber.tag("Migration21To22").w(e, "Column may already exist")
        }
        try {
            db.execSQL("ALTER TABLE song ADD COLUMN romanizeLyrics INTEGER NOT NULL DEFAULT 1")
        } catch (e: Exception) {
            Timber.tag("Migration21To22").w(e, "Column may already exist")
        }
        try {
            db.execSQL("ALTER TABLE song ADD COLUMN isDownloaded INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            Timber.tag("Migration21To22").w(e, "Column may already exist")
        }
    }
}

class Migration22To23 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // No changes needed for 22→23
    }
}

class Migration23To24: AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        var hasIsUploaded = false
        db.query("PRAGMA table_info('song')").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (colName == "isUploaded") {
                    hasIsUploaded = true
                    break
                }
            }
        }

        if (!hasIsUploaded) {
            db.execSQL("ALTER TABLE `song` ADD COLUMN `isUploaded` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

val MIGRATION_24_25 =
    object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add perceptualLoudnessDb column to format table for improved audio normalization
            var columnExists = false
            db.query("PRAGMA table_info(format)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "perceptualLoudnessDb") {
                        columnExists = true
                        break
                    }
                }
            }

            if (!columnExists) {
                // Add the column allowing NULL values (since existing rows won't have this data)
                db.execSQL("ALTER TABLE format ADD COLUMN perceptualLoudnessDb REAL DEFAULT NULL")
            }
        }
    }

class Migration29To30 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // Ensure isVideo column exists (safeguard)
        var hasIsVideo = false
        db.query("PRAGMA table_info('song')").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (colName == "isVideo") {
                    hasIsVideo = true
                    break
                }
            }
        }
        if (!hasIsVideo) {
            db.execSQL("ALTER TABLE song ADD COLUMN isVideo INTEGER NOT NULL DEFAULT 0")
        }

        // Ensure provider column exists in lyrics table
        var hasProvider = false
        db.query("PRAGMA table_info('lyrics')").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (colName == "provider") {
                    hasProvider = true
                    break
                }
            }
        }
        if (!hasProvider) {
            db.execSQL("ALTER TABLE lyrics ADD COLUMN provider TEXT NOT NULL DEFAULT 'Unknown'")
        }
    }
}
