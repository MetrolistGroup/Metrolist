package com.metrolist.android.ui.player

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanvasCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 5_000)
    fun playbackDoesNotWaitForAnUnfinishedPreloadCacheSpan() {
        val directory = File(context.cacheDir, "canvas-contention-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = CanvasCache(directory, database, 1)
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val key = CanvasCache.key("song", "SPOTIFY_CANVAS")
        val factory = cache.factory(key, DataSource.Factory { ByteArrayDataSource(bytes) })
        val preloader = factory.createDataSource()
        try {
            preloader.open(DataSpec.Builder().setUri(key).build())
            assertArrayEquals(bytes, read(factory, key))
        } finally {
            preloader.close()
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }

    @Test
    fun hlsPlaylistAndSegmentsNeverShareAStandaloneVideoKey() =
        runBlocking {
            val directory = File(context.cacheDir, "canvas-hls-test-${System.nanoTime()}")
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(directory, database, 1)
            val playlist = File.createTempFile("canvas", ".m3u8", context.cacheDir)
            val segment = File.createTempFile("canvas", ".mp4", context.cacheDir)
            try {
                val manifest = "#EXTM3U\n#EXTINF:3,\n${segment.name}\n#EXT-X-ENDLIST".toByteArray()
                val video = ByteArray(1024) { (it % 251).toByte() }
                playlist.writeBytes(manifest)
                segment.writeBytes(video)
                val key = CanvasCache.key("song", "APPLE_MUSIC")
                val uri = Uri.fromFile(playlist).toString()
                val upstream = DataSource.Factory { FileDataSource() }
                val factory = cache.factory(key, upstream, identity = uri)
                assertArrayEquals(manifest, read(factory, uri))
                assertArrayEquals(video, read(factory, Uri.fromFile(segment).toString()))
                assertArrayEquals(manifest, read(factory, uri))
                assertNull(cache.find(key))
                assertNull(cache.preload(key, upstream, uri))
            } finally {
                cache.release()
                SimpleCache.delete(directory, database)
                database.close()
                playlist.delete()
                segment.delete()
            }
        }

    @Test
    fun completeVideoSurvivesRestartWithoutOpeningUpstream() {
        val directory = File(context.cacheDir, "canvas-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        val key = CanvasCache.key("song", "youtube", "720")
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        var cache = CanvasCache(directory, database, 1)
        try {
            val factory = cache.factory(key, DataSource.Factory { ByteArrayDataSource(bytes) }, "format", "video/mp4", 12000)
            read(factory, key, length = 1024)
            assertNull(cache.find(key))
            assertArrayEquals(bytes, read(factory, key))
            assertNotNull(cache.find(key))
            cache.release()
            cache = CanvasCache(directory, database, 1)
            val entry = requireNotNull(cache.find(key))
            assertEquals("video/mp4", entry.mimeType)
            assertEquals(12000L, entry.durationMs)
            assertArrayEquals(bytes, read(cache.factory(entry.url, null), entry.url))
            val forbidden =
                DataSource.Factory {
                    object : DataSource by ByteArrayDataSource(bytes) {
                        override fun open(dataSpec: DataSpec): Long = error("A fully cached Canvas must not open the network")
                    }
                }
            assertArrayEquals(bytes, read(cache.factory(key, forbidden, "format"), key))
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }

    @Test
    fun sparseFullVideosAreNotOfflineReadyAndEvictionDisableAndClearAreImmediate() {
        val directory = File(context.cacheDir, "canvas-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = CanvasCache(directory, database, 2)
        try {
            val bytes = ByteArray(768 * 1024) { 7 }
            val first = CanvasCache.key("first", "spotify")
            val second = CanvasCache.key("second", "spotify")
            val factory = cache.factory(first, DataSource.Factory { ByteArrayDataSource(bytes) })
            read(factory, first, length = 1024)
            read(factory, first, position = (bytes.size - 1024).toLong())
            assertNull(cache.find(first))
            read(factory, first)
            read(cache.factory(second, DataSource.Factory { ByteArrayDataSource(bytes) }), second)
            assertNotNull(cache.find(first))
            assertNotNull(cache.find(second))
            cache.setLimit(1)
            assertTrue(cache.sizeBytes() <= 1024 * 1024)
            assertNull(cache.find(first))
            assertNotNull(cache.find(second))
            cache.setLimit(0)
            assertEquals(0L, cache.sizeBytes())
            assertNull(cache.find(second))
            read(cache.factory(second, DataSource.Factory { ByteArrayDataSource(bytes) }), second)
            assertEquals(0L, cache.sizeBytes())
            cache.setLimit(1)
            read(cache.factory(second, DataSource.Factory { ByteArrayDataSource(bytes) }), second)
            cache.clear()
            assertEquals(0L, cache.sizeBytes())
            assertNull(cache.find(second))
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }

    @Test
    fun changedStreamCannotReuseBytesFromAnIncompleteOldFormat() {
        val directory = File(context.cacheDir, "canvas-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = CanvasCache(directory, database, 1)
        try {
            val key = CanvasCache.key("song", "youtube")
            val oldFactory = cache.factory(key, DataSource.Factory { ByteArrayDataSource(ByteArray(4096) { 1 }) }, "old")
            read(oldFactory, key, length = 1024)
            val replacement = ByteArray(2048) { 2 }
            assertArrayEquals(replacement, read(cache.factory(key, DataSource.Factory { ByteArrayDataSource(replacement) }, "new"), key))
            val cachedReplacement = requireNotNull(cache.find(key)).url
            read(oldFactory, key)
            assertArrayEquals(replacement, read(cache.factory(cachedReplacement, null), cachedReplacement))
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }

    @Test
    fun preloadingWarmsOfflineReadsAndStopsAtTheCacheBudget() =
        runBlocking {
            val directory = File(context.cacheDir, "canvas-preload-test-${System.nanoTime()}")
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(directory, database, 1)
            val key = CanvasCache.key("next", "spotify")
            val bytes = ByteArray(64 * 1024) { it.toByte() }
            try {
                val entry =
                    requireNotNull(cache.preload(key, DataSource.Factory { ByteArrayDataSource(bytes) }, "https://canvas.invalid/next"))
                assertArrayEquals(bytes, read(cache.factory(entry.url, null), entry.url))
                val forbidden = DataSource.Factory { error("A cached preload must not open upstream") }
                assertNotNull(cache.preload(key, forbidden, "https://canvas.invalid/next"))
                cache.clear()
                var bytesRead = 0
                val large =
                    DataSource.Factory {
                        val source = ByteArrayDataSource(ByteArray(2 * 1024 * 1024))
                        object : DataSource by source {
                            override fun read(
                                buffer: ByteArray,
                                offset: Int,
                                length: Int,
                            ): Int =
                                source.read(buffer, offset, length).also {
                                    if (it > 0) bytesRead += it
                                }
                        }
                    }
                assertNull(cache.preload(key, large, "https://canvas.invalid/large"))
                assertEquals(1024 * 1024, bytesRead)
                cache.setLimit(0)
                assertNull(cache.preload(key, forbidden, "https://canvas.invalid/disabled"))
            } finally {
                cache.release()
                SimpleCache.delete(directory, database)
                database.close()
            }
        }

    private fun read(
        factory: DataSource.Factory,
        key: String,
        position: Long = 0,
        length: Long = -1,
    ): ByteArray {
        val source = factory.createDataSource()
        return try {
            source.open(
                DataSpec
                    .Builder()
                    .setUri(Uri.parse(key))
                    .setPosition(position)
                    .setLength(length)
                    .build(),
            )
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }
}
