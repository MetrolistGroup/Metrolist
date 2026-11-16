package com.metrolist.shared.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.MAX_AUDIO_BOOST_DB
import com.metrolist.shared.equalizer.MIN_AUDIO_PREAMP_DB
import com.metrolist.shared.equalizer.ParametricEqualizer
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.loadTracksWithMediaType
import platform.AVFoundation.setAudioMix
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMItemCount
import platform.CoreMedia.CMItemCountVar
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapFlags
import platform.MediaToolbox.MTAudioProcessingTapFlagsVar
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PreEffects
import platform.MediaToolbox.kMTAudioProcessingTapFlag_StartOfStream
import platform.darwin.OSStatus
import kotlin.concurrent.Volatile

internal class IosAudioTapState(
    val scope: CoroutineScope,
    val normalizationGain: StateFlow<Float>,
    val pitch: StateFlow<Float>,
    val pitchAvailable: MutableStateFlow<Boolean>,
) {
    @Volatile private var configuration = EqualizerProfiles.configuration
    private val configurationJob =
        scope.launch {
            snapshotFlow { EqualizerProfiles.configuration }.collect {
                configuration = it
            }
        }
    var sampleRate = 0
    var channelCount = 0
    var maxFrames = 0
    var supportsFloat32 = false
    var samples = FloatArray(0)
    var equalizer: ParametricEqualizer? = null
    var pitchUnit: IosPitchAudioUnit? = null

    fun process(sampleCount: Int) {
        val eq = equalizer ?: return
        val current = configuration
        eq.update(current.profile)
        val gainDb = current.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
        val gain = normalizationGain.value
        for (index in 0 until sampleCount) samples[index] *= gain
        eq.processPcmFloat(samples, sampleCount, gainDb)
        AudioSpectrumMeter.accept(samples, sampleCount, channelCount, sampleRate, eq.gainReductionDb.toFloat())
    }

    fun close() {
        configurationJob.cancel()
        pitchUnit?.close()
        pitchUnit = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun installIosAudioProcessingTap(
    scope: CoroutineScope,
    asset: AVAsset,
    item: AVPlayerItem,
    normalizationGain: StateFlow<Float>,
    pitch: StateFlow<Float>,
    pitchAvailable: MutableStateFlow<Boolean>,
    onReady: (Boolean) -> Unit,
) {
    asset.loadTracksWithMediaType(AVMediaTypeAudio) { tracks, error ->
        val track = tracks?.firstOrNull() as? AVAssetTrack
        if (error != null || track == null) {
            PlaybackEventLog.error(
                kind = PlaybackEventKind.PLAYER,
                message = "AVFoundation could not load an audio track for normalization",
                details =
                    buildMap {
                        error?.let {
                            it.domain?.let { domain -> put("errorDomain", domain) }
                            put("errorCode", it.code.toString())
                        }
                    },
            )
            pitchAvailable.value = false
            onReady(false)
            return@loadTracksWithMediaType
        }
        val tap =
            createIosAudioProcessingTap(scope, normalizationGain, pitch, pitchAvailable) ?: run {
                PlaybackEventLog.error(
                    PlaybackEventKind.PLAYER,
                    "AVFoundation could not create an audio processing tap",
                )
                pitchAvailable.value = false
                onReady(false)
                return@loadTracksWithMediaType
            }
        val parameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(track)
        parameters.setAudioTapProcessor(tap)
        val mix = AVMutableAudioMix()
        mix.setInputParameters(listOf(parameters))
        item.setAudioMix(mix)
        CFRelease(tap)
        onReady(true)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createIosAudioProcessingTap(
    scope: CoroutineScope,
    normalizationGain: StateFlow<Float>,
    pitch: StateFlow<Float>,
    pitchAvailable: MutableStateFlow<Boolean>,
): MTAudioProcessingTapRef? {
    val state = IosAudioTapState(scope, normalizationGain, pitch, pitchAvailable)
    val stableState = StableRef.create(state)
    return memScoped {
        val callbacks = alloc<MTAudioProcessingTapCallbacks>()
        callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
        callbacks.clientInfo = stableState.asCPointer()
        callbacks.init = staticCFunction(::initializeIosAudioTap)
        callbacks.finalize = staticCFunction(::finalizeIosAudioTap)
        callbacks.prepare = staticCFunction(::prepareIosAudioTap)
        callbacks.unprepare = staticCFunction(::unprepareIosAudioTap)
        callbacks.process = staticCFunction(::processIosAudioTap)
        val tapOut = alloc<MTAudioProcessingTapRefVar>()
        val status =
            MTAudioProcessingTapCreate(
                allocator = null,
                callbacks = callbacks.ptr,
                flags = kMTAudioProcessingTapCreationFlag_PreEffects,
                tapOut = tapOut.ptr,
            )
        if (status != 0) {
            state.close()
            stableState.dispose()
            null
        } else {
            tapOut.value
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun initializeIosAudioTap(
    tap: MTAudioProcessingTapRef?,
    clientInfo: COpaquePointer?,
    tapStorageOut: CPointer<COpaquePointerVar>?,
) {
    tapStorageOut?.pointed?.value = clientInfo
}

@OptIn(ExperimentalForeignApi::class)
private fun finalizeIosAudioTap(tap: MTAudioProcessingTapRef?) {
    val reference = MTAudioProcessingTapGetStorage(tap)?.asStableRef<IosAudioTapState>() ?: return
    reference.get().close()
    reference.dispose()
}

@OptIn(ExperimentalForeignApi::class)
private fun prepareIosAudioTap(
    tap: MTAudioProcessingTapRef?,
    maxFrames: CMItemCount,
    processingFormat: CPointer<AudioStreamBasicDescription>?,
) {
    val state = audioTapState(tap) ?: return
    val format = processingFormat?.pointed ?: return
    val channels = format.mChannelsPerFrame.toInt()
    val frames = maxFrames.toInt()
    if (channels <= 0 || frames <= 0 || format.mSampleRate <= 0.0) return
    state.sampleRate = format.mSampleRate.toInt()
    state.channelCount = channels
    state.maxFrames = frames
    state.supportsFloat32 = format.mBitsPerChannel == 32u && format.mFormatFlags and kAudioFormatFlagIsFloat != 0u
    state.samples = FloatArray(frames * channels)
    state.equalizer = ParametricEqualizer(state.sampleRate, channels)
    val details =
        mapOf(
            "tapSampleRate" to format.mSampleRate.toString(),
            "tapChannels" to channels.toString(),
            "tapMaxFrames" to frames.toString(),
            "tapFormatId" to format.mFormatID.toString(),
            "tapFormatFlags" to format.mFormatFlags.toString(),
            "tapBitsPerChannel" to format.mBitsPerChannel.toString(),
            "tapBytesPerFrame" to format.mBytesPerFrame.toString(),
        )
    state.scope.launch {
        PlaybackEventLog.info(PlaybackEventKind.PLAYER, "iOS audio tap prepared", details = details)
    }
    state.pitchUnit?.close()
    state.pitchUnit = null
    state.pitchAvailable.value = false
    if (state.supportsFloat32) {
        state.pitchUnit =
            IosPitchAudioUnit.create(processingFormat, frames) { count, buffers, framesOut, flagsOut ->
                MTAudioProcessingTapGetSourceAudio(tap, count.toLong(), buffers, flagsOut, null, framesOut)
            }
        state.pitchAvailable.value = state.pitchUnit != null
        if (state.pitchUnit == null) Logger.e("MusicPlayer", "Unable to initialize iOS pitch audio unit")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun unprepareIosAudioTap(tap: MTAudioProcessingTapRef?) {
    val state = audioTapState(tap) ?: return
    state.pitchUnit?.close()
    state.pitchUnit = null
    state.pitchAvailable.value = false
    state.sampleRate = 0
    state.channelCount = 0
    state.maxFrames = 0
    state.samples = FloatArray(0)
    state.equalizer = null
}

@OptIn(ExperimentalForeignApi::class)
private fun processIosAudioTap(
    tap: MTAudioProcessingTapRef?,
    numberFrames: CMItemCount,
    processingFlags: MTAudioProcessingTapFlags,
    bufferListInOut: CPointer<AudioBufferList>?,
    numberFramesOut: CPointer<CMItemCountVar>?,
    flagsOut: CPointer<MTAudioProcessingTapFlagsVar>?,
) {
    val buffers = bufferListInOut ?: return
    val framesOut = numberFramesOut ?: return
    val sourceFlags = flagsOut ?: return
    val state = audioTapState(tap) ?: return
    val pitchUnit = state.pitchUnit
    val pitch = state.pitch.value
    val status: OSStatus =
        if (pitchUnit != null) {
            val resetStatus = if (pitch != 1f && processingFlags and kMTAudioProcessingTapFlag_StartOfStream != 0u) pitchUnit.reset() else 0
            if (resetStatus !=
                0
            ) {
                resetStatus
            } else {
                pitchUnit.render(numberFrames.toInt(), buffers, pitch, framesOut, sourceFlags)
            }
        } else {
            MTAudioProcessingTapGetSourceAudio(tap, numberFrames, buffers, sourceFlags, null, framesOut)
        }
    if (status != 0) {
        state.pitchAvailable.value = false
        state.pitchUnit?.close()
        state.pitchUnit = null
        framesOut.pointed.value = 0L
        return
    }
    val frames = framesOut.pointed.value.toInt()
    if (!state.supportsFloat32 || frames <= 0 || frames > state.maxFrames) return
    if (state.equalizer == null) return
    processIosFloatAudioBuffers(buffers, frames, state.channelCount, state.samples) { _, sampleCount -> state.process(sampleCount) }
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun processIosFloatAudioBuffers(
    buffers: CPointer<AudioBufferList>,
    frames: Int,
    channels: Int,
    scratch: FloatArray,
    process: (FloatArray, Int) -> Unit,
) {
    if (frames <= 0 || channels <= 0 || frames > scratch.size / channels) return
    val sampleCount = frames * channels
    val bufferCount = buffers.pointed.mNumberBuffers.toInt()
    if (bufferCount != 1 && bufferCount != channels) return
    val audioBuffers = buffers.pointed.mBuffers
    if (bufferCount == 1) {
        val buffer = audioBuffers[0]
        if (buffer.mNumberChannels.toInt() != channels || buffer.mDataByteSize.toLong() < sampleCount.toLong() * Float.SIZE_BYTES) return
        val samples = buffer.mData?.reinterpret<FloatVar>() ?: return
        for (index in 0 until sampleCount) scratch[index] = samples[index]
    } else {
        for (channel in 0 until channels) {
            val buffer = audioBuffers[channel]
            if (buffer.mNumberChannels != 1u || buffer.mDataByteSize.toLong() < frames.toLong() * Float.SIZE_BYTES) return
            val samples = buffer.mData?.reinterpret<FloatVar>() ?: return
            for (frame in 0 until frames) scratch[frame * channels + channel] = samples[frame]
        }
    }
    process(scratch, sampleCount)
    if (bufferCount == 1) {
        val samples = audioBuffers[0].mData?.reinterpret<FloatVar>() ?: return
        for (index in 0 until sampleCount) samples[index] = scratch[index]
    } else {
        for (channel in 0 until channels) {
            val samples = audioBuffers[channel].mData?.reinterpret<FloatVar>() ?: return
            for (frame in 0 until frames) samples[frame] = scratch[frame * channels + channel]
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun audioTapState(tap: MTAudioProcessingTapRef?) = MTAudioProcessingTapGetStorage(tap)?.asStableRef<IosAudioTapState>()?.get()
