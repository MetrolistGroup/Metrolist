package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAudioQualityTest {
    @Test
    fun opusQualityIsAvailableByDefaultAcrossClientsAndQualities() =
        runTest {
            for (quality in AudioQuality.entries) assertEquals(quality, iosPlaybackAudioQuality(quality.name))
            assertEquals(AudioQuality.AUTO, iosPlaybackAudioQuality("invalid"))
            for (client in listOf("VISIONOS", "WEB_REMIX")) {
                for (quality in listOf(AudioQuality.AUTO, AudioQuality.HIGH, AudioQuality.LOW)) {
                    for (refresh in listOf(false, true)) {
                        val opus = resource("audio/webm").copy(itag = 251, codecs = "opus", clientName = client)
                        val requests = mutableListOf<Triple<AudioQuality, Boolean, Boolean>>()
                        val resolved =
                            resolveIosAudioResource(quality, refresh) { requestedQuality, isRefresh, allowHls ->
                                requests += Triple(requestedQuality, isRefresh, allowHls)
                                opus
                            }
                        assertEquals(opus, resolved)
                        assertEquals(listOf(Triple(quality, refresh, false)), requests)
                    }
                }
                val hints = ContentHints(playbackClientOverrideId = client, sabrFirst = true).withIosAudioCapabilities()
                assertEquals(client, hints.playbackClientOverrideId)
                assertTrue(hints.allowBoundedRange)
                assertTrue(hints.allowSabr)
                assertFalse(hints.sabrFirst)
                assertFalse(hints.allowHls)
            }
        }

    @Test
    fun aacRemainsPlayableAtEveryQualityAndDownloadsRequestAac() =
        runTest {
            for (quality in AudioQuality.entries) {
                for (forDownload in listOf(false, true)) {
                    val requests = mutableListOf<AudioQuality>()
                    val aac = resource("audio/mp4")
                    assertEquals(
                        aac,
                        resolveIosAudioResource(quality, forDownload = forDownload) { requestedQuality, _, allowHls ->
                            assertFalse(allowHls)
                            requests += requestedQuality
                            aac
                        },
                    )
                    val expected = if (forDownload && quality != AudioQuality.LOW) AudioQuality.MP4 else quality
                    assertEquals(listOf(expected), requests)
                }
            }
        }

    @Test
    fun clientsWithoutPlayableFormatsRetryAacBeforeAllowingHls() =
        runTest {
            for (quality in listOf(AudioQuality.LOW, AudioQuality.AUTO, AudioQuality.HIGH)) {
                for (refresh in listOf(false, true)) {
                    for (reason in listOf(
                        StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                        StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
                    )) {
                        val requests = mutableListOf<Triple<AudioQuality, Boolean, Boolean>>()
                        val aac = resource("audio/mp4")
                        val resolved =
                            resolveIosAudioResource(quality, refresh) { requestedQuality, isRefresh, allowHls ->
                                requests += Triple(requestedQuality, isRefresh, allowHls)
                                if (requestedQuality != AudioQuality.MP4) throw StreamResolveException(reason, "No format")
                                aac
                            }
                        assertEquals(aac, resolved)
                        assertEquals(listOf(Triple(quality, refresh, false), Triple(AudioQuality.MP4, refresh, false)), requests)
                    }
                }
            }
        }

    @Test
    fun captureRecoveryRequestsOnlyAacRegardlessOfQualityDuringResolveAndRefresh() =
        runTest {
            for (quality in AudioQuality.entries) {
                for (refresh in listOf(false, true)) {
                    val aac = resource("audio/mp4")
                    val requests = mutableListOf<Triple<AudioQuality, Boolean, Boolean>>()
                    val resolved =
                        resolveIosAudioResource(quality, refresh, forceAac = true) { requestedQuality, isRefresh, allowHls ->
                            requests += Triple(requestedQuality, isRefresh, allowHls)
                            aac
                        }
                    assertEquals(aac, resolved)
                    assertEquals(listOf(Triple(AudioQuality.MP4, refresh, false)), requests)
                }
            }
        }

    @Test
    fun captureRecoveryRejectsAnotherOpusResponseWithoutLooping() =
        runTest {
            val requests = mutableListOf<Pair<AudioQuality, Boolean>>()
            assertFailsWith<StreamResolveException> {
                resolveIosAudioResource(AudioQuality.HIGH, forceAac = true) { quality, _, allowHls ->
                    requests += quality to allowHls
                    resource("audio/webm").copy(codecs = "opus")
                }
            }
            assertEquals(listOf(AudioQuality.MP4 to false, AudioQuality.MP4 to true), requests)
        }

    @Test
    fun downloadsAndOversizedOpusInputsRetryAac() =
        runTest {
            for (forDownload in listOf(false, true)) {
                for (refresh in listOf(false, true)) {
                    val requests = mutableListOf<AudioQuality>()
                    val aac = resource("audio/mp4")
                    val opus = resource("audio/webm").copy(codecs = "opus", contentLengthBytes = IOS_OPUS_MAX_INPUT_BYTES + 1)
                    val resolved =
                        resolveIosAudioResource(AudioQuality.LOW, refresh, forDownload) { quality, isRefresh, allowHls ->
                            assertEquals(refresh, isRefresh)
                            assertFalse(allowHls)
                            requests += quality
                            if (quality == AudioQuality.LOW) opus else aac
                        }
                    assertEquals(aac, resolved)
                    assertEquals(listOf(AudioQuality.LOW, AudioQuality.MP4), requests)
                }
            }
            val opus = resource("audio/webm").copy(codecs = "opus")
            val requests = mutableListOf<AudioQuality>()
            resolveIosAudioResource(AudioQuality.LOW, forDownload = true) { quality, _, _ ->
                requests += quality
                if (quality == AudioQuality.LOW) opus else resource("audio/mp4")
            }
            assertEquals(listOf(AudioQuality.LOW, AudioQuality.MP4), requests)
        }

    @Test
    fun hlsRemainsTheLastPlaybackFallback() =
        runTest {
            val requests = mutableListOf<Pair<AudioQuality, Boolean>>()
            val hls = resource("application/vnd.apple.mpegurl")
            val resolved =
                resolveIosAudioResource(AudioQuality.HIGH) { quality, _, allowHls ->
                    requests += quality to allowHls
                    if (!allowHls) throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No format")
                    hls
                }
            assertEquals(hls, resolved)
            assertEquals(listOf(AudioQuality.HIGH to false, AudioQuality.MP4 to false, AudioQuality.HIGH to true), requests)
        }

    @Test
    fun lowQualityResolutionFallsBackToMp4ForResolveAndRefresh() =
        runTest {
            for (refresh in listOf(false, true)) {
                val requests = mutableListOf<Triple<AudioQuality, Boolean, Boolean>>()
                val result =
                    resolveIosAudioResource(AudioQuality.LOW, refresh) { quality, isRefresh, allowHls ->
                        requests += Triple(quality, isRefresh, allowHls)
                        resource(if (quality == AudioQuality.LOW) "audio/webm" else "audio/mp4")
                    }

                assertEquals(
                    listOf(Triple(AudioQuality.LOW, refresh, false), Triple(AudioQuality.MP4, refresh, false)),
                    requests,
                )
                assertEquals("audio/mp4", result.mimeType)
            }
        }

    @Test
    fun retriesHlsOnlyWhenNoProgressiveAudioStreamExists() =
        runTest {
            for (
            reason in
            listOf(
                StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
            )
            ) {
                val requests = mutableListOf<Boolean>()
                var firstAttempt = true
                val result =
                    resolveIosAudioResource(AudioQuality.MP4) { _, _, allowHls ->
                        requests += allowHls
                        if (firstAttempt) {
                            firstAttempt = false
                            throw StreamResolveException(reason = reason, message = "No progressive audio stream found")
                        }
                        resource("application/vnd.apple.mpegurl")
                    }

                assertEquals(listOf(false, true), requests)
                assertEquals("application/vnd.apple.mpegurl", result.mimeType)
            }
        }

    @Test
    fun compatibleLowResourceIsKeptAndResolverFailuresDoNotTriggerFallback() =
        runTest {
            val requests = mutableListOf<AudioQuality>()
            val compatible =
                resolveIosAudioResource(AudioQuality.LOW) { quality, _, allowHls ->
                    assertEquals(false, allowHls)
                    requests += quality
                    resource("audio/mp4")
                }
            assertEquals("audio/mp4", compatible.mimeType)
            assertEquals(listOf(AudioQuality.LOW), requests)

            requests.clear()
            assertFailsWith<IllegalStateException> {
                resolveIosAudioResource(AudioQuality.LOW) { quality, _, allowHls ->
                    assertEquals(false, allowHls)
                    requests += quality
                    error("resolver failed")
                }
            }
            assertEquals(listOf(AudioQuality.LOW), requests)

            requests.clear()
            assertFailsWith<StreamResolveException> {
                resolveIosAudioResource(AudioQuality.MP4) { _, _, allowHls ->
                    assertEquals(false, allowHls)
                    requests += AudioQuality.MP4
                    throw StreamResolveException(
                        reason = StreamResolveException.Reason.NETWORK,
                        message = "Network request failed",
                    )
                }
            }
            assertEquals(listOf(AudioQuality.MP4), requests)
        }

    @Test
    fun downloadsNeverRetryWithHlsDuringResolveOrRefresh() =
        runTest {
            for (refresh in listOf(false, true)) {
                val requests = mutableListOf<Triple<AudioQuality, Boolean, Boolean>>()
                assertFailsWith<StreamResolveException> {
                    resolveIosAudioResource(AudioQuality.MP4, refresh, forDownload = true) { quality, isRefresh, allowHls ->
                        requests += Triple(quality, isRefresh, allowHls)
                        throw StreamResolveException(
                            reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                            message = "No downloadable audio stream",
                        )
                    }
                }
                assertEquals(listOf(Triple(AudioQuality.MP4, refresh, false)), requests)
            }
        }

    @Test
    fun sabrFormatsAreCapturedAndRenewalsKeepDirectClientsFirst() =
        runTest {
            for (quality in AudioQuality.entries) {
                for (itag in listOf(251, 140)) {
                    val sabr = sabrAudioResource(itag = itag)
                    assertTrue(sabr.requiresIosAudioCapture)
                    assertTrue(canUseIosAudioProcessingTap(sabr, sabr.url))
                    assertEquals(
                        sabr,
                        resolveIosAudioResource(quality, refresh = true) { _, refresh, _ ->
                            assertTrue(refresh)
                            sabr
                        },
                    )
                }
            }
            val aac = sabrAudioResource(itag = 140)
            assertEquals(
                aac,
                resolveIosAudioResource(AudioQuality.HIGH, forceAac = true) { quality, _, _ ->
                    assertEquals(AudioQuality.MP4, quality)
                    aac
                },
            )
            assertFalse(ContentHints(sabrFirst = true).withIosAudioCapabilities(allowHls = true).sabrFirst)
            assertFailsWith<StreamResolveException> {
                resolveIosAudioResource(AudioQuality.AUTO, forceAac = true) { _, _, _ -> sabrAudioResource() }
            }
        }

    private fun resource(mimeType: String) =
        PlaybackResource(
            videoId = "song",
            url = "https://media.example/audio",
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            mimeType = mimeType,
        )
}
