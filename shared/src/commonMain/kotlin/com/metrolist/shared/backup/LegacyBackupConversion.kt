package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private val VERSION_ONE_EXPLICIT_TABLES = setOf("song", "artist", "playlist", "song_artist_map", "playlist_song_map")

/** Requires no active transaction because conversion toggles foreign keys and attaches the legacy file. */
fun copyLegacyBackupTables(
    database: SQLiteConnection,
    legacyPath: String,
    legacyVersion: Int,
) {
    database.execSQL("PRAGMA foreign_keys = OFF")
    database.execSQL("ATTACH DATABASE '${legacyPath.replace("'", "''")}' AS legacy")
    try {
        database.execSQL("BEGIN IMMEDIATE TRANSACTION")
        try {
            requireSupportedBackupDatabaseTables(legacyVersion, database.userTables("legacy").toSet())
            database.userTables("main").forEach { table -> database.copyLegacyTable(table, legacyVersion) }
            if (legacyVersion == 1) database.importVersionOne()
            legacyBackupDataMigrations(legacyVersion).forEach(database::execSQL)
            database.prepare("PRAGMA foreign_key_check").use {
                check(!it.step()) { "Legacy database contains invalid relationships" }
            }
            database.execSQL("COMMIT")
        } catch (error: Throwable) {
            runCatching { database.execSQL("ROLLBACK") }.onFailure(error::addSuppressed)
            throw error
        }
    } finally {
        database.execSQL("DETACH DATABASE legacy")
    }
}

private fun SQLiteConnection.copyLegacyTable(
    table: String,
    legacyVersion: Int,
) {
    execSQL("DELETE FROM main.${table.sqlIdentifier()}")
    if (legacyVersion == 1 && table in VERSION_ONE_EXPLICIT_TABLES) return
    val sourceTable =
        when {
            legacyTableExists(table) -> table
            table == "speed_dial" && legacyTableExists("speed_dial_item") -> "speed_dial_item"
            else -> return
        }
    val legacyColumns = tableColumns("legacy", sourceTable)
    val sharedColumns = tableColumns("main", table).intersect(legacyColumns).toMutableSet()
    val needsSpeedDialIndex = table == "speed_dial" && "index" !in legacyColumns
    if (needsSpeedDialIndex) {
        val count =
            prepare("SELECT COUNT(*) FROM legacy.${sourceTable.sqlIdentifier()}").use {
                check(it.step()) { "Legacy database query returned no result" }
                it.getLong(0)
            }
        check(count <= LEGACY_SPEED_DIAL_MAX_ROWS) { "Legacy speed dial contains too many rows" }
        sharedColumns.remove("index")
    }
    val needsCachedAudioKind = table == "cached_audio" && "kind" !in legacyColumns
    val suppliedColumns =
        buildSet {
            if (needsSpeedDialIndex) add("index")
            if (needsCachedAudioKind) add("kind")
        }
    val missingRequired = requiredColumns(table) - legacyColumns - suppliedColumns
    check(missingRequired.isEmpty()) { "Legacy $table data is missing required columns: ${missingRequired.joinToString()}" }
    if (sharedColumns.isEmpty() && suppliedColumns.isEmpty()) return
    val columns = sharedColumns.map { it.sqlIdentifier() }
    val targetColumns =
        buildList {
            addAll(columns)
            if (needsSpeedDialIndex) add("\"index\"")
            if (needsCachedAudioKind) add("\"kind\"")
        }.joinToString(",")
    val sourceColumns =
        buildList {
            addAll(columns)
            if (needsSpeedDialIndex) add(legacySpeedDialIndexExpression(sourceTable))
            if (needsCachedAudioKind) add("'CACHE'")
        }.joinToString(",")
    execSQL(
        "INSERT INTO main.${table.sqlIdentifier()} ($targetColumns) " +
            "SELECT $sourceColumns FROM ${legacyTableSourceExpression(sourceTable, needsSpeedDialIndex)}",
    )
}

private fun SQLiteConnection.userTables(schema: String): List<String> =
    prepare(
        "SELECT name FROM $schema.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table','android_metadata')",
    ).use { statement ->
        buildList { while (statement.step()) add(statement.getText(0)) }
    }

private fun SQLiteConnection.legacyTableExists(table: String): Boolean =
    prepare("SELECT 1 FROM legacy.sqlite_master WHERE type='table' AND name=?").use {
        it.bindText(1, table)
        it.step()
    }

private fun SQLiteConnection.tableColumns(
    schema: String,
    table: String,
): LinkedHashSet<String> =
    prepare("PRAGMA $schema.table_info(${table.sqlIdentifier()})").use { statement ->
        buildList { while (statement.step()) add(statement.getText(1)) }.toCollection(linkedSetOf())
    }

private fun SQLiteConnection.requiredColumns(table: String): Set<String> =
    prepare("PRAGMA main.table_info(${table.sqlIdentifier()})").use { statement ->
        buildSet {
            while (statement.step()) {
                if (statement.getLong(3) != 0L && statement.isNull(4)) add(statement.getText(1))
            }
        }
    }

private fun SQLiteConnection.importVersionOne() {
    execSQL(
        """
        INSERT INTO main.song (id,title,duration,liked,totalPlayTime,inLibrary,isDownloaded)
        SELECT id,title,duration,liked,0,create_date,CASE WHEN download_state != 0 THEN 1 ELSE 0 END FROM legacy.song
        """.trimIndent(),
    )
    execSQL("INSERT INTO main.artist (id,name,lastUpdateTime) SELECT CAST(id AS TEXT),name,0 FROM legacy.artist")
    execSQL("INSERT INTO main.playlist (id,name) SELECT CAST(playlistId AS TEXT),name FROM legacy.playlist")
    execSQL(
        "INSERT INTO main.song_artist_map (songId,artistId,position) SELECT id,CAST(artistId AS TEXT),0 FROM legacy.song WHERE artistId IS NOT NULL",
    )
    execSQL(
        """
        INSERT INTO main.playlist_song_map (id,playlistId,songId,position)
        SELECT id,CAST(playlistId AS TEXT),songId,idInPlaylist FROM legacy.playlist_song
        """.trimIndent(),
    )
}
