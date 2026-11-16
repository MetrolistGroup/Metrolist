package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.time.Instant

internal fun iosPreparedPlaybackRejection(
    prepared: PlaybackResource,
    requested: PlaybackResource?,
    url: String,
    headers: Map<String, String>,
    ready: Boolean,
    failed: Boolean,
    tapInstalled: Boolean,
    now: Instant,
): String? =
    when {
        requested != null && prepared.videoId != requested.videoId -> "resource-mismatch"
        prepared.url != url || prepared.headers != headers -> "resource-mismatch"
        prepared.expiresAt?.let { it <= now } == true -> "resource-expired"
        failed -> "item-failed"
        !ready -> "item-loading"
        canUseIosAudioProcessingTap(prepared, url) && !tapInstalled -> "tap-not-installed"
        else -> null
    }
