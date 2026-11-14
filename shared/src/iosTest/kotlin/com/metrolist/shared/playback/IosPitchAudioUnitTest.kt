@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreMedia.CMItemCountVar
import platform.MediaToolbox.MTAudioProcessingTapFlagsVar
import platform.MediaToolbox.kMTAudioProcessingTapFlag_EndOfStream
import platform.MediaToolbox.kMTAudioProcessingTapFlag_StartOfStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosPitchAudioUnitTest {
    @Test
    fun unityPitchReadsExactlyOneCycleWithoutPaddingOrLatencyPreroll() {
        for ((channels, planar) in listOf(1 to false, 2 to false, 2 to true)) {
            memScoped {
                val maxFrames = 4474
                val bufferCount = if (planar) channels else 1
                val format = alloc<AudioStreamBasicDescription>()
                format.mSampleRate = 48_000.0
                format.mFormatID = kAudioFormatLinearPCM
                format.mFormatFlags =
                    kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked or (if (planar) kAudioFormatFlagIsNonInterleaved else 0u)
                format.mBytesPerPacket = (Float.SIZE_BYTES * if (planar) 1 else channels).toUInt()
                format.mFramesPerPacket = 1u
                format.mBytesPerFrame = format.mBytesPerPacket
                format.mChannelsPerFrame = channels.toUInt()
                format.mBitsPerChannel = 32u
                format.mReserved = 0u
                val sourceData = List(bufferCount) { allocArray<FloatVar>(maxFrames * if (planar) 1 else channels) }
                var position = 0
                var cycleFrames = 0
                var availableFrames = 0
                var calls = 0
                var endOfStream = false
                val unit =
                    assertNotNull(
                        IosPitchAudioUnit.create(format.ptr, maxFrames) { requested, buffers, count, flags ->
                            assertEquals(cycleFrames, requested)
                            assertEquals(1, ++calls, "Unity pitch must not pull more source in the same cycle")
                            repeat(bufferCount) { index ->
                                buffers.pointed.mBuffers[index].apply {
                                    mNumberChannels = (if (planar) 1 else channels).toUInt()
                                    mData = sourceData[index]
                                    mDataByteSize = (availableFrames * mNumberChannels.toInt() * Float.SIZE_BYTES).toUInt()
                                    repeat(availableFrames) { frame ->
                                        repeat(mNumberChannels.toInt()) { channel ->
                                            val absoluteChannel = if (planar) index else channel
                                            sourceData[index][frame * mNumberChannels.toInt() + channel] =
                                                (position + frame + 1 + absoluteChannel * 100_000).toFloat()
                                        }
                                    }
                                }
                            }
                            count.pointed.value = availableFrames.toLong()
                            flags.pointed.value = if (endOfStream) kMTAudioProcessingTapFlag_EndOfStream else 0u
                            position += availableFrames
                            0
                        },
                    )
                try {
                    val buffers =
                        allocArray<ByteVar>(
                            sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (bufferCount - 1),
                        ).reinterpret<AudioBufferList>()
                    buffers.pointed.mNumberBuffers = bufferCount.toUInt()
                    val count = alloc<CMItemCountVar>()
                    val flags = alloc<MTAudioProcessingTapFlagsVar>()
                    for (available in listOf(4474, 4473, 4474, 0, 4473, 73)) {
                        cycleFrames = if (available == 4473) 4473 else maxFrames
                        availableFrames = available
                        endOfStream = available == 73
                        calls = 0
                        val start = position
                        assertEquals(0, unit.render(cycleFrames, buffers, 1f, count.ptr, flags.ptr))
                        assertEquals(1, calls)
                        assertEquals(available.toLong(), count.value)
                        assertEquals(if (endOfStream) kMTAudioProcessingTapFlag_EndOfStream else 0u, flags.value)
                        repeat(channels) { channel ->
                            val data = assertNotNull(buffers.pointed.mBuffers[if (planar) channel else 0].mData).reinterpret<FloatVar>()
                            repeat(available) { frame ->
                                assertEquals(
                                    (start + frame + 1 + channel * 100_000).toFloat(),
                                    data[if (planar) frame else frame * channels + channel],
                                )
                            }
                        }
                    }
                } finally {
                    unit.close()
                }
            }
        }
    }

    @Test
    fun shortReadsPreserveEverySourceFrameAndOnlyPadAtEofOrNoProgress() {
        for ((channels, planar) in listOf(1 to false, 2 to false, 2 to true)) {
            memScoped {
                val maxFrames = 1024
                val totalFrames = maxFrames * 2 + 73
                val bufferCount = if (planar) channels else 1
                val format = alloc<AudioStreamBasicDescription>()
                format.mSampleRate = 48_000.0
                format.mFormatID = kAudioFormatLinearPCM
                format.mFormatFlags =
                    kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked or (if (planar) kAudioFormatFlagIsNonInterleaved else 0u)
                format.mBytesPerPacket = (Float.SIZE_BYTES * if (planar) 1 else channels).toUInt()
                format.mFramesPerPacket = 1u
                format.mBytesPerFrame = format.mBytesPerPacket
                format.mChannelsPerFrame = channels.toUInt()
                format.mBitsPerChannel = 32u
                format.mReserved = 0u
                val sourceData = List(bufferCount) { allocArray<FloatVar>(maxFrames * if (planar) 1 else channels) }
                var position = 0
                var starved = false
                var calls = 0
                var failSecondRead = false
                val unit =
                    assertNotNull(
                        IosPitchAudioUnit.create(format.ptr, maxFrames) { requested, buffers, count, flags ->
                            calls++
                            if (failSecondRead && calls == 2) return@create -50
                            val available = if (starved) 0 else minOf(requested, 960, totalFrames - position)
                            repeat(bufferCount) { index ->
                                val buffer = buffers.pointed.mBuffers[index]
                                buffer.mData = sourceData[index]
                                repeat(available) { frame ->
                                    repeat(buffer.mNumberChannels.toInt()) { channel ->
                                        val absoluteChannel = if (planar) index else channel
                                        sourceData[index][frame * buffer.mNumberChannels.toInt() + channel] =
                                            (position + frame + 1 + absoluteChannel * totalFrames).toFloat()
                                    }
                                }
                                buffer.mDataByteSize = available.toUInt() * buffer.mNumberChannels * Float.SIZE_BYTES.toUInt()
                            }
                            position += available
                            count.pointed.value = available.toLong()
                            flags.pointed.value = if (position == totalFrames) kMTAudioProcessingTapFlag_EndOfStream else 0u
                            0
                        },
                    )
                try {
                    val buffers =
                        allocArray<ByteVar>(
                            sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (channels - 1),
                        ).reinterpret<AudioBufferList>()
                    buffers.pointed.mNumberBuffers = channels.toUInt()
                    repeat(3) { block ->
                        assertEquals(0, unit.read(maxFrames, buffers))
                        assertEquals(minOf((block + 1) * maxFrames, totalFrames), position)
                        repeat(channels) { channel ->
                            val data = assertNotNull(buffers.pointed.mBuffers[channel].mData).reinterpret<FloatVar>()
                            repeat(maxFrames) { frame ->
                                val sourceFrame = block * maxFrames + frame
                                val expected = if (sourceFrame < totalFrames) (sourceFrame + 1 + channel * totalFrames).toFloat() else 0f
                                assertEquals(expected, data[frame], "block=$block frame=$frame channel=$channel")
                            }
                        }
                    }
                    assertEquals(5, calls)
                    assertEquals(0, unit.reset())
                    position = 0
                    calls = 0
                    starved = true
                    assertEquals(0, unit.read(maxFrames, buffers))
                    assertEquals(1, calls, "A zero-frame source read must not spin")
                    repeat(channels) { channel ->
                        val data = assertNotNull(buffers.pointed.mBuffers[channel].mData).reinterpret<FloatVar>()
                        repeat(maxFrames) { assertEquals(0f, data[it]) }
                    }
                    assertEquals(0, unit.reset())
                    calls = 0
                    starved = false
                    failSecondRead = true
                    assertEquals(-50, unit.read(maxFrames, buffers))
                    assertEquals(960, position)
                    assertEquals(2, calls)
                } finally {
                    unit.close()
                }
            }
        }
    }

    @Test
    fun pitchChangesPreserveDurationAndChannelsAndResetAfterEndOfStream() {
        for ((channels, planar) in listOf(1 to false, 2 to false, 2 to true)) {
            memScoped {
                val sampleRate = 48_000
                val maxFrames = 1024
                val totalFrames = sampleRate * 2 + 73
                val bufferCount = if (planar) channels else 1
                val format = alloc<AudioStreamBasicDescription>()
                format.mSampleRate = sampleRate.toDouble()
                format.mFormatID = kAudioFormatLinearPCM
                format.mFormatFlags =
                    kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked or (if (planar) kAudioFormatFlagIsNonInterleaved else 0u)
                format.mBytesPerPacket = (Float.SIZE_BYTES * if (planar) 1 else channels).toUInt()
                format.mFramesPerPacket = 1u
                format.mBytesPerFrame = format.mBytesPerPacket
                format.mChannelsPerFrame = channels.toUInt()
                format.mBitsPerChannel = 32u
                format.mReserved = 0u
                val sourceData = List(bufferCount) { allocArray<FloatVar>(maxFrames * if (planar) 1 else channels) }
                var sourcePosition = 0
                var failSource = false
                val unit =
                    assertNotNull(
                        IosPitchAudioUnit.create(format.ptr, maxFrames) { requested, buffers, count, flags ->
                            if (failSource) return@create -50
                            val available = minOf(requested, totalFrames - sourcePosition)
                            repeat(buffers.pointed.mNumberBuffers.toInt()) { index ->
                                val buffer = buffers.pointed.mBuffers[index]
                                val data = sourceData[index]
                                buffer.mData = data
                                repeat(available) { frame ->
                                    repeat(buffer.mNumberChannels.toInt()) { channel ->
                                        val absoluteChannel = if (planar) index else channel
                                        data[frame * buffer.mNumberChannels.toInt() + channel] =
                                            (
                                                0.2 *
                                                    sin(
                                                        2 * PI * (440 + absoluteChannel * 220) * (sourcePosition + frame) / sampleRate,
                                                    )
                                            ).toFloat()
                                    }
                                }
                                buffer.mDataByteSize = available.toUInt() * buffer.mNumberChannels * Float.SIZE_BYTES.toUInt()
                            }
                            flags.pointed.value = if (sourcePosition == 0) kMTAudioProcessingTapFlag_StartOfStream else 0u
                            sourcePosition += available
                            if (sourcePosition ==
                                totalFrames
                            ) {
                                flags.pointed.value = flags.pointed.value or kMTAudioProcessingTapFlag_EndOfStream
                            }
                            count.pointed.value = available.toLong()
                            0
                        },
                    )
                try {
                    val buffers =
                        allocArray<ByteVar>(
                            sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (bufferCount - 1),
                        ).reinterpret<AudioBufferList>()
                    buffers.pointed.mNumberBuffers = bufferCount.toUInt()
                    repeat(bufferCount) { index ->
                        buffers.pointed.mBuffers[index].apply {
                            mNumberChannels = (if (planar) 1 else channels).toUInt()
                            mData = allocArray<FloatVar>(maxFrames * mNumberChannels.toInt())
                        }
                    }
                    val count = alloc<CMItemCountVar>()
                    val flags = alloc<MTAudioProcessingTapFlagsVar>()
                    for (ratio in listOf(0.5f, 2f, 1f)) {
                        sourcePosition = 0
                        assertEquals(0, unit.reset())
                        var rendered = 0
                        var renderCalls = 0
                        val captured = Array(channels) { mutableListOf<Float>() }
                        do {
                            assertTrue(++renderCalls < totalFrames / maxFrames + 64)
                            repeat(bufferCount) { index ->
                                val buffer = buffers.pointed.mBuffers[index]
                                buffer.mDataByteSize = maxFrames.toUInt() * buffer.mNumberChannels * Float.SIZE_BYTES.toUInt()
                            }
                            val livePitch = if (rendered < sampleRate) 1f else ratio
                            assertEquals(0, unit.render(maxFrames, buffers, livePitch, count.ptr, flags.ptr))
                            assertTrue(count.value in 0L..maxFrames.toLong())
                            processIosFloatAudioBuffers(
                                buffers,
                                count.value.toInt(),
                                channels,
                                FloatArray(maxFrames * channels),
                            ) { samples, size ->
                                repeat(size / channels) { frame ->
                                    repeat(channels) { channel -> captured[channel].add(samples[frame * channels + channel]) }
                                }
                            }
                            rendered += count.value.toInt()
                            assertTrue(rendered <= totalFrames + maxFrames)
                        } while (flags.value and kMTAudioProcessingTapFlag_EndOfStream == 0u)
                        assertEquals(totalFrames, rendered)
                        captured.forEachIndexed { channel, samples ->
                            val tail = samples.subList(sampleRate * 3 / 2, sampleRate * 2)
                            assertTrue(tail.all { it.isFinite() })
                            val crossings = tail.zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
                            val frequency = crossings * sampleRate.toDouble() / (tail.size - 1)
                            assertTrue(
                                abs(frequency - (440 + channel * 220) * ratio) < 15,
                                "frequency=$frequency, pitch=$ratio, channel=$channel",
                            )
                        }
                    }
                    val firstPitchedBlock = Array(channels) { FloatArray(maxFrames) }
                    var expectedSourcePosition = 0
                    repeat(2) { pass ->
                        sourcePosition = 0
                        assertEquals(0, unit.render(maxFrames, buffers, 1f, count.ptr, flags.ptr))
                        if (pass == 0) assertEquals(0, unit.reset())
                        assertEquals(0, unit.render(maxFrames, buffers, 0.5f, count.ptr, flags.ptr))
                        assertEquals(maxFrames.toLong(), count.value)
                        if (pass == 0) expectedSourcePosition = sourcePosition else assertEquals(expectedSourcePosition, sourcePosition)
                        repeat(channels) { channel ->
                            val data = assertNotNull(buffers.pointed.mBuffers[if (planar) channel else 0].mData).reinterpret<FloatVar>()
                            repeat(maxFrames) { frame ->
                                val sample = data[if (planar) frame else frame * channels + channel]
                                if (pass == 0) {
                                    firstPitchedBlock[channel][frame] = sample
                                } else {
                                    assertEquals(firstPitchedBlock[channel][frame], sample, "Re-enabled pitch must discard old AU state")
                                }
                            }
                        }
                    }
                    assertEquals(0, unit.reset())
                    failSource = true
                    assertEquals(-50, unit.render(maxFrames, buffers, 1f, count.ptr, flags.ptr))
                } finally {
                    unit.close()
                }
            }
        }
    }
}
