package com.metrolist.android.playback

import com.metrolist.shared.playback.PlaybackRecoveryBudget
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T : Any> withQueuePlaybackResolveDeadline(
    deadlineAtMs: Long,
    nowMs: () -> Long,
    block: suspend () -> T,
): T? {
    val remainingMs = deadlineAtMs - nowMs()
    if (remainingMs <= 0L) return null
    return withTimeoutOrNull(remainingMs) { block() }
}

internal fun queuePlaybackResolveBudget(
    nowMs: Long,
    clientOverrideId: String?,
): PlaybackRecoveryBudget {
    val budget = PlaybackRecoveryBudget.starting(nowMs)
    // Samsung can spend 30 seconds waiting for pre-roll before preparation starts.
    return if (clientOverrideId == "TVHTML5_SAMSUNG") budget.copy(deadlineAtMs = nowMs + 55_000L) else budget
}
