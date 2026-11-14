package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackNetworkChangedException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailureReportingTest {
    @BeforeTest
    @AfterTest
    fun clearEvents() {
        PlaybackEventLog.clear()
    }

    @Test
    fun recoveredFailuresStayInThePlaybackLogInsteadOfSentry() {
        val context =
            PlaybackErrorContext(
                stage = "stream_recovery",
                videoId = "video",
                clientName = "WEB_REMIX",
                profileId = "WEB_REMIX_SABR__po",
                recovered = true,
                retryCount = 2,
            )
        val failures = sabrFailures() + PlaybackNetworkChangedException()
        for (error in failures) {
            assertFalse(error.shouldReportPlaybackFailure(context))
            val event = PlaybackEventLog.events.value.last()
            assertEquals(PlaybackEventKind.RECOVERY, event.kind)
            assertEquals(PlaybackEventLevel.DEBUG, event.level)
            assertEquals(context.videoId, event.videoId)
            assertEquals(context.clientName, event.clientName)
            assertEquals(context.profileId, event.profileId)
            assertEquals(context.stage, event.details["stage"])
            assertEquals("2", event.details["retryCount"])
            assertEquals(error::class.simpleName, event.details["exceptionTypes"])
        }
        assertEquals(failures.size, PlaybackEventLog.events.value.size)
    }

    @Test
    fun terminalAttestationAndSabrTransportFailuresRemainReportable() {
        for (error in sabrFailures()) {
            for (attempts in listOf(0, 2)) {
                val context = PlaybackErrorContext("stream_retry_exhausted", recovered = false, retryCount = attempts)
                assertTrue(error.shouldReportPlaybackFailure(context))
                assertTrue(IllegalStateException("source failed", error).shouldReportPlaybackFailure(context))
            }
            assertTrue(error.shouldReportPlaybackFailure(PlaybackErrorContext("audio_prepare")))
        }
        assertTrue(PlaybackEventLog.events.value.isEmpty())
    }

    @Test
    fun cancellationAndExistingExpectedFailuresRemainFiltered() {
        val context = PlaybackErrorContext("stream_retry_exhausted", recovered = false)
        val failures =
            listOf(
                CancellationException("cancelled"),
                HttpRequestTimeoutException("https://example.test/media", 30_000L),
                StreamResolveException(StreamResolveException.Reason.UNAVAILABLE, "unavailable"),
                StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "restricted"),
            )
        for (error in failures) {
            assertFalse(error.shouldReportPlaybackFailure(context))
            assertFalse(IllegalStateException("source failed", error).shouldReportPlaybackFailure(context))
        }
        assertTrue(IllegalStateException("decoder failed").shouldReportPlaybackFailure(context))
    }

    private fun sabrFailures(): List<Throwable> =
        listOf(
            SabrProtocolException("SABR stream protection requires attestation", SabrFailureKind.ATTESTATION_REQUIRED),
            // InnerTubeX's transport wrapper discards the timeout cause, as in Sentry G9.
            SabrProtocolException("SABR transport failed: HttpRequestTimeoutException"),
        )
}
