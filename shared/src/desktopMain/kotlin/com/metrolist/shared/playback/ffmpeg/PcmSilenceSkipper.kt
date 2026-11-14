package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.playback.InstantSilenceDetector
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow

internal class PcmSilenceSkipper(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val frameBytes = channels * 2
    private val pending = ByteArray((sampleRate / 2).coerceAtLeast(1) * frameBytes)
    private val silenceThreshold = (Short.MAX_VALUE * 10.0.pow(-50.0 / 20.0)).toInt()
    private val output = ByteArrayOutputStream()
    private var pendingBytes = 0
    private var retainedFrames = 0
    var outputFrames = 0L
        private set
    private val instantDetector = InstantSilenceDetector(sampleRate)
    val isLongSilence: Boolean get() = instantDetector.isSilent

    fun process(
        data: ByteArray,
        enabled: Boolean,
        instant: Boolean,
        skipped: (Long, Long) -> Unit,
    ): ByteArray {
        if (!enabled) {
            val output = if (pendingBytes == 0) data else pending.copyOf(pendingBytes) + data
            pendingBytes = 0
            retainedFrames = 0
            instantDetector.reset()
            outputFrames += output.size / frameBytes
            return output
        }
        require(data.size % frameBytes == 0)
        output.reset()
        var audibleStart = -1

        fun emit(
            bytes: ByteArray,
            length: Int,
            offset: Int = 0,
        ) {
            if (length == 0) return
            output.write(bytes, offset, length)
            outputFrames += length / frameBytes
        }
        for (offset in data.indices step frameBytes) {
            var peak = 0
            repeat(channels) { channel ->
                val index = offset + channel * 2
                val sample = (data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)
                peak = maxOf(peak, abs(sample))
            }
            if (instant) instantDetector.frame(peak) else instantDetector.reset()
            if (peak <= silenceThreshold) {
                if (audibleStart >= 0) {
                    emit(data, offset - audibleStart, audibleStart)
                    audibleStart = -1
                }
                data.copyInto(pending, pendingBytes, offset, offset + frameBytes)
                pendingBytes += frameBytes
                if (pendingBytes == pending.size) {
                    val frames = pendingBytes / frameBytes
                    val keep = minOf(frames / 5, (sampleRate * 2 - retainedFrames).coerceAtLeast(0))
                    emit(pending, keep * frameBytes)
                    retainedFrames += keep
                    skipped(outputFrames, (frames - keep).toLong())
                    pendingBytes = 0
                }
            } else {
                emit(pending, pendingBytes)
                pendingBytes = 0
                retainedFrames = 0
                if (audibleStart < 0) audibleStart = offset
            }
        }
        if (audibleStart >= 0) emit(data, data.size - audibleStart, audibleStart)
        return output.toByteArray()
    }

    fun drain(): ByteArray =
        pending.copyOf(pendingBytes).also {
            outputFrames += pendingBytes / frameBytes
            pendingBytes = 0
        }

    fun reset() {
        pendingBytes = 0
        retainedFrames = 0
        outputFrames = 0
        instantDetector.reset()
    }
}
