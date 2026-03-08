package com.metrolist.android.playback

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.metrolist.shared.playback.InstantSilenceDetector
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.instantSilenceSeekTarget
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

private const val MUSIC_MINIMUM_SILENCE_DURATION_US = 500_000L
private const val MUSIC_SILENCE_THRESHOLD_DB = -50.0

internal class SilenceSkippingSupport(
    context: Context,
    vararg extraProcessors: AudioProcessor,
    onAudioOutputEvent: ((String, Map<String, String>) -> Unit)? = null,
    onOutputSignal: (AudioOutputSignal) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val detector = SilenceDetectorAudioProcessor()
    private val audioPositionDiagnostics = AudioPositionDiagnostics()
    val playoutTracker = AudioPlayoutTracker()
    val positionDiagnostics: Map<String, String> get() = audioPositionDiagnostics.details

    @Volatile private var equalizer: EqualizerAudioProcessor? = null

    @Volatile
    var audioOutputDetails: Map<String, String> = emptyMap()
        private set

    fun audioDiagnosticDetails(): Map<String, String> = positionDiagnostics + audioOutputDetails + equalizer?.diagnosticDetails().orEmpty()

    val renderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink {
                val equalizer = EqualizerAudioProcessor().also { this@SilenceSkippingSupport.equalizer = it }
                return pitchSafeAudioSink(
                    context,
                    enableAudioOutputPlaybackParams,
                    playoutTracker.wrap(musicAudioProcessorChain(equalizer, *extraProcessors, detector)),
                    outputProvider = {
                        playoutTracker.wrap(
                            audioPositionDiagnostics.wrap(
                                AudioTrackAudioOutputProvider.Builder(context).build(),
                                onOutputSignal = onOutputSignal,
                                onOutputEvent =
                                    onAudioOutputEvent?.let { callback ->
                                        { event, details ->
                                            audioOutputDetails = details
                                            if (event != "sample") callback(event, details)
                                        }
                                    },
                            ),
                        )
                    },
                )
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    fun attach(player: ExoPlayer) {
        player.skipSilenceEnabled = Settings.shared.skipSilence
        detector.enabled = Settings.shared.skipSilence && Settings.shared.skipSilenceInstant
        val playing = MutableStateFlow(player.isPlaying)
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    detector.resetTracking()
                    playing.value = isPlaying
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    detector.resetTracking()
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    // A newly available duration can allow the pending silence seek.
                    detector.signalSilence()
                }
            }
        player.addListener(listener)
        scope.launch {
            try {
                snapshotFlow { Settings.shared.skipSilence to Settings.shared.skipSilenceInstant }
                    .combine(playing) { settings, isPlaying -> Triple(settings.first, settings.second, isPlaying) }
                    .collectLatest { (enabled, instant, isPlaying) ->
                        player.skipSilenceEnabled = enabled
                        detector.enabled = enabled && instant
                        detector.resetTracking()
                        if (enabled && instant && isPlaying) {
                            detector.skipWhenConfirmed(
                                isPlaying = { player.isPlaying },
                                positionMs = { player.currentPosition },
                                durationMs = { player.duration },
                                seekTo = { position ->
                                    PlaybackSeekSource(
                                        "silence_skip",
                                        mapOf("playerId" to Integer.toHexString(System.identityHashCode(player))),
                                    ).record(player.currentPosition, position, player.currentMediaItem?.mediaId)
                                    player.seekTo(position)
                                },
                            )
                        }
                    }
            } finally {
                player.removeListener(listener)
            }
        }
    }

    fun close() = scope.cancel()
}

internal fun pitchSafeAudioSink(
    context: Context,
    hardwarePlaybackParameters: Boolean,
    processors: AudioProcessorChain,
    outputProvider: () -> AudioOutputProvider = { AudioTrackAudioOutputProvider.Builder(context).build() },
): AudioSink {
    val sink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(hardwarePlaybackParameters)
            .setAudioOutputProvider(outputProvider())
            .setAudioProcessorChain(processors)
            .build()
    return object : ForwardingAudioSink(sink) {
        private var softwarePitch = !hardwarePlaybackParameters

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            if (!softwarePitch && playbackParameters.pitch != 1f) {
                softwarePitch = true
                sink.setAudioOutputProvider(
                    object : ForwardingAudioOutputProvider(outputProvider()) {
                        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
                            super.getOutputConfig(formatConfig.buildUpon().setEnablePlaybackParameters(false).build())
                    },
                )
            }
            super.setPlaybackParameters(playbackParameters)
        }
    }
}

internal fun musicAudioProcessorChain(vararg processors: AudioProcessor): DefaultAudioSink.DefaultAudioProcessorChain =
    DefaultAudioSink.DefaultAudioProcessorChain(
        arrayOf(*processors),
        SilenceSkippingAudioProcessor(
            MUSIC_MINIMUM_SILENCE_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            (Short.MAX_VALUE * 10.0.pow(MUSIC_SILENCE_THRESHOLD_DB / 20.0)).toInt().toShort(),
        ),
        SonicAudioProcessor(),
    )

internal class SilenceDetectorAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
        @Synchronized set(value) {
            if (field == value) return
            field = value
            resetTracking()
        }
    private var detector: InstantSilenceDetector? = null
    private val thresholds = Channel<Long>(Channel.CONFLATED)
    private var generation = 0L
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var pendingOutput = AudioProcessor.EMPTY_BUFFER

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingOutput.hasRemaining()) return
        pendingInput.position(pendingInput.limit())
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        if (!inputBuffer.hasRemaining()) return
        if (enabled) {
            synchronized(this) {
                if (enabled) {
                    val detector = checkNotNull(detector)
                    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    var offset = inputBuffer.position()
                    while (offset + inputAudioFormat.bytesPerFrame <= inputBuffer.limit()) {
                        var peak = 0
                        repeat(inputAudioFormat.channelCount) {
                            peak = maxOf(peak, abs(inputBuffer.getShort(offset + it * 2).toInt()))
                        }
                        val wasSilent = detector.isSilent
                        detector.frame(peak)
                        if (wasSilent != detector.isSilent) {
                            generation++
                            if (detector.isSilent) thresholds.trySend(generation)
                        }
                        offset += inputAudioFormat.bytesPerFrame
                    }
                }
            }
        }
        // A separate cursor keeps upstream from reusing the PCM until downstream drains this view.
        pendingInput = inputBuffer
        pendingOutput = inputBuffer.duplicate().order(inputBuffer.order())
    }

    override fun getOutput(): ByteBuffer {
        super.getOutput()
        return pendingOutput
    }

    override fun isEnded(): Boolean = super.isEnded() && !pendingOutput.hasRemaining()

    internal suspend fun skipWhenConfirmed(
        isPlaying: () -> Boolean,
        positionMs: () -> Long,
        durationMs: () -> Long,
        seekTo: (Long) -> Unit,
    ) {
        for (threshold in thresholds) {
            if (!isPlaying() || !isSilent(threshold)) continue
            delay(200)
            if (!isPlaying() || !isSilent(threshold)) continue
            val target = instantSilenceSeekTarget(positionMs(), durationMs()) ?: continue
            resetTracking()
            seekTo(target)
            delay(350)
        }
    }

    @Synchronized private fun isSilent(threshold: Long): Boolean = enabled && generation == threshold && detector?.isSilent == true

    @Synchronized fun signalSilence() {
        if (enabled && detector?.isSilent == true) thresholds.trySend(generation)
    }

    @Synchronized fun resetTracking() {
        generation++
        detector?.reset()
    }

    @Synchronized
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        detector =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
                InstantSilenceDetector(inputAudioFormat.sampleRate)
            } else {
                null
            }
        resetTracking()
    }

    @Synchronized
    override fun onReset() {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        detector = null
        resetTracking()
    }
}
