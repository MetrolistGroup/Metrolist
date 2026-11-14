package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrFailureKind

data class PlaybackFailure(
    val stage: String,
    val message: String,
    val cause: Throwable? = null,
    val sourceName: String? = null,
    val code: String? = null,
    val httpStatusCode: Int? = cause?.findPlaybackHttpStatusCode(),
    val isReportable: Boolean = true,
    val videoId: String? = null,
    val playbackInstanceId: Long? = null,
    val sabrFailureKind: SabrFailureKind? = null,
    val playbackPositionMs: Long = 0L,
    val resumePlayback: Boolean = true,
    val isMediaSourceFailure: Boolean = true,
    val shouldExcludePlaybackProfile: Boolean = false,
    val category: PlaybackFailureCategory = PlaybackFailureClassifier.fromThrowable(cause),
)
