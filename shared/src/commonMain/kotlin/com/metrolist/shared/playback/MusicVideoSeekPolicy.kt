package com.metrolist.shared.playback

internal fun canApplyMusicVideoSeek(
    isForeground: Boolean,
    seekInFlight: Boolean,
    itemReady: Boolean,
): Boolean = isForeground && !seekInFlight && itemReady

internal fun shouldResynchronizeMusicVideoOnForeground(
    wasForeground: Boolean,
    isForeground: Boolean,
): Boolean = !wasForeground && isForeground

internal fun shouldAcceptMusicVideoSeekCompletion(
    isForeground: Boolean,
    finished: Boolean,
): Boolean = isForeground && finished

internal fun isCurrentMusicVideoSeek(
    completedSeekGeneration: Long,
    currentSeekGeneration: Long,
): Boolean = completedSeekGeneration == currentSeekGeneration

internal fun shouldRevealMusicVideoAfterSeek(
    finished: Boolean,
    userSeekPending: Boolean,
): Boolean = finished && !userSeekPending

internal fun isMaterialMusicVideoTargetChange(
    targetDeltaMs: Long,
    expectedPlaybackAdvanceMs: Long,
    toleranceMs: Long,
): Boolean = kotlin.math.abs(targetDeltaMs - expectedPlaybackAdvanceMs) > toleranceMs
