@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import com.metrolist.shared.utils.playbackPitchToSemitones
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AudioToolbox.AURenderCallbackStruct
import platform.AudioToolbox.AudioComponentDescription
import platform.AudioToolbox.AudioComponentFindNext
import platform.AudioToolbox.AudioComponentInstanceDispose
import platform.AudioToolbox.AudioComponentInstanceNew
import platform.AudioToolbox.AudioComponentInstanceVar
import platform.AudioToolbox.AudioUnit
import platform.AudioToolbox.AudioUnitGetProperty
import platform.AudioToolbox.AudioUnitInitialize
import platform.AudioToolbox.AudioUnitRender
import platform.AudioToolbox.AudioUnitRenderActionFlagsVar
import platform.AudioToolbox.AudioUnitReset
import platform.AudioToolbox.AudioUnitSetParameter
import platform.AudioToolbox.AudioUnitSetProperty
import platform.AudioToolbox.AudioUnitUninitialize
import platform.AudioToolbox.kAudioUnitManufacturer_Apple
import platform.AudioToolbox.kAudioUnitProperty_Latency
import platform.AudioToolbox.kAudioUnitProperty_MaximumFramesPerSlice
import platform.AudioToolbox.kAudioUnitProperty_SetRenderCallback
import platform.AudioToolbox.kAudioUnitProperty_StreamFormat
import platform.AudioToolbox.kAudioUnitScope_Global
import platform.AudioToolbox.kAudioUnitScope_Input
import platform.AudioToolbox.kAudioUnitScope_Output
import platform.AudioToolbox.kAudioUnitSubType_NewTimePitch
import platform.AudioToolbox.kAudioUnitType_FormatConverter
import platform.AudioToolbox.kNewTimePitchParam_Pitch
import platform.AudioToolbox.kNewTimePitchParam_Rate
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioTimeStampSampleTimeValid
import platform.CoreMedia.CMItemCountVar
import platform.MediaToolbox.MTAudioProcessingTapFlagsVar
import platform.MediaToolbox.kMTAudioProcessingTapFlag_EndOfStream
import platform.posix.memset
import kotlin.math.ceil

internal class IosPitchAudioUnit private constructor(
    private val unit: AudioUnit,
    format: CPointer<AudioStreamBasicDescription>,
    private val maxFrames: Int,
    private val readSource: (Int, CPointer<AudioBufferList>, CPointer<CMItemCountVar>, CPointer<MTAudioProcessingTapFlagsVar>) -> Int,
) {
    private val reference = StableRef.create(this)
    private val arena = Arena()
    private val channels = format.pointed.mChannelsPerFrame.toInt()
    private val planar = format.pointed.mFormatFlags and kAudioFormatFlagIsNonInterleaved != 0u
    private val bufferCount = if (planar) channels else 1
    private val bytesPerFrame = (if (planar) 1 else channels) * Float.SIZE_BYTES
    private val inputData = List(channels) { arena.allocArray<FloatVar>(maxFrames) }
    private val outputData = List(bufferCount) { arena.allocArray<FloatVar>(maxFrames * if (planar) 1 else channels) }
    private val renderBuffers =
        arena
            .allocArray<ByteVar>(
                sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (channels - 1),
            ).reinterpret<AudioBufferList>()
    private val sourceBuffers =
        arena
            .allocArray<ByteVar>(
                sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (bufferCount - 1),
            ).reinterpret<AudioBufferList>()
    private val sourceCount = arena.alloc<CMItemCountVar>()
    private val sourceFlagsOut = arena.alloc<MTAudioProcessingTapFlagsVar>()
    private val timestamp = arena.alloc<AudioTimeStamp>()
    private val actionFlags = arena.alloc<AudioUnitRenderActionFlagsVar>()
    private var renderTime = 0L
    private var latencyFrames = 0
    private var latencyToSkip = 0
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var sourceEnded = false
    private var sourceFlags = 0u
    private var pitch = Float.NaN
    private var needsReset = false

    fun reset(): Int {
        renderTime = 0L
        latencyToSkip = latencyFrames
        inputFrames = 0L
        outputFrames = 0L
        sourceEnded = false
        sourceFlags = 0u
        val status = AudioUnitReset(unit, kAudioUnitScope_Global, 0u)
        needsReset = status != 0
        return status
    }

    fun render(
        frames: Int,
        buffers: CPointer<AudioBufferList>,
        pitchRatio: Float,
        framesOut: CPointer<CMItemCountVar>,
        flagsOut: CPointer<MTAudioProcessingTapFlagsVar>,
    ): Int {
        if (frames !in 1..maxFrames || buffers.pointed.mNumberBuffers.toInt() != bufferCount) return -50
        if (pitchRatio == 1f) {
            needsReset = true
            return readSource(frames, buffers, framesOut, flagsOut)
        }
        if (needsReset) {
            val status = reset()
            if (status != 0) return status
        }
        if (pitch != pitchRatio) {
            val status =
                AudioUnitSetParameter(
                    unit,
                    kNewTimePitchParam_Pitch,
                    kAudioUnitScope_Global,
                    0u,
                    playbackPitchToSemitones(pitchRatio) * 100f,
                    0u,
                )
            if (status != 0) return status
            pitch = pitchRatio
        }
        sourceFlags = 0u
        while (latencyToSkip > 0) {
            val skipped = minOf(latencyToSkip, maxFrames)
            val status = renderUnit(skipped)
            if (status != 0) return status
            latencyToSkip -= skipped
        }
        val status = renderUnit(frames)
        if (status != 0) return status
        val produced = if (sourceEnded) minOf(frames.toLong(), (inputFrames - outputFrames).coerceAtLeast(0L)).toInt() else frames
        outputFrames += produced
        framesOut.pointed.value = produced.toLong()
        flagsOut.pointed.value = sourceFlags and kMTAudioProcessingTapFlag_EndOfStream.inv()
        if (sourceEnded &&
            outputFrames >= inputFrames
        ) {
            flagsOut.pointed.value = flagsOut.pointed.value or kMTAudioProcessingTapFlag_EndOfStream
        }
        repeat(channels) { channel ->
            val samples =
                renderBuffers.pointed.mBuffers[channel]
                    .mData
                    ?.reinterpret<FloatVar>() ?: return -50
            val target = outputData[if (planar) channel else 0]
            repeat(produced) { frame -> target[if (planar) frame else frame * channels + channel] = samples[frame] }
        }
        repeat(bufferCount) { index ->
            buffers.pointed.mBuffers[index].apply {
                mData = outputData[index]
                mDataByteSize = (produced * bytesPerFrame).toUInt()
            }
        }
        return 0
    }

    private fun renderUnit(frames: Int): Int {
        memset(timestamp.ptr, 0, sizeOf<AudioTimeStamp>().toULong())
        timestamp.mSampleTime = renderTime.toDouble()
        timestamp.mFlags = kAudioTimeStampSampleTimeValid
        actionFlags.value = 0u
        renderBuffers.pointed.mNumberBuffers = channels.toUInt()
        repeat(channels) { channel ->
            renderBuffers.pointed.mBuffers[channel].apply {
                mNumberChannels = 1u
                mData = null
                mDataByteSize = (frames * Float.SIZE_BYTES).toUInt()
            }
        }
        val status = AudioUnitRender(unit, actionFlags.ptr, timestamp.ptr, 0u, frames.toUInt(), renderBuffers)
        if (status == 0) renderTime += frames
        return status
    }

    internal fun read(
        frames: Int,
        buffers: CPointer<AudioBufferList>,
    ): Int {
        if (frames !in 1..maxFrames || buffers.pointed.mNumberBuffers.toInt() != channels) return -50
        var filled = 0
        while (filled < frames && !sourceEnded) {
            val requested = frames - filled
            sourceCount.value = 0L
            sourceFlagsOut.value = 0u
            sourceBuffers.pointed.mNumberBuffers = bufferCount.toUInt()
            repeat(bufferCount) { index ->
                sourceBuffers.pointed.mBuffers[index].apply {
                    mNumberChannels = (if (planar) 1 else channels).toUInt()
                    mDataByteSize = (requested * bytesPerFrame).toUInt()
                    mData = null
                }
            }
            val status = readSource(requested, sourceBuffers, sourceCount.ptr, sourceFlagsOut.ptr)
            if (status != 0) return status
            if (sourceCount.value < 0L || sourceCount.value > requested) return -50
            inputFrames += sourceCount.value
            sourceFlags = sourceFlags or sourceFlagsOut.value
            sourceEnded = sourceFlagsOut.value and kMTAudioProcessingTapFlag_EndOfStream != 0u
            val received = sourceCount.value.toInt()
            if (received == 0) break
            repeat(channels) { channel ->
                val source = sourceBuffers.pointed.mBuffers[if (planar) channel else 0]
                if (source.mNumberChannels.toInt() != (if (planar) 1 else channels)) return -50
                val data = source.mData?.reinterpret<FloatVar>() ?: return -50
                if (source.mDataByteSize.toLong() < received.toLong() * bytesPerFrame) return -50
                repeat(received) { frame ->
                    inputData[channel][filled + frame] = data[if (planar) frame else frame * channels + channel]
                }
            }
            filled += received
        }
        repeat(channels) { channel ->
            for (frame in filled until frames) inputData[channel][frame] = 0f
            buffers.pointed.mBuffers[channel].apply {
                mData = inputData[channel]
                mDataByteSize = (frames * Float.SIZE_BYTES).toUInt()
            }
        }
        return 0
    }

    fun close() {
        AudioUnitUninitialize(unit)
        AudioComponentInstanceDispose(unit)
        reference.dispose()
        arena.clear()
    }

    companion object {
        fun create(
            format: CPointer<AudioStreamBasicDescription>,
            maxFrames: Int,
            readSource: (Int, CPointer<AudioBufferList>, CPointer<CMItemCountVar>, CPointer<MTAudioProcessingTapFlagsVar>) -> Int,
        ): IosPitchAudioUnit? =
            memScoped {
                val description = alloc<AudioComponentDescription>()
                description.componentType = kAudioUnitType_FormatConverter
                description.componentSubType = kAudioUnitSubType_NewTimePitch
                description.componentManufacturer = kAudioUnitManufacturer_Apple
                description.componentFlags = 0u
                description.componentFlagsMask = 0u
                val component = AudioComponentFindNext(null, description.ptr) ?: return@memScoped null
                val output = alloc<AudioComponentInstanceVar>()
                if (AudioComponentInstanceNew(component, output.ptr) != 0) return@memScoped null
                val unit = output.value ?: return@memScoped null
                val processor = IosPitchAudioUnit(unit, format, maxFrames, readSource)
                val unitFormat = alloc<AudioStreamBasicDescription>()
                unitFormat.mSampleRate = format.pointed.mSampleRate
                unitFormat.mFormatID = kAudioFormatLinearPCM
                unitFormat.mFormatFlags = kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked or kAudioFormatFlagIsNonInterleaved
                unitFormat.mBytesPerPacket = Float.SIZE_BYTES.toUInt()
                unitFormat.mFramesPerPacket = 1u
                unitFormat.mBytesPerFrame = Float.SIZE_BYTES.toUInt()
                unitFormat.mChannelsPerFrame = format.pointed.mChannelsPerFrame
                unitFormat.mBitsPerChannel = 32u
                unitFormat.mReserved = 0u
                val callback = alloc<AURenderCallbackStruct>()
                callback.inputProc = staticCFunction(::readPitchSource)
                callback.inputProcRefCon = processor.reference.asCPointer()
                val maximum = alloc<UIntVar>()
                maximum.value = maxFrames.toUInt()
                if (AudioUnitSetProperty(
                        unit,
                        kAudioUnitProperty_StreamFormat,
                        kAudioUnitScope_Input,
                        0u,
                        unitFormat.ptr,
                        sizeOf<AudioStreamBasicDescription>().toUInt(),
                    ) !=
                    0 ||
                    AudioUnitSetProperty(
                        unit,
                        kAudioUnitProperty_StreamFormat,
                        kAudioUnitScope_Output,
                        0u,
                        unitFormat.ptr,
                        sizeOf<AudioStreamBasicDescription>().toUInt(),
                    ) !=
                    0 ||
                    AudioUnitSetProperty(
                        unit,
                        kAudioUnitProperty_MaximumFramesPerSlice,
                        kAudioUnitScope_Global,
                        0u,
                        maximum.ptr,
                        sizeOf<UIntVar>().toUInt(),
                    ) !=
                    0 ||
                    AudioUnitSetProperty(
                        unit,
                        kAudioUnitProperty_SetRenderCallback,
                        kAudioUnitScope_Input,
                        0u,
                        callback.ptr,
                        sizeOf<AURenderCallbackStruct>().toUInt(),
                    ) !=
                    0 ||
                    AudioUnitSetParameter(unit, kNewTimePitchParam_Rate, kAudioUnitScope_Global, 0u, 1f, 0u) != 0 ||
                    AudioUnitInitialize(unit) != 0
                ) {
                    processor.close()
                    return@memScoped null
                }
                val latency = alloc<DoubleVar>()
                val latencySize = alloc<UIntVar>()
                latencySize.value = sizeOf<DoubleVar>().toUInt()
                if (AudioUnitGetProperty(unit, kAudioUnitProperty_Latency, kAudioUnitScope_Global, 0u, latency.ptr, latencySize.ptr) != 0 ||
                    !latency.value.isFinite() || latency.value < 0.0
                ) {
                    processor.close()
                    return@memScoped null
                }
                processor.latencyFrames = ceil(latency.value * format.pointed.mSampleRate).toInt()
                processor.latencyToSkip = processor.latencyFrames
                processor
            }
    }
}

private fun readPitchSource(
    context: COpaquePointer?,
    actionFlags: CPointer<AudioUnitRenderActionFlagsVar>?,
    timestamp: CPointer<AudioTimeStamp>?,
    bus: UInt,
    frames: UInt,
    buffers: CPointer<AudioBufferList>?,
): Int {
    val processor = context?.asStableRef<IosPitchAudioUnit>()?.get() ?: return -50
    return processor.read(frames.toInt(), buffers ?: return -50)
}
