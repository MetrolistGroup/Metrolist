package com.metrolist.android.playback

import com.metrolist.shared.listentogether.ListenTogetherConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun roomNeedsPlaybackService(
    inRoom: Boolean,
    requestPending: Boolean,
    connection: ListenTogetherConnectionState,
): Boolean =
    inRoom || requestPending || connection == ListenTogetherConnectionState.CONNECTING ||
        connection == ListenTogetherConnectionState.RECONNECTING

internal fun shouldKeepPlaybackServiceInForeground(
    roomForegroundRequired: Boolean,
    startInForegroundRequired: Boolean,
): Boolean = roomForegroundRequired || startInForegroundRequired

internal enum class RoomForegroundAction { NONE, PROMOTE, RELEASE_AND_REFRESH, REFRESH }

/**
 * Tracks the foreground claim a Listen Together room holds outside Media3. Media3 cannot demote
 * a startForeground it never issued, so a direct promotion is released before Media3 refreshes
 * its notification when the room demand ends.
 */
internal class RoomForegroundOwner {
    private var demandActive = false
    private var directPromotionHeld = false

    val holdsDirectPromotion: Boolean get() = directPromotionHeld

    fun onDemandChanged(required: Boolean): RoomForegroundAction {
        if (required == demandActive) return RoomForegroundAction.NONE
        demandActive = required
        if (required) return RoomForegroundAction.PROMOTE
        if (directPromotionHeld) {
            directPromotionHeld = false
            return RoomForegroundAction.RELEASE_AND_REFRESH
        }
        return RoomForegroundAction.REFRESH
    }

    fun onDirectPromotionStarted() {
        directPromotionHeld = true
    }

    fun onPromotionDenied() {
        directPromotionHeld = false
    }
}

internal suspend fun observePlaybackServiceLifetime(
    taskRemoved: Flow<Boolean>,
    playbackRequired: Flow<Boolean>,
    stopOnTaskClear: Flow<Boolean>,
    stop: suspend () -> Unit,
) {
    combine(taskRemoved, playbackRequired, stopOnTaskClear) { removed, required, stopOnClear ->
        (removed && (stopOnClear || !required)) to stopOnClear
    }.distinctUntilChanged().collectLatest { (shouldStop, stopOnClear) ->
        if (shouldStop) {
            // Allow focus recovery or a notification command to resume before releasing the player.
            if (!stopOnClear) delay(60_000)
            stop()
        }
    }
}
