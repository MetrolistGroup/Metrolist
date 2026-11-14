package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRecoveryPolicyTest {
    @Test
    fun expiryKeepsTheProfileEligibleEvenAtTheTokenlessCutoff() {
        val resource =
            PlaybackResource(
                "song",
                "https://example.test/expired",
                emptyMap(),
                null,
                null,
                profileId = "VISIONOS_SABR__nopo",
            )
        assertTrue(TokenlessStreamRenewal.recoveryExclusions(resource, 70_000L).isNotEmpty())
        assertTrue(PlaybackRecoveryPolicy.recoveryExclusions(PlaybackFailureCategory.URL_EXPIRED, resource, 70_000L).isEmpty())
        assertEquals(
            TokenlessStreamRenewal.recoveryExclusions(resource, 70_000L) + resource.profileId,
            PlaybackRecoveryPolicy.recoveryExclusions(PlaybackFailureCategory.CDN_FORBIDDEN, resource, 70_000L),
        )
    }

    @Test
    fun rejectedUrlsHaveTwoRefreshesEvenAfterEarlierSabrRecoveryExhaustsItsBudget() {
        var budget =
            PlaybackRecoveryBudget
                .starting(0L)
                .consumeRequest()
                .consumeRequest()
                .consumeAttempt()
                .consumeAttempt()
        for ((index, category) in listOf(PlaybackFailureCategory.CDN_FORBIDDEN, PlaybackFailureCategory.URL_EXPIRED).withIndex()) {
            val now = 60_000L + index * 60_000L
            budget = budget.forPlaybackFailure(61_519L + index * 30_000L, now, category)
            assertTrue(budget.canStart(now))
            assertEquals(now + 30_000L, budget.deadlineAtMs)
            val decision = PlaybackRecoveryPolicy.decide(category, budget, now)
            assertEquals(PlaybackRecoveryAction.REFRESH_URL, decision.action)
            budget = decision.budget.consumeRequest()
        }
        val progressed = budget.forPlaybackFailure(150_000L, 200_000L, PlaybackFailureCategory.CDN_FORBIDDEN)
        assertEquals(0, progressed.urlRefreshesRemaining)
        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.CDN_FORBIDDEN, progressed, 200_000L).action,
        )
    }

    @Test
    fun repeatedFailuresAfterSuccessfulPreparationShareTheBudgetUntilPlaybackAdvances() {
        var budget = PlaybackRecoveryBudget.starting(0L)
        repeat(3) { attempt ->
            budget = budget.forPlaybackFailure(50_000L, attempt * 1_000L)
            val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.RENDERER, budget, attempt * 1_000L)
            assertEquals(PlaybackRecoveryAction.REBUILD_RENDERER, decision.action)
            budget = decision.budget
        }
        val stalled = budget.forPlaybackFailure(50_000L, 3_000L)
        assertEquals(PlaybackRecoveryAction.NONE, PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.RENDERER, stalled, 3_000L).action)
        val healthy = budget.forPlaybackFailure(80_000L, 60_000L)
        assertEquals(3, healthy.attemptsRemaining)
        assertEquals(90_000L, healthy.deadlineAtMs)
        assertEquals(
            PlaybackRecoveryAction.REBUILD_RENDERER,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.RENDERER, healthy, 60_000L).action,
        )
        assertEquals(0, budget.forPlaybackFailure(20_000L, 60_000L).attemptsRemaining)
    }

    @Test
    fun outputFailuresReachBoundedRendererRecoveryWithoutBlamingTheSource() {
        for (category in listOf(PlaybackFailureCategory.DECODER, PlaybackFailureCategory.RENDERER)) {
            val failure = PlaybackFailure("audio_stream", "output failed", isMediaSourceFailure = false, category = category)
            assertTrue(PlaybackRecoveryPolicy.canRecover(failure))
            assertFalse(failure.shouldExcludePlaybackProfile)
            var budget = PlaybackRecoveryBudget.starting(0L)
            repeat(3) {
                val decision = PlaybackRecoveryPolicy.decide(failure.category, budget, 1L)
                assertEquals(PlaybackRecoveryAction.REBUILD_RENDERER, decision.action)
                budget = decision.budget
            }
            assertEquals(PlaybackRecoveryAction.NONE, PlaybackRecoveryPolicy.decide(failure.category, budget, 1L).action)
            assertEquals(2, budget.requestsRemaining)
        }
        assertFalse(PlaybackRecoveryPolicy.canRecover(PlaybackFailure("control", "failed", isMediaSourceFailure = false)))
    }

    @Test
    fun categoriesChooseSpecificRecoveryActions() {
        val budget = PlaybackRecoveryBudget.starting(1_000L)

        assertEquals(
            PlaybackRecoveryAction.REFRESH_URL,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.CDN_FORBIDDEN, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.SABR_ATTESTATION, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.REBUILD_RENDERER,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.DECODER, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.INVALIDATE_NETWORK,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.NETWORK, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.RETRY_SAME_URL,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.UNAVAILABLE, budget, 1_000L).action,
        )
        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.RATE_LIMIT, budget, 1_000L).action,
        )
    }

    @Test
    fun stalledReplacementRetriesTheUrlOnceThenChangesClientWithinTheBudget() {
        var budget = PlaybackRecoveryBudget.starting(1_000L)

        for (action in listOf(
            PlaybackRecoveryAction.RETRY_SAME_URL,
            PlaybackRecoveryAction.RETRY_CLIENT,
            PlaybackRecoveryAction.RETRY_CLIENT,
        )) {
            val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, budget, 1_000L)
            assertEquals(action, decision.action)
            budget = decision.budget
            if (action == PlaybackRecoveryAction.RETRY_CLIENT) budget = budget.consumeRequest()
        }
        assertEquals(0, budget.requestsRemaining)
        val progressed = budget.copy(lastFailurePositionMs = 70_000L).forPlaybackFailure(100_000L, 60_000L)
        assertEquals(
            PlaybackRecoveryAction.RETRY_SAME_URL,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, progressed, 60_000L).action,
        )

        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, budget, 1_000L).action,
        )
    }

    @Test
    fun cutoffRecoverySkipsTokenlessRetriesButPreservesOutputAndLocalRecovery() {
        val resource =
            PlaybackResource(
                "song",
                "https://example.test/audio",
                emptyMap(),
                null,
                null,
                clientName = "VISIONOS",
                profileId = "VISIONOS_SABR__nopo",
            )
        var budget = PlaybackRecoveryBudget.starting(0L)
        repeat(2) { attempt ->
            budget = budget.forPlaybackFailure(69_000L, attempt * 1_000L)
            val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, budget, attempt * 1_000L, resource, 69_000L)
            assertEquals(PlaybackRecoveryAction.RETRY_CLIENT, decision.action)
            budget = decision.budget.consumeRequest()
        }
        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, budget, 2_000L, resource, 69_000L).action,
        )
        for (other in listOf(resource.copy(clientName = "local"), resource.copy(profileId = "WEB_REMIX__po"))) {
            assertEquals(
                PlaybackRecoveryAction.RETRY_SAME_URL,
                PlaybackRecoveryPolicy
                    .decide(
                        PlaybackFailureCategory.TIMEOUT,
                        PlaybackRecoveryBudget.starting(0L),
                        0L,
                        other,
                        69_000L,
                    ).action,
            )
            assertTrue(TokenlessStreamRenewal.recoveryExclusions(other, 69_000L).isEmpty())
        }
        assertEquals(
            PlaybackRecoveryAction.REBUILD_RENDERER,
            PlaybackRecoveryPolicy
                .decide(
                    PlaybackFailureCategory.RENDERER,
                    PlaybackRecoveryBudget.starting(0L),
                    0L,
                    resource,
                    69_000L,
                ).action,
        )
        assertTrue(TokenlessStreamRenewal.recoveryExclusions(resource, 10_000L).isEmpty())
    }

    @Test
    fun failedNetworkRefreshRetainsCurrentStreamForBoundedRetry() {
        assertEquals(
            "current",
            PlaybackRecoveryPolicy.retainCurrentResourceAfterFailedRefresh(
                PlaybackRecoveryAction.INVALIDATE_NETWORK,
                "current",
            ),
        )
        assertNull(
            PlaybackRecoveryPolicy.retainCurrentResourceAfterFailedRefresh(
                PlaybackRecoveryAction.REFRESH_URL,
                "current",
            ),
        )
    }

    @Test
    fun boundedBudgetStopsAfterAttemptsOrDeadline() {
        val initial = PlaybackRecoveryBudget.starting(1_000L)
        val exhausted = initial.consumeAttempt().consumeAttempt().consumeAttempt()

        assertFalse(exhausted.canStart(1_100L))
        assertFalse(initial.canStart(31_000L))
        assertTrue(initial.canStart(30_999L))
    }

    @Test
    fun structuredHttpStatusClassifiesWithoutParsingMessages() {
        val error = TestHttpStatusException(403)

        assertEquals(403, error.findPlaybackHttpStatusCode())
        assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, PlaybackFailureClassifier.fromThrowable(error))
    }

    @Test
    fun sabrAttestationExceptionKeepsItsSpecificRecoveryCategory() {
        val error = SabrProtocolException("attestation rejected", SabrFailureKind.ATTESTATION_REQUIRED)

        assertEquals(PlaybackFailureCategory.SABR_ATTESTATION, PlaybackFailureClassifier.fromThrowable(error))
    }

    @Test
    fun authenticationAndRateLimitStatusesDoNotTriggerClientFanOut() {
        assertEquals(PlaybackFailureCategory.TOKEN, PlaybackFailureClassifier.fromHttpStatus(401))
        assertEquals(PlaybackFailureCategory.RATE_LIMIT, PlaybackFailureClassifier.fromHttpStatus(429))
    }

    private class TestHttpStatusException(
        override val playbackHttpStatusCode: Int,
    ) : IllegalStateException(),
        PlaybackHttpStatusCarrier
}
