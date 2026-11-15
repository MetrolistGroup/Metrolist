package com.metrolist.shared.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
        SET userLyrics = lyrics, lyrics = '', provider = 'Unknown', isUserSelected = 0
        WHERE provider IN ('Manual', 'METROLIST_LOCAL', 'Local')
          AND userLyrics IS NULL AND stashedUserLyrics IS NULL
        """.trimIndent(),
    )
}
