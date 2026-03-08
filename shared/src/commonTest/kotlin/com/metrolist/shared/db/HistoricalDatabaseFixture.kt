package com.metrolist.shared.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.metrolist.shared.db.entities.SpeedDialItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun createHistoricalLibrary(
    path: String,
    version: Int,
    driver: SQLiteDriver,
) {
    val schema =
        Json
            .parseToJsonElement(historicalRoomSchema(version))
            .jsonObject
            .getValue("database")
            .jsonObject
    driver.open(path).use { connection ->
        schema.getValue("entities").jsonArray.forEach { entity ->
            val table = entity.jsonObject
            val name = table.getValue("tableName").jsonPrimitive.content
            connection.execSQL(
                table
                    .getValue("createSql")
                    .jsonPrimitive.content
                    .replace("\${TABLE_NAME}", name),
            )
            table["indices"]?.jsonArray?.forEach { index ->
                connection.execSQL(
                    index.jsonObject
                        .getValue("createSql")
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", name),
                )
            }
        }
        schema.getValue("views").jsonArray.forEach { view ->
            val name =
                view.jsonObject
                    .getValue("viewName")
                    .jsonPrimitive.content
            connection.execSQL(
                view.jsonObject
                    .getValue("createSql")
                    .jsonPrimitive.content
                    .replace("\${VIEW_NAME}", name),
            )
        }
        schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
        connection.execSQL("PRAGMA user_version = $version")
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL(
            "INSERT INTO song (id, title, duration, liked, totalPlayTime) VALUES ('song', 'Keep my music', 240, 1, 30000)",
        )
        connection.execSQL("INSERT INTO playlist (id, name) VALUES ('playlist', 'Keep my playlist')")
        connection.execSQL("INSERT INTO playlist_song_map (playlistId, songId, position) VALUES ('playlist', 'song', 0)")
        connection.execSQL("INSERT INTO event (id, songId, timestamp, playTime) VALUES (1, 'song', 1000, 30000)")
        connection.execSQL("UPDATE song SET lyricsOffset = 425 WHERE id = 'song'")
        val offsetColumn = if (version >= 45) ", offsetMs" else ""
        val offsetValue = if (version >= 45) ", 425" else ""
        connection.execSQL(
            "INSERT INTO lyrics (id, lyrics, provider, translatedLyrics$offsetColumn) " +
                "VALUES ('song', '[00:01]Saved source', 'SimpMusic', 'Saved translation'$offsetValue)",
        )
        connection.execSQL("INSERT INTO lyrics (id, lyrics, provider) VALUES ('local', 'My edit', 'METROLIST_LOCAL')")
        if (version >= 57) connection.execSQL("UPDATE lyrics SET isUserSelected = 1 WHERE id = 'local'")
        connection.execSQL(
            "INSERT INTO speed_dial (id, secondaryId, title, subtitle, thumbnailUrl, type, explicit, `index`, createDate) " +
                "VALUES ('pin', 'secondary', 'Saved pin', 'Saved artist', 'saved-thumbnail', 'SONG', 1, 4, 1234)",
        )
        if (version >= 39) {
            connection.execSQL("UPDATE speed_dial SET subtitleIds = 'artist', albumId = 'album', albumName = 'Saved album'")
        }
    }
}

internal fun historicalSpeedDialItem(version: Int): SpeedDialItem =
    SpeedDialItem(
        id = "pin",
        secondaryId = "secondary",
        title = "Saved pin",
        subtitle = "Saved artist",
        thumbnailUrl = "saved-thumbnail",
        type = "SONG",
        explicit = true,
        index = 4,
        createDate = 1234,
        subtitleIds = "artist".takeIf { version >= 39 },
        albumId = "album".takeIf { version >= 39 },
        albumName = "Saved album".takeIf { version >= 39 },
    )
