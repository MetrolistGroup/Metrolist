package com.metrolist.android.listentogether

import com.metrolist.shared.listentogether.ListenTogetherTrackResolver
import com.metrolist.shared.listentogether.toSongItem
import com.metrolist.shared.model.SongItem

internal fun androidListenTogetherTrackResolver(
    metadata: suspend (String) -> SongItem?,
    fallbackArtist: suspend () -> String,
): ListenTogetherTrackResolver =
    ListenTogetherTrackResolver { track ->
        metadata(track.id) ?: track.toSongItem(
            fallbackArtist = fallbackArtist(),
            omitNonPositiveDuration = true,
        )
    }
