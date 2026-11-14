package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SabrDownloadRenewalTest {
    private val resource =
        PlaybackResource(
            "song",
            "sabr://song",
            emptyMap(),
            null,
            null,
            clientName = "VISIONOS",
            profileId = "VISIONOS_SABR__nopo",
            sabrBootstrap =
                SabrBootstrap(
                    videoId = "song",
                    serverAbrStreamingUrl = "https://example.test/sabr",
                    videoPlaybackUstreamerConfig = byteArrayOf(1),
                    clientName = 1,
                    clientVersion = "test",
                    audioFormat = SabrFormatId(251, lastModified = 123),
                    discardVideoFormat = SabrFormatId(136),
                    discardVideoHeight = 720,
                    durationMs = 135_000,
                    contentLengthBytes = 10_240,
                    mimeType = "audio/webm",
                ),
        )
    private val init = SabrChunk(ByteArray(1024), 0, true, null, null, null)
    private val media =
        (0..8).map { index ->
            SabrChunk(ByteArray(1024) { index.toByte() }, (index + 1) * 1024L, false, index, index * 15_000L, 15_000L)
        }

    @Test
    fun renewsBeforeEachCutoffAndSkipsRepeatedInitializationAndBoundarySegments() =
        runTest {
            val starts = mutableListOf<Long>()
            var renewals = 0
            val chunks =
                renewingSabrDownload(
                    resource,
                    openStream = { _, start ->
                        starts += start
                        flow {
                            emit(init)
                            for (chunk in media.filter { it.startMs!! + it.durationMs!! >= start }) {
                                check(chunk.startMs!! < start + 60_000) { "Tokenless session cut off" }
                                emit(chunk)
                            }
                        }
                    },
                    renew = {
                        renewals++
                        it.copy(url = "sabr://session-$renewals")
                    },
                ).toList()
            assertEquals(listOf(0L, 45_000L, 90_000L), starts)
            assertEquals(2, renewals)
            assertEquals(listOf(init) + media, chunks)
        }

    @Test
    fun tokenizedAndShortDownloadsRetainOneSession() =
        runTest {
            for (current in listOf(
                resource.copy(profileId = "VISIONOS_SABR__po"),
                resource.copy(sabrBootstrap = resource.sabrBootstrap!!.copy(durationMs = 30_000, contentLengthBytes = 3072)),
            )) {
                val expected = listOf(init) + media.take(if (current.sabrBootstrap!!.durationMs == 30_000L) 2 else 9)
                val actual = renewingSabrDownload(current, { _, _ -> expected.asFlow() }, { error("Unexpected renewal") }).toList()
                assertEquals(expected, actual)
            }
        }

    @Test
    fun rejectsChangedRepresentationBeforeAppendingRenewedBytes() =
        runTest {
            assertFailsWith<IncompleteAudioDownloadException> {
                renewingSabrDownload(resource, { _, _ -> (listOf(init) + media).asFlow() }, {
                    it.copy(sabrBootstrap = it.sabrBootstrap!!.copy(audioFormat = SabrFormatId(140, 123)))
                }).toList()
            }
        }

    @Test
    fun rejectsGapAfterRenewal() =
        runTest {
            assertFailsWith<IncompleteAudioDownloadException> {
                renewingSabrDownload(resource, { _, start ->
                    (listOf(init) + if (start == 0L) media else media.drop(4)).asFlow()
                }, { it }).toList()
            }
        }

    @Test
    fun cancellationDoesNotBecomeARenewalRetry() =
        runTest {
            var renewals = 0
            assertFailsWith<CancellationException> {
                renewingSabrDownload(resource, { _, _ ->
                    flow {
                        emit(init)
                        throw CancellationException("cancelled")
                    }
                }, {
                    renewals++
                    it
                }).toList()
            }
            assertEquals(0, renewals)
        }

    @Test
    fun renewalFailurePropagatesWithoutRestartingFromZero() =
        runTest {
            var opened = 0
            assertFailsWith<IllegalStateException> {
                renewingSabrDownload(resource, { _, _ ->
                    opened++
                    (listOf(init) + media).asFlow()
                }, { error("resolve failed") }).toList()
            }
            assertEquals(1, opened)
        }
}
