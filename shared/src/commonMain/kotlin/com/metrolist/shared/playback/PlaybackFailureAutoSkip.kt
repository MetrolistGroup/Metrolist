package com.metrolist.shared.playback

fun shouldSkipAfterPlaybackFailure(
    autoSkipNextOnError: Boolean,
    wantsPlayback: Boolean,
    networkConnected: Boolean,
    repeatOne: Boolean,
    category: PlaybackFailureCategory,
    originalCategory: PlaybackFailureCategory = category,
): Boolean =
    autoSkipNextOnError && wantsPlayback && networkConnected && !repeatOne &&
        !PlaybackNetworkRecovery.handles(category) && !PlaybackNetworkRecovery.handles(originalCategory)

class PlaybackFailureSkipBudget {
    private var consecutiveSkips = 0
    private var lastFailureId: Long? = null
    private var healthyRequestId: Long? = null
    private var progressRequestId: Long? = null
    private var progressStartMs = 0L

    fun tryAdvance(failureId: Long): Boolean {
        if (lastFailureId == failureId || consecutiveSkips >= 3) return false
        lastFailureId = failureId
        consecutiveSkips++
        return true
    }

    fun observeProgress(
        requestId: Long,
        positionMs: Long,
    ) {
        if (progressRequestId != requestId || positionMs < progressStartMs) {
            progressRequestId = requestId
            progressStartMs = positionMs
        }
        if (positionMs - progressStartMs >= 30_000L && healthyRequestId != requestId) {
            healthyRequestId = requestId
            consecutiveSkips = 0
        }
    }
}
