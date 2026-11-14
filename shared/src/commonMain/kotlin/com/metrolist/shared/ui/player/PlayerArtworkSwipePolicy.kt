package com.metrolist.shared.ui.player

internal fun shouldEnableArtworkSwipe(
    enabled: Boolean,
    isTv: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean = enabled && !isTv && !touchExplorationEnabled

internal fun artworkSwipeThresholdPx(
    widthPx: Float,
    sensitivity: Float,
): Float = widthPx * (0.42f - sensitivity.coerceIn(0f, 100f) * 0.0024f)

fun shouldShowMusicVideoSurface(
    isVideoModeActive: Boolean,
    isVideoViewerVisible: Boolean,
    hasVideo: Boolean,
    presentationAllowed: Boolean = true,
): Boolean = presentationAllowed && isVideoModeActive && isVideoViewerVisible && hasVideo

internal fun shouldShowPlayerLoadingScreen(
    isPlaybackLoading: Boolean,
    hasVisibleVideoTransitionIndicator: Boolean,
): Boolean = isPlaybackLoading && !hasVisibleVideoTransitionIndicator
