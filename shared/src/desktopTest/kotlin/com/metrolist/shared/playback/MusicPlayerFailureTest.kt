package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.InetSocketAddress
import javax.sound.sampled.LineUnavailableException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MusicPlayerFailureTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun unavailableOutputRetainsTheTrackWithoutReportingOrRefetchingMedia() =
        runBlocking {
            val unavailable = LineUnavailableException()
            val player = MusicPlayer(rendererFactory = { _, _ -> throw unavailable })
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            try {
                val song = SongItem("audio-output", "Audio output", null, emptyList(), null)
                val fixture = File(assertNotNull(javaClass.getResource("/ffmpeg/opus-251.webm")).toURI())
                val resource = PlaybackResource(song.id, fixture.path, emptyMap(), null, null)
                assertFalse(player.playPaused(song, resource))
                val failure = withTimeout(5_000L) { failures.receive() }
                assertSame(unavailable, failure.cause)
                assertEquals(PlaybackFailureCategory.RENDERER, failure.category)
                assertFalse(failure.isMediaSourceFailure)
                assertFalse(failure.isReportable)
                assertFalse(failure.shouldExcludePlaybackProfile)
                assertSame(song, player.currentSong.value)
                assertTrue(PlaybackRecoveryPolicy.canRecover(failure))
            } finally {
                player.release()
                collector.cancelAndJoin()
                failures.close()
            }
        }

    @Test
    fun rejectedHttpPreparationEmitsOneFailureWithTheOriginalStatus() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body(byteArrayOf().toResponseBody())
                            .build()
                    }.build()
            val player = MusicPlayer(mediaHttpClient = client)
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            try {
                val song = SongItem("rejected", "Rejected", null, emptyList(), null)
                val resource =
                    PlaybackResource(
                        videoId = song.id,
                        url = "https://media.example.test/videoplayback",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                        contentLengthBytes = 1_024L,
                    )

                assertFalse(player.playPaused(song, resource))
                val failure = withTimeout(5_000L) { failures.receive() }
                assertEquals("audio_prepare", failure.stage)
                assertEquals(403, failure.httpStatusCode, failure.cause?.stackTraceToString())
                assertEquals(403, assertNotNull(failure.cause).findPlaybackHttpStatusCode())
                assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, failure.category)
                assertEquals(player.currentPlaybackInstanceId, failure.playbackInstanceId)
                assertNull(player.loadFailure.value)
                assertNull(withTimeoutOrNull(250L) { failures.receive() }, "One rejected source must start recovery only once")

                var prepareFailure: Throwable? = null
                assertFalse(
                    player.playRecovered(
                        song = song,
                        resource = resource,
                        positionMs = 0L,
                        resumePlayback = false,
                        onPrepareFailure = { prepareFailure = it },
                    ),
                )
                assertEquals(403, assertNotNull(prepareFailure).findPlaybackHttpStatusCode())
                assertSame(resource, player.currentPlaybackResource.value)
                assertNull(withTimeoutOrNull(250L) { failures.receive() }, "A rejected candidate belongs to the active recovery")

                player.invalidateActiveStreamForNetworkChange()
                assertNull(withTimeoutOrNull(250L) { failures.receive() }, "A route change must not duplicate the preparation failure")
                player.invalidateActiveStreamForNetworkChange(forceRecovery = true)
                val networkFailure = withTimeout(5_000L) { failures.receive() }
                assertEquals("network_change", networkFailure.stage)
                assertEquals(PlaybackFailureCategory.NETWORK, networkFailure.category)
                assertEquals(player.currentPlaybackInstanceId, networkFailure.playbackInstanceId)
            } finally {
                player.release()
                collector.cancelAndJoin()
                failures.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }

    @Test
    fun failedHlsPreparationWaitsForRecoveryBeforeTerminalReporting() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/audio.m3u8") { exchange ->
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
            server.start()
            val player = MusicPlayer()
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            try {
                val song = SongItem("rejected-hls", "Rejected HLS", null, emptyList(), null)
                val resource =
                    PlaybackResource(
                        videoId = song.id,
                        url = "http://127.0.0.1:${server.address.port}/audio.m3u8",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                        mimeType = "application/x-mpegURL",
                    )

                assertFalse(player.playPaused(song, resource))
                val failure = withTimeout(5_000L) { failures.receive() }
                assertEquals("audio_prepare", failure.stage)
                assertTrue(assertNotNull(failure.cause).message.orEmpty().contains("403 Forbidden"))
                assertNull(player.loadFailure.value)
                assertNull(withTimeoutOrNull(250L) { failures.receive() })
            } finally {
                player.release()
                collector.cancelAndJoin()
                failures.close()
                server.stop(0)
            }
        }
}
