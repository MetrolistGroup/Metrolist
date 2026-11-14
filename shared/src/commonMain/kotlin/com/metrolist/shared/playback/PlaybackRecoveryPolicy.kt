package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackResource

enum class PlaybackRecoveryAction {
    NONE,
    RETRY_SAME_URL,
    REFRESH_URL,
    INVALIDATE_NETWORK,
    REFRESH_TOKEN,
    RETRY_CLIENT,
    RELOAD_PLAYER_CONFIG,
    REBUILD_RENDERER,
}

data class PlaybackRecoveryBudget(
    val requestsRemaining: Int,
    val attemptsRemaining: Int,
    val deadlineAtMs: Long,
    val lastFailurePositionMs: Long? = null,
    val sameUrlRetried: Boolean = false,
    val urlRefreshesRemaining: Int = 2,
) {
    fun canStart(nowMs: Long): Boolean = requestsRemaining > 0 && attemptsRemaining > 0 && nowMs < deadlineAtMs

    fun forPlaybackFailure(
        positionMs: Long,
        nowMs: Long,
        category: PlaybackFailureCategory = PlaybackFailureCategory.UNKNOWN,
    ): PlaybackRecoveryBudget {
        if (PlaybackRecoveryPolicy.requiresFreshUrl(category) && urlRefreshesRemaining > 0) {
            return copy(
                requestsRemaining = maxOf(requestsRemaining, urlRefreshesRemaining),
                attemptsRemaining = maxOf(attemptsRemaining, urlRefreshesRemaining),
                deadlineAtMs = nowMs + 30_000L,
                lastFailurePositionMs = positionMs,
            )
        }
        val progressed = lastFailurePositionMs?.let { positionMs - it >= 30_000L } == true
        return (if (progressed) starting(nowMs) else this).copy(
            lastFailurePositionMs = positionMs,
            urlRefreshesRemaining = urlRefreshesRemaining,
        )
    }

    fun consumeRequest(): PlaybackRecoveryBudget = copy(requestsRemaining = (requestsRemaining - 1).coerceAtLeast(0))

    fun consumeAttempt(): PlaybackRecoveryBudget = copy(attemptsRemaining = (attemptsRemaining - 1).coerceAtLeast(0))

    companion object {
        fun starting(nowMs: Long): PlaybackRecoveryBudget =
            PlaybackRecoveryBudget(
                requestsRemaining = 2,
                attemptsRemaining = 3,
                deadlineAtMs = nowMs + 30_000L,
            )
    }
}

data class PlaybackRecoveryDecision(
    val action: PlaybackRecoveryAction,
    val budget: PlaybackRecoveryBudget,
)

object PlaybackRecoveryPolicy {
    fun recoveryExclusions(
        category: PlaybackFailureCategory,
        resource: PlaybackResource?,
        positionMs: Long,
    ): Set<String> {
        if (category == PlaybackFailureCategory.URL_EXPIRED) return emptySet()
        return TokenlessStreamRenewal.recoveryExclusions(resource, positionMs) +
            setOfNotNull(resource?.profileId?.takeIf { category == PlaybackFailureCategory.CDN_FORBIDDEN })
    }

    fun requiresFreshUrl(category: PlaybackFailureCategory): Boolean =
        category == PlaybackFailureCategory.CDN_FORBIDDEN || category == PlaybackFailureCategory.URL_EXPIRED

    fun canRecover(failure: PlaybackFailure): Boolean =
        failure.isMediaSourceFailure ||
            failure.category == PlaybackFailureCategory.DECODER ||
            failure.category == PlaybackFailureCategory.RENDERER

    fun <T> retainCurrentResourceAfterFailedRefresh(
        action: PlaybackRecoveryAction,
        currentResource: T?,
    ): T? = currentResource.takeIf { action == PlaybackRecoveryAction.INVALIDATE_NETWORK }

    fun decide(
        category: PlaybackFailureCategory,
        budget: PlaybackRecoveryBudget,
        nowMs: Long,
        resource: PlaybackResource? = null,
        positionMs: Long = 0L,
    ): PlaybackRecoveryDecision {
        if (!budget.canStart(nowMs) || (requiresFreshUrl(category) && budget.urlRefreshesRemaining <= 0)) {
            return PlaybackRecoveryDecision(PlaybackRecoveryAction.NONE, budget)
        }
        val timeoutAction =
            if (budget.sameUrlRetried ||
                TokenlessStreamRenewal.recoveryExclusions(resource, positionMs).isNotEmpty()
            ) {
                PlaybackRecoveryAction.RETRY_CLIENT
            } else {
                PlaybackRecoveryAction.RETRY_SAME_URL
            }
        val action =
            when (category) {
                PlaybackFailureCategory.CDN_FORBIDDEN -> PlaybackRecoveryAction.REFRESH_URL
                PlaybackFailureCategory.TOKEN, PlaybackFailureCategory.SABR_ATTESTATION -> PlaybackRecoveryAction.REFRESH_TOKEN
                PlaybackFailureCategory.URL_EXPIRED -> PlaybackRecoveryAction.REFRESH_URL
                PlaybackFailureCategory.SABR_PROTOCOL -> PlaybackRecoveryAction.RETRY_CLIENT
                PlaybackFailureCategory.TIMEOUT -> timeoutAction
                PlaybackFailureCategory.NETWORK -> PlaybackRecoveryAction.INVALIDATE_NETWORK
                PlaybackFailureCategory.RATE_LIMIT -> PlaybackRecoveryAction.NONE
                PlaybackFailureCategory.DECODER, PlaybackFailureCategory.RENDERER -> PlaybackRecoveryAction.REBUILD_RENDERER
                PlaybackFailureCategory.PLAYABILITY, PlaybackFailureCategory.UNKNOWN -> PlaybackRecoveryAction.RETRY_CLIENT
                PlaybackFailureCategory.UNAVAILABLE -> PlaybackRecoveryAction.NONE
            }
        return PlaybackRecoveryDecision(
            action,
            budget.consumeAttempt().copy(
                sameUrlRetried = budget.sameUrlRetried || action == PlaybackRecoveryAction.RETRY_SAME_URL,
                urlRefreshesRemaining = budget.urlRefreshesRemaining - if (action == PlaybackRecoveryAction.REFRESH_URL) 1 else 0,
            ),
        )
    }
}
