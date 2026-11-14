package com.metrolist.shared.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.toSabrBootstrap
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playback.opus.OpusMp4Remuxer
import com.metrolist.shared.playback.opus.opusWebmFixture
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.call.body
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalSabrApi::class)
class LiveIosSabrAudioTest {
    @Test
    fun tokenlessWholeTrackAndRemux() =
        runBlocking {
            if (System.getProperty("runLiveDesktopSabrPlaybackTest") != "true") return@runBlocking
            File("/tmp/ios-sabr-fixture.mp4").writeBytes(OpusMp4Remuxer.remux(opusWebmFixture()))
            val client = createHttpClient()
            try {
                withTimeout(240_000) {
                    val innerTube = InnerTube(client)
                    innerTube.visitorData = innerTube.fetchFreshVisitorData()
                    val profile = YouTubeClient.VISIONOS_SABR
                    var selected: PlayerResponse? = null
                    for (id in listOf("fJ9rUzIMcZQ", "kJQP7kiw5Fk", "XqZsoesa55w")) {
                        val candidate = innerTube.player(profile, id).body<PlayerResponse>()
                        val audioItags =
                            candidate.streamingData
                                ?.adaptiveFormats
                                ?.filter { it.isAudio }
                                ?.map { it.itag }
                        println("[ios-sabr] video=$id status=${candidate.playabilityStatus.status} itags=$audioItags")
                        if (candidate.streamingData?.adaptiveFormats?.any { it.itag == 251 } == true) {
                            selected = candidate
                            break
                        }
                    }
                    val response = checkNotNull(selected) { "No normal-video SABR response" }
                    for (itag in listOf(251, 140)) {
                        val format = checkNotNull(response.streamingData?.adaptiveFormats?.firstOrNull { it.itag == itag && !it.isDrc })
                        val bootstrap = response.toSabrBootstrap(profile, format)
                        assertEquals(null, bootstrap.poToken)
                        val resource =
                            PlaybackResource(
                                bootstrap.videoId,
                                "sabr://audio",
                                emptyMap(),
                                null,
                                null,
                                itag = itag,
                                mimeType = bootstrap.mimeType,
                                codecs = if (itag == 251) "opus" else "mp4a.40.2",
                                contentLengthBytes = bootstrap.contentLengthBytes,
                                sabrBootstrap = bootstrap,
                            )
                        val file = File("/tmp/ios-sabr-$itag.${if (itag == 251) "webm" else "m4a"}")
                        val start = TimeSource.Monotonic.markNow()
                        var written = 0L
                        var mediaMs = 0L
                        var failure: Throwable? = null
                        file.outputStream().use { output ->
                            try {
                                fetchSabrAudio(client, resource, IOS_OPUS_MAX_INPUT_BYTES, onResponse = { response ->
                                    response.bufferedRanges.forEach { mediaMs = maxOf(mediaMs, it.startTimeMs + it.durationMs) }
                                }) { _, bytes, count ->
                                    output.write(bytes, 0, count)
                                    written += count
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                failure = error
                            }
                        }
                        println(
                            "[ios-sabr] itag=$itag bytes=$written mediaMs=$mediaMs expectedBytes=${bootstrap.contentLengthBytes} expectedMs=${bootstrap.durationMs} fetchMs=${start.elapsedNow().inWholeMilliseconds} failure=${failure?.javaClass?.simpleName}",
                        )
                        if (itag == 251 && written > 1024) {
                            try {
                                File("/tmp/ios-sabr-opus.mp4").writeBytes(OpusMp4Remuxer.remux(file.readBytes()))
                                println("[ios-sabr] remux=success")
                            } catch (error: Exception) {
                                println("[ios-sabr] remux=${error.javaClass.simpleName}")
                            }
                        }
                        assertTrue(written > 1024)
                        if (mediaMs + 2000 < bootstrap.durationMs) {
                            assertTrue(failure != null, "A truncated fetch must fail")
                        }
                    }
                }
            } finally {
                client.close()
            }
        }
}
