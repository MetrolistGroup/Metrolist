package com.metrolist.shared.playback

data class VideoPlayerStats(
    val videoId: String = "",
    val cpn: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val containerFormat: String = "",
    val videoResolution: String = "",
    val optimalResolution: String = "",
    val viewportResolution: String = "",
    val fps: Double = 0.0,
    val targetFps: Double = 0.0,
    val videoBitrateKbps: Long = 0L,
    val audioBitrateKbps: Long = 0L,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val pixelFormat: String = "",
    val colorSpace: String = "",
    val colorConversion: String = "",
    val decodedFrames: Long = 0L,
    val renderedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val videoPtsMs: Long = 0L,
    val audioClockMs: Long = 0L,
    val avSyncOffsetMs: Long = 0L,
    val renderDurationMs: Double = 0.0,
    val streamSource: String = "",
    val mediaSizeBytes: Long = 0L,
    val streamPositionBytes: Long = 0L,
    val bufferHealthMs: Long = 0L,
    val volume: Float = 1.0f,
    val isPaused: Boolean = false,
    val isResolving: Boolean = false,
    val counterpartSource: String = "",
)

expect class VideoFrame

expect class VideoPlayer(
    onFrame: (VideoFrame) -> Unit,
    onEnded: () -> Unit = {},
    onSeekLanded: ((Long) -> Unit)? = null,
    onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    onRenderedFirstFrame: () -> Unit = {},
    looping: Boolean = false,
) {
    val hasOwnAudio: Boolean

    /** Optional provider for the master audio stream's broadcast PTS in milliseconds (for live streams). */
    var audioPtsProvider: (() -> Long?)?

    fun getStats(): VideoPlayerStats

    /** The master position provider returns milliseconds; true means the stream is prepared. */
    fun load(
        url: String,
        headers: Map<String, String> = emptyMap(),
        positionProvider: () -> Long,
    ): Boolean

    fun load(
        source: SeekableMediaSource,
        positionProvider: () -> Long,
    ): Boolean

    /** startPaused still decodes the first frame before pausing. */
    fun play(startPaused: Boolean = false)

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun setSurface(surface: Any?)

    fun close()
}
