package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog

internal class IosPlaybackRequestLog(
    var generation: Long,
    val videoId: String?,
) {
    fun event(
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            message,
            videoId = videoId,
            details = details + ("requestGeneration" to generation.toString()),
        )
    }
}
