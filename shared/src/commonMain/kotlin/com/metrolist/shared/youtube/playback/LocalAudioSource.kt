package com.metrolist.shared.youtube.playback

import com.metrolist.shared.model.SongItem

fun interface LocalAudioSource {
    suspend fun resolve(
        videoId: String,
        song: SongItem?,
    ): PlaybackResource?
}
