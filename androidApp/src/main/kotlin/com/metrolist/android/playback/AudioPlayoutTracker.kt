package com.metrolist.android.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer

internal class AudioPlayoutTracker {
    internal class OutputProgress(
        val config: AudioOutputProvider.OutputConfig,
    ) {
        var writtenBytes = 0L

        @Volatile var positionUs = -1L

        fun writtenDurationUs(): Long? =
            if (Util.isEncodingLinearPcm(config.encoding)) {
                Util.sampleCountToDurationUs(
                    writtenBytes / Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask)),
                    config.sampleRate,
                )
            } else {
                null
            }
    }

    internal class Checkpoint(
        val output: OutputProgress?,
        @Volatile var endUs: Long? = null,
    )

    @Volatile private var output: OutputProgress? = null
    private var processingParameters = PlaybackParameters.DEFAULT
    private var pendingCheckpoint: Checkpoint? = null

    fun checkpoint(parameters: PlaybackParameters): Checkpoint {
        val current = output
        return Checkpoint(current).also { checkpoint ->
            if (current?.config?.usePlaybackParameters == true || processingParameters == parameters) {
                checkpoint.endUs = current?.writtenDurationUs()
            } else {
                pendingCheckpoint = checkpoint
            }
        }
    }

    fun hasPlayed(checkpoint: Checkpoint): Boolean =
        checkpoint.output == null || checkpoint.output !== output ||
            checkpoint.endUs?.let { checkpoint.output.positionUs > it } == true

    fun wrap(processors: AudioProcessorChain): AudioProcessorChain =
        object : AudioProcessorChain by processors {
            override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
                processors.applyPlaybackParameters(playbackParameters).also { applied ->
                    processingParameters = applied
                    // Media3 calls this only after writing the previous processors' tails to the output.
                    pendingCheckpoint?.let { checkpoint ->
                        checkpoint.endUs = output?.writtenDurationUs()
                        pendingCheckpoint = null
                    }
                }
        }

    fun wrap(provider: AudioOutputProvider): AudioOutputProvider =
        object : ForwardingAudioOutputProvider(provider) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val delegate = super.getAudioOutput(config)
                var progress = OutputProgress(config).also { output = it }
                return object : ForwardingAudioOutput(delegate) {
                    override fun write(
                        buffer: ByteBuffer,
                        encodedAccessUnitCount: Int,
                        presentationTimeUs: Long,
                    ): Boolean {
                        val before = buffer.position()
                        return super.write(buffer, encodedAccessUnitCount, presentationTimeUs).also {
                            progress.writtenBytes += buffer.position() - before
                        }
                    }

                    override fun getPositionUs(): Long = super.getPositionUs().also { progress.positionUs = it }

                    override fun flush() {
                        super.flush()
                        progress = OutputProgress(config).also { output = it }
                    }

                    override fun release() {
                        super.release()
                        if (output === progress) output = null
                    }
                }
            }
        }
}
