package com.metrolist.shared.backup

import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.MIGRATION_44_45_DATA_SQL
import com.metrolist.shared.db.MIGRATION_45_46_SQL
import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val platform: BackupPlatform,
    val databaseVersion: Int? = null,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2
    }
}

@Serializable
enum class BackupPlatform { ANDROID, DESKTOP, IOS }

const val BACKUP_MANIFEST_ENTRY = "backup.json"
const val BACKUP_DATABASE_ENTRY = "song.db"
const val BACKUP_SETTINGS_ENTRY = "settings.json"
const val LEGACY_SETTINGS_ENTRY = "settings.preferences_pb"
const val LEGACY_SPEED_DIAL_MAX_ROWS = 1_000
const val LIKED_SONGS_SETTING_KEY = "likedSongsJson"

private val ROOM_DATA_TABLES =
    setOf(
        "song",
        "artist",
        "album",
        "playlist",
        "song_artist_map",
        "song_album_map",
        "album_artist_map",
        "playlist_song_map",
        "search_history",
        "format",
        "lyrics",
        "event",
        "related_song_map",
        "set_video_id",
        "playCount",
        "recognition_history",
        "speed_dial",
        "podcast",
    )

object BackupArchiveLimits {
    const val DATABASE_BYTES = 512L * 1024L * 1024L
    const val SETTINGS_BYTES = 10L * 1024L * 1024L
    const val MANIFEST_BYTES = 64L * 1024L
    const val UNKNOWN_ENTRY_BYTES = 10L * 1024L * 1024L
    const val ENTRY_COUNT = 16
    const val TOTAL_UNCOMPRESSED_BYTES = 600L * 1024L * 1024L
}

enum class BackupValidationFailure { UNSUPPORTED_FORMAT, UNSUPPORTED_DATABASE, TOO_LARGE, INVALID_DATA }

class BackupValidationException(
    val reason: BackupValidationFailure,
    cause: Throwable? = null,
    message: String = reason.name,
) : IllegalArgumentException(message, cause)

fun requireBackupSize(
    size: Long,
    limit: Long,
) {
    if (size > limit) throw BackupValidationException(BackupValidationFailure.TOO_LARGE)
}

fun BackupManifest.requireSupportedFormat() {
    if (formatVersion !in 1..BackupManifest.CURRENT_FORMAT_VERSION) {
        throw BackupValidationException(BackupValidationFailure.UNSUPPORTED_FORMAT, message = "Unsupported backup format")
    }
}

fun shouldMigrateDatabaseLikes(settingKeys: Set<String>?): Boolean = LIKED_SONGS_SETTING_KEY !in settingKeys.orEmpty()

fun requireSupportedBackupDatabaseVersion(
    version: Int,
    currentVersion: Int,
) {
    if (version != 1 && version !in 36..currentVersion) {
        throw BackupValidationException(
            BackupValidationFailure.UNSUPPORTED_DATABASE,
            message = "Backup database version $version is not supported",
        )
    }
}

// Holds per-query scratch rows only, so its presence says nothing about the backup's version.
private val TRANSIENT_TABLES = setOf("history_stat_exclusion")

fun requireSupportedBackupDatabaseTables(
    version: Int,
    allTables: Set<String>,
) {
    requireSupportedBackupDatabaseVersion(version, DATABASE_VERSION)
    val tables = allTables - TRANSIENT_TABLES
    val required =
        when {
            version == 1 -> setOf("song", "artist", "playlist", "playlist_song")
            version < 40 -> ROOM_DATA_TABLES - "speed_dial"
            version < 50 -> ROOM_DATA_TABLES
            version < 51 -> ROOM_DATA_TABLES + "import_track_match"
            else -> ROOM_DATA_TABLES + setOf("import_track_match", "history_snapshot", "history_provenance", "history_import_policy")
        } + (if (version >= 58) setOf("pending_scrobble") else emptySet())
    val supported =
        when {
            version == 1 -> {
                ROOM_DATA_TABLES + setOf("cached_audio", "playlist_song", "speed_dial_item", "download")
            }

            version < 40 -> {
                ROOM_DATA_TABLES + "speed_dial_item"
            }

            else -> {
                ROOM_DATA_TABLES + "cached_audio" +
                    (if (version >= 50) setOf("import_track_match") else emptySet()) +
                    (if (version >= 51) setOf("history_snapshot", "history_provenance", "history_import_policy") else emptySet()) +
                    (if (version >= 58) setOf("pending_scrobble") else emptySet())
            }
        }
    val speedDialTables = tables.intersect(setOf("speed_dial", "speed_dial_item"))
    require(
        tables.containsAll(required) &&
            tables.all { it in supported } &&
            speedDialTables.size <= 1 &&
            (version !in 36..39 || speedDialTables.isNotEmpty()),
    ) {
        "Backup database schema does not match its declared version"
    }
}

fun legacyBackupDataMigrations(version: Int): List<String> {
    requireSupportedBackupDatabaseVersion(version, DATABASE_VERSION)
    return buildList {
        if (version < 45) addAll(MIGRATION_44_45_DATA_SQL)
        if (version < 46) addAll(MIGRATION_45_46_SQL)
        if (version < 51) {
            add("UPDATE event SET playTimeKnown = 0 WHERE source = 'YOUTUBE'")
            add("INSERT INTO history_import_policy (id, enabled) VALUES (1, 0)")
        }
    }
}

fun shouldConvertRestoredDatabase(
    backupVersion: Int,
    backupIdentityHash: String?,
    currentVersion: Int,
    currentIdentityHash: String?,
): Boolean {
    require(backupVersion <= currentVersion) {
        "Backup database version $backupVersion is newer than supported version $currentVersion"
    }
    return backupVersion < currentVersion ||
        backupIdentityHash == null ||
        currentIdentityHash == null ||
        backupIdentityHash != currentIdentityHash
}

fun legacySpeedDialIndexExpression(sourceTable: String): String =
    """
    (SELECT COUNT(*) - 1
    FROM legacy.${sourceTable.sqlIdentifier()} AS earlier_speed_dial
    WHERE earlier_speed_dial.createDate < legacy_speed_dial.createDate
        OR (earlier_speed_dial.createDate = legacy_speed_dial.createDate
            AND earlier_speed_dial.id <= legacy_speed_dial.id))
    """.trimIndent()

fun legacyTableSourceExpression(
    sourceTable: String,
    needsSpeedDialIndex: Boolean,
): String =
    buildString {
        append("legacy.")
        append(sourceTable.sqlIdentifier())
        if (needsSpeedDialIndex) append(" AS legacy_speed_dial")
    }

internal fun String.sqlIdentifier(): String = "\"${replace("\"", "\"\"")}\""
