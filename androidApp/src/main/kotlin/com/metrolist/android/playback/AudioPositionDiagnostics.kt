package com.metrolist.android.playback

import android.annotation.SuppressLint
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

internal class AudioPositionDiagnostics {
    @Volatile var details: Map<String, String> = emptyMap()
        private set

    // Diagnostic only. Hidden APIs can be absent or blocked on the current Android release.
    @SuppressLint("PrivateApi")
    private val latencyMethod = runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull()

    fun wrap(
        provider: AudioOutputProvider,
        onOutputEvent: ((String, Map<String, String>) -> Unit)? = null,
        onOutputSignal: (AudioOutputSignal) -> Unit = {},
    ): AudioOutputProvider =
        object : ForwardingAudioOutputProvider(provider) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val platformOutput = super.getAudioOutput(config)
                val track = (platformOutput as? AudioTrackAudioOutput)?.audioTrack ?: return platformOutput
                val diagnosticOutput =
                    onOutputEvent?.let { DiagnosticAudioOutput(platformOutput, config, it, onOutputSignal = onOutputSignal) }
                        ?: platformOutput
                val output =
                    if (Util.isEncodingLinearPcm(config.encoding) && !config.isOffload && !config.isTunneling) {
                        TimestampAudioOutput(
                            diagnosticOutput,
                            Util.getPcmFrameSize(config.encoding, track.channelCount),
                            track::getTimestamp,
                            { track.routedDevice?.id },
                        )
                    } else {
                        diagnosticOutput
                    }
                details = emptyMap()
                return object : ForwardingAudioOutput(output) {
                    private var lastSampleNs = Long.MIN_VALUE
                    private val timestamp = AudioTimestamp()

                    @Volatile private var released = false
                    private val routingListener =
                        AudioRouting.OnRoutingChangedListener {
                            if (!released) {
                                try {
                                    onOutputSignal(track.routedDevice.outputSignal())
                                } catch (_: RuntimeException) {
                                    // A vendor routing getter must not interrupt playback.
                                }
                            }
                        }

                    init {
                        track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
                    }

                    override fun getPositionUs(): Long {
                        val positionUs = super.getPositionUs()
                        val nowNs = System.nanoTime()
                        if (lastSampleNs == Long.MIN_VALUE || nowNs - lastSampleNs >= 1_000_000_000L) {
                            lastSampleNs = nowNs
                            details =
                                runCatching { snapshot(track, config, timestamp, positionUs, nowNs, playbackParameters.speed) }
                                    .getOrElse { mapOf("audioPositionDiagnosticError" to it.javaClass.simpleName) }
                        }
                        return positionUs
                    }

                    override fun release() {
                        released = true
                        track.removeOnRoutingChangedListener(routingListener)
                        details = emptyMap()
                        super.release()
                    }
                }
            }
        }

    private fun snapshot(
        track: AudioTrack,
        config: AudioOutputProvider.OutputConfig,
        timestamp: AudioTimestamp,
        positionUs: Long,
        nowNs: Long,
        outputSpeed: Float,
    ): Map<String, String> {
        val hasTimestamp = track.getTimestamp(timestamp)
        val latencyMs = runCatching { latencyMethod?.invoke(track) as? Int }.getOrNull()
        val pcm = Util.isEncodingLinearPcm(config.encoding)
        val bufferDurationUs =
            if (pcm) {
                val frameSize = Util.getPcmFrameSize(config.encoding, track.channelCount)
                config.bufferSize.toLong() / frameSize * C.MICROS_PER_SECOND / config.sampleRate
            } else {
                null
            }
        return buildMap {
            put("audioPositionSampleNs", nowNs.toString())
            put("audioOutputPositionUs", positionUs.toString())
            put("audioOutputSpeed", outputSpeed.toString())
            put("audioPlaybackHeadFrames", Integer.toUnsignedLong(track.playbackHeadPosition).toString())
            put("audioSampleRate", config.sampleRate.toString())
            put("audioRouteType", track.routedDevice?.type.toString())
            put("audioRouteId", track.routedDevice?.id.toString())
            put("audioOutputOffload", config.isOffload.toString())
            put("audioBufferDurationUs", bufferDurationUs.toString())
            put("audioTrackLatencyMs", latencyMs.toString())
            put("audioPcmFallbackLatencyUs", pcmFallbackLatencyUs(latencyMs, bufferDurationUs).toString())
            put("audioTimestampAvailable", hasTimestamp.toString())
            if (hasTimestamp) {
                val timestampPositionUs =
                    timestamp.framePosition * C.MICROS_PER_SECOND / config.sampleRate +
                        ((nowNs - timestamp.nanoTime) / 1_000L * outputSpeed).toLong()
                put("audioTimestampFrames", timestamp.framePosition.toString())
                put("audioTimestampNs", timestamp.nanoTime.toString())
                put("audioTimestampAgeUs", ((nowNs - timestamp.nanoTime) / 1_000L).toString())
                put("audioTimestampPositionUs", timestampPositionUs.toString())
                put("audioOutputMinusTimestampUs", (positionUs - timestampPositionUs).toString())
            }
        }
    }
}

// Mirrors Media3 1.12's PCM fallback calculation, without changing its position or timestamp choice.
internal fun pcmFallbackLatencyUs(
    latencyMs: Int?,
    bufferDurationUs: Long?,
): Long? {
    if (latencyMs == null || bufferDurationUs == null) return null
    val latencyUs = (latencyMs * 1_000L - bufferDurationUs).coerceAtLeast(0L)
    return if (latencyUs > 10_000_000L) 0L else latencyUs
}
