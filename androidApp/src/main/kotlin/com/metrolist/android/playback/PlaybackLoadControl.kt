package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal fun playbackTargetBufferBytes(hasVideo: Boolean): Int =
    if (hasVideo) {
        DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
    } else {
        2 * 1024 * 1024
    }

internal fun createPlaybackLoadControl(bufferForPlaybackMs: Int = 2_000): DefaultLoadControl = PlaybackLoadControl(bufferForPlaybackMs)

private class PlaybackLoadControl(
    bufferForPlaybackMs: Int,
) : DefaultLoadControl(
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        10_000,
        10_000,
        20_000,
        20_000,
        bufferForPlaybackMs,
        bufferForPlaybackMs,
        5_000,
        5_000,
        C.LENGTH_UNSET,
        false,
        false,
        0,
        false,
        emptyMap(),
    ) {
    private var usesAudioBursts = false
    private var loadingAudioBurst = false

    override fun onPrepared(playerId: PlayerId) {
        usesAudioBursts = false
        loadingAudioBurst = false
        super.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        val windowIndex = parameters.timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, Timeline.Period()).windowIndex
        val scheme =
            parameters.timeline
                .getWindow(windowIndex, Timeline.Window())
                .mediaItem.localConfiguration
                ?.uri
                ?.scheme
        usesAudioBursts =
            scheme in listOf("http", "https") &&
            trackSelections.any { it?.trackGroup?.type == C.TRACK_TYPE_AUDIO } &&
            trackSelections.none { it?.trackGroup?.type == C.TRACK_TYPE_VIDEO }
        loadingAudioBurst = false
        super.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (!usesAudioBursts) return super.shouldContinueLoading(parameters)
        val byteBudgetReached = getAllocator(parameters.playerId).totalBytesAllocated >= playbackTargetBufferBytes(false)
        val refillDurationUs =
            Util
                .getMediaDurationForPlayoutDuration(20_000_000L, parameters.playbackSpeed.coerceAtLeast(1f))
                .coerceAtMost(90_000_000L)
        loadingAudioBurst =
            when {
                byteBudgetReached || parameters.bufferedDurationUs >= 90_000_000L -> false
                parameters.bufferedDurationUs < refillDurationUs -> true
                else -> loadingAudioBurst
            }
        return loadingAudioBurst
    }

    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelectionArray: Array<out ExoTrackSelection?>,
    ): Int = playbackTargetBufferBytes(trackSelectionArray.any { it?.trackGroup?.type == C.TRACK_TYPE_VIDEO })
}
