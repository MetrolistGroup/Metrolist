package com.metrolist.shared.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import java.io.File

actual fun getDatabaseBuilder(context: Any?): RoomDatabase.Builder<MusicDatabase> {
    val dbFile = context as? File ?: File(XdgUtils.getXdgDataHome(), "metrolist/metrolist.db")
    dbFile.parentFile.mkdirs()
    return getDesktopDatabaseBuilder(dbFile)
}

internal fun getDesktopDatabaseBuilder(dbFile: File): RoomDatabase.Builder<MusicDatabase> {
    checkDesktopDatabaseVersion(dbFile)
    return Room
        .databaseBuilder<MusicDatabase>(
            name = dbFile.absolutePath,
        ).setDriver(JdbcSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(
            object : RoomDatabase.Callback() {
                override suspend fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL(
                        """
                        UPDATE song SET totalPlayTime = measured.total
                        FROM (
                            SELECT songId, SUM(playTime) AS total
                            FROM event
                            WHERE playTimeKnown = 1 AND playTime > 0
                            GROUP BY songId
                        ) AS measured
                        WHERE song.id = measured.songId AND song.totalPlayTime < measured.total
                        """.trimIndent(),
                    )
                }
            },
        ).addMigrations(
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
            MIGRATION_46_47,
            MIGRATION_47_48,
            MIGRATION_48_49,
            MIGRATION_49_50,
            MIGRATION_50_51,
            MIGRATION_51_52,
            MIGRATION_52_53,
            MIGRATION_53_54,
            MIGRATION_54_55,
            MIGRATION_55_56,
            MIGRATION_56_57,
            MIGRATION_57_58,
            MIGRATION_58_59,
            MIGRATION_59_60,
            MIGRATION_60_61,
            MIGRATION_61_62,
            MIGRATION_62_63,
            MIGRATION_63_64,
            MIGRATION_64_65,
        )
}
