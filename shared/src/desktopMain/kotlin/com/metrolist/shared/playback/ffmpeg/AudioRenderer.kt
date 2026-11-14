package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerConfiguration
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.MAX_AUDIO_BOOST_DB
import com.metrolist.shared.equalizer.MIN_AUDIO_PREAMP_DB
import com.metrolist.shared.equalizer.ParametricEqualizer
import com.metrolist.shared.playback.normalizationBoostPcm16
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

class AudioRenderer private constructor(
    private val sampleRate: Int,
    private val channelCount: Int,
    lineFactory: (AudioFormat, Int) -> SourceDataLine,
    private val configuration: () -> EqualizerConfiguration,
    private val silenceSettings: () -> Pair<Boolean, Boolean>,
) {
    constructor(sampleRate: Int, channelCount: Int) :
        this(sampleRate, channelCount, ::openAudioLine, { EqualizerProfiles.configuration }, {
            Settings.shared.skipSilence to
                Settings.shared.skipSilenceInstant
        })

    internal constructor(
        sampleRate: Int,
        channelCount: Int,
        line: SourceDataLine,
        silenceSettings: () -> Pair<Boolean, Boolean> = { false to false },
        activeEqualizer: () -> EqualizerProfile? = { null },
    ) : this(sampleRate, channelCount, {
        _,
        _,
        ->
        line
    }, { EqualizerConfiguration(activeEqualizer(), Settings.shared.audioBoostDb.toDouble()) }, silenceSettings)

    private val audioFormat = AudioFormat(sampleRate.toFloat(), 16, channelCount, true, false)
    private val line: SourceDataLine
    private val masterGainControl: FloatControl?

    @Volatile
    private var hardwareVolumeApplied = false

    @Volatile
    private var volume = 1.0f

    @Volatile
    var normalizationGain = 1f

    @Volatile
    private var isPlaying = false

    @Volatile
    private var hasWrittenAudio = false

    private var playbackSpeed = 1f
    private var playbackPitch = 1f
    private var processorPitch = 1f
    private var positionSpeed = 1f
    private var writtenFrameCount = 0L
    private var expectingOutput = false
    private var lastWriteAtNs = 0L
    private var lastUnderrunAtNs = 0L
    private var underrunCount = 0L

    @Volatile
    private var outputGeneration = 0L
    private var tempoProcessor: AudioTempoProcessor? = null
    private val equalizer = ParametricEqualizer(sampleRate, channelCount)
    private val positionClock = AudioPositionClock(audioFormat.sampleRate)
    private val silenceSkipper = PcmSilenceSkipper(sampleRate, channelCount)

    fun isLongSilence(): Boolean =
        synchronized(safeLock) {
            val (enabled, instant) = silenceSettings()
            enabled && instant && silenceSkipper.isLongSilence
        }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val safeLock = Object()

    init {
        val latency = 0.15
        val bufferSize = (latency * audioFormat.sampleRate * audioFormat.frameSize).toInt()
        line =
            lineFactory(audioFormat, bufferSize).apply {
                if (!isOpen) open(audioFormat, bufferSize)
                start()
                isPlaying = true
            }

        masterGainControl =
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                line.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            } else {
                null
            }
    }

    fun writePcmData(
        data: ByteArray?,
        isCurrent: () -> Boolean = { true },
    ) {
        if (data == null || data.isEmpty()) return
        val (output, generation, outputSpeed) =
            synchronized(safeLock) {
                if (!line.isOpen || !isCurrent()) return
                val current = configuration()
                equalizer.update(current.profile)
                val boostDb = current.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
                val equalized = equalize(data, boostDb)
                val (enabled, instant) = silenceSettings()
                val input = silenceSkipper.process(equalized, enabled, instant, positionClock::skipSilence)
                val chunks =
                    buildList {
                        if (tempoProcessor != null && (processorPitch != playbackPitch || (playbackSpeed == 1f && playbackPitch == 1f))) {
                            tempoProcessor?.process(null, ::add)
                            tempoProcessor?.close()
                            tempoProcessor = null
                        }
                        if ((playbackSpeed != 1f || playbackPitch != 1f) && tempoProcessor == null) {
                            tempoProcessor = AudioTempoProcessor(sampleRate, channelCount, playbackSpeed, playbackPitch)
                            processorPitch = playbackPitch
                        }
                        if (input.isNotEmpty()) tempoProcessor?.process(input, ::add) ?: add(input)
                    }
                Triple(chunks, outputGeneration, playbackSpeed)
            }
        if (output.isNotEmpty()) recordOutputUnderrun(generation)
        for (chunk in output) {
            if (!writeOutput(chunk, generation, outputSpeed, isCurrent)) return
        }
    }

    private fun writeOutput(
        data: ByteArray,
        generation: Long,
        outputSpeed: Float,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        val currentVol = if (volume == 0f || !hardwareVolumeApplied) volume else 1f
        val boost = normalizationGain
        val processedData =
            if (currentVol != 1f || boost > 1f) {
                applyVolume(data, currentVol, boost)
            } else {
                data
            }
        var offset = 0
        while (offset < processedData.size) {
            val writtenBytes =
                synchronized(safeLock) {
                    while (generation == outputGeneration && line.isOpen && !isPlaying) {
                        try {
                            safeLock.wait()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                    if (generation != outputGeneration || !line.isOpen || !isCurrent()) return false
                    val writableBytes =
                        minOf(processedData.size - offset, line.available()) / audioFormat.frameSize * audioFormat.frameSize
                    if (writableBytes == 0) {
                        val bufferedFrames = (line.bufferSize - line.available()).coerceAtLeast(0) / audioFormat.frameSize
                        val waitMs = (bufferedFrames * 500L / sampleRate).coerceIn(1L, 20L)
                        try {
                            safeLock.wait(waitMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                        0
                    } else {
                        if (outputSpeed != positionSpeed) {
                            positionClock.changeSpeed(writtenFrameCount, outputSpeed)
                            positionSpeed = outputSpeed
                        }
                        line.write(processedData, offset, writableBytes).also { writtenBytes ->
                            if (writtenBytes > 0) {
                                writtenFrameCount += writtenBytes / audioFormat.frameSize
                                hasWrittenAudio = true
                                expectingOutput = true
                                lastWriteAtNs = System.nanoTime()
                            }
                        }
                    }
                }
            if (writtenBytes > 0) {
                offset += writtenBytes
            }
        }
        return true
    }

    private fun recordOutputUnderrun(generation: Long) {
        val details =
            synchronized(safeLock) {
                if (generation != outputGeneration || !line.isOpen || !isPlaying || !expectingOutput) return
                val bufferBytes = line.bufferSize
                if (bufferBytes <= 0 || line.available() < bufferBytes) return
                expectingOutput = false
                underrunCount++
                val now = System.nanoTime()
                if (lastUnderrunAtNs != 0L && now - lastUnderrunAtNs < 1_000_000_000L) return
                lastUnderrunAtNs = now
                mapOf(
                    "rendererId" to System.identityHashCode(this).toString(),
                    "underrunCount" to underrunCount.toString(),
                    "audioBufferBytes" to bufferBytes.toString(),
                    "audioBufferMs" to (bufferBytes * 1_000L / audioFormat.frameSize / sampleRate).toString(),
                    "lastAudioWriteMs" to ((now - lastWriteAtNs) / 1_000_000L).toString(),
                    "lineActive" to line.isActive.toString(),
                    "lineRunning" to line.isRunning.toString(),
                    "lineFramePosition" to line.longFramePosition.toString(),
                    "writtenFrames" to writtenFrameCount.toString(),
                    "sampleRate" to sampleRate.toString(),
                    "channels" to channelCount.toString(),
                    "renderThreadPriority" to Thread.currentThread().priority.toString(),
                )
            }
        PlaybackEventLog.warn(PlaybackEventKind.PLAYER, "Desktop audio output buffer emptied between writes.", details = details)
    }

    private var equalizerSamples = ShortArray(0)
    private var equalizerOutput = ByteArray(0)

    private fun equalize(
        data: ByteArray,
        outputBoostDb: Double,
    ): ByteArray {
        val count = data.size / 2
        if (equalizerSamples.size < count) equalizerSamples = ShortArray(count)
        if (equalizerOutput.size != data.size) equalizerOutput = ByteArray(data.size)
        for (i in 0 until count) {
            equalizerSamples[i] = ((data[2 * i].toInt() and 0xff) or (data[2 * i + 1].toInt() shl 8)).toShort()
        }
        equalizer.processPcm16(equalizerSamples, count, outputBoostDb)
        AudioSpectrumMeter.accept(equalizerSamples, count, channelCount, sampleRate, equalizer.gainReductionDb.toFloat())
        data.copyInto(equalizerOutput)
        for (i in 0 until count) {
            equalizerOutput[2 * i] = equalizerSamples[i].toByte()
            equalizerOutput[2 * i + 1] = (equalizerSamples[i].toInt() shr 8).toByte()
        }
        return equalizerOutput
    }

    private var volumeBuffer = ByteArray(0)

    private fun applyVolume(
        data: ByteArray,
        volume: Float,
        boost: Float,
    ): ByteArray {
        if (volumeBuffer.size != data.size) {
            volumeBuffer = ByteArray(data.size)
        }
        val result = volumeBuffer
        var byteIdx = 0
        val len = data.size - 1
        while (byteIdx < len) {
            val low = data[byteIdx].toInt() and 0xFF
            val high = data[byteIdx + 1].toInt()
            val sample = (high shl 8) or low
            val scaled =
                (
                    normalizationBoostPcm16(
                        sample.toShort(),
                        boost,
                    ) * volume
                ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[byteIdx] = (scaled and 0xFF).toByte()
            result[byteIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
            byteIdx += 2
        }
        return result
    }

    fun resetPosition(mediaMs: Long) {
        synchronized(safeLock) {
            outputGeneration++
            expectingOutput = false
            safeLock.notifyAll()
            val lineFrame = line.longFramePosition
            writtenFrameCount = lineFrame
            positionSpeed = playbackSpeed
            positionClock.reset(mediaMs, lineFrame, playbackSpeed)
            silenceSkipper.reset()
            hasWrittenAudio = false
        }
    }

    fun getPlayedPositionMs(): Long =
        if (line.isOpen) {
            positionClock.position(line.longFramePosition)
        } else {
            0L
        }

    fun pause() {
        synchronized(safeLock) {
            isPlaying = false
            expectingOutput = false
            safeLock.notifyAll()
            if (line.isOpen && line.isActive) {
                try {
                    line.stop()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun resume() {
        synchronized(safeLock) {
            isPlaying = true
            if (line.isOpen && !line.isActive) {
                try {
                    line.start()
                } catch (_: Exception) {
                }
            }
            safeLock.notifyAll()
        }
    }

    fun flush() {
        synchronized(safeLock) {
            outputGeneration++
            expectingOutput = false
            safeLock.notifyAll()
            tempoProcessor?.close()
            tempoProcessor = null
            equalizer.reset()
            silenceSkipper.reset()
            if (line.isOpen) {
                line.flush()
            }
        }
    }

    fun setPlaybackPitch(pitch: Float) {
        require(pitch.isFinite() && pitch in 0.5f..2f)
        synchronized(safeLock) { playbackPitch = pitch }
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..2f)
        synchronized(safeLock) {
            if (speed != playbackSpeed) {
                tempoProcessor?.setSpeed(speed)
                playbackSpeed = speed
            }
        }
    }

    fun drain(shouldContinue: () -> Boolean): Boolean {
        val (pending, generation, outputSpeed) =
            synchronized(safeLock) {
                if (!shouldContinue()) return false
                expectingOutput = false
                Triple(
                    buildList {
                        val tail = silenceSkipper.drain()
                        if (tail.isNotEmpty()) tempoProcessor?.process(tail, ::add) ?: add(tail)
                        tempoProcessor?.process(null, ::add)
                    },
                    outputGeneration,
                    playbackSpeed,
                )
            }
        for (data in pending) {
            if (!shouldContinue() || !writeOutput(data, generation, outputSpeed)) return false
        }
        synchronized(safeLock) {
            if (generation != outputGeneration || !shouldContinue()) return false
            expectingOutput = false
            positionClock.finishSilence(writtenFrameCount, silenceSkipper.outputFrames)
        }
        // SourceDataLine.drain() cannot be interrupted reliably on every mixer.
        return awaitAudioDrain(
            shouldContinue = { generation == outputGeneration && shouldContinue() },
            hasPendingAudio = {
                synchronized(safeLock) {
                    while (generation == outputGeneration && line.isOpen && !isPlaying && shouldContinue()) safeLock.wait()
                    line.isOpen && line.available() < line.bufferSize
                }
            },
        )
    }

    fun setVolume(volume: Float) {
        val clampedVol = volume.coerceIn(0.0f, 1.0f)
        this.volume = clampedVol
        hardwareVolumeApplied = false
        masterGainControl?.let { control ->
            try {
                if (clampedVol <= 0.0001f) {
                    control.value = control.minimum
                } else {
                    val dB = (20.0f * log10(clampedVol)).coerceIn(control.minimum, control.maximum)
                    control.value = dB
                }
                hardwareVolumeApplied = true
            } catch (_: Exception) {
                runCatching { control.value = 0f.coerceIn(control.minimum, control.maximum) }
            }
        }
    }

    fun getVolume(): Float = this.volume

    fun isPlaying(): Boolean = isPlaying && hasWrittenAudio

    fun getAudioSessionId(): Int = 0

    fun setPreferredDevice(device: Any?) {
    }

    fun close() {
        synchronized(safeLock) {
            outputGeneration++
            safeLock.notifyAll()
            isPlaying = false
            hasWrittenAudio = false
            tempoProcessor?.close()
            tempoProcessor = null
            if (line.isOpen) {
                line.flush()
                line.stop()
                line.close()
            }
        }
    }
}

internal class AudioPositionClock(
    private val sampleRate: Float,
) {
    private data class Segment(
        val lineFrame: Long,
        val mediaMs: Long,
        val speed: Float,
    )

    private val segments = mutableListOf(Segment(0L, 0L, 1f))
    private var lastPositionMs = 0L
    private var anchorMs = 0L
    private var skippedFrames = 0L
    private val silenceOffsets = mutableListOf<Pair<Long, Long>>()

    @Synchronized
    fun skipSilence(
        outputFrames: Long,
        frames: Long,
    ) {
        skippedFrames += frames
        val boundary = anchorMs + (outputFrames * 1_000.0 / sampleRate).toLong()
        if (silenceOffsets.lastOrNull()?.first == boundary) silenceOffsets.removeAt(silenceOffsets.lastIndex)
        silenceOffsets += boundary to skippedFrames
    }

    @Synchronized
    fun finishSilence(
        lineFrame: Long,
        outputFrames: Long,
    ) {
        if (skippedFrames == 0L) return
        // Anchor trailing silence at the actual output boundary because tempo filters round frame counts.
        segments += Segment(lineFrame, anchorMs + (outputFrames * 1_000.0 / sampleRate).toLong(), segments.last().speed)
    }

    @Synchronized
    fun reset(
        mediaMs: Long,
        lineFrame: Long,
        playbackSpeed: Float,
    ) {
        lastPositionMs = mediaMs.coerceAtLeast(0L)
        anchorMs = lastPositionMs
        skippedFrames = 0
        silenceOffsets.clear()
        segments.clear()
        segments += Segment(lineFrame, lastPositionMs, playbackSpeed)
    }

    @Synchronized
    fun changeSpeed(
        firstFrameAtNewSpeed: Long,
        playbackSpeed: Float,
    ) {
        val previous = segments.last()
        segments +=
            Segment(
                lineFrame = firstFrameAtNewSpeed,
                mediaMs = positionIn(previous, firstFrameAtNewSpeed),
                speed = playbackSpeed,
            )
    }

    @Synchronized
    fun position(lineFrame: Long): Long {
        if (sampleRate <= 0f) return lastPositionMs
        val segmentIndex = segments.indexOfLast { lineFrame >= it.lineFrame }.coerceAtLeast(0)
        val position = positionIn(segments[segmentIndex], lineFrame)
        if (segmentIndex > 0) segments.subList(0, segmentIndex).clear()
        val silenceIndex = silenceOffsets.indexOfLast { position >= it.first }
        val removedMs = if (silenceIndex >= 0) silenceOffsets[silenceIndex].second * 1_000.0 / sampleRate else 0.0
        if (silenceIndex > 0) silenceOffsets.subList(0, silenceIndex).clear()
        lastPositionMs = (position + removedMs).toLong().coerceAtLeast(lastPositionMs)
        return lastPositionMs
    }

    private fun positionIn(
        segment: Segment,
        lineFrame: Long,
    ): Long =
        segment.mediaMs +
            (((lineFrame - segment.lineFrame).coerceAtLeast(0L) * 1_000.0 * segment.speed) / sampleRate).toLong()
}
