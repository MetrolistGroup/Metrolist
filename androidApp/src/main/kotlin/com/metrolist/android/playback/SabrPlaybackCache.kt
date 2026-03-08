package com.metrolist.android.playback

import android.os.SystemClock
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource

class SabrPlaybackCache internal constructor(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val budgetBytes: () -> Long = {
        val configured = Settings.shared.maxSongCacheSize.toLong() * 1024L * 1024L
        if (configured < 0L) MAX_CAPTURE_BYTES else minOf(configured, MAX_CAPTURE_BYTES)
    },
) {
    internal data class Candidate(
        val resource: PlaybackResource,
        val capture: SabrInputStream.CompleteCapture?,
        val playedMs: Long,
    ) {
        fun canDownload(historyDurationSeconds: Float): Boolean = playedMs >= historyDurationSeconds * 1000.0

        fun matches(other: PlaybackResource): Boolean =
            resource.videoId == other.videoId && resource.musicVideoId == other.musicVideoId &&
                resource.itag == other.itag && resource.mimeType == other.mimeType && resource.codecs == other.codecs &&
                resource.hasSameSabrAudio(other)
    }

    private data class Active(
        val songId: String,
        val resource: PlaybackResource,
        val source: SabrInputStream,
        var valid: Boolean = true,
    )

    private val released = linkedMapOf<String, Candidate>()
    private var active: Active? = null
    private var songId: String? = null
    private var accumulatedMs = 0L
    private var playingSinceMs: Long? = null

    @Volatile
    internal var generation = 0L
        private set

    @Synchronized
    internal fun start(
        songId: String,
        resource: PlaybackResource,
        source: SeekableMediaSource?,
        playing: Boolean,
        newPlayback: Boolean,
    ) {
        if (newPlayback || this.songId != songId) {
            accumulatedMs = 0L
            playingSinceMs = null
        }
        this.songId = songId
        if (source !is SabrInputStream) released.remove(songId)?.capture?.closeAsync()
        active = (source as? SabrInputStream)?.let { Active(songId, resource, it, budgetBytes() > 0L) }
        setPlaying(playing)
    }

    @Synchronized
    internal fun setPlaying(playing: Boolean) {
        val since = playingSinceMs
        if (playing && since == null) playingSinceMs = nowMs()
        if (!playing && since != null) {
            accumulatedMs += (nowMs() - since).coerceAtLeast(0L)
            playingSinceMs = null
        }
    }

    @Synchronized
    internal fun release(source: SeekableMediaSource?) {
        val current = active?.takeIf { it.source === source } ?: return
        setPlaying(false)
        snapshot(current)
        active = null
    }

    @Synchronized
    internal fun candidate(songId: String): Candidate? {
        active?.takeIf { it.songId == songId }?.let(::snapshot)
        return released[songId]
    }

    private fun snapshot(current: Active) {
        if (!current.valid || budgetBytes() <= 0L) return
        val size = current.source.mediaSize
        val previous = released.remove(current.songId)
        val capture =
            if (size != null && size in 1024L..budgetBytes() && current.resource.hasSameSabrAudio(current.resource)) {
                previous?.takeIf { it.matches(current.resource) }?.capture ?: current.source.retainCompleteCapture()
            } else {
                null
            }
        if (previous?.capture !== capture) previous?.capture?.closeAsync()
        val playedMs = accumulatedMs + (playingSinceMs?.let { (nowMs() - it).coerceAtLeast(0L) } ?: 0L)
        released[current.songId] = Candidate(current.resource, capture, playedMs)
        while (released.size > 32) discard(released.keys.first())
        while (released.values.count { it.capture != null } > 4 || released.values.sumOf { it.capture?.size ?: 0L } > budgetBytes()) {
            val oldest = released.entries.first { it.value.capture != null }
            oldest.value.capture?.closeAsync()
            released[oldest.key] = oldest.value.copy(capture = null)
        }
    }

    @Synchronized
    internal fun discard(songId: String) {
        active?.takeIf { it.songId == songId }?.valid = false
        released.remove(songId)?.capture?.closeAsync()
    }

    @Synchronized
    internal fun clear() {
        generation++
        active?.valid = false
        released.values.forEach { it.capture?.closeAsync() }
        released.clear()
    }

    private companion object {
        const val MAX_CAPTURE_BYTES = 512L * 1024 * 1024
    }
}
