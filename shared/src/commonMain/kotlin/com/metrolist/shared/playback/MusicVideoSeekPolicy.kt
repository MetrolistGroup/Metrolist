package com.metrolist.shared.playback

internal fun canApplyMusicVideoSeek(
    isForeground: Boolean,
    seekInFlight: Boolean,
    itemReady: Boolean,
): Boolean = isForeground && !seekInFlight && itemReady
