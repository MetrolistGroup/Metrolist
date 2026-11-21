package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode

internal data class DesktopMediaControls(
    val playPause: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
)

internal fun desktopMediaControls(
    hasTrack: Boolean,
    loading: Boolean,
    guest: Boolean,
    queueSize: Int,
    currentIndex: Int,
    positionMs: Long,
    isLiveStream: Boolean,
    repeatMode: RepeatMode,
    autoLoadMore: Boolean,
): DesktopMediaControls {
    if (!hasTrack || loading || guest) return DesktopMediaControls()
    return DesktopMediaControls(
        playPause = true,
        previous =
            desktopPreviousQueueIndex(currentIndex, queueSize - 1, repeatMode, isLiveStream) != null ||
                (!isLiveStream && positionMs > 0L),
        next = currentIndex + 1 < queueSize || repeatMode == RepeatMode.ALL || (repeatMode == RepeatMode.OFF && autoLoadMore),
    )
}
