package com.metrolist.android.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SabrPlaybackCacheTest {
    @Test
    fun completeCaptureSurvivesReleaseAndDoesNotRequireTheHistoryThreshold() =
        runBlocking {
            val cache = SabrPlaybackCache({ 0L }, { 4096L })
            val resource = sabrCacheTestResource()
            val bytes = ByteArray(2048) { it.toByte() }
            val source = SabrInputStream(2048L, { _, _ -> flowOf(SabrChunk(bytes, 0L, true, null, null, null)) })
            val directory = Files.createTempDirectory("sabr-publication").toFile()
            try {
                cache.start("song", resource, source, playing = true, newPlayback = true)
                assertEquals(bytes.size, source.readNBytes(bytes.size).size)
                cache.release(source)
                source.close()
                val candidate = checkNotNull(cache.candidate("song"))
                assertFalse(candidate.canDownload(30f))
                assertNotNull(candidate.capture)
                val destination = directory.resolve("audio")
                assertTrue(checkNotNull(candidate.capture).copyTo(destination))
                assertArrayEquals(bytes, destination.readBytes())
            } finally {
                source.close()
                cache.clear()
                directory.deleteRecursively()
            }
        }

    @Test
    fun incompleteFallbackCountsPlayingTimeAcrossPausesAndSourceReplacement() {
        var now = 0L
        val cache = SabrPlaybackCache({ now }, { 4096L })
        val resource = sabrCacheTestResource()
        val first = SabrInputStream(2048L, { _, _ -> flowOf() }, startImmediately = false)
        val second = SabrInputStream(2048L, { _, _ -> flowOf() }, startImmediately = false)
        try {
            cache.start("song", resource, first, playing = false, newPlayback = true)
            now = 60_000L
            cache.setPlaying(true)
            now += 15_000L
            cache.setPlaying(false)
            now += 60_000L
            cache.release(first)
            assertFalse(checkNotNull(cache.candidate("song")).canDownload(30f))
            cache.start("song", resource, second, playing = true, newPlayback = false)
            now += 14_999L
            assertFalse(checkNotNull(cache.candidate("song")).canDownload(30f))
            now++
            cache.release(second)
            val candidate = checkNotNull(cache.candidate("song"))
            assertEquals(30_000L, candidate.playedMs)
            assertTrue(candidate.canDownload(30f))
            assertNull(candidate.capture)
            cache.start("song", resource, first, playing = true, newPlayback = true)
            assertFalse(checkNotNull(cache.candidate("song")).canDownload(30f))
            cache.start("song", resource.copy(sabrBootstrap = null), null, playing = true, newPlayback = false)
            assertNull(cache.candidate("song"))
        } finally {
            first.close()
            second.close()
            cache.clear()
        }
    }

    @Test
    fun retainedSpoolsShareTheBudgetAndEvictTheOldestCapture() {
        val cache = SabrPlaybackCache({ 0L }, { 2048L })
        val sources = mutableListOf<SabrInputStream>()
        try {
            for (id in listOf("first", "second")) {
                val base = sabrCacheTestResource()
                val resource = base.copy(videoId = id, sabrBootstrap = checkNotNull(base.sabrBootstrap).copy(videoId = id))
                val source = SabrInputStream(2048L, { _, _ -> flowOf(SabrChunk(ByteArray(2048), 0L, true, null, null, null)) })
                sources += source
                source.readNBytes(2048)
                cache.start(id, resource, source, playing = false, newPlayback = true)
                cache.release(source)
                source.close()
            }
            assertNull(checkNotNull(cache.candidate("first")).capture)
            assertNotNull(checkNotNull(cache.candidate("second")).capture)
        } finally {
            sources.forEach { it.close() }
            cache.clear()
        }
    }

    @Test
    fun clearInvalidatesActiveCapturesAndBudgetRejectsOversizeRetention() {
        var budget = 1024L
        val cache = SabrPlaybackCache({ 0L }, { budget })
        val resource = sabrCacheTestResource()
        val source = SabrInputStream(2048L, { _, _ -> flowOf(SabrChunk(ByteArray(2048), 0L, true, null, null, null)) })
        try {
            source.readNBytes(2048)
            cache.start("song", resource, source, playing = true, newPlayback = true)
            assertNull(checkNotNull(cache.candidate("song")).capture)
            budget = 4096L
            assertNotNull(checkNotNull(cache.candidate("song")).capture)
            val generation = cache.generation
            cache.clear()
            assertTrue(cache.generation > generation)
            assertNull(cache.candidate("song"))
            cache.release(source)
            assertNull(cache.candidate("song"))
        } finally {
            source.close()
            cache.clear()
        }
    }
}

internal fun sabrCacheTestResource(): PlaybackResource =
    PlaybackResource(
        videoId = "song",
        url = "sabr://song",
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        contentLengthBytes = 2048L,
        itag = 251,
        mimeType = "audio/webm",
        sabrBootstrap =
            SabrBootstrap(
                videoId = "song",
                serverAbrStreamingUrl = "https://example.com/sabr",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 1,
                clientVersion = "test",
                audioFormat = SabrFormatId(251, 123L),
                discardVideoFormat = SabrFormatId(136),
                discardVideoHeight = 720,
                durationMs = 30_000L,
                contentLengthBytes = 2048L,
                mimeType = "audio/webm",
            ),
    )
