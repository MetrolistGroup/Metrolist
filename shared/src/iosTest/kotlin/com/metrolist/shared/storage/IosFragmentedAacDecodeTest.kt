@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosFragmentedAacDecodeTest {
    @Test
    fun systemDecoderReadsEveryFragmentAndSeeksIntoALaterOne() {
        val aac = fragmentedAacFixture()
        val path = "${NSTemporaryDirectory()}/${NSUUID.UUID().UUIDString}.mp4"
        val files = NSFileManager.defaultManager
        assertTrue(files.createFileAtPath(path, aac.usePinned { NSData.create(it.addressOf(0), aac.size.toULong()) }, null))
        try {
            val asset = AVURLAsset(NSURL.fileURLWithPath(path), null)
            val total = CMTimeGetSeconds(asset.duration)
            assertTrue(abs(total - 4.0) < 0.1, "Duration is $total")
            assertDecodes(asset, start = 0.0, seconds = total)
            assertDecodes(asset, start = 2.6, seconds = total - 2.6)
        } finally {
            files.removeItemAtPath(path, null)
        }
    }

    private fun assertDecodes(
        asset: AVURLAsset,
        start: Double,
        seconds: Double,
    ) {
        val track = assertNotNull(asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack)
        val reader = AVAssetReader(asset, null)
        reader.timeRange = CMTimeRangeMake(CMTimeMakeWithSeconds(start, 44_100), CMTimeMakeWithSeconds(seconds, 44_100))
        val output =
            AVAssetReaderTrackOutput(
                track,
                mapOf(
                    "AVFormatIDKey" to kAudioFormatLinearPCM,
                    "AVSampleRateKey" to 44_100,
                    "AVNumberOfChannelsKey" to 2,
                    "AVLinearPCMBitDepthKey" to 16,
                    "AVLinearPCMIsBigEndianKey" to false,
                    "AVLinearPCMIsFloatKey" to false,
                    "AVLinearPCMIsNonInterleaved" to false,
                ),
            )
        assertTrue(reader.canAddOutput(output))
        reader.addOutput(output)
        assertTrue(reader.startReading(), reader.error?.localizedDescription)
        var samples = 0L
        var energy = 0.0
        while (reader.status == AVAssetReaderStatusReading) {
            val sample = output.copyNextSampleBuffer() ?: break
            try {
                val block = assertNotNull(CMSampleBufferGetDataBuffer(sample))
                val length = CMBlockBufferGetDataLength(block).toInt()
                if (length == 0) continue
                val bytes = ByteArray(length)
                assertEquals(0, bytes.usePinned { CMBlockBufferCopyDataBytes(block, 0u, length.toULong(), it.addressOf(0)) })
                for (index in 0 until length / 2) {
                    val pcm = ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                    energy += pcm.toDouble() * pcm
                    samples++
                }
            } finally {
                CFRelease(sample)
            }
        }
        assertEquals(AVAssetReaderStatusCompleted, reader.status, reader.error?.localizedDescription)
        val decoded = samples / 2 / 44_100.0
        assertTrue(abs(decoded - seconds) < 0.25, "Decoded $decoded seconds from $start, expected $seconds")
        assertTrue(sqrt(energy / samples) / Short.MAX_VALUE > 0.02, "Decoded silence from $start")
    }
}
