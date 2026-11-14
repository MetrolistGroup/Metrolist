package com.metrolist.shared.playback.ffmpeg

import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import java.io.InputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

class AudioDecoder private constructor(
    val frameGrabber: FFmpegFrameGrabber,
    internal val inputStream: InputStream? = null,
    private val copySamples: (Buffer?) -> ByteArray? = ::copyPcmSamples,
) {
    var wantVideo: Boolean = false
        set(value) {
            field = value
            if (frameGrabber.started) {
                frameGrabber.videoStream = if (value) -1 else -2
            }
        }
    constructor(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ) : this(
        FFmpegFrameGrabber(path).apply {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                options["reconnect"] = "1"
                options["reconnect_streamed"] = "1"
                options["reconnect_delay_max"] = "5"
                options["timeout"] = "15000000"
                options["rw_timeout"] = "15000000"
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!ua.isNullOrBlank()) {
                    options["user_agent"] = ua
                }
                headers
                    .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                    .takeIf { it.isNotEmpty() }
                    ?.entries
                    ?.joinToString(separator = "\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" }
                    ?.let { options["headers"] = it }
            }
        },
        null,
    )

    internal constructor(
        inputStream: InputStream,
        maximumSize: Int,
        copySamples: (Buffer?) -> ByteArray? = ::copyPcmSamples,
    ) : this(FFmpegFrameGrabber(inputStream, maximumSize), inputStream, copySamples)

    init {
        try {
            frameGrabber.videoStream = if (wantVideo) -1 else -2
            frameGrabber.sampleFormat = AV_SAMPLE_FMT_S16
            frameGrabber.pixelFormat = AV_PIX_FMT_RGBA
            frameGrabber.start()
        } catch (error: Throwable) {
            runCatching { frameGrabber.stop() }
            runCatching { frameGrabber.release() }
            throw error
        }
    }

    val sampleRate = frameGrabber.sampleRate
    val audioChannels = frameGrabber.audioChannels

    val duration = frameGrabber.getLengthInTime() / 1000

    @Volatile
    var lastDecodedPtsMs: Long? = null
        private set

    private var videoFrameCount = 0

    fun readFrame(videoEnabled: Boolean): Frame? = frameGrabber.grabFrame(true, videoEnabled, true, false, false)

    fun readPCMFrame(videoListener: ((Frame) -> Unit)? = null): ByteArray? =
        synchronized(frameGrabber) {
            // FFmpeg owns frame buffers until the next grab or release, including during prebuffering.
            while (true) {
                val frame = readFrame(videoListener != null) ?: return null
                if (frame.image != null) {
                    videoFrameCount++
                    videoListener?.invoke(frame)
                }

                val samples = frame.samples ?: continue
                if (samples.isEmpty()) continue

                val ptsMs = (frame.timestamp / 1000L).takeIf { it > 0L }
                if (ptsMs != null) {
                    lastDecodedPtsMs = ptsMs
                }

                return copySamples(samples[0])
            }
            null
        }

    fun seekTo(
        position: Long,
        managePlaybackPosition: Boolean = true,
    ) {
        val playbackInput = frameGrabberInputStream() as? PlaybackPositionInput
        if (managePlaybackPosition) playbackInput?.beginPlaybackSeek(position)
        try {
            frameGrabber.setTimestamp(position * 1000, true)
        } finally {
            if (managePlaybackPosition) playbackInput?.endPlaybackSeek(frameGrabber.timestamp / 1000)
        }
    }

    fun updatePlaybackSeekTarget(position: Long) {
        (frameGrabberInputStream() as? PlaybackPositionInput)?.beginPlaybackSeek(position)
    }

    fun finishPlaybackSeek() {
        (frameGrabberInputStream() as? PlaybackPositionInput)?.endPlaybackSeek(frameGrabber.timestamp / 1000)
    }

    fun getCurrentPosition(): Long =
        (frameGrabber.timestamp / 1000).also { position ->
            (frameGrabberInputStream() as? PlaybackPositionInput)?.updatePlaybackPosition(position)
        }

    val streamFailure: Throwable?
        get() = (frameGrabberInputStream() as? PlaybackPositionInput)?.streamFailure

    fun cancelPendingReads() {
        frameGrabberInputStream()?.takeIf { it is PlaybackPositionInput }?.let { runCatching { it.close() } }
    }

    fun close() {
        try {
            frameGrabber.stop()
            frameGrabber.release()
        } catch (_: Exception) {
        }
    }

    private fun frameGrabberInputStream(): InputStream? = frameGrabber.mediaInputStream
}

internal fun copyPcmSamples(buffer: Buffer?): ByteArray? =
    when (buffer) {
        is ShortBuffer -> {
            val byteArray = ByteArray(buffer.remaining() * 2)
            val resultByteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
            resultByteBuffer.asShortBuffer().put(buffer)
            byteArray
        }

        is ByteBuffer -> {
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray)
            byteArray
        }

        else -> {
            null
        }
    }
