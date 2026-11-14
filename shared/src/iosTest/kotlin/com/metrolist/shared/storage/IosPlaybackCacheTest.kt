@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.storage

import com.metrolist.shared.playback.IOS_OPUS_MAX_INPUT_BYTES
import com.metrolist.shared.playback.opus.OpusMp4Remuxer
import com.metrolist.shared.playback.opus.opusWebmFixture
import com.metrolist.shared.playback.sabrAudioResource
import com.metrolist.shared.playback.sabrAudioResponse
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.cancelLoading
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.playable
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class IosPlaybackCacheTest {
    @Test
    fun optionalAacCaptureKeepsTheRejectedRequestExcludedUntilItsUrlIsRenewed() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = true })
            val client = HttpClient(MockEngine { respond(byteArrayOf()) })
            val cache = IosPlaybackCache(client, backgroundScope)
            try {
                for (status in listOf(403, 410)) {
                    val resource =
                        PlaybackResource(
                            "song",
                            "https://rr1.googlevideo.com/videoplayback?expired",
                            emptyMap(),
                            null,
                            null,
                            contentLengthBytes = 4096,
                            itag = 140,
                            mimeType = "audio/mp4",
                            codecs = "mp4a.40.2",
                            requireBoundedRange = false,
                        )
                    val failed = assertNotNull(cache.acquire(resource))
                    failed.markFailed(AudioRangeHttpStatusException(status))
                    repeat(3) { assertNull(cache.acquire(resource)) }
                    assertNull(cache.acquire(resource.copy(loudnessDb = -3.0)))
                    assertEquals(1, failed.users)
                    val renewed = resource.copy(url = "https://rr1.googlevideo.com/videoplayback?renewed")
                    val replacement = assertNotNull(cache.acquire(renewed))
                    assertTrue(replacement !== failed)
                    cache.release(failed)
                    cache.release(replacement)
                }
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rejectedOrExpiredSabrCaptureIsRetiredBeforeRenewalWhileTheOldAssetStillHasALease() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
            val client = HttpClient(MockEngine { respond(byteArrayOf()) })
            val cache = IosPlaybackCache(client, backgroundScope)
            try {
                for (status in listOf(403, 404, 410)) {
                    val resource = sabrAudioResource(20L * 1024 * 1024)
                    val expired = assertNotNull(cache.acquire(resource))
                    expired.markFailed(AudioRangeHttpStatusException(status))
                    val renewed =
                        resource.copy(
                            sabrBootstrap =
                                checkNotNull(
                                    resource.sabrBootstrap,
                                ).copy(serverAbrStreamingUrl = "https://example.test/renewed/$status"),
                        )
                    assertEquals(1, expired.users)
                    val replacement = assertNotNull(cache.acquire(renewed))
                    assertTrue(replacement !== expired)
                    assertEquals(1, replacement.users)
                    assertEquals(1, expired.users)
                    cache.release(expired)
                    cache.release(replacement)
                }
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun fragmentedSabrAacPlaysThroughTheResourceLoader() {
        assertSabrPlayback(fragmentedAacFixture(), itag = 140, durationMs = 4000)
    }

    @Test
    fun sabrOpusPlaysThroughTheResourceLoader() {
        assertSabrPlayback(opusWebmFixture(), itag = 251, durationMs = 2000)
    }

    private fun assertSabrPlayback(
        input: ByteArray,
        itag: Int,
        durationMs: Long,
    ) {
        Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        sabrAudioResponse(input, itag, durationMs),
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                },
            )
        val cache = IosPlaybackCache(client, scope)
        val player = AVPlayer()
        val label = if (itag == 140) "fragmented AAC" else "remuxed Opus control"
        try {
            val capture = assertNotNull(cache.acquire(sabrAudioResource(input.size.toLong(), itag, durationMs)))
            assertFalse(capture.mayPublish)
            val asset = capture.asset
            try {
                val keys = listOf("playable", "tracks", "duration")
                val loaded = CompletableDeferred<Unit>()
                asset.loadValuesAsynchronouslyForKeys(keys) { loaded.complete(Unit) }
                waitForNative("$label asset properties did not load") {
                    assertFalse(capture.failed, "$label capture failed: ${capture.failure}")
                    loaded.isCompleted
                }
                for (key in keys) {
                    memScoped {
                        val error = alloc<ObjCObjectVar<NSError?>>()
                        error.value = null
                        val status = asset.statusOfValueForKey(key, error.ptr)
                        assertEquals(AVKeyValueStatusLoaded, status, "$label asset key $key: ${error.value}")
                    }
                }
                assertTrue(asset.playable, "$label asset is not playable")
                assertTrue(asset.tracksWithMediaType(AVMediaTypeAudio).isNotEmpty(), "$label has no audio track")
                val expectedSeconds = durationMs / 1000.0
                val actualSeconds = CMTimeGetSeconds(asset.duration)
                assertTrue(abs(actualSeconds - expectedSeconds) < 0.1, "$label duration is $actualSeconds")

                val item = AVPlayerItem(asset = asset)
                player.replaceCurrentItemWithPlayerItem(item)

                fun checkFailure() {
                    assertFalse(capture.failed, "$label capture failed: ${capture.failure}")
                    assertFalse(item.status == AVPlayerItemStatusFailed, "$label player item failed: ${item.error}")
                }
                waitForNative("$label player item did not become readyToPlay") {
                    checkFailure()
                    item.status == AVPlayerItemStatusReadyToPlay
                }
                player.play()
                waitForNative("$label playback clock did not advance; compare the Opus control for simulator media-service failures") {
                    checkFailure()
                    CMTimeGetSeconds(player.currentTime()) > 0.2
                }
                player.pause()
                val target = expectedSeconds * 0.65
                val zero = CMTimeMakeWithSeconds(0.0, 600)
                val seekFinished = CompletableDeferred<Boolean>()
                player.seekToTime(CMTimeMakeWithSeconds(target, 600), toleranceBefore = zero, toleranceAfter = zero) {
                    seekFinished.complete(it)
                }
                waitForNative("$label seek did not finish") {
                    checkFailure()
                    seekFinished.isCompleted
                }
                assertTrue(seekFinished.getCompleted(), "$label seek was cancelled")
                val soughtSeconds = CMTimeGetSeconds(player.currentTime())
                assertTrue(abs(soughtSeconds - target) < 0.1, "$label seek to $target landed at $soughtSeconds")
                player.play()
                waitForNative("$label playback clock did not advance after seeking") {
                    checkFailure()
                    CMTimeGetSeconds(player.currentTime()) > soughtSeconds + 0.2
                }
            } finally {
                player.pause()
                player.replaceCurrentItemWithPlayerItem(null)
                asset.cancelLoading()
                cache.release(capture)
            }
        } finally {
            scope.cancel()
            cache.close()
            client.close()
            Settings.replaceSharedForTest(null)
        }
    }

    private fun waitForNative(
        message: String,
        condition: () -> Boolean,
    ) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            assertTrue(started.elapsedNow() < 15.seconds, message)
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
        }
    }

    @Test
    fun sabrCaptureUsesTheSameRemuxAndLeasesWithCachingDisabled() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
            try {
                for (itag in listOf(251, 140)) {
                    val input = if (itag == 251) opusWebmFixture() else ByteArray(4096) { it.toByte() }
                    val resource = sabrAudioResource(input.size.toLong(), itag)
                    val client =
                        HttpClient(
                            MockEngine {
                                respond(
                                    sabrAudioResponse(input, itag),
                                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                                )
                            },
                        )
                    val cache = IosPlaybackCache(client, backgroundScope)
                    try {
                        val capture = assertNotNull(cache.acquire(resource))
                        assertSame(capture, cache.acquire(resource))
                        assertFalse(capture.mayPublish)
                        val expected = if (itag == 251) OpusMp4Remuxer.remux(input) else input
                        assertEquals("audio/mp4" to expected.size.toLong(), capture.playbackInformation())
                        assertContentEquals(expected, capture.playbackBytes(0, expected.size))
                        assertNull(cache.acquireForPublication(resource))
                        cache.release(capture)
                        assertEquals(1, capture.users)
                        assertContentEquals(expected.copyOfRange(10, 100), capture.playbackBytes(10, 90))
                        cache.release(capture)
                        assertTrue(
                            NSFileManager.defaultManager
                                .contentsOfDirectoryAtPath(cache.directory, null)
                                .orEmpty()
                                .isEmpty(),
                        )
                    } finally {
                        cache.close()
                        client.close()
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedSabrCaptureDeletesItsFileAndAllowsAnotherProfile() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
            val client =
                HttpClient(MockEngine { respond(byteArrayOf(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) })
            val cache = IosPlaybackCache(client, backgroundScope)
            try {
                val resource = sabrAudioResource(20L * 1024 * 1024)
                val capture = assertNotNull(cache.acquire(resource))
                assertFailsWith<Exception> { capture.playbackInformation() }
                capture.markFailed()
                assertTrue(
                    NSFileManager.defaultManager
                        .contentsOfDirectoryAtPath(cache.directory, null)
                        .orEmpty()
                        .isEmpty(),
                )
                assertNull(cache.acquire(resource))
                assertNull(
                    cache.acquire(
                        resource.copy(
                            sabrBootstrap =
                                checkNotNull(
                                    resource.sabrBootstrap,
                                ).copy(serverAbrStreamingUrl = "https://example.test/renewed-codec-failure"),
                        ),
                    ),
                )
                val fallback = assertNotNull(cache.acquire(resource.copy(profileId = "WEB_SABR__po")))
                cache.release(fallback)
                cache.release(capture)
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun opusCaptureRemuxesWithCachingOffAndNeverPublishesRawWebm() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
            val input = opusWebmFixture()
            var requests = 0
            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            requests++
                            respond(input, headers = headersOf(HttpHeaders.ContentLength, input.size.toString()))
                        }
                    }
                }
            val cache = IosPlaybackCache(client, backgroundScope)
            val resource =
                PlaybackResource(
                    "opus",
                    "https://rr1.googlevideo.com/videoplayback",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = input.size.toLong(),
                    itag = 251,
                    mimeType = "audio/webm",
                    codecs = "opus",
                    requireBoundedRange = true,
                )
            try {
                assertNull(cache.acquire(resource.copy(contentLengthBytes = IOS_OPUS_MAX_INPUT_BYTES + 1)))
                val capture = assertNotNull(cache.acquire(resource))
                assertFalse(capture.mayPublish)
                assertEquals(0, requests)
                val mp4 = OpusMp4Remuxer.remux(input)
                assertEquals("audio/mp4" to mp4.size.toLong(), capture.playbackInformation())
                assertContentEquals(mp4, capture.playbackBytes(0, mp4.size))
                assertContentEquals(mp4.copyOfRange(8, 128), capture.playbackBytes(8, 120))
                assertEquals(1, requests)
                assertNull(cache.acquireForPublication(resource))
                cache.release(capture)
                assertTrue(
                    NSFileManager.defaultManager
                        .contentsOfDirectoryAtPath(cache.directory, null)
                        .orEmpty()
                        .isEmpty(),
                )
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOpusCaptureAllowsAacForTheSameSong() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = false })
            try {
                for (oversized in listOf(false, true)) {
                    val input = ByteArray(4096)
                    val size = if (oversized) IOS_OPUS_MAX_INPUT_BYTES + 1 else input.size.toLong()
                    val client =
                        HttpClient(
                            MockEngine { request ->
                                val responseSize = if (request.url.parameters["aac"] != null) input.size.toLong() else size
                                respond(input, headers = headersOf(HttpHeaders.ContentLength, responseSize.toString()))
                            },
                        )
                    val captureScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                    val cache = IosPlaybackCache(client, captureScope)
                    val opus =
                        PlaybackResource(
                            "song",
                            "https://rr1.googlevideo.com/videoplayback",
                            emptyMap(),
                            null,
                            null,
                            itag = 251,
                            mimeType = "audio/webm",
                            codecs = "opus",
                        )
                    try {
                        val capture = assertNotNull(cache.acquire(opus))
                        if (oversized) {
                            assertFailsWith<IllegalStateException> { capture.playbackInformation() }
                        } else {
                            assertFailsWith<IllegalArgumentException> { capture.playbackInformation() }
                        }
                        assertTrue(captureScope.isActive, "A failed remux must not cancel the capture owner")
                        capture.markFailed()
                        assertNull(cache.acquire(opus.copy(url = "${opus.url}?refreshed=1")))
                        val aac =
                            opus.copy(
                                url = "${opus.url}?aac=1",
                                itag = 140,
                                mimeType = "audio/mp4",
                                codecs = "mp4a.40.2",
                                contentLengthBytes = input.size.toLong(),
                            )
                        val recovered = assertNotNull(cache.acquire(aac))
                        assertEquals("audio/mp4" to input.size.toLong(), recovered.playbackInformation())
                        assertContentEquals(input, recovered.playbackBytes(0, input.size))
                        cache.release(capture)
                        cache.release(recovered)
                    } finally {
                        captureScope.cancel()
                        cache.close()
                        client.close()
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun requiredAudioCapturesRemainAvailableWithoutOfflineCaching() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val client = HttpClient(MockEngine { error("Asset creation must not transfer audio") })
            val cache = IosPlaybackCache(client, backgroundScope)
            val resource =
                PlaybackResource(
                    "aac",
                    "https://rr1.googlevideo.com/videoplayback",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = 4096,
                    mimeType = "audio/mp4",
                    requireBoundedRange = true,
                )
            try {
                for ((enabled, limit) in listOf(false to 1024, true to 0)) {
                    Settings.shared.songCacheEnabled = enabled
                    Settings.shared.maxSongCacheSize = limit
                    for (audio in listOf(resource, resource.copy(mimeType = "audio/webm", codecs = "opus"))) {
                        val capture = assertNotNull(cache.acquire(audio))
                        assertFalse(capture.mayPublish)
                        assertSame(capture, cache.acquire(audio))
                        assertEquals(2, capture.users)
                        cache.release(capture)
                        cache.release(capture)
                        assertTrue(
                            NSFileManager.defaultManager
                                .contentsOfDirectoryAtPath(cache.directory, null)
                                .orEmpty()
                                .isEmpty(),
                        )
                    }
                }
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun preloadsShareCaptureButOldCompletionsCannotPublishAfterClear() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = true })
            val client = HttpClient(MockEngine) { engine { addHandler { error("Asset creation must not transfer audio") } } }
            val cache = IosPlaybackCache(client, backgroundScope)
            try {
                cache.initialize()
                val resource =
                    PlaybackResource(
                        "song",
                        "https://rr1.googlevideo.com/videoplayback?cpn=first",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = 4096,
                        itag = 140,
                        mimeType = "audio/mp4",
                        codecs = "mp4a.40.2",
                    )
                val first = checkNotNull(cache.acquire(resource))
                val preloadResource = resource.copy(url = "https://rr1.googlevideo.com/videoplayback?cpn=preload")
                assertSame(first, cache.acquire(preloadResource))
                assertEquals(2, first.users)
                cache.release(first)
                cache.invalidate()
                assertNull(cache.acquireForPublication(resource))
                val nextResource = resource.copy(url = "https://rr1.googlevideo.com/videoplayback?cpn=next")
                val next = checkNotNull(cache.acquire(nextResource))
                assertNull(cache.acquireForPublication(preloadResource))
                assertSame(next, cache.acquireForPublication(nextResource))
                cache.release(next)
                cache.release(next)
                cache.release(first)
            } finally {
                cache.close()
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun admissionCreditsWrittenBytesAndReclaimsUnusedCapturesBeforeRecheckingSpace() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = true })
            val audio = ByteArray(10 * 1024)
            val client =
                HttpClient(MockEngine) {
                    engine { addHandler { respond(audio, headers = headersOf(HttpHeaders.ContentLength, audio.size.toString())) } }
                }
            var free = IosPlaybackCache.DISK_RESERVE_BYTES + 14 * 1024L
            var evictedPath: String? = null
            var changes = 0
            val files = NSFileManager.defaultManager
            val cache =
                IosPlaybackCache(client, backgroundScope, onFilesChanged = { changes++ }, availableSpace = { _ ->
                    if (evictedPath?.let { !files.fileExistsAtPath(it) } == true) {
                        free += 10 * 1024L
                        evictedPath = null
                    }
                    free
                })
            val destination = "${NSTemporaryDirectory()}/${NSUUID.UUID().UUIDString}"
            try {
                cache.initialize()
                val resource =
                    PlaybackResource(
                        "first",
                        "https://rr1.googlevideo.com/videoplayback",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = audio.size.toLong(),
                        mimeType = "audio/mp4",
                    )
                val first = assertNotNull(cache.acquire(resource))
                withContext(Dispatchers.Default) { first.copyCompleted(destination) }
                val firstPath = cache.directory + "/" + files.contentsOfDirectoryAtPath(cache.directory, null).orEmpty().single()
                cache.release(first)
                assertTrue(changes > 0)
                assertEquals(0L, first.pendingBytes)
                val secondResource = resource.copy(videoId = "second", contentLengthBytes = 5 * 1024L)
                val second = assertNotNull(cache.acquire(secondResource))
                assertSame(first, cache.acquire(resource))
                cache.release(first)
                assertEquals(1, second.users)
                evictedPath = firstPath
                free = IosPlaybackCache.DISK_RESERVE_BYTES + 6 * 1024L
                val third = assertNotNull(cache.acquire(resource.copy(videoId = "third", contentLengthBytes = 5 * 1024L)))
                assertFalse(files.fileExistsAtPath(firstPath))
                assertSame(second, cache.acquire(secondResource))
                cache.release(second)
                cache.release(second)
                cache.release(third)
            } finally {
                cache.close()
                files.removeItemAtPath(destination, null)
                client.close()
                Settings.replaceSharedForTest(null)
                Dispatchers.resetMain()
            }
        }
}
