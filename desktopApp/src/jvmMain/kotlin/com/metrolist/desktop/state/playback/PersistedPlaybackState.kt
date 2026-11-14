package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.StoredQueueSnapshotSerializer
import kotlinx.serialization.Serializable

@Serializable
data class PersistedPlaybackState(
    @Serializable(with = StoredQueueSnapshotSerializer::class)
    val queue: MusicQueueSnapshot,
    val positionMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val revision: String? = null,
)
