package com.metrolist.desktop.storage

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCachePromotionTest {
    @Test
    fun unknownLengthAudioIsCachedAndDisablingTheCacheImmediatelyRemovesIt() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val previousLimit = SongCacheManager.getMaxCacheSizeMB()
            val id = "unknown-${UUID.randomUUID()}"
            val bytes = ByteArray(4096) { 7 }
            val secondId = "second-$id"
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/media") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Content-Type", "audio/mp4")
                    it.sendResponseHeaders(200, 0)
                    it.responseBody.write(bytes)
                }
            }
            server.createContext("/oversize") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Content-Type", "audio/mp4")
                    it.sendResponseHeaders(200, 0)
                    runCatching { it.responseBody.write(ByteArray(2 * 1024 * 1024)) }
                }
            }
            server.start()
            try {
                SongCacheManager.setMaxCacheSizeMB(1)
                val resource =
                    PlaybackResource(id, "http://127.0.0.1:${server.address.port}/media", emptyMap(), null, null, mimeType = "audio/mp4")
                SongCacheManager.cacheSong(SongItem(id, "Track", null, listOf(ArtistTiny(null, "Artist")), null), resource)
                withTimeout(5000) { SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == id } } }
                val file = File(checkNotNull(SongCacheManager.getCachedFilePath(id)))
                assertContentEquals(bytes, file.readBytes())
                SongCacheManager.cacheSong(SongItem(secondId, "Track", null, emptyList(), null), resource.copy(videoId = secondId))
                withTimeout(5000) { SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == secondId } } }
                assertTrue(SongCacheManager.isSongCached(id))
                assertTrue(
                    runCatching {
                        SongCacheManager.fetch(
                            "http://127.0.0.1:${server.address.port}/oversize",
                            "failed-$id",
                            false,
                            maxBytes =
                                1024 * 1024,
                        )
                    }.isFailure,
                )
                assertTrue(SongCacheManager.isSongCached(id))
                assertTrue(SongCacheManager.isSongCached(secondId))
                assertContentEquals(bytes, file.readBytes())
                SongCacheManager.setMaxCacheSizeMB(0)
                assertFalse(SongCacheManager.isSongCached(id))
                assertFalse(file.exists())
            } finally {
                SongCacheManager.removeSongFromCache(id)
                SongCacheManager.removeSongFromCache(secondId)
                SongCacheManager.setMaxCacheSizeMB(previousLimit)
                server.stop(0)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun deferredSourceDoesNotHoldTheCacheDownloadMutex(): Unit =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val previousLimit = SongCacheManager.getMaxCacheSizeMB()
            val id = "deferred-${UUID.randomUUID()}"
            val other = "ready-$id"
            val ready = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/media") { exchange ->
                exchange.use {
                    it.sendResponseHeaders(200, 0)
                    it.responseBody.write(ByteArray(4096) { 7 })
                }
            }
            server.start()
            try {
                SongCacheManager.setMaxCacheSizeMB(1)
                val song = SongItem(id, "Track", null, emptyList(), null)
                val resource =
                    PlaybackResource(id, "http://127.0.0.1:${server.address.port}/media", emptyMap(), null, null, mimeType = "audio/mp4")
                SongCacheManager.cacheSong(song, resource) {
                    waiting.complete(Unit)
                    ready.await()
                }
                withTimeout(5000) { waiting.await() }
                SongCacheManager.cacheSong(song.copy(id = other), resource.copy(videoId = other))
                withTimeout(5000) { SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == other } } }
                assertFalse(SongCacheManager.isSongCached(id))
                ready.complete(Unit)
                withTimeout(5000) { SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == id } } }
            } finally {
                SongCacheManager.removeSongFromCache(id)
                SongCacheManager.removeSongFromCache(other)
                SongCacheManager.setMaxCacheSizeMB(previousLimit)
                server.stop(0)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun playbackCapturePublishesCacheAndSuppliesMatchingDownloadBytes() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val id = "tee-${UUID.randomUUID()}"
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val resource =
                PlaybackResource(
                    id,
                    "https://invalid.example.test/must-not-fetch",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = bytes.size.toLong(),
                    itag = 140,
                    mimeType = "audio/mp4",
                    codecs = "mp4a.40.2",
                )
            val destination = File(SongCacheManager.cacheDir, "$id.download.tmp")
            val capture = DirectPlaybackCache.begin(resource, File(SongCacheManager.cacheDir, "playback-tee"), 8192)!!
            try {
                capture.record(0, bytes, bytes.size.toLong())
                SongCacheManager.cacheSong(SongItem(id, "Track", null, listOf(ArtistTiny("artist", "Artist")), null), resource)
                withTimeout(5000) {
                    SongCacheManager.cachedSongs.first { songs -> songs.any { it.songId == id } }
                }
                assertFalse(SongCacheManager.copyCachedAudio(resource.copy(itag = 141), destination))
                assertTrue(SongCacheManager.copyCachedAudio(resource, destination))
                assertContentEquals(bytes, destination.readBytes())
                assertTrue(SongCacheManager.isSongCached(id))
            } finally {
                capture.close()
                SongCacheManager.removeSongFromCache(id)
                destination.delete()
                Settings.replaceSharedForTest(null)
            }
        }
}
