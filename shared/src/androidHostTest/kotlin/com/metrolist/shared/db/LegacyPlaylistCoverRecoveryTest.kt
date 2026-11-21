package com.metrolist.shared.db

import android.content.ContextWrapper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LegacyPlaylistCoverRecoveryTest {
    private val packageName = "com.metrolist.music.debug"
    private val uri = "content://$packageName.FileProvider/cache/playlist_cover_crop_123.jpg"

    @Test
    fun survivingCoverMovesToPersistentStorageWithoutDeletingOriginalAndIsIdempotent() =
        fixture { connection, files, cache ->
            val source = File(cache, "playlist_cover_crop_123.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            insert(connection, uri)
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            val saved = thumbnail(connection)
            val destination = File(saved)
            assertEquals(File(files, "playlist_covers"), destination.parentFile)
            assertArrayEquals(source.readBytes(), destination.readBytes())
            connection.prepare("SELECT customThumbnailUrl FROM playlist").use {
                assertTrue(it.step())
                assertEquals(saved, it.getText(0))
            }
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(saved, thumbnail(connection))
            assertEquals(1, destination.parentFile.listFiles()!!.size)
            assertTrue(source.exists())
        }

    @Test
    fun missingCachePreservesReferenceAndCanRecoverOnRetry() =
        fixture { connection, files, cache ->
            insert(connection, uri)
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            assertFalse(File(files, "playlist_covers").exists())
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(9))
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertArrayEquals(byteArrayOf(9), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun blockedDestinationPreservesReferenceAndCanRetry() =
        fixture { connection, files, cache ->
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(4))
            val directory = File(files, "playlist_covers").apply { writeText("existing user file") }
            insert(connection, uri)
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            assertEquals("existing user file", directory.readText())
            directory.delete()
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertArrayEquals(byteArrayOf(4), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun failedDatabaseWriteRemovesNewCopyAndKeepsOriginal() =
        fixture { connection, files, cache ->
            val source = File(cache, "playlist_cover_crop_123.jpg").apply { writeBytes(byteArrayOf(4)) }
            insert(connection, uri)
            connection.execSQL("CREATE TRIGGER fail_cover BEFORE UPDATE ON playlist BEGIN SELECT RAISE(ABORT, 'probe failure'); END")
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            assertTrue(source.exists())
            assertEquals(0, File(files, "playlist_covers").listFiles()!!.size)
            connection.execSQL("DROP TRIGGER fail_cover")
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertArrayEquals(source.readBytes(), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun unrelatedReferencesAndNewCustomCoverAreNeverRewritten() =
        fixture { connection, files, cache ->
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(4))
            for (other in listOf(
                uri.replace(packageName, "other.app"),
                uri.replace("/cache/", "/cache/../"),
                uri + "?query=1",
                uri.replace("123.jpg", "%31%32%33.jpg"),
                "https://example.test/cover.jpg",
            )) {
                connection.execSQL("DELETE FROM playlist")
                insert(connection, other)
                recoverLegacyPlaylistCovers(connection, files, cache, packageName)
                assertEquals(other, thumbnail(connection))
            }
            connection.execSQL("DELETE FROM playlist")
            insert(connection, uri)
            connection.execSQL("UPDATE playlist SET customThumbnailUrl = '/user/new-cover.jpg'")
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            assertFalse(File(files, "playlist_covers").exists())
        }

    @Test
    fun symlinkOutsideCacheAndEmptyFilesAreIgnored() =
        fixture { connection, files, cache ->
            val source = File(cache, "playlist_cover_crop_123.jpg")
            source.writeBytes(byteArrayOf())
            insert(connection, uri)
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            source.delete()
            val outside = File(files, "private.jpg").apply { writeBytes(byteArrayOf(8)) }
            Files.createSymbolicLink(source.toPath(), outside.toPath())
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            assertFalse(File(files, "playlist_covers").exists())
        }

    @Test
    fun remotePlaylistIsExcludedButMatchingLegacyCustomReferenceRecovers() =
        fixture { connection, files, cache ->
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(4))
            insert(connection, uri)
            connection.execSQL("UPDATE playlist SET browseId = 'PLremote'")
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertEquals(uri, thumbnail(connection))
            connection.execSQL("UPDATE playlist SET browseId = NULL, customThumbnailUrl = thumbnailUrl")
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertArrayEquals(byteArrayOf(4), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun failureAfterSuccessfulUpdateNeverDeletesReferencedCopy() =
        fixture { connection, files, cache ->
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(7))
            insert(connection, uri)
            val failing =
                object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (sql == "SELECT changes()") error("post-write probe failure")
                        return connection.prepare(sql)
                    }
                }
            recoverLegacyPlaylistCovers(failing, files, cache, packageName)
            assertArrayEquals(byteArrayOf(7), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun unreadableSourcePreservesReferenceAndRetriesAfterPermissionRestored() =
        fixture { connection, files, cache ->
            val source = File(cache, "playlist_cover_crop_123.jpg").apply { writeBytes(byteArrayOf(6)) }
            insert(connection, uri)
            assertTrue(source.setReadable(false, false))
            try {
                assertFalse(source.canRead())
                recoverLegacyPlaylistCovers(connection, files, cache, packageName)
                assertEquals(uri, thumbnail(connection))
            } finally {
                assertTrue(source.setReadable(true, true))
            }
            recoverLegacyPlaylistCovers(connection, files, cache, packageName)
            assertArrayEquals(byteArrayOf(6), File(thumbnail(connection)).readBytes())
        }

    @Test
    fun roomOpenCallbackInvokesRecoveryUsingApplicationDirectories() =
        fixture { connection, files, cache ->
            File(cache, "playlist_cover_crop_123.jpg").writeBytes(byteArrayOf(5))
            insert(connection, uri)
            val context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getFilesDir(): File = files

                    override fun getCacheDir(): File = cache

                    override fun getPackageName(): String = this@LegacyPlaylistCoverRecoveryTest.packageName
                }
            runBlocking { LegacyPlaylistCoverRecovery(context).onOpen(connection) }
            assertArrayEquals(byteArrayOf(5), File(thumbnail(connection)).readBytes())
        }

    private fun fixture(block: (SQLiteConnection, File, File) -> Unit) {
        val root = Files.createTempDirectory("cover-recovery").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            AndroidSQLiteDriver().open(File(root, "probe.db").path).use { connection ->
                connection.execSQL("CREATE TABLE playlist(id TEXT PRIMARY KEY, thumbnailUrl TEXT, customThumbnailUrl TEXT, browseId TEXT)")
                block(connection, files, cache)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun insert(
        connection: SQLiteConnection,
        uri: String,
    ) {
        connection.prepare("INSERT INTO playlist(id, thumbnailUrl) VALUES ('local', ?)").use {
            it.bindText(1, uri)
            it.step()
        }
    }

    private fun thumbnail(connection: SQLiteConnection): String =
        connection.prepare("SELECT thumbnailUrl FROM playlist").use {
            assertTrue(it.step())
            it.getText(0)
        }
}
