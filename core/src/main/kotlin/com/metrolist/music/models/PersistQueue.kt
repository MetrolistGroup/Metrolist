/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import java.io.Serializable

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val queueType: QueueType = QueueType.LIST,
    val queueData: QueueData? = null,
) : Serializable

sealed class QueueType : Serializable {
    object LIST : QueueType()
    object YOUTUBE : QueueType()
    object YOUTUBE_ALBUM_RADIO : QueueType()
    object LOCAL_ALBUM_RADIO : QueueType()
}

sealed class QueueData : Serializable {
    data class YouTubeData(
        val endpoint: String,
        val continuation: String? = null
    ) : QueueData()
    
    data class YouTubeAlbumRadioData(
        val playlistId: String,
        val albumSongCount: Int = 0,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false
    ) : QueueData()
    
    data class LocalAlbumRadioData(
        val albumId: String,
        val startIndex: Int = 0,
        val playlistId: String? = null,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false
    ) : QueueData()
}
