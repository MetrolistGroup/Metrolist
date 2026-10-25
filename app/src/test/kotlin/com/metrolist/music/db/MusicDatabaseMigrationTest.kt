/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.app.Application
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MusicDatabaseMigrationTest {
    @Test
    fun `21 to 24 shortcut adds every version 24 column`() {
        assertVersion24Columns(MIGRATION_21_24, sourceVersion = 21, playlistHasThumbnail = false)
    }

    @Test
    fun `22 to 24 shortcut tolerates partially added columns`() {
        assertVersion24Columns(MIGRATION_22_24, sourceVersion = 22, playlistHasThumbnail = true)
    }

    private fun assertVersion24Columns(
        migration: Migration,
        sourceVersion: Int,
        playlistHasThumbnail: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val databaseName = "migration-$sourceVersion-to-24.db"
        context.deleteDatabase(databaseName)

        openHelper(
            context = context,
            name = databaseName,
            version = sourceVersion,
            onCreate = { db ->
                db.execSQL("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE album (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL(
                    if (playlistHasThumbnail) {
                        "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, thumbnailUrl TEXT)"
                    } else {
                        "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY)"
                    },
                )
            },
        ).use { it.writableDatabase }

        try {
            openHelper(
                context = context,
                name = databaseName,
                version = 24,
                onUpgrade = { db, _, _ -> migration.migrate(db) },
            ).use { helper ->
                val db = helper.writableDatabase
                val songColumns = db.columns("song")
                val albumColumns = db.columns("album")
                val playlistColumns = db.columns("playlist")

                assertNull(songColumns.getValue("libraryAddToken").defaultValue)
                assertNull(songColumns.getValue("libraryRemoveToken").defaultValue)
                assertEquals("true", songColumns.getValue("romanizeLyrics").defaultValue)
                assertEquals("0", songColumns.getValue("isDownloaded").defaultValue)
                assertEquals("false", songColumns.getValue("isUploaded").defaultValue)
                assertEquals("false", albumColumns.getValue("isUploaded").defaultValue)
                assertTrue("thumbnailUrl" in playlistColumns)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun openHelper(
        context: Application,
        name: String,
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = onUpgrade(db, oldVersion, newVersion)
                    },
                ).build(),
        )

    private fun SupportSQLiteDatabase.columns(tableName: String): Map<String, ColumnInfo> =
        buildMap {
            query("PRAGMA table_info('$tableName')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(nameIndex),
                        ColumnInfo(defaultValue = cursor.getString(defaultValueIndex)),
                    )
                }
            }
        }

    private data class ColumnInfo(
        val defaultValue: String?,
    )
}
