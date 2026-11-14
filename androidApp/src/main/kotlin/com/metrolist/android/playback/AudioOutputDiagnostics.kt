package com.metrolist.android.playback

import android.media.AudioDeviceInfo
import android.media.AudioTimestamp
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import java.nio.ByteBuffer
import kotlin.math.abs

internal class DiagnosticAudioOutput(
    output: AudioOutput,
    private val config: AudioOutputProvider.OutputConfig,
    private val onEvent: (String, Map<String, String>) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val onOutputSignal: (AudioOutputSignal) -> Unit = {},
) : ForwardingAudioOutput(output) {
    private val track = (output as? AudioTrackAudioOutput)?.audioTrack
    private val probe = AudioOutputProbe()
    private val stallProbe = PcmOutputStallProbe()
    private val timestamp = AudioTimestamp()
    private var playing = false
    private var volume = 1f
    private var lastSampleMs = Long.MIN_VALUE
    private var routedDeviceId: Int? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private var peak = 0
    private var silentFrames = 0L
    private var writtenBytes = 0L
    private var sampledWrittenBytes = 0L
    private var probeAvailable = true
    private val bytesPerFrame = Integer.bitCount(config.channelMask) * 2
    private val pcm16 = config.encoding == C.ENCODING_PCM_16BIT

    init {
        record("created")
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        val start = buffer.position()
        val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        writtenBytes += buffer.position() - start
        if (pcm16) {
            var offset = start
            while (offset + bytesPerFrame <= buffer.position()) {
                var framePeak = 0
                for (channel in 0 until bytesPerFrame / 2) {
                    framePeak = maxOf(framePeak, abs(buffer.getShort(offset + channel * 2).toInt()))
                }
                peak = maxOf(peak, framePeak)
                silentFrames = if (framePeak == 0) silentFrames + 1 else 0L
                offset += bytesPerFrame
            }
        }
        sample()
        return handled
    }

    override fun getPositionUs(): Long = super.getPositionUs().also { sample() }

    override fun play() {
        super.play()
        playing = true
        probe.reset()
        stallProbe.reset()
        record("play")
    }

    override fun pause() {
        super.pause()
        playing = false
        probe.reset()
        stallProbe.reset()
        record("pause")
    }

    override fun stop() {
        playing = false
        probe.reset()
        stallProbe.reset()
        record("stop")
        super.stop()
    }

    override fun flush() {
        super.flush()
        probe.reset()
        stallProbe.reset()
        writtenBytes = 0L
        sampledWrittenBytes = 0L
        silentFrames = 0L
        peak = 0
        record("flush")
    }

    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        this.volume = volume
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        super.setPreferredDevice(preferredDevice)
        this.preferredDevice = preferredDevice
        record("preferred_device_changed")
    }

    override fun release() {
        record("release")
        super.release()
    }

    private fun sample() {
        val now = nowMs()
        if (lastSampleMs != Long.MIN_VALUE && now - lastSampleMs < 1_000L) return
        lastSampleMs = now
        if (track == null) return
        if (!probeAvailable) return
        try {
            sampleTrack(now)
        } catch (_: RuntimeException) {
            // A vendor diagnostic getter must not interrupt playback.
            probeAvailable = false
            record("probe_unavailable")
        }
    }

    private fun sampleTrack(now: Long) {
        val track = checkNotNull(track)
        val device = track.routedDevice
        if (device?.id != routedDeviceId) {
            routedDeviceId = device?.id
            record("route_changed")
        }
        if (pcm16) {
            probe
                .observe(
                    nowMs = now,
                    playbackHead = track.playbackHeadPosition.toLong() and 0xffffffffL,
                    writtenFrames = writtenBytes / bytesPerFrame,
                    silentFrames = silentFrames,
                    sampleRate = config.sampleRate,
                    expectProgress = playing && volume > 0f,
                )?.let(::record)
        }
        val stalled =
            pcm16 && !config.isOffload &&
                stallProbe.observe(
                    nowMs = now,
                    timestampFrame = if (track.getTimestamp(timestamp)) timestamp.framePosition else null,
                    writtenFrames = writtenBytes / bytesPerFrame,
                    expectProgress = playing && volume > 0f,
                )
        onOutputSignal(
            device.outputSignal(stalled).copy(
                pcmAccepted = pcm16 && !config.isOffload && playing && volume > 0f && writtenBytes > sampledWrittenBytes,
            ),
        )
        sampledWrittenBytes = writtenBytes
        record("sample")
        peak = 0
    }

    private fun record(event: String) {
        onEvent(
            event,
            buildMap {
                put("outputId", Integer.toHexString(System.identityHashCode(this@DiagnosticAudioOutput)))
                put("outputSampleElapsedMs", nowMs().toString())
                put("outputProbeAvailable", probeAvailable.toString())
                put("outputSessionId", config.audioSessionId.toString())
                put("outputPlaying", playing.toString())
                put("outputVolume", volume.toString())
                put("outputWrittenBytes", writtenBytes.toString())
                put("outputEncoding", config.encoding.toString())
                put("outputSampleRate", config.sampleRate.toString())
                put("outputChannelMask", config.channelMask.toString())
                put("outputOffload", config.isOffload.toString())
                put("outputHardwareParameters", config.usePlaybackParameters.toString())
                put("outputUsage", config.audioAttributes.usage.toString())
                put("outputContentType", config.audioAttributes.contentType.toString())
                put("outputPreferredDevice", preferredDevice.diagnosticId())
                if (pcm16) {
                    put("outputWrittenFrames", (writtenBytes / bytesPerFrame).toString())
                    put("outputPcmPeak", peak.toString())
                    put("outputSilentFrames", silentFrames.toString())
                }
                try {
                    put("outputSessionId", audioSessionId.toString())
                    val parameters = playbackParameters
                    put("outputSpeed", parameters.speed.toString())
                    put("outputPitch", parameters.pitch.toString())
                    track?.let {
                        put("outputRoutedDevice", it.routedDevice.diagnosticId())
                        put("outputPlaybackHeadFrames", (it.playbackHeadPosition.toLong() and 0xffffffffL).toString())
                        put("outputTrackState", it.state.toString())
                        put("outputTrackPlayState", it.playState.toString())
                        put("outputBufferFrames", it.bufferSizeInFrames.toString())
                        put("outputUnderruns", it.underrunCount.toString())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            put("outputPerformanceMode", it.performanceMode.toString())
                        }
                    }
                } catch (error: RuntimeException) {
                    put("outputDiagnosticsError", error.javaClass.simpleName)
                }
            },
        )
    }
}

internal fun AudioDeviceInfo?.diagnosticId(): String = this?.let { "id=${it.id},type=${it.type}" } ?: "none"

internal fun AudioDeviceInfo?.outputSignal(stalled: Boolean = false): AudioOutputSignal =
    AudioOutputSignal(
        routeId = this?.id,
        bluetooth =
            this != null && type in
                setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                ),
        stalled = stalled,
        a2dpAddress =
            if (this?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                address
            } else {
                null
            },
    )

// These signals request diagnostics only. Neither proves that the endpoint is audible.
internal class AudioOutputProbe {
    private var previousHead = -1L
    private var unchangedSinceMs = 0L
    private var reported: String? = null
    private var hadPendingFrames = false

    fun reset() {
        previousHead = -1L
        reported = null
        hadPendingFrames = false
    }

    fun observe(
        nowMs: Long,
        playbackHead: Long,
        writtenFrames: Long,
        silentFrames: Long,
        sampleRate: Int,
        expectProgress: Boolean,
    ): String? {
        val pendingFrames = expectProgress && writtenFrames > playbackHead
        if (!pendingFrames || !hadPendingFrames || playbackHead != previousHead) {
            unchangedSinceMs = nowMs
        }
        hadPendingFrames = pendingFrames
        previousHead = playbackHead
        val reason =
            when {
                !expectProgress -> null
                pendingFrames && nowMs - unchangedSinceMs >= 2_000L -> "playback_head_stalled"
                silentFrames >= sampleRate * 2L -> "zero_pcm"
                else -> null
            }
        if (reason == reported) return null
        val previous = reported
        reported = reason
        return reason ?: previous?.takeIf { expectProgress }?.let { "${it}_cleared" }
    }
}
