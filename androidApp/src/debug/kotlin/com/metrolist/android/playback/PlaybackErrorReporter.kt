package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.youtube.playback.PlaybackResource

internal object PlaybackErrorReporter {
    fun setActivePlayback(
        song: SongItem,
        resource: PlaybackResource?,
    ) = Unit

    fun clearActivePlayback() = Unit

    fun report(
        error: Throwable,
        context: PlaybackErrorContext,
    ) = Unit
}
