package com.metrolist.android.playback

import android.media.AudioTimestamp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import java.nio.ByteBuffer

internal class TimestampAudioOutput(
    output: AudioOutput,
    private val bytesPerFrame: Int,
    readTimestamp: (AudioTimestamp) -> Boolean,
    private val routeId: () -> Int?,
    nanoTime: () -> Long = System::nanoTime,
) : ForwardingAudioOutput(output) {
    private val clock = AudioTimestampClock(output.sampleRate, readTimestamp, nanoTime)
    private var writtenBytes = 0L
    private var playing = false
    private var stopped = false
    private var pausedPositionUs: Long? = null
    private var currentRouteId = routeId()
    private var lastPositionUs = 0L

    override fun getPositionUs(): Long {
        val fallbackUs = super.getPositionUs()
        if (!playing) return pausedPositionUs ?: fallbackUs
        val route = routeId()
        if (route != currentRouteId) {
            currentRouteId = route
            clock.reset()
        }
        val positionUs = clock.positionUs(writtenBytes / bytesPerFrame, playbackParameters.speed, sampleTimestamp = !stopped) ?: fallbackUs
        lastPositionUs = maxOf(lastPositionUs, positionUs)
        return lastPositionUs
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        val start = buffer.position()
        return super.write(buffer, encodedAccessUnitCount, presentationTimeUs).also {
            writtenBytes += buffer.position() - start
        }
    }

    override fun play() {
        clock.reset()
        pausedPositionUs = null
        super.play()
        playing = true
    }

    override fun pause() {
        if (playing) pausedPositionUs = positionUs
        super.pause()
        playing = false
        clock.reset()
    }

    override fun stop() {
        stopped = true
        super.stop()
    }

    override fun flush() {
        super.flush()
        writtenBytes = 0L
        stopped = false
        pausedPositionUs = null
        lastPositionUs = 0L
        clock.reset(resetFrames = true)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        super.setPlaybackParameters(playbackParams)
        clock.reset()
    }
}

internal class AudioTimestampClock(
    private val sampleRate: Int,
    private val readTimestamp: (AudioTimestamp) -> Boolean,
    private val nanoTime: () -> Long,
) {
    private val timestamp = AudioTimestamp()
    private var validFromNs = nanoTime()
    private var lastPollNs = Long.MIN_VALUE
    private var previousFrames = -1L
    private var previousTimeNs = Long.MIN_VALUE
    private var rawFrames = 0L
    private var wrapFrames = 0L
    private var advancing = false

    fun reset(resetFrames: Boolean = false) {
        validFromNs = nanoTime()
        lastPollNs = Long.MIN_VALUE
        previousFrames = -1L
        previousTimeNs = Long.MIN_VALUE
        advancing = false
        if (resetFrames) {
            rawFrames = 0L
            wrapFrames = 0L
        }
    }

    fun positionUs(
        writtenFrames: Long,
        speed: Float,
        sampleTimestamp: Boolean = true,
    ): Long? {
        val nowNs = nanoTime()
        val pollIntervalNs =
            if (advancing || (nowNs - validFromNs > 500_000_000L && previousFrames < 0L)) 10_000_000_000L else 10_000_000L
        if (sampleTimestamp && (lastPollNs == Long.MIN_VALUE || nowNs - lastPollNs >= pollIntervalNs)) {
            lastPollNs = nowNs
            val available =
                try {
                    readTimestamp(timestamp)
                } catch (_: RuntimeException) {
                    false
                }
            if (!available || timestamp.nanoTime < validFromNs || timestamp.framePosition < 0L) {
                advancing = false
                previousFrames = -1L
                return null
            }
            val raw = timestamp.framePosition
            val wrapped = rawFrames - raw >= (1L shl 31)
            val frames = raw + wrapFrames + if (wrapped) 1L shl 32 else 0L
            val projectedUs = timestampPositionUs(frames, timestamp.nanoTime, nowNs, speed)
            val writtenUs = Util.sampleCountToDurationUs(writtenFrames, sampleRate)
            if (frames > writtenFrames || (raw < rawFrames && !wrapped) || timestamp.nanoTime < previousTimeNs ||
                projectedUs !in 0L..writtenUs
            ) {
                advancing = false
                previousFrames = -1L
                return null
            }
            if (frames == previousFrames && timestamp.nanoTime > previousTimeNs) advancing = false
            if (frames > previousFrames && timestamp.nanoTime > previousTimeNs && previousFrames >= 0L) advancing = true
            rawFrames = raw
            if (wrapped) wrapFrames += 1L shl 32
            previousFrames = frames
            previousTimeNs = timestamp.nanoTime
        }
        if (!advancing) return null
        val positionUs = timestampPositionUs(previousFrames, previousTimeNs, nowNs, speed)
        val writtenUs = Util.sampleCountToDurationUs(writtenFrames, sampleRate)
        if (positionUs >= writtenUs) {
            // Underruns and drained streams need a fresh timestamp before extrapolating again.
            reset()
            return writtenUs
        }
        return positionUs.takeIf { it >= 0L }
    }

    private fun timestampPositionUs(
        frames: Long,
        timestampNs: Long,
        nowNs: Long,
        speed: Float,
    ): Long = Util.sampleCountToDurationUs(frames, sampleRate) + ((nowNs - timestampNs) / 1_000L * speed).toLong()
}
