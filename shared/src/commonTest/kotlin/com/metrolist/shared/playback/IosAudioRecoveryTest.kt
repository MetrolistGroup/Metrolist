package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAudioRecoveryTest {
    @Test
    fun urlFailureKeepsTheCaptureExcludedUntilTheRequestChanges() {
        val sabr = sabrAudioResource()
        val direct = sabr.copy(url = "https://example.test/audio", sabrBootstrap = null, profileId = "WEB_REMIX__po")
        for (status in listOf(403, 404, 410)) {
            val failure =
                object : Exception(), PlaybackHttpStatusCarrier {
                    override val playbackHttpStatusCode = status
                }
            assertFalse(direct.canRetireIosCaptureFor(direct, failure))
            assertFalse(direct.canRetireIosCaptureFor(direct.copy(loudnessDb = -3.0), failure))
            assertTrue(direct.canRetireIosCaptureFor(direct.copy(url = "https://example.test/renewed"), failure))
            assertTrue(direct.canRetireIosCaptureFor(direct.copy(headers = mapOf("Authorization" to "renewed")), failure))
            assertFalse(sabr.canRetireIosCaptureFor(sabr.copy(loudnessDb = -3.0), failure))
            assertTrue(
                sabr.canRetireIosCaptureFor(
                    sabr.copy(
                        sabrBootstrap = checkNotNull(sabr.sabrBootstrap).copy(serverAbrStreamingUrl = "https://example.test/renewed"),
                    ),
                    failure,
                ),
            )
        }
        assertFalse(direct.canRetireIosCaptureFor(direct.copy(url = "https://example.test/renewed"), IllegalStateException("codec failed")))
    }

    @Test
    fun expiredDirectAndSabrUrlsRenewTheSameProfileWithoutForcingAac() {
        val sabr = sabrAudioResource()
        for (resource in listOf(sabr, sabr.copy(sabrBootstrap = null, profileId = "WEB_REMIX__po"))) {
            for (status in listOf(404, 410)) {
                val recovery = IosAudioRecovery()
                val expired =
                    object : Exception(), PlaybackHttpStatusCarrier {
                        override val playbackHttpStatusCode = status
                    }
                val failure = IosAudioCaptureFailure(resource, true, expired)
                recovery.select(resource.videoId)
                assertFalse(failure.shouldExcludeProfile)
                repeat(2) {
                    assertTrue(recovery.retry(failure))
                    assertTrue(recovery.excludedProfiles(resource.videoId).isEmpty())
                    assertFalse(recovery.forceAac(resource.videoId))
                }
                assertFalse(recovery.retry(failure))
            }
        }
    }

    @Test
    fun directHttpFailuresExcludeProfilesTwiceWithoutSpendingTheAacFallback() {
        val recovery = IosAudioRecovery()
        val resource = sabrAudioResource().copy(sabrBootstrap = null, profileId = "WEB_REMIX__po")
        val forbidden =
            object : Exception(), PlaybackHttpStatusCarrier {
                override val playbackHttpStatusCode = 403
            }
        recovery.select(resource.videoId)
        val failure = IosAudioCaptureFailure(resource, true, forbidden)
        assertTrue(failure.shouldExcludeProfile)
        assertTrue(recovery.retry(failure))
        assertFalse(recovery.retry(failure))
        assertFalse(recovery.forceAac(resource.videoId))
        assertEquals(setOf(resource.profileId), recovery.excludedProfiles(resource.videoId))
        assertTrue(recovery.retry(failure.copy(resource = resource.copy(profileId = "TVHTML5_SIMPLY__po"))))
        assertFalse(recovery.retry(failure.copy(resource = resource.copy(profileId = "WEB_CREATOR__po"))))
        assertTrue(recovery.retry(failure.copy(cause = null)))
        assertTrue(recovery.forceAac(resource.videoId))
    }

    @Test
    fun sabrFailuresExcludeEachProfileOnceAndDirectCaptureStillRetriesAacOnce() {
        val recovery = IosAudioRecovery()
        val sabr = sabrAudioResource()
        recovery.select(sabr.videoId)
        val failure = IosAudioCaptureFailure(sabr, false, SabrProtocolException("Rejected", SabrFailureKind.ATTESTATION_REQUIRED))
        assertEquals(PlaybackFailureCategory.SABR_ATTESTATION, failure.category)
        assertTrue(recovery.retry(failure))
        assertFalse(recovery.retry(failure))
        assertFalse(recovery.forceAac(sabr.videoId))
        assertEquals(setOf(sabr.profileId), recovery.excludedProfiles(sabr.videoId))
        assertTrue(recovery.retry(failure.copy(resource = sabr.copy(profileId = "WEB_SABR__po"))))
        val direct = failure.copy(resource = sabr.copy(sabrBootstrap = null))
        assertTrue(recovery.retry(direct))
        assertTrue(recovery.forceAac(sabr.videoId))
        assertFalse(recovery.retry(direct))
        recovery.select("next")
        assertFalse(recovery.forceAac("next"))
        assertTrue(recovery.excludedProfiles("next").isEmpty())
        assertFalse(recovery.retry(failure))
    }
}
