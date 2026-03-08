@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IosAudioProcessingTapTest {
    @Test
    fun monoInterleavedAndPlanarSamplesPreserveChannelOrder() {
        for ((channels, planar) in listOf(1 to false, 2 to false, 2 to true)) {
            memScoped {
                val count = if (planar) channels else 1
                val buffers =
                    allocArray<ByteVar>(
                        sizeOf<AudioBufferList>() + sizeOf<AudioBuffer>() * (count - 1),
                    ).reinterpret<AudioBufferList>()
                buffers.pointed.mNumberBuffers = count.toUInt()
                repeat(count) { channel ->
                    val samples = allocArray<FloatVar>(if (planar) 2 else 2 * channels)
                    repeat(if (planar) 2 else 2 * channels) { index ->
                        samples[index] =
                            if (planar) (index * channels + channel + 1).toFloat() else (index + 1).toFloat()
                    }
                    buffers.pointed.mBuffers[channel].apply {
                        mNumberChannels = (if (planar) 1 else channels).toUInt()
                        mDataByteSize = ((if (planar) 2 else 2 * channels) * Float.SIZE_BYTES).toUInt()
                        mData = samples
                    }
                }
                processIosFloatAudioBuffers(buffers, 2, channels, FloatArray(2 * channels)) { samples, size ->
                    assertContentEquals(FloatArray(size) { (it + 1).toFloat() }, samples)
                    repeat(size) { samples[it] *= 2f }
                }
                repeat(count) { channel ->
                    val samples =
                        buffers.pointed.mBuffers[channel]
                            .mData!!
                            .reinterpret<FloatVar>()
                    repeat(if (planar) 2 else 2 * channels) { index ->
                        assertEquals((if (planar) index * channels + channel + 1 else index + 1) * 2f, samples[index])
                    }
                }
                buffers.pointed.mBuffers[count - 1].mDataByteSize = 1u
                processIosFloatAudioBuffers(
                    buffers,
                    2,
                    channels,
                    FloatArray(2 * channels),
                ) { _, _ -> error("Undersized buffers must not be processed") }
            }
        }
    }
}
