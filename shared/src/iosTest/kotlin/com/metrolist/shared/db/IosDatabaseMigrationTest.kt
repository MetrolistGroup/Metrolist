@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IosDatabaseMigrationTest {
    @Test
    fun unreadableVersionDoesNotBlockRoomOrChangeTheFile() {
        val path = "${NSTemporaryDirectory()}corrupt-${NSUUID().UUIDString}.db"
        try {
            val bytes = "unreadable legacy database".encodeToByteArray()
            FileSystem.SYSTEM.write(path.toPath()) { write(bytes) }
            getIosDatabaseBuilder(path)
            assertContentEquals(bytes, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @Test
    fun supportedVersionsAndMissingFilesReachRoomUnchanged() {
        val path = "${NSTemporaryDirectory()}supported-${NSUUID().UUIDString}.db"
        try {
            getIosDatabaseBuilder(path)
            assertFalse(FileSystem.SYSTEM.exists(path.toPath()))
            listOf(0, DATABASE_VERSION - 1, DATABASE_VERSION).forEach { version ->
                BundledSQLiteDriver().open(path).use { it.execSQL("PRAGMA user_version = $version") }
                val before = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
                getIosDatabaseBuilder(path)
                assertContentEquals(before, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
            }
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @Test
    fun newerSchemaIsRejectedWithoutChangingTheFile() {
        val path = "${NSTemporaryDirectory()}downgrade-${NSUUID().UUIDString}.db"
        try {
            BundledSQLiteDriver().open(path).use {
                it.execSQL("CREATE TABLE library (title TEXT)")
                it.execSQL("INSERT INTO library VALUES ('Keep my music')")
                it.execSQL("PRAGMA user_version = ${DATABASE_VERSION + 1}")
            }
            val before = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
            val error = assertFailsWith<DatabaseDowngradeException> { getIosDatabaseBuilder(path) }
            assertEquals(DATABASE_VERSION + 1, error.databaseVersion)
            assertContentEquals(before, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @Test
    fun everyExportedSchemaRetainsLibraryAndHistory() =
        runTest {
            for (version in DATABASE_VERSION downTo 36) {
                val path = "${NSTemporaryDirectory()}migration-${NSUUID().UUIDString}.db"
                try {
                    createHistoricalLibrary(path, version, BundledSQLiteDriver())
                    val upgraded = getIosDatabaseBuilder(path).build()
                    try {
                        assertEquals(
                            "Keep my music",
                            upgraded
                                .dao()
                                .getSongById("song")
                                ?.song
                                ?.title,
                            "version $version",
                        )
                        assertEquals(1, upgraded.dao().eventCount().first())
                        assertEquals(
                            listOf("song"),
                            upgraded
                                .dao()
                                .playlistSongs("playlist")
                                .first()
                                .map { it.song.id },
                        )
                        assertEquals(425, upgraded.dao().cachedLyrics("song")?.offsetMs)
                        assertEquals(listOf(historicalSpeedDialItem(version)), upgraded.speedDialDao().getAllOnce())
                    } finally {
                        upgraded.close()
                    }
                    BundledSQLiteDriver().open(path).use { connection ->
                        connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                        connection.prepare("PRAGMA user_version").use {
                            check(it.step())
                            assertEquals(DATABASE_VERSION.toLong(), it.getLong(0))
                        }
                    }
                } finally {
                    listOf(path, "$path-wal", "$path-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
                }
            }
        }
}
