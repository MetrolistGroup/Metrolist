package com.metrolist.shared.playlist

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import coil3.PlatformContext
import coil3.request.Options
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosPlaylistCoverStorageTest {
    @Test
    fun replacementAndRemovalOnlyDeletePreviouslyCommittedCover() =
        runTest {
            val directory = NSTemporaryDirectory() + NSUUID().UUIDString
            val files = NSFileManager.defaultManager
            var saved: String? = null
            try {
                storeIosPlaylistCover(byteArrayOf(1), null, directory) { saved = it }
                val first = assertNotNull(saved)
                val firstPath = assertNotNull(resolveIosPlaylistCover(first, directory))
                assertTrue(files.fileExistsAtPath(firstPath))
                storeIosPlaylistCover(byteArrayOf(2), first, directory) { next ->
                    assertTrue(files.fileExistsAtPath(firstPath))
                    assertTrue(files.fileExistsAtPath(assertNotNull(resolveIosPlaylistCover(assertNotNull(next), directory))))
                    saved = next
                }
                val second = assertNotNull(saved)
                val secondPath = assertNotNull(resolveIosPlaylistCover(second, directory))
                assertNotEquals(first, second)
                assertFalse(files.fileExistsAtPath(firstPath))
                assertTrue(files.fileExistsAtPath(secondPath))
                storeIosPlaylistCover(null, second, directory) {
                    assertEquals(null, it)
                    assertTrue(files.fileExistsAtPath(secondPath))
                    saved = it
                }
                assertEquals(null, saved)
                assertFalse(files.fileExistsAtPath(secondPath))
            } finally {
                files.removeItemAtPath(directory, error = null)
            }
        }

    @Test
    fun failedDatabaseCommitRollsBackNewFileAndKeepsOldCover() =
        runTest {
            val directory = NSTemporaryDirectory() + NSUUID().UUIDString
            val files = NSFileManager.defaultManager
            var saved: String? = null
            try {
                storeIosPlaylistCover(byteArrayOf(1), null, directory) { saved = it }
                val first = assertNotNull(saved)
                val firstPath = assertNotNull(resolveIosPlaylistCover(first, directory))
                var staged: String? = null
                val replacement =
                    runCatching {
                        storeIosPlaylistCover(byteArrayOf(2), first, directory) {
                            staged = it
                            error("Database unavailable")
                        }
                    }
                assertTrue(replacement.isFailure)
                assertTrue(files.fileExistsAtPath(firstPath))
                assertFalse(files.fileExistsAtPath(assertNotNull(resolveIosPlaylistCover(assertNotNull(staged), directory))))
                assertEquals(first, saved)
                val removal =
                    runCatching {
                        storeIosPlaylistCover(null, first, directory) { error("Database unavailable") }
                    }
                assertTrue(removal.isFailure)
                assertTrue(files.fileExistsAtPath(firstPath))
            } finally {
                files.removeItemAtPath(directory, error = null)
            }
        }

    @Test
    fun cleanupDoesNotDeleteFilesOutsideCoverDirectory() =
        runTest {
            val root = NSTemporaryDirectory() + NSUUID().UUIDString
            val files = NSFileManager.defaultManager
            var foreign: String? = null
            try {
                storeIosPlaylistCover(byteArrayOf(1), null, "$root/other") {
                    foreign =
                        resolveIosPlaylistCover(assertNotNull(it), "$root/other")
                }
                storeIosPlaylistCover(byteArrayOf(2), foreign, "$root/covers") { }
                assertTrue(files.fileExistsAtPath(assertNotNull(foreign)))
                val empty =
                    runCatching {
                        storeIosPlaylistCover(byteArrayOf(), foreign, "$root/covers") { error("Must not save empty images") }
                    }
                assertTrue(empty.isFailure)
                assertTrue(files.fileExistsAtPath(assertNotNull(foreign)))
            } finally {
                files.removeItemAtPath(root, error = null)
            }
        }

    @Test
    fun relocatedCoversMigrateAndCleanupUsesTheCurrentContainer() =
        runTest {
            val root = NSTemporaryDirectory() + NSUUID().UUIDString
            val oldDirectory = "$root/Containers/Data/Application/${NSUUID().UUIDString}/Library/Application Support/playlist_covers"
            val newDirectory = "$root/relocated/playlist_covers"
            val files = NSFileManager.defaultManager
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            var saved: String? = null
            try {
                storeIosPlaylistCover(byteArrayOf(1), null, oldDirectory) { saved = it }
                val identifier = assertNotNull(saved)
                assertTrue(identifier.startsWith("playlist_covers/"))
                val oldPath = assertNotNull(resolveIosPlaylistCover(identifier, oldDirectory))
                val playlist = PlaylistEntity(id = "local", name = "Local", isLocal = true, customThumbnailUrl = oldPath)
                val remote = PlaylistEntity(id = "remote", name = "Remote", customThumbnailUrl = "https://example.com/cover.jpg")
                val foreign = playlist.copy(id = "foreign", customThumbnailUrl = "$root/other/${identifier.substringAfterLast('/')}")
                listOf(playlist, remote, foreign).forEach { database.dao().upsert(it) }
                val storedPlaylist = assertNotNull(database.dao().findPlaylistEntity(playlist.id))
                val storedRemote = assertNotNull(database.dao().findPlaylistEntity(remote.id))
                val storedForeign = assertNotNull(database.dao().findPlaylistEntity(foreign.id))
                assertTrue(
                    files.createDirectoryAtPath("$root/relocated", withIntermediateDirectories = true, attributes = null, error = null),
                )
                assertTrue(files.moveItemAtPath(oldDirectory, toPath = newDirectory, error = null))
                val currentPath = assertNotNull(resolveIosPlaylistCover(oldPath, newDirectory))
                assertTrue(files.fileExistsAtPath(currentPath))
                val mapper = IosPlaylistCoverMapper(newDirectory)
                assertEquals(currentPath, mapper.map(oldPath, Options(PlatformContext.INSTANCE))?.path)
                assertEquals(currentPath, mapper.map(identifier, Options(PlatformContext.INSTANCE))?.path)
                assertNull(mapper.map(assertNotNull(remote.customThumbnailUrl), Options(PlatformContext.INSTANCE)))
                assertNull(mapper.map(assertNotNull(foreign.customThumbnailUrl), Options(PlatformContext.INSTANCE)))
                assertNull(resolveIosPlaylistCover("playlist_covers/../${identifier.substringAfterLast('/')}", newDirectory))
                migrateIosPlaylistCovers(database, newDirectory)
                migrateIosPlaylistCovers(database, newDirectory)
                assertEquals(storedPlaylist.copy(customThumbnailUrl = identifier), database.dao().findPlaylistEntity(playlist.id))
                assertEquals(storedRemote, database.dao().findPlaylistEntity(remote.id))
                assertEquals(storedForeign, database.dao().findPlaylistEntity(foreign.id))
                storeIosPlaylistCover(byteArrayOf(2), oldPath, newDirectory) { saved = it }
                assertFalse(files.fileExistsAtPath(currentPath))
                val replacementPath = assertNotNull(resolveIosPlaylistCover(assertNotNull(saved), newDirectory))
                assertTrue(files.fileExistsAtPath(replacementPath))
                storeIosPlaylistCover(null, saved, newDirectory) { saved = it }
                assertNull(saved)
                assertFalse(files.fileExistsAtPath(replacementPath))
            } finally {
                database.close()
                files.removeItemAtPath(root, error = null)
            }
        }
}
