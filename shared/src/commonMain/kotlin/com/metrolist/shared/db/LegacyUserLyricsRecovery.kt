package com.metrolist.shared.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// v13 did not record edits of provider lyrics, so protect every surviving cache entry once.
internal val PROTECT_LEGACY_LYRICS_SQL =
    """
    UPDATE lyrics SET isLegacyProtected = 1
    WHERE trim(lyrics) != '' AND lyrics NOT IN ('LYRICS_NOT_FOUND', 'LYRICS_NOT_FOUND_V2')
      AND provider NOT IN ('LYRICS_NOT_FOUND', 'LYRICS_NOT_FOUND_V2')
    """.trimIndent()

internal object LegacyUserLyricsRecovery : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) {
        connection.recoverLegacyUserLyrics()
    }
}

internal fun SQLiteConnection.recoverLegacyUserLyrics() {
    // Run on open too, because schema 65 installs can still contain v13 Manual rows.
    execSQL(
        """
        UPDATE lyrics
        SET userLyrics = lyrics, lyrics = '', provider = 'Unknown', isUserSelected = 0, isLegacyProtected = 0
        WHERE provider IN ('Manual', 'METROLIST_LOCAL', 'Local')
          AND userLyrics IS NULL AND stashedUserLyrics IS NULL
        """.trimIndent(),
    )
}
