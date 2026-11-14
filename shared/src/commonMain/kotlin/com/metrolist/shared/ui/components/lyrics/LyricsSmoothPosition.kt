/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import com.metrolist.shared.ui.components.playbackAnimationDelayMillis
import com.metrolist.shared.ui.components.playbackAnimationOrigin
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Composable
fun rememberSmoothLyricsPosition(
    currentPosition: Long,
    isPlaying: Boolean,
    songId: String? = null,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
): Long =
    rememberSmoothLyricsPositionState(
        currentPosition,
        isPlaying,
        songId,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
    ).longValue

@Composable
fun rememberSmoothLyricsPosition(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    songId: String? = null,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
): Long =
    rememberSmoothLyricsPositionState(
        positionFlow = positionFlow,
        isPlaying = isPlaying,
        songId = songId,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
    ).longValue

@Composable
fun rememberSmoothLyricsPositionState(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    songId: String? = null,
    frameInterval: Duration = 16.milliseconds,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
    positionReader: (() -> Long)? = null,
): LongState {
    val boundedMaxPosition = maxPosition.coerceAtLeast(0L)
    val visibility = LocalVisualWorkActive.current
    val smoothPosition =
        remember(songId, positionFlow, enabled) {
            val initial =
                if (enabled && visibility.value &&
                    !isCasting
                ) {
                    positionReader?.invoke() ?: positionFlow.value
                } else {
                    positionFlow.value
                }
            mutableLongStateOf(initial.coerceIn(0L, boundedMaxPosition))
        }
    val readPosition by rememberUpdatedState(positionReader)
    val hasLiveReader = positionReader != null
    LaunchedEffect(
        positionFlow,
        enabled,
        isPlaying,
        isCasting,
        songId,
        frameInterval,
        boundedMaxPosition,
        playbackSpeed,
        visibility,
        hasLiveReader,
    ) {
        smoothLyricsPositions(
            positionFlow = positionFlow,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            maxPosition = boundedMaxPosition,
            isCasting = isCasting,
            enabled = enabled,
            visibility = visibility,
            frameInterval = frameInterval,
            positionReader = if (hasLiveReader) ({ readPosition?.invoke() ?: positionFlow.value }) else null,
            songId = songId,
        ).collect { smoothPosition.longValue = it }
    }
    return smoothPosition
}

internal fun smoothLyricsPositions(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    playbackSpeed: Float,
    visibility: StateFlow<Boolean>,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
    frameInterval: Duration = 16.milliseconds,
    positionReader: (() -> Long)? = null,
    songId: String? = null,
    timeSource: TimeSource = TimeSource.Monotonic,
    tickOrigin: TimeMark = playbackAnimationOrigin,
): Flow<Long> =
    channelFlow {
        visibility.collectLatest { shown ->
            if (!enabled || !shown || !isPlaying || isCasting) {
                positionFlow.collect { sample ->
                    val position = if (enabled && shown && !isCasting) positionReader?.invoke() ?: sample else sample
                    send(position.coerceIn(0L, maxPosition))
                }
                return@collectLatest
            }
            val clock = LyricsPositionClock(playbackSpeed, maxPosition, timeSource)
            clock.sample(positionFlow.value, positionReader)
            send(clock.positionMs())
            var logMark: TimeMark? = null
            coroutineScope {
                launch {
                    positionFlow.collect { sample ->
                        clock.sample(sample, positionReader)
                    }
                }
                launch {
                    lyricsPositionTicks(frameInterval, tickOrigin).collect {
                        val position = clock.positionMs(positionReader)
                        send(position)
                        if (positionReader != null && (logMark == null || logMark!!.elapsedNow().inWholeMilliseconds >= 5_000L)) {
                            logMark = timeSource.markNow()
                            PlaybackEventLog.debug(
                                PlaybackEventKind.PLAYER,
                                "Lyrics playback clock.",
                                videoId = songId,
                                details =
                                    mapOf(
                                        "flowPositionMs" to positionFlow.value.toString(),
                                        "anchorPositionMs" to clock.anchorPositionMs.toString(),
                                        "anchorAgeMs" to clock.anchorAgeMs.toString(),
                                        "renderPositionMs" to position.toString(),
                                        "playbackSpeed" to playbackSpeed.toString(),
                                        "livePositionReader" to "true",
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

internal class LyricsPositionClock(
    private val playbackSpeed: Float,
    private val maxPosition: Long = Long.MAX_VALUE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var anchorPositionMs = 0L
        private set
    private var anchorMark = timeSource.markNow()
    private var lastPositionMs = 0L
    private var initialized = false
    val anchorAgeMs: Long get() = anchorMark.elapsedNow().inWholeMilliseconds

    fun sample(
        positionMs: Long,
        positionReader: (() -> Long)? = null,
    ) {
        val bounded = (positionReader?.invoke() ?: positionMs).coerceIn(0L, maxPosition)
        if (!initialized || isPlaybackPositionDiscontinuity(anchorPositionMs, bounded, anchorAgeMs, playbackSpeed)) {
            lastPositionMs = bounded
        }
        anchorPositionMs = bounded
        anchorMark = timeSource.markNow()
        initialized = true
    }

    fun positionMs(positionReader: (() -> Long)? = null): Long {
        val position =
            positionReader?.invoke()?.coerceIn(0L, maxPosition)
                ?: advanceSmoothPlaybackPosition(anchorPositionMs.toDouble(), anchorAgeMs, playbackSpeed, maxPosition).toLong()
        lastPositionMs = maxOf(lastPositionMs, position)
        return lastPositionMs
    }
}

@Composable
fun rememberSmoothLyricsPositionState(
    currentPosition: Long,
    isPlaying: Boolean,
    songId: String? = null,
    frameInterval: Duration = 16.milliseconds,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
): LongState {
    val positionFlow = remember(songId) { MutableStateFlow(currentPosition) }
    positionFlow.value = currentPosition
    LaunchedEffect(currentPosition) {
        positionFlow.value = currentPosition
    }
    return rememberSmoothLyricsPositionState(
        positionFlow = positionFlow,
        isPlaying = isPlaying,
        songId = songId,
        frameInterval = frameInterval,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
        enabled = enabled,
    )
}

internal fun lyricsPositionTicks(
    interval: Duration,
    origin: TimeMark = playbackAnimationOrigin,
): Flow<Long> =
    flow {
        val intervalMillis = interval.inWholeMilliseconds.coerceAtLeast(1L)
        while (true) {
            delay(playbackAnimationDelayMillis(origin.elapsedNow().inWholeMilliseconds, intervalMillis))
            emit(origin.elapsedNow().inWholeMilliseconds)
        }
    }

internal fun advanceSmoothPlaybackPosition(
    position: Double,
    elapsedMillis: Long,
    playbackSpeed: Float,
    maxPosition: Long,
): Double =
    (position + elapsedMillis.coerceAtLeast(0L) * playbackSpeed.coerceAtLeast(0.02f))
        .coerceIn(0.0, maxPosition.coerceAtLeast(0L).toDouble())

internal fun isPlaybackPositionDiscontinuity(
    previousPositionMs: Long,
    samplePositionMs: Long,
    elapsedSincePreviousSampleMs: Long,
    playbackSpeed: Float,
): Boolean {
    val expectedDeltaMs = elapsedSincePreviousSampleMs.coerceAtLeast(0L).toDouble() * playbackSpeed.coerceAtLeast(0.1f)
    val observedDeltaMs = (samplePositionMs - previousPositionMs).toDouble()
    return samplePositionMs < previousPositionMs || abs(observedDeltaMs - expectedDeltaMs) > 300.0
}
