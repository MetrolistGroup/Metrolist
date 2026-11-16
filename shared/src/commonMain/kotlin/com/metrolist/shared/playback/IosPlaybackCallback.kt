package com.metrolist.shared.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal const val IOS_PLAYBACK_CALLBACK_TIMEOUT_MS = 5_000L

internal suspend fun <T> awaitIosPlaybackCallback(
    timeoutMs: Long = IOS_PLAYBACK_CALLBACK_TIMEOUT_MS,
    begin: (complete: (T) -> Unit) -> Unit,
): T? =
    withTimeoutOrNull(timeoutMs) {
        val result = CompletableDeferred<T>()
        begin { result.complete(it) }
        result.await()
    }
