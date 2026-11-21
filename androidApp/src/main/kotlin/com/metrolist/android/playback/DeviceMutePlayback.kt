package com.metrolist.android.playback

import androidx.media3.common.Player

internal fun playbackActiveForDeviceMute(
    isPlaying: Boolean,
    wantsPlayback: Boolean,
    playbackState: Int,
    hasSong: Boolean,
    isLocalPlayback: () -> Boolean,
): Boolean = isPlaying || (wantsPlayback && playbackState == Player.STATE_BUFFERING && hasSong && isLocalPlayback())
