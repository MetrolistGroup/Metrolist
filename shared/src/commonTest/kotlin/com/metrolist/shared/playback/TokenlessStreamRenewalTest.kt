package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.WebPoTokenHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TokenlessStreamRenewalTest {
    private val tokenless =
        PlaybackResource(
            "song",
            "https://example.test/audio",
            emptyMap(),
            null,
            null,
            clientName = "VISIONOS",
            profileId = "VISIONOS_0_1__nopo",
        )

    @Test
    fun remoteTokenlessCatalogProfilesUseRenewalAndRecoveryExclusions() {
        for (manifest in PlaybackClientCatalog.manifests) {
            val resource = tokenless.copy(profileId = "${manifest.id}__nopo")
            assertTrue(TokenlessStreamRenewal.needsRenewal(resource))
            assertTrue(resource.profileId in TokenlessStreamRenewal.recoveryExclusions(resource, 90_000L))
        }
    }

    @Test
    fun rejectedWebTokensKeepVisionStreamsButStillRenewOtherTokenlessProfiles() {
        try {
            WebPoTokenHealth.recordRejection()
            assertFalse(TokenlessStreamRenewal.needsRenewal(tokenless))
            assertTrue(TokenlessStreamRenewal.recoveryExclusions(tokenless, 90_000L).isEmpty())
            val other = tokenless.copy(profileId = "ANDROID_VR_NO_AUTH__nopo")
            assertTrue(TokenlessStreamRenewal.needsRenewal(other))
            val excluded = TokenlessStreamRenewal.recoveryExclusions(other, 90_000L)
            assertTrue("ANDROID_VR_NO_AUTH__nopo" in excluded)
            assertFalse(WebPoTokenHealth.TOKENLESS_AUDIO_PROFILE in excluded)
        } finally {
            WebPoTokenHealth.reset()
        }
        assertTrue(TokenlessStreamRenewal.needsRenewal(tokenless))
    }

    @Test
    fun failedTokenResolutionRetainsPlaybackAndTokenSuccessReplacesIt() =
        runTest {
            for (tokenAvailable in listOf(true, false)) {
                var resolutions = 0
                var replacements = 0
                val replacement = tokenless.copy(profileId = "WEB_REMIX__po")
                assertEquals(
                    tokenAvailable,
                    TokenlessStreamRenewal.renew(tokenless, { 20_000L }, { true }, resolve = { excluded ->
                        resolutions++
                        assertTrue(tokenless.profileId in excluded)
                        assertTrue("VISIONOS_SABR__nopo" in excluded)
                        if (!tokenAvailable) error("No token path")
                        replacement
                    }, replace = {
                        replacements++
                        assertSame(replacement, it)
                        true
                    }),
                )
                assertEquals(1, resolutions)
                assertEquals(if (tokenAvailable) 1 else 0, replacements)
            }
        }

    @Test
    fun trackChangeCancelsResolutionEvenBeforeTheResourceChanges() =
        runTest {
            var current = true
            var cancelled = false
            val job =
                launch {
                    assertFalse(
                        TokenlessStreamRenewal.renew(tokenless, { 20_000L }, { current }, resolve = { _ ->
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }, replace = { error("Stale replacement") }),
                    )
                }
            runCurrent()
            current = false
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(cancelled)
            job.join()
        }

    @Test
    fun renewalWaitsForMediaProgressAndRunsOncePerResource() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless)
            val positions = MutableStateFlow(0L)
            var active = true
            var renewals = 0
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { active }) {
                        renewals++
                        true
                    }
                }
            runCurrent()
            positions.value = 19_000L
            runCurrent()
            assertEquals(0, renewals)
            active = false
            positions.value = 20_000L
            runCurrent()
            assertEquals(0, renewals)
            active = true
            positions.value = 20_001L
            runCurrent()
            assertEquals(1, renewals)
            positions.value = 90_001L
            runCurrent()
            assertEquals(1, renewals)
            positions.value = 10_000L
            runCurrent()
            positions.value = 55_000L
            runCurrent()
            assertEquals(1, renewals)
            resources.value = tokenless.copy(clientName = "WEB_REMIX", profileId = "WEB_REMIX__po")
            runCurrent()
            positions.value = 200_000L
            runCurrent()
            assertEquals(1, renewals)
            job.cancelAndJoin()
            assertFalse(TokenlessStreamRenewal.needsRenewal(tokenless.copy(clientName = "local")))
            assertFalse(TokenlessStreamRenewal.needsRenewal(tokenless.copy(url = "https://example.test/live/1")))
        }

    @Test
    fun seekingBackDoesNotPostponeRenewalPastTheSessionCutoff() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless)
            val positions = MutableStateFlow(0L)
            var renewed = false
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { true }) {
                        renewed = true
                        true
                    }
                }
            runCurrent()
            positions.value = 19_000L
            runCurrent()
            positions.value = 0L
            runCurrent()
            positions.value = 1_000L
            runCurrent()
            assertTrue(renewed)
            job.cancelAndJoin()
        }

    @Test
    fun restoredPositionBeyondTheRenewalThresholdDoesNotWaitForAnotherWindow() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless.copy(durationSeconds = 580L))
            val positions = MutableStateFlow(69_000L)
            var renewals = 0
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { true }) {
                        renewals++
                        true
                    }
                }
            runCurrent()
            assertEquals(1, renewals)
            assertEquals(69_000L, positions.value)
            job.cancelAndJoin()
        }

    @Test
    fun successfulAlternativeReplacesPlaybackAndFailedPreparationKeepsIt() =
        runTest {
            for (succeeds in listOf(true, false)) {
                var resolves = 0
                val result =
                    TokenlessStreamRenewal.renew(
                        tokenless,
                        { 45_000L },
                        { true },
                        resolve = { _ ->
                            resolves++
                            tokenless.copy(clientName = "WEB_REMIX", profileId = "WEB_REMIX__po")
                        },
                        replace = { succeeds },
                    )
                assertEquals(succeeds, result)
                assertEquals(1, resolves)
            }
        }

    @Test
    fun fullyEndingTrackDoesNotRenew() =
        runTest {
            val resource = MutableStateFlow<PlaybackResource?>(tokenless.copy(durationSeconds = 60L))
            val position = MutableStateFlow(0L)
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resource, position, { true }) { error("Track ends before the cutoff") }
                }
            runCurrent()
            position.value = 45_000L
            runCurrent()
            position.value = 59_000L
            runCurrent()
            job.cancelAndJoin()
        }

    @Test
    fun changingTracksCancelsPendingRenewalAndFailuresBackOff() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless)
            val positions = MutableStateFlow(0L)
            var calls = 0
            var cancelled = false
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { true }) {
                        calls++
                        if (calls == 1) {
                            false
                        } else {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }
                    }
                }
            runCurrent()
            positions.value = 20_000L
            runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(TokenlessStreamRenewal.RETRY_DELAY_MS - 1)
            runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls)
            resources.value = null
            runCurrent()
            assertTrue(cancelled)
            job.cancelAndJoin()
        }

    @Test
    fun sameCutoffRenewalsNeverReplacePlaybackAndStopAfterTheAttemptLimit() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless.copy(durationSeconds = 580L))
            val positions = MutableStateFlow(0L)
            var resolutions = 0
            var replacements = 0
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { true }) { current ->
                        TokenlessStreamRenewal.renew(current, { positions.value }, { true }, resolve = { excluded ->
                            assertTrue("VISIONOS_0_1__nopo" in excluded)
                            assertTrue("VISIONOS_SABR__nopo" in excluded)
                            resolutions++
                            current.copy(url = "https://example.test/fresh/$resolutions", profileId = "VISIONOS_SABR__nopo")
                        }, replace = {
                            replacements++
                            positions.value = 69_000L
                            true
                        })
                    }
                }
            runCurrent()
            positions.value = 69_000L
            runCurrent()
            repeat(TokenlessStreamRenewal.MAX_ATTEMPTS) {
                advanceTimeBy(TokenlessStreamRenewal.RETRY_DELAY_MS)
                runCurrent()
            }
            assertEquals(TokenlessStreamRenewal.MAX_ATTEMPTS, resolutions)
            assertEquals(0, replacements)
            assertEquals(69_000L, positions.value)
            job.cancelAndJoin()
        }

    @Test
    fun fullLengthReplacementPassesTheCutoffWithoutAnotherRenewalOrRewind() =
        runTest {
            val resources = MutableStateFlow<PlaybackResource?>(tokenless.copy(durationSeconds = 580L))
            val positions = MutableStateFlow(0L)
            var attempts = 0
            var replacements = 0
            val job =
                launch {
                    TokenlessStreamRenewal.observe(resources, positions, { true }) { current ->
                        TokenlessStreamRenewal.renew(current, { positions.value }, { true }, resolve = { excluded ->
                            assertTrue(current.profileId in excluded)
                            attempts++
                            if (attempts == 1) {
                                current.copy(url = "https://example.test/same-cutoff")
                            } else {
                                current.copy(clientName = "WEB_REMIX", profileId = "WEB_REMIX__po")
                            }
                        }, replace = { fresh ->
                            replacements++
                            resources.value = fresh
                            true
                        })
                    }
                }
            runCurrent()
            positions.value = 69_000L
            runCurrent()
            assertEquals(0, replacements)
            advanceTimeBy(TokenlessStreamRenewal.RETRY_DELAY_MS)
            runCurrent()
            assertEquals(69_000L, positions.value)
            for (position in 70_000L..180_000L step 1_000L) {
                val cutoff = if (TokenlessStreamRenewal.needsRenewal(resources.value!!)) 70_000L else 580_000L
                positions.value = position.coerceAtMost(cutoff)
                runCurrent()
                advanceTimeBy(1_000L)
            }
            assertEquals(180_000L, positions.value)
            assertEquals("song", resources.value?.videoId)
            assertEquals(2, attempts)
            assertEquals(1, replacements)
            assertEquals("WEB_REMIX__po", resources.value?.profileId)
            job.cancelAndJoin()
        }

    @Test
    fun resolutionTimeoutRetainsPlaybackWithoutRetryingTheTokenlessManifest() =
        runTest {
            var calls = 0
            assertFalse(
                TokenlessStreamRenewal.renew(tokenless, { 45_000L }, { true }, resolve = { _ ->
                    calls++
                    delay(TokenlessStreamRenewal.RESOLVE_TIMEOUT_MS + 1)
                    error("must time out")
                }, replace = { error("unresolved") }),
            )
            assertEquals(1, calls)
            assertEquals(TokenlessStreamRenewal.RESOLVE_TIMEOUT_MS, testScheduler.currentTime)
        }

    @Test
    fun staleResultsAndCancellationNeverReplacePlayback() =
        runTest {
            var current = true
            var replaced = false
            assertFalse(
                TokenlessStreamRenewal.renew(
                    tokenless,
                    { 45_000L },
                    { current },
                    resolve = { _ ->
                        current = false
                        tokenless
                    },
                    replace = {
                        replaced = true
                        true
                    },
                ),
            )
            assertFalse(replaced)
            var calls = 0
            try {
                TokenlessStreamRenewal.renew(tokenless, { 45_000L }, { true }, resolve = { _ ->
                    calls++
                    throw CancellationException()
                }, replace = { error("cancelled") })
                error("cancellation swallowed")
            } catch (_: CancellationException) {
                assertEquals(1, calls)
            }
        }
}
