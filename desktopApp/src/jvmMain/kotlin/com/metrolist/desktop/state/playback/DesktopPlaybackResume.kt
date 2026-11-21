package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode

internal fun completeDesktopPlaybackResume(
    positionMs: Long?,
    prepared: Boolean,
    currentTransition: Boolean,
    desiredPlaying: () -> Boolean,
    seek: (Long) -> Unit,
    setPlaying: (Boolean) -> Unit,
): Boolean {
    if (positionMs == null || !prepared || !currentTransition) return false
    seek(positionMs)
    setPlaying(desiredPlaying())
    return true
}

internal fun desktopPreviousRestartPosition(
    pendingResumePosition: Pair<String, Long>?,
    positionMs: Long,
    isLive: Boolean,
    hasPrevious: Boolean,
): Pair<String, Long>? = pendingResumePosition?.takeIf { !isLive && (positionMs > 3_000L || !hasPrevious) }?.let { it.first to 0L }

internal fun desktopPreviousQueueIndex(
    currentIndex: Int,
    lastIndex: Int,
    repeatMode: RepeatMode,
    isLive: Boolean,
): Int? =
    when {
        currentIndex > 0 -> currentIndex - 1
        currentIndex == 0 && lastIndex > 0 && repeatMode == RepeatMode.ALL && !isLive -> lastIndex
        else -> null
    }
