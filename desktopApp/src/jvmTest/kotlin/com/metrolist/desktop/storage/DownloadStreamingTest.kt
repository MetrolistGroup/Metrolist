package com.metrolist.desktop.storage

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DownloadStreamingTest {
    @Test
    fun unknownLengthCacheFitsActualSizeAndFailurePreservesCachedSongs() =
        runBlocking {
            val prefix = "cache-test-${UUID.randomUUID()}"
            val oldId = "$prefix-old"
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val sizes = mapOf("old" to 512 * 1024, "small" to 32 * 1024, "failed" to 10, "oversized" to 2 * 1024 * 1024)
            for ((name, size) in sizes) {
                server.createContext("/$name") { exchange ->
                    exchange.use {
                        it.responseHeaders.add("Content-Type", "audio/mp4")
                        it.sendResponseHeaders(200, 0)
                        runCatching { it.responseBody.write(ByteArray(size) { 7 }) }
                    }
                }
            }
            server.start()
            val previousLimit = SongCacheManager.getMaxCacheSizeMB()
            val url = "http://127.0.0.1:${server.address.port}"
            try {
                SongCacheManager.setMaxCacheSizeMB(1)
                SongCacheManager.cacheSong(
                    SongItem(oldId, "Track", null, listOf(ArtistTiny(null, "Artist")), null),
                    PlaybackResource(oldId, "$url/old", emptyMap(), null, null, mimeType = "audio/mp4"),
                )
                withTimeout(10_000) {
                    SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == oldId } }
                }
                val index = SongCacheManager.cacheDir.resolve("cache_index_v1.json")
                val savedIndex = index.readText()
                val published = SongCacheManager.cachedSongs.value
                repeat(20) { assertNotNull(SongCacheManager.getCachedPlaybackResource(oldId, song = null)) }
                assertEquals(savedIndex, index.readText())
                assertSame(published, SongCacheManager.cachedSongs.value)

                assertNull(SongCacheManager.fetch("$url/failed", "$prefix-failed", false, maxBytes = 1024 * 1024))
                assertFailsWith<IOException> {
                    SongCacheManager.fetch("$url/oversized", "$prefix-oversized", false, maxBytes = 1024 * 1024)
                }
                val small = assertNotNull(SongCacheManager.fetch("$url/small", "$prefix-small", false, maxBytes = 1024 * 1024))
                assertEquals(32 * 1024L, small.length())
                assertTrue(SongCacheManager.isSongCached(oldId))
                assertEquals(512 * 1024L, File(assertNotNull(SongCacheManager.getCachedFilePath(oldId))).length())
            } finally {
                SongCacheManager.removeSongFromCache(oldId)
                SongCacheManager.setMaxCacheSizeMB(previousLimit)
                server.stop(0)
                SongCacheManager.cacheDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.startsWith(prefix) }
                    .forEach { it.delete() }
            }
        }

    @Test
    fun downloadsWriteToDiskBeforeTheResponseCompletes() =
        verifyStreaming(DownloadManager.getDefaultDownloadDir()) { url, name ->
            DownloadManager.downloadUrlSingle(url, name, true, emptyMap(), null)?.let(::File)
        }

    @Test
    fun automaticCacheWritesToDiskBeforeTheResponseCompletes() =
        verifyStreaming(SongCacheManager.cacheDir) { url, name ->
            SongCacheManager.fetch(url, name, true)
        }

    private fun verifyStreaming(
        directory: File,
        download: suspend (String, String) -> File?,
    ) = runBlocking {
        val name = "streaming-test-${UUID.randomUUID()}"
        val firstBytesSent = CountDownLatch(1)
        val finishBody = CountDownLatch(1)
        val block = ByteArray(64 * 1024) { 7 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            exchange.use {
                it.responseHeaders.add("Content-Type", "image/png")
                it.sendResponseHeaders(200, block.size * 2L)
                it.responseBody.write(block)
                it.responseBody.flush()
                firstBytesSent.countDown()
                finishBody.await()
                it.responseBody.write(block)
            }
        }
        server.start()
        val job = async(Dispatchers.IO) { download("http://127.0.0.1:${server.address.port}/media", name) }
        try {
            assertTrue(firstBytesSent.await(5, TimeUnit.SECONDS))
            // Polls instead of a WatchService: Windows reports neither size nor modify events for a file that is still open.
            withTimeout(10_000.milliseconds) {
                while (directory.listFiles().orEmpty().none { it.name.startsWith(name) && it.length() > 0 }) {
                    delay(10.milliseconds)
                }
            }
            finishBody.countDown()
            val result = assertNotNull(withTimeout(5_000.milliseconds) { job.await() })
            assertEquals(block.size * 2L, result.length())
            assertTrue(result.readBytes().all { it == 7.toByte() })
        } finally {
            finishBody.countDown()
            job.cancelAndJoin()
            server.stop(0)
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith(name) }
                .forEach { it.delete() }
        }
    }
}
