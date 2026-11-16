package com.metrolist.shared.playback

internal fun canApplyMusicVideoSeek(
    isForeground: Boolean,
    seekInFlight: Boolean,
    itemReady: Boolean,
): Boolean = isForeground && !seekInFlight && itemReady

internal fun isMaterialMusicVideoTargetChange(
    targetDeltaMs: Long,
    expectedPlaybackAdvanceMs: Long,
    toleranceMs: Long,
): Boolean = kotlin.math.abs(targetDeltaMs - expectedPlaybackAdvanceMs) > toleranceMs
