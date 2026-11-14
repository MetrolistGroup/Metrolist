package com.metrolist.android.ui.utils

import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun playSong(
    song: SongItem,
    playerConnection: PlayerConnection?,
    scope: CoroutineScope,
    songs: List<SongItem> = emptyList(),
    startVideo: Boolean = false,
) {
    playerConnection?.let { pc ->
        scope.launch {
            val index = songs.indexOfFirst { it === song }.takeIf { it >= 0 } ?: songs.indexOfFirst { it.id == song.id }
            if (index >= 0) {
                pc.playQueue(songs, startIndex = index, finite = true, startVideo = startVideo)
            } else {
                pc.playSong(song, "", startVideo = startVideo)
            }
        }
    }
}

fun playQueue(
    songs: List<SongItem>,
    index: Int,
    playerConnection: PlayerConnection?,
    queueTitle: String? = null,
    shuffleModeEnabled: Boolean? = null,
) {
    playerConnection?.playQueue(songs, index, queueTitle, shuffleModeEnabled = shuffleModeEnabled)
}
