package com.metrolist.desktop.state.playback

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
