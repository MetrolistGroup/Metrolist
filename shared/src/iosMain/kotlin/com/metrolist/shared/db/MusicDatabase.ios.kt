package com.metrolist.shared.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal fun iosDatabaseFilePath(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
    val metrolistDir = "${requireNotNull(paths.firstOrNull() as? String)}/metrolist"
    val fileManager = NSFileManager.defaultManager
    check(
        fileManager.fileExistsAtPath(metrolistDir) ||
            fileManager.createDirectoryAtPath(metrolistDir, withIntermediateDirectories = true, attributes = null, error = null),
    )
    return "$metrolistDir/metrolist.db"
}

@OptIn(ExperimentalForeignApi::class)
actual fun getDatabaseBuilder(context: Any?): RoomDatabase.Builder<MusicDatabase> =
    getIosDatabaseBuilder(context as? String ?: iosDatabaseFilePath())

internal fun getIosDatabaseBuilder(path: String): RoomDatabase.Builder<MusicDatabase> {
    if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
        val version =
            runCatching {
                BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { connection ->
                    connection.prepare("PRAGMA user_version").use { statement ->
                        if (statement.step()) statement.getLong(0).toInt() else null
                    }
                }
            }.getOrNull()
        version?.let(::checkDatabaseVersion)
    }
    return Room
        .databaseBuilder<MusicDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(
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
