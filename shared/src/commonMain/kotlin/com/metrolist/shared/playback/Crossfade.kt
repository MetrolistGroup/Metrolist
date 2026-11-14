package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.LoudnessLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.nextDown
import kotlin.math.pow

data class PlaybackCrossfadeState<T>(
    val nextSong: T,
    val progress: Float,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val outgoingSong: T? = null,
) {
    fun nextSongFor(currentSong: T?): T? = nextSong.takeIf { currentSong == outgoingSong }
}

class PlaybackCrossfadeController<T> {
    private val mutableState = MutableStateFlow<PlaybackCrossfadeState<T>?>(null)
    val state = mutableState.asStateFlow()

    fun start(
        nextSong: T,
        outgoingSong: T? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ) {
        mutableState.value =
            PlaybackCrossfadeState(
                nextSong = nextSong,
                progress = 0f,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                outgoingSong = outgoingSong,
            )
    }

    fun update(
        progress: Float,
        positionMs: Long? = null,
        durationMs: Long? = null,
    ) {
        val current = mutableState.value ?: return
        if (!progress.isFinite()) return
        mutableState.value =
            current.copy(
                progress = maxOf(current.progress, progress.coerceIn(0f, 1f)),
                positionMs = positionMs?.coerceAtLeast(current.positionMs) ?: current.positionMs,
                durationMs = durationMs?.takeIf { it > 0L } ?: current.durationMs,
            )
    }

    fun complete() {
        update(1f)
    }

    fun cancel() {
        mutableState.value = null
    }
}

internal fun playbackInstanceIdForCurrentPlayer(
    playbackInstanceId: Long,
    crossfadeOutgoingInstanceId: Long?,
): Long = crossfadeOutgoingInstanceId ?: playbackInstanceId

fun playbackCrossfadeProgress(
    startPositionMs: Long,
    endPositionMs: Long,
    currentPositionMs: Long,
    outgoingHasEnded: Boolean = false,
): Float {
    if (outgoingHasEnded || endPositionMs <= startPositionMs) return 1f
    return ((currentPositionMs - startPositionMs).toFloat() / (endPositionMs - startPositionMs)).coerceIn(0f, 1f)
}

fun shouldStartPlaybackCrossfade(
    currentPositionMs: Long,
    durationMs: Long,
    fadeDurationMs: Long,
    incomingIsRendering: Boolean,
): Boolean = incomingIsRendering && currentPositionMs > 0L && durationMs - currentPositionMs in 1L..fadeDurationMs

fun songsShareAlbum(
    first: SongItem?,
    second: SongItem?,
): Boolean {
    val firstAlbum = first?.album ?: return false
    val secondAlbum = second?.album ?: return false
    if (!firstAlbum.id.isNullOrBlank() || !secondAlbum.id.isNullOrBlank()) {
        return !firstAlbum.id.isNullOrBlank() && firstAlbum.id == secondAlbum.id
    }
    return firstAlbum.name.isNotBlank() && firstAlbum.name == secondAlbum.name &&
        first.artists.isNotEmpty() &&
        first.artists.map { it.id?.takeIf(String::isNotBlank) ?: it.name }.toSet() ==
        second.artists.map { it.id?.takeIf(String::isNotBlank) ?: it.name }.toSet()
}

fun playbackCrossfadeDurationMs(
    enabled: Boolean,
    preserveSameAlbumGapless: Boolean,
    sameAlbum: Boolean,
    configuredDurationMs: Long,
    outgoingDurationMs: Long,
    incomingDurationMs: Long,
): Long {
    if (!enabled || (preserveSameAlbumGapless && sameAlbum)) return 0L
    return minOf(configuredDurationMs.coerceIn(0L, 15_000L), outgoingDurationMs.coerceAtLeast(0L), incomingDurationMs.coerceAtLeast(0L))
}

fun playbackCrossfadeGains(progress: Float): Pair<Float, Float> {
    if (!progress.isFinite() || progress <= 0f) return 1f to 0f
    if (progress >= 1f) return 0f to 1f
    val t = progress.coerceIn(0f, 1f)
    val shaped = t * t * t * (t * (t * 6f - 15f) + 10f)
    return (1f - shaped).coerceAtLeast(0f) to shaped.coerceAtMost(1f.nextDown())
}

fun playbackFadeThroughGains(progress: Float): Pair<Float, Float> {
    if (!progress.isFinite()) return 1f to 0f
    if (progress <= 0f) return 1f to 0f
    if (progress >= 1f) return 0f to 1f
    val pauseHalfWidth = CROSSFADE_CENTER_PAUSE_PROGRESS / 2f
    val outgoingEnd = 0.5f - pauseHalfWidth
    val incomingStart = 0.5f + pauseHalfWidth
    return when {
        progress < outgoingEnd -> {
            (1f - crossfadeLinearProgress(progress / outgoingEnd)).coerceAtLeast(0f) to 0f
        }

        progress > incomingStart -> {
            0f to crossfadeLinearProgress((progress - incomingStart) / (1f - incomingStart)).coerceIn(0f, 1f.nextDown())
        }

        else -> {
            0f to 0f
        }
    }
}

fun shouldPromotePlaybackCrossfade(incomingGain: Float): Boolean = incomingGain.isFinite() && incomingGain > 0f

private const val CROSSFADE_CENTER_PAUSE_PROGRESS = 0.01f

internal fun crossfadeLinearProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

fun playbackNormalizationGain(
    loudnessDb: Double?,
    enabled: Boolean,
    level: LoudnessLevel = LoudnessLevel.BALANCED,
    perceptualLoudnessDb: Double? = null,
): Float {
    if (!enabled) return 1f
    val measuredLufs =
        perceptualLoudnessDb?.takeIf { it.isFinite() }
            ?: loudnessDb?.takeIf { it.isFinite() }?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }
            ?: return 1f
    val gainMb = ((level.targetLufs - measuredLufs) * 100.0).toInt().coerceIn(-1500, 300)
    return 10.0.pow(gainMb / 2000.0).toFloat()
}

fun playbackOutputGain(
    requestedVolume: Float,
    sleepTimerGain: Float,
    transitionGain: Float,
    normalizationGain: Float = 1f,
): Float =
    (requestedVolume.coerceIn(0f, 1f) * normalizationGain.coerceAtLeast(0f)).coerceAtMost(1f) *
        sleepTimerGain.coerceIn(0f, 1f) *
        transitionGain.coerceIn(0f, 1f)
