package com.metrolist.shared.cast

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream

interface MdnsCastDiscovery : Closeable {
    fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit = {},
    )

    fun stop()

    override fun close() = stop()
}

data class CastMedia(
    val title: String,
    val artworkUrl: String?,
    val resource: PlaybackResource,
    val durationMs: Long,
    val artist: String? = null,
    val album: String? = null,
    val queueEntry: MusicQueueEntry? = null,
) {
    constructor(
        song: SongItem,
        artworkUrl: String?,
        resource: PlaybackResource,
        durationMs: Long,
        queueEntry: MusicQueueEntry? = null,
    ) : this(
        song.title,
        artworkUrl,
        resource,
        durationMs,
        song.artists.joinToString(", ") { it.name }.takeIf(String::isNotBlank),
        song.album?.name,
        queueEntry,
    )
}

// Sonos can't decode WebM/Opus; YouTube's AAC (m4a) rendition of the same track plays natively.
fun castAudioQuality(protocol: CastProtocol?): AudioQuality? = AudioQuality.MP4.takeIf { protocol == CastProtocol.SONOS }

fun PlaybackResource.isPlayableOn(protocol: CastProtocol?): Boolean =
    protocol != CastProtocol.SONOS || mimeType?.startsWith("audio/mp4", ignoreCase = true) == true

internal data class CastSessionState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val hasEnded: Boolean = false,
    val mediaGeneration: Long = 0L,
)

internal fun InputStream.readUpTo(count: Int): ByteArray {
    require(count >= 0)
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read <= 0) break
        offset += read
    }
    return if (offset == count) bytes else bytes.copyOf(offset)
}

internal interface CastSession : Closeable {
    val state: StateFlow<CastSessionState>

    suspend fun load(
        mediaUrl: String,
        media: CastMedia,
        positionMs: Long,
        autoplay: Boolean,
        volume: Float,
        mediaGeneration: Long,
    )

    suspend fun play()

    suspend fun pause()

    suspend fun seekTo(positionMs: Long)

    suspend fun setVolume(volume: Float)

    suspend fun stop()
}
