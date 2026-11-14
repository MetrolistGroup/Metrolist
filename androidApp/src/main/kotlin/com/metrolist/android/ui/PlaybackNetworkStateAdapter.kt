package com.metrolist.android.ui

import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore

object PlaybackNetworkStateAdapter {
    @Volatile
    var latest: PlaybackNetworkState = PlaybackNetworkState(scope = "", connected = true)
        private set

    fun publish(
        scope: String,
        connected: Boolean,
        isCellular: Boolean = false,
    ) {
        latest = PlaybackNetworkState(scope = scope, connected = connected, isCellular = isCellular)
        PlaybackNetworkStateStore.publish(latest)
    }
}
