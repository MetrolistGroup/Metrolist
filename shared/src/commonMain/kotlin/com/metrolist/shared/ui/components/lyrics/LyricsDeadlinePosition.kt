/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import com.metrolist.shared.ui.components.lyrics.words.LyricsAnimationStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.time.TimeSource

internal data class LyricsDeadlinePosition(
    val positionMs: Long,
    val seekGeneration: Int = 0,
)

internal class LyricsPlaybackAnchor(
    var positionMs: Long,
    var isPlaying: Boolean,
    var playbackSpeed: Float,
    timeSource: TimeSource,
) {
    var mark = timeSource.markNow()
    var needsReseed = false
}

internal fun nextLyricsDeadlineIndex(
    deadlines: List<Long>,
    positionMs: Long,
): Int {
    val index = deadlines.binarySearch(positionMs)
    return if (index >= 0) index + 1 else -index - 1
}

internal fun nextLyricsDeadlineDelay(
    deadlines: List<Long>,
    positionMs: Long,
    playbackSpeed: Float,
): Long? {
    if (!playbackSpeed.isFinite() || playbackSpeed <= 0f) return null
    val next = deadlines.getOrNull(nextLyricsDeadlineIndex(deadlines, positionMs)) ?: return null
    return ceil((next.toDouble() - positionMs.toDouble()) / playbackSpeed).toLong().coerceAtLeast(1L)
}

internal fun deadlineLyricsPositions(
    positionFlow: StateFlow<Long>,
    deadlines: List<Long>,
    initial: LyricsDeadlinePosition,
    isPlaying: Boolean,
    isCasting: Boolean,
    playbackSpeed: Float,
    timingOffsetMs: Int,
    visibility: StateFlow<Boolean>,
    timeSource: TimeSource = TimeSource.Monotonic,
    anchor: LyricsPlaybackAnchor = LyricsPlaybackAnchor(initial.positionMs, isPlaying, playbackSpeed, timeSource),
    positionReader: (() -> Long)? = null,
): Flow<LyricsDeadlinePosition> =
    channelFlow {
        var published = initial
        visibility.collectLatest { shown ->
            if (!shown) {
                anchor.needsReseed = true
                positionFlow.collect { rawSample ->
                    val sample = rawSample.coerceAtLeast(0L)
                    anchor.positionMs = sample
                    anchor.mark = timeSource.markNow()
                    published = LyricsDeadlinePosition(sample, published.seekGeneration)
                    send(published)
                }
                return@collectLatest
            }
            var firstSample = true
            positionFlow.collectLatest sample@{ rawSample ->
                val sample = (if (isCasting) rawSample else positionReader?.invoke() ?: rawSample).coerceAtLeast(0L)
                val mark = timeSource.markNow()
                val isSeek =
                    anchor.needsReseed || sample < anchor.positionMs - 80L ||
                        isPlaybackPositionDiscontinuity(
                            anchor.positionMs,
                            sample,
                            if (anchor.isPlaying) anchor.mark.elapsedNow().inWholeMilliseconds else 0L,
                            anchor.playbackSpeed,
                        )
                anchor.needsReseed = false
                anchor.positionMs = sample
                anchor.mark = mark
                anchor.isPlaying = isPlaying
                anchor.playbackSpeed = playbackSpeed
                val effectiveSample =
                    if (!isSeek && !firstSample && isPlaying && published.positionMs - sample in 1L..250L) {
                        published.positionMs
                    } else {
                        sample
                    }
                val generation = published.seekGeneration + if (isSeek) 1 else 0
                if (firstSample || isSeek || !isPlaying || isCasting ||
                    nextLyricsDeadlineIndex(deadlines, effectiveSample + timingOffsetMs) !=
                    nextLyricsDeadlineIndex(deadlines, published.positionMs + timingOffsetMs)
                ) {
                    published = LyricsDeadlinePosition(effectiveSample, generation)
                    send(published)
                }
                firstSample = false
                if (!isPlaying || isCasting || !playbackSpeed.isFinite() || playbackSpeed <= 0f) return@sample

                while (true) {
                    val position =
                        maxOf(
                            effectiveSample,
                            published.positionMs,
                            positionReader?.invoke()
                                ?: (sample + (mark.elapsedNow().inWholeMilliseconds * playbackSpeed.toDouble()).toLong()),
                        )
                    if (nextLyricsDeadlineIndex(deadlines, position + timingOffsetMs) !=
                        nextLyricsDeadlineIndex(deadlines, published.positionMs + timingOffsetMs)
                    ) {
                        published = LyricsDeadlinePosition(position, generation)
                        send(published)
                    }
                    val waitMs = nextLyricsDeadlineDelay(deadlines, position + timingOffsetMs, playbackSpeed) ?: break
                    delay(if (positionReader != null) minOf(waitMs, 16L) else waitMs)
                }
            }
        }
    }

@Composable
internal fun rememberDeadlineLyricsPositionState(
    positionFlow: StateFlow<Long>,
    deadlines: List<Long>,
    isPlaying: Boolean,
    songId: String?,
    timingOffsetMs: Int,
    isCasting: Boolean,
    enabled: Boolean,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    positionReader: (() -> Long)? = null,
): State<LyricsDeadlinePosition> {
    val visibility = LocalVisualWorkActive.current
    val position =
        remember(songId, positionFlow, enabled) {
            val initial =
                if (enabled && visibility.value &&
                    !isCasting
                ) {
                    positionReader?.invoke() ?: positionFlow.value
                } else {
                    positionFlow.value
                }
            mutableStateOf(LyricsDeadlinePosition(initial.coerceAtLeast(0L)))
        }
    val anchor =
        remember(songId) { LyricsPlaybackAnchor(positionFlow.value.coerceAtLeast(0L), isPlaying, playbackSpeed, TimeSource.Monotonic) }
    val readPosition by rememberUpdatedState(positionReader)
    val hasLiveReader = positionReader != null
    LaunchedEffect(
        positionFlow,
        deadlines,
        enabled,
        isPlaying,
        songId,
        timingOffsetMs,
        isCasting,
        playbackSpeed,
        visibility,
        hasLiveReader,
    ) {
        if (!enabled) {
            anchor.needsReseed = true
            positionFlow.collect { rawSample ->
                val sample = rawSample.coerceAtLeast(0L)
                anchor.positionMs = sample
                position.value = LyricsDeadlinePosition(sample, position.value.seekGeneration)
            }
            return@LaunchedEffect
        }
        deadlineLyricsPositions(
            positionFlow,
            deadlines,
            position.value,
            isPlaying,
            isCasting,
            playbackSpeed,
            timingOffsetMs,
            visibility,
            anchor = anchor,
            positionReader = if (hasLiveReader) ({ readPosition?.invoke() ?: positionFlow.value }) else null,
        ).collect { position.value = it }
    }
    return position
}

internal fun buildCaptionLyricsDeadlines(
    lyrics: List<LyricsEntry>,
    finalLineDurationMs: Long?,
): List<Long> =
    buildSet {
        for ((index, line) in lyrics.withIndex()) {
            add(line.time - LyricsUtils.LINE_SELECTION_LOOK_AHEAD_MS + 1L)
            add(line.time)
            val end = LyricsUtils.getLineEndTime(line, lyrics.getOrNull(index + 1), finalLineDurationMs)
            add(end + 1L)
            if (index == lyrics.lastIndex && end < Long.MAX_VALUE - 2_000L) add(end + 2_001L)
        }
    }.sorted()

@Composable
fun rememberCaptionLyricsPositionState(
    lyrics: List<LyricsEntry>,
    isPlaying: Boolean,
    songId: String?,
    timingOffsetMs: Int,
    isCasting: Boolean,
    currentPositionFlow: StateFlow<Long>? = null,
    positionReader: (() -> Long)? = null,
    currentPosition: Long = 0L,
    enabled: Boolean = true,
    finalLineDurationMs: Long? = null,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
): State<Long> {
    val effectiveOffsetMs = LyricsUtils.combinedLyricsOffsetMs(timingOffsetMs, Settings.shared.globalLyricsOffsetMs)
    val positionFlow =
        currentPositionFlow ?: remember(songId) { MutableStateFlow(currentPosition) }.also {
            it.value = currentPosition
            LaunchedEffect(currentPosition) { it.value = currentPosition }
        }
    val clockEnabled = enabled && remember(lyrics) { LyricsUtils.hasPlaybackTiming(lyrics) }
    val hasAnimatedWords =
        LyricsAnimationStyle.fromString(Settings.shared.lyricsAnimationStyle) != LyricsAnimationStyle.NONE &&
            remember(lyrics, Settings.shared.lyricsRomanizationEnabled) {
                lyrics.any { it.hasAnimatedWordTiming(Settings.shared.lyricsRomanizationEnabled) }
            }
    if (hasAnimatedWords) {
        val position =
            rememberSmoothLyricsPositionState(
                positionFlow = positionFlow,
                playbackSpeed = playbackSpeed,
                positionReader = positionReader,
                isPlaying = isPlaying,
                songId = songId,
                isCasting = isCasting,
                enabled = clockEnabled,
            )
        return remember(position, effectiveOffsetMs) {
            derivedStateOf { LyricsUtils.lyricsPositionForOffset(position.longValue, effectiveOffsetMs) }
        }
    }
    val deadlines = remember(lyrics, finalLineDurationMs) { buildCaptionLyricsDeadlines(lyrics, finalLineDurationMs) }
    val position =
        rememberDeadlineLyricsPositionState(
            positionFlow = positionFlow,
            deadlines = deadlines,
            isPlaying = isPlaying,
            songId = songId,
            timingOffsetMs = effectiveOffsetMs,
            isCasting = isCasting,
            enabled = clockEnabled,
            playbackSpeed = playbackSpeed,
            positionReader = positionReader,
        )
    return remember(position, effectiveOffsetMs) {
        derivedStateOf { LyricsUtils.lyricsPositionForOffset(position.value.positionMs, effectiveOffsetMs) }
    }
}
