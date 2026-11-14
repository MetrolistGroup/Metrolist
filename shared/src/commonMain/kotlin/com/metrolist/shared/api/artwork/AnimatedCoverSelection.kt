package com.metrolist.shared.api.artwork

import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

private const val YOUTUBE_CANVAS_CLIP_LENGTH_MS = 3_000L

enum class CanvasArtworkState {
    NOT_REQUESTED,
    LOADING,
    UNAVAILABLE,
    AVAILABLE,
}

enum class CanvasContentSource {
    SPOTIFY_CANVAS,
    YOUTUBE_MUSIC_VIDEO,
    APPLE_MUSIC,
    ;

    companion object {
        val defaultOrder = listOf(YOUTUBE_MUSIC_VIDEO, SPOTIFY_CANVAS, APPLE_MUSIC)

        fun parseOrder(csv: String): List<CanvasContentSource> {
            val parsed = csv.split(",").mapNotNull { token -> entries.find { it.name == token.trim() } }
            return (parsed + defaultOrder).distinct()
        }

        fun serialize(order: List<CanvasContentSource>): String =
            order.distinct().filter { it in defaultOrder }.joinToString(",") { it.name }

        fun parseEnabled(csv: String): Set<CanvasContentSource> =
            if (csv.isBlank()) entries.toSet() else csv.split(",").mapNotNull { token -> entries.find { it.name == token.trim() } }.toSet()
    }
}

fun CanvasContentSource.isEnabled(
    canvasEnabled: Boolean,
    animatedCoversEnabled: Boolean,
): Boolean = if (this == CanvasContentSource.APPLE_MUSIC) animatedCoversEnabled else canvasEnabled

fun animatedCoverCacheQuality(
    resolution: AnimatedCoverResolution,
    tall: Boolean,
): String = if (tall) "$resolution:tall" else resolution.toString()

data class YouTubeCanvasClip(
    val startMs: Long,
    val endMs: Long,
)

fun youtubeCanvasPlaybackClips(
    mode: YouTubeCanvasMode,
    resource: PlaybackResource,
    randomStart: (Long) -> Long = Random::nextLong,
): List<YouTubeCanvasClip> =
    when (mode) {
        YouTubeCanvasMode.SHORT_CLIPS -> youtubeCanvasClips(resource.durationSeconds?.times(1000L), randomStart)
        YouTubeCanvasMode.FULL_VIDEO -> emptyList()
    }

fun youtubeCanvasClips(
    durationMs: Long?,
    randomStart: (Long) -> Long = Random::nextLong,
): List<YouTubeCanvasClip> {
    val duration = durationMs?.takeIf { it > 0L } ?: return emptyList()
    val clipLength = YOUTUBE_CANVAS_CLIP_LENGTH_MS.coerceAtMost(duration)
    val latestStart = (duration - clipLength).coerceAtLeast(0L)
    return List(4) {
        val start = if (latestStart > 0L) randomStart(latestStart + 1L).coerceIn(0L, latestStart) else 0L
        YouTubeCanvasClip(start, start + clipLength)
    }
}

fun youtubeCanvasSeekPrerollMs(
    clips: List<YouTubeCanvasClip>,
    useActualDurationForClips: Boolean,
): Long? = clips.firstOrNull()?.let { it.endMs - it.startMs } ?: YOUTUBE_CANVAS_CLIP_LENGTH_MS.takeIf { useActualDurationForClips }

suspend fun runYouTubeCanvasPlaybackClock(
    clips: List<YouTubeCanvasClip>,
    currentPositionMs: () -> Long,
    isPlaying: () -> Boolean,
    playWhenReady: StateFlow<Boolean>,
    isEnded: () -> Boolean,
    updatePlaybackPosition: (Long) -> Unit,
    seekTo: (Long) -> Unit,
) {
    var clipIndex = 0

    fun seekToClip() {
        val start = clips[clipIndex].startMs
        updatePlaybackPosition(start)
        seekTo(start)
    }
    if (clips.isNotEmpty()) seekToClip()
    while (true) {
        playWhenReady.first { it }
        delay(100L)
        if (!playWhenReady.value) continue
        val position = currentPositionMs()
        val playing = isPlaying()
        val ended = isEnded()
        if (clips.isNotEmpty() && playWhenReady.value && (ended || (playing && position >= clips[clipIndex].endMs))) {
            clipIndex = (clipIndex + 1) % clips.size
            seekToClip()
        } else if (playing && !ended && (clips.isEmpty() || position >= clips[clipIndex].startMs)) {
            updatePlaybackPosition(position)
        }
    }
}

fun selectAnimatedCoverUrl(
    canvasUrl: String?,
    musicVideoUrl: String?,
    animatedCoverUrl: String?,
    canvasState: CanvasArtworkState,
    canvasEnabled: Boolean,
    animatedCoversEnabled: Boolean,
    failedUrls: Set<String> = emptySet(),
    contentOrder: List<CanvasContentSource> = CanvasContentSource.defaultOrder,
    enabledSources: Set<CanvasContentSource> = CanvasContentSource.entries.toSet(),
    pendingSources: Set<CanvasContentSource> = emptySet(),
): String? {
    for (source in contentOrder) {
        if (source !in enabledSources || !source.isEnabled(canvasEnabled, animatedCoversEnabled)) continue
        val url =
            when (source) {
                CanvasContentSource.SPOTIFY_CANVAS -> canvasUrl.takeIf { canvasState == CanvasArtworkState.AVAILABLE }
                CanvasContentSource.YOUTUBE_MUSIC_VIDEO -> musicVideoUrl
                CanvasContentSource.APPLE_MUSIC -> animatedCoverUrl
            }
        url?.takeUnless { it in failedUrls }?.let { return it }
        if (source in pendingSources) return null
    }
    return null
}

fun isHlsArtworkUrl(url: String): Boolean = runCatching { Url(url).encodedPath.endsWith(".m3u8", ignoreCase = true) }.getOrDefault(false)

fun animatedArtworkSource(
    url: String,
    spotifyUrl: String?,
    musicVideoUrl: String?,
): CanvasContentSource =
    when (url) {
        spotifyUrl -> CanvasContentSource.SPOTIFY_CANVAS
        musicVideoUrl -> CanvasContentSource.YOUTUBE_MUSIC_VIDEO
        else -> CanvasContentSource.APPLE_MUSIC
    }
