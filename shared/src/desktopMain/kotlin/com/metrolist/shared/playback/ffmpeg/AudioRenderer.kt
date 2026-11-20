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
import java.util.ArrayDeque
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

private data class RenderedAudioChunk(
    val data: ByteArray,
    val playbackSpeed: Float,
)

class AudioRenderer internal constructor(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val lineFactory: (AudioFormat, Int) -> SourceDataLine,
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
    ) : this(
        sampleRate,
        channelCount,
        { _, _ -> line },
        { EqualizerConfiguration(activeEqualizer(), Settings.shared.audioBoostDb.toDouble()) },
        silenceSettings,
    )

    private val audioFormat = AudioFormat(sampleRate.toFloat(), 16, channelCount, true, false)
    private var line: SourceDataLine
    private var masterGainControl: FloatControl? = null
    private val lineBufferSize = (0.15 * audioFormat.sampleRate * audioFormat.frameSize).toInt()

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
    private var processorSpeed = 1f
    private var processorPitch = 1f
    private var positionSpeed = 1f
    private var writtenFrameCount = 0L

    // Retain the unplayed tail so a replacement line can replay frames accepted by a closed device.
    private val pendingOutputChunks = ArrayDeque<ByteArray>()
    private var pendingOutputChunkOffset = 0
    private var pendingOutputBaseFrame = 0L
    private var lineFrameOffset = 0L
    private var lastValidOutputFrame = 0L
    private var replayOutputChunks = emptyList<ByteArray>()
    private var replayOutputChunkIndex = 0
    private var replayOutputChunkOffset = 0
    private var expectingOutput = false
    private var lastWriteAtNs = 0L
    private var lastUnderrunAtNs = 0L
    private var underrunCount = 0L

    @Volatile
    private var outputGeneration = 0L
    private var closed = false
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
        line = createOutputLine(start = true)
        isPlaying = true
        updateMasterGainControl()
    }

    fun writePcmData(
        data: ByteArray?,
        isCurrent: () -> Boolean = { true },
    ) {
        if (data == null || data.isEmpty()) return
        val (output, generation) =
            synchronized(safeLock) {
                if (closed) return
                if (!isCurrent()) return
                reopenOutputLineIfClosed()
                val current = configuration()
                equalizer.update(current.profile)
                val boostDb = current.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
                val equalized = equalize(data, boostDb)
                val (enabled, instant) = silenceSettings()
                val input = silenceSkipper.process(equalized, enabled, instant, positionClock::skipSilence)
                val chunks =
                    buildList {
                        if (
                            tempoProcessor != null &&
                            (
                                processorPitch != playbackPitch ||
                                    (playbackSpeed == 1f && playbackPitch == 1f) ||
                                    tempoProcessor?.requiresRebuild(playbackSpeed, playbackPitch) == true
                            )
                        ) {
                            val previousSpeed = processorSpeed
                            tempoProcessor?.process(null) { add(RenderedAudioChunk(it, previousSpeed)) }
                            tempoProcessor?.close()
                            tempoProcessor = null
                        }
                        if ((playbackSpeed != 1f || playbackPitch != 1f) && tempoProcessor == null) {
                            tempoProcessor = AudioTempoProcessor(sampleRate, channelCount, playbackSpeed, playbackPitch)
                            processorSpeed = playbackSpeed
                            processorPitch = playbackPitch
                        }
                        if (tempoProcessor != null && processorSpeed != playbackSpeed) {
                            tempoProcessor?.setSpeed(playbackSpeed)
                            processorSpeed = playbackSpeed
                        }
                        if (input.isNotEmpty()) {
                            tempoProcessor?.process(input) { add(RenderedAudioChunk(it, processorSpeed)) }
                                ?: add(RenderedAudioChunk(input, 1f))
                        }
                    }
                chunks to outputGeneration
            }
        if (output.isNotEmpty()) recordOutputUnderrun(generation)
        for (chunk in output) {
            if (!writeOutput(chunk.data, generation, chunk.playbackSpeed, isCurrent)) return
        }
    }

    private fun writeOutput(
        data: ByteArray,
        generation: Long,
        outputSpeed: Float,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        var offset = 0
        while (offset < data.size) {
            val result =
                synchronized(safeLock) {
                    while (!closed && generation == outputGeneration && !isPlaying) {
                        try {
                            safeLock.wait()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                    if (closed || generation != outputGeneration || !isCurrent()) return false
                    reopenOutputLineIfClosed()
                    if (replayOutputChunkIndex < replayOutputChunks.size) {
                        val replayedBytes = writePendingReplayOutput()
                        if (replayedBytes == 0) {
                            try {
                                safeLock.wait(10L)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return false
                            }
                        }
                        return@synchronized OutputWriteResult(inputBytesWritten = 0)
                    }
                    val currentVol = if (volume == 0f || !hardwareVolumeApplied) volume else 1f
                    val boost = normalizationGain
                    val outputData =
                        if (currentVol != 1f || boost > 1f) {
                            applyVolume(data, currentVol, boost)
                        } else {
                            data
                        }
                    val availableBytes =
                        try {
                            line.available()
                        } catch (error: Exception) {
                            if (!line.isOpen) {
                                reopenOutputLineIfClosed()
                                return@synchronized OutputWriteResult(inputBytesWritten = 0)
                            }
                            throw error
                        }
                    val writableBytes =
                        minOf(outputData.size - offset, availableBytes) / audioFormat.frameSize * audioFormat.frameSize
                    if (writableBytes == 0) {
                        val bufferedFrames = (line.bufferSize - availableBytes).coerceAtLeast(0) / audioFormat.frameSize
                        val waitMs = (bufferedFrames * 500L / sampleRate).coerceIn(1L, 20L)
                        try {
                            safeLock.wait(waitMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                        OutputWriteResult(inputBytesWritten = 0)
                    } else {
                        if (outputSpeed != positionSpeed) {
                            positionClock.changeSpeed(writtenFrameCount, outputSpeed)
                            positionSpeed = outputSpeed
                        }
                        try {
                            line.write(outputData, offset, writableBytes)
                        } catch (error: Exception) {
                            if (!line.isOpen) {
                                reopenOutputLineIfClosed()
                                return@synchronized OutputWriteResult(inputBytesWritten = 0)
                            }
                            throw error
                        }.let { writtenBytes ->
                            if (writtenBytes > 0) {
                                rememberWrittenOutput(data, offset, writtenBytes)
                                writtenFrameCount += writtenBytes / audioFormat.frameSize
                                discardPlayedOutput()
                                hasWrittenAudio = true
                                expectingOutput = true
                                lastWriteAtNs = System.nanoTime()
                            }
                            OutputWriteResult(inputBytesWritten = writtenBytes)
                        }
                    }
                }
            if (result.inputBytesWritten > 0) {
                offset += result.inputBytesWritten
            }
        }
        return true
    }

    private data class OutputWriteResult(
        val inputBytesWritten: Int,
    )

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
                    "lineFramePosition" to currentOutputFrame().toString(),
                    "writtenFrames" to writtenFrameCount.toString(),
                    "sampleRate" to sampleRate.toString(),
                    "channels" to channelCount.toString(),
                    "renderThreadPriority" to Thread.currentThread().priority.toString(),
                )
            }
        PlaybackEventLog.warn(PlaybackEventKind.PLAYER, "Desktop audio output buffer emptied between writes.", details = details)
    }

    private fun createOutputLine(start: Boolean): SourceDataLine =
        lineFactory(audioFormat, lineBufferSize).apply {
            if (!isOpen) open(audioFormat, lineBufferSize)
            if (start) start()
        }

    private fun reopenOutputLineIfClosed() {
        if (closed || line.isOpen) return
        val playedFrame = currentOutputFrame().coerceIn(0L, writtenFrameCount)
        discardPlayedOutput()
        val replacement = createOutputLine(start = isPlaying)
        line = replacement
        lineFrameOffset = playedFrame - replacement.longFramePosition
        lastValidOutputFrame = playedFrame
        updateMasterGainControl()
        replayOutputChunks =
            pendingOutputChunks.mapIndexed { index, chunk ->
                if (index == 0 && pendingOutputChunkOffset > 0) chunk.copyOfRange(pendingOutputChunkOffset, chunk.size) else chunk.copyOf()
            }
        replayOutputChunkIndex = 0
        replayOutputChunkOffset = 0
        expectingOutput = pendingOutputChunks.isNotEmpty()
    }

    private fun rememberWrittenOutput(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (pendingOutputChunks.isEmpty()) {
            pendingOutputChunkOffset = 0
            pendingOutputBaseFrame = writtenFrameCount
        }
        pendingOutputChunks.addLast(data.copyOfRange(offset, offset + length))
    }

    private fun discardPlayedOutput() {
        if (pendingOutputChunks.isEmpty()) {
            pendingOutputBaseFrame = writtenFrameCount
            pendingOutputChunkOffset = 0
            return
        }
        val playedFrames = (currentOutputFrame().coerceAtMost(writtenFrameCount) - pendingOutputBaseFrame).coerceAtLeast(0L)
        var bytesToDiscard = (playedFrames * audioFormat.frameSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (bytesToDiscard > 0 && pendingOutputChunks.isNotEmpty()) {
            val first = pendingOutputChunks.first()
            val available = first.size - pendingOutputChunkOffset
            val discarded = minOf(bytesToDiscard, available)
            pendingOutputChunkOffset += discarded
            bytesToDiscard -= discarded
            if (pendingOutputChunkOffset == first.size) {
                pendingOutputChunks.removeFirst()
                pendingOutputChunkOffset = 0
            }
        }
        val discardedFrames = playedFrames - bytesToDiscard / audioFormat.frameSize
        pendingOutputBaseFrame += discardedFrames
        if (pendingOutputChunks.isEmpty()) {
            pendingOutputBaseFrame = writtenFrameCount
            pendingOutputChunkOffset = 0
        }
    }

    private fun writePendingReplayOutput(): Int {
        val chunk = replayOutputChunks.getOrNull(replayOutputChunkIndex) ?: return 0
        val currentVol = if (volume == 0f || !hardwareVolumeApplied) volume else 1f
        val boost = normalizationGain
        val outputData = if (currentVol != 1f || boost > 1f) applyVolume(chunk, currentVol, boost) else chunk
        val availableBytes =
            try {
                line.available()
            } catch (error: Exception) {
                if (!line.isOpen) {
                    reopenOutputLineIfClosed()
                    return 0
                }
                throw error
            }
        val writableBytes = minOf(outputData.size - replayOutputChunkOffset, availableBytes) / audioFormat.frameSize * audioFormat.frameSize
        if (writableBytes <= 0) return 0
        val writtenBytes =
            try {
                line.write(outputData, replayOutputChunkOffset, writableBytes)
            } catch (error: Exception) {
                if (!line.isOpen) {
                    reopenOutputLineIfClosed()
                    return 0
                }
                throw error
            }
        if (writtenBytes > 0) {
            replayOutputChunkOffset += writtenBytes
            if (replayOutputChunkOffset >= chunk.size) {
                replayOutputChunkIndex++
                replayOutputChunkOffset = 0
            }
            discardPlayedOutput()
            expectingOutput = replayOutputChunkIndex < replayOutputChunks.size || pendingOutputChunks.isNotEmpty()
            lastWriteAtNs = System.nanoTime()
        }
        return writtenBytes
    }

    private fun currentOutputFrame(): Long {
        if (line.isOpen) {
            lastValidOutputFrame = maxOf(lastValidOutputFrame, lineFrameOffset + line.longFramePosition)
        }
        return lastValidOutputFrame
    }

    private fun updateMasterGainControl() {
        synchronized(safeLock) {
            masterGainControl =
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    line.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
                } else {
                    null
                }
            hardwareVolumeApplied = false
            setHardwareVolume()
        }
    }

    private fun setHardwareVolume() {
        synchronized(safeLock) {
            masterGainControl?.let { control ->
                try {
                    if (volume <= 0.0001f) {
                        control.value = control.minimum
                    } else {
                        val dB = (20.0f * log10(volume)).coerceIn(control.minimum, control.maximum)
                        control.value = dB
                    }
                    hardwareVolumeApplied = true
                } catch (_: Exception) {
                    runCatching { control.value = 0f.coerceIn(control.minimum, control.maximum) }
                }
            }
        }
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
            val lineFrame = currentOutputFrame()
            writtenFrameCount = lineFrame
            pendingOutputChunks.clear()
            pendingOutputChunkOffset = 0
            pendingOutputBaseFrame = lineFrame
            replayOutputChunks = emptyList()
            replayOutputChunkIndex = 0
            replayOutputChunkOffset = 0
            positionSpeed = playbackSpeed
            positionClock.reset(mediaMs, lineFrame, playbackSpeed)
            silenceSkipper.reset()
            hasWrittenAudio = false
        }
    }

    fun getPlayedPositionMs(): Long =
        synchronized(safeLock) {
            runCatching { positionClock.position(currentOutputFrame()) }.getOrDefault(positionClock.position(0L))
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
            if (closed) return
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
            pendingOutputChunks.clear()
            pendingOutputChunkOffset = 0
            pendingOutputBaseFrame = writtenFrameCount
            replayOutputChunks = emptyList()
            replayOutputChunkIndex = 0
            replayOutputChunkOffset = 0
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
                val processor = tempoProcessor
                if (processor != null && !processor.requiresRebuild(speed, playbackPitch)) {
                    processor.setSpeed(speed)
                    processorSpeed = speed
                }
                playbackSpeed = speed
            }
        }
    }

    fun drain(shouldContinue: () -> Boolean): Boolean {
        val (pending, generation) =
            synchronized(safeLock) {
                if (closed || !shouldContinue()) return false
                expectingOutput = false
                val chunks =
                    buildList {
                        val tail = silenceSkipper.drain()
                        if (tail.isNotEmpty()) {
                            tempoProcessor?.process(tail) { add(RenderedAudioChunk(it, processorSpeed)) }
                                ?: add(RenderedAudioChunk(tail, 1f))
                        }
                        tempoProcessor?.process(null) { add(RenderedAudioChunk(it, processorSpeed)) }
                    }
                chunks to outputGeneration
            }
        for (chunk in pending) {
            if (!shouldContinue() || !writeOutput(chunk.data, generation, chunk.playbackSpeed)) return false
        }
        while (true) {
            val replayPending =
                synchronized(safeLock) {
                    if (closed || generation != outputGeneration || !shouldContinue()) return false
                    reopenOutputLineIfClosed()
                    replayOutputChunkIndex < replayOutputChunks.size
                }
            if (!replayPending) break
            val written = synchronized(safeLock) { writePendingReplayOutput() }
            if (written == 0) {
                synchronized(safeLock) { safeLock.wait(10L) }
            }
        }
        val drainTimeoutMs =
            synchronized(safeLock) {
                if (closed || generation != outputGeneration || !shouldContinue()) return false
                reopenOutputLineIfClosed()
                expectingOutput = false
                positionClock.finishSilence(writtenFrameCount, silenceSkipper.outputFrames)
                val bufferedBytes = (line.bufferSize.toLong() - line.available()).coerceAtLeast(0L)
                val bytesPerSecond = sampleRate.toLong() * audioFormat.frameSize
                (bufferedBytes * 1_000L + bytesPerSecond - 1L) / bytesPerSecond + 250L
            }
        // SourceDataLine.drain() cannot be interrupted reliably on every mixer.
        // Some Linux mixers never report an empty buffer, so allow the queued duration plus scheduling margin.
        return awaitAudioDrain(
            timeoutMs = drainTimeoutMs,
            shouldContinue = { generation == outputGeneration && shouldContinue() },
            awaitResume = {
                synchronized(safeLock) {
                    while (generation == outputGeneration && line.isOpen && !isPlaying && shouldContinue()) safeLock.wait()
                }
            },
            hasPendingAudio = {
                synchronized(safeLock) { line.isOpen && line.available() < line.bufferSize }
            },
        )
    }

    fun setVolume(volume: Float) {
        val clampedVol = volume.coerceIn(0.0f, 1.0f)
        synchronized(safeLock) {
            if (closed) return
            this.volume = clampedVol
            hardwareVolumeApplied = false
            setHardwareVolume()
        }
    }

    fun getVolume(): Float = this.volume

    fun isPlaying(): Boolean = isPlaying && hasWrittenAudio

    fun getAudioSessionId(): Int = 0

    fun setPreferredDevice(device: Any?) {
    }

    fun close() {
        synchronized(safeLock) {
            if (closed) return
            closed = true
            outputGeneration++
            safeLock.notifyAll()
            pendingOutputChunks.clear()
            pendingOutputChunkOffset = 0
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
