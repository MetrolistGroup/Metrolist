/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

actual fun getDatabaseBuilder(context: Any?): RoomDatabase.Builder<MusicDatabase> {
    val appContext = context as? Context ?: throw IllegalArgumentException("Context is required for Android")
    val dbFile = appContext.getDatabasePath("song.db")
    checkAndroidDatabaseVersion(appContext)
    return Room
        .databaseBuilder<MusicDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
        ).setDriver(AndroidSQLiteDriver())
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

fun checkAndroidDatabaseVersion(context: Context) {
    val dbFile = context.getDatabasePath("song.db")
    if (dbFile.isFile) {
        val version =
            runCatching {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) { }.use { it.version }
            }.getOrNull()
        version?.let(::checkDatabaseVersion)
    }
}
