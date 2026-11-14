@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.playback.opus

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
import platform.CoreMedia.CMSampleBufferGetOutputDuration
import platform.CoreMedia.CMSampleBufferGetOutputPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosOpusDecodeTest {
    @Test
    fun systemDecoderReadsRemuxedOpusWithTheDeclaredDuration() {
        val mp4 = OpusMp4Remuxer.remux(opusWebmFixture())
        val path = "${NSTemporaryDirectory()}/${NSUUID.UUID().UUIDString}.mp4"
        val files = NSFileManager.defaultManager
        assertTrue(files.createFileAtPath(path, mp4.usePinned { NSData.create(it.addressOf(0), mp4.size.toULong()) }, null))
        try {
            val asset = AVURLAsset(NSURL.fileURLWithPath(path), null)
            val track = assertNotNull(asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack)
            assertTrue(abs(CMTimeGetSeconds(asset.duration) - 2.0) < 1.0 / 48_000)
            val reader = AVAssetReader(asset, null)
            reader.timeRange = CMTimeRangeMake(CMTimeMake(0, 48_000), asset.duration)
            val output =
                AVAssetReaderTrackOutput(
                    track,
                    mapOf(
                        "AVFormatIDKey" to kAudioFormatLinearPCM,
                        "AVSampleRateKey" to 48_000,
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
            var presentationFrames = 0L
            var presentationEnd = 0.0
            while (reader.status == AVAssetReaderStatusReading) {
                val sample = output.copyNextSampleBuffer() ?: break
                try {
                    val duration = CMTimeGetSeconds(CMSampleBufferGetOutputDuration(sample))
                    val start = CMTimeGetSeconds(CMSampleBufferGetOutputPresentationTimeStamp(sample))
                    assertTrue(duration.isFinite() && start.isFinite())
                    assertTrue(abs(start - presentationEnd) < 1.0 / 48_000, "Discontinuous Opus presentation at $start")
                    presentationFrames += (duration * 48_000).roundToLong()
                    presentationEnd = start + duration
                    val block = assertNotNull(CMSampleBufferGetDataBuffer(sample))
                    val length = CMBlockBufferGetDataLength(block).toInt()
                    val bytes = ByteArray(length)
                    if (length >
                        0
                    ) {
                        assertEquals(0, bytes.usePinned { CMBlockBufferCopyDataBytes(block, 0u, length.toULong(), it.addressOf(0)) })
                    }
                    for (index in 0 until length / 2) {
                        val pcm = ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                        energy += pcm.toDouble() * pcm
                        samples++
                    }
                    assertTrue(samples <= 48_000 * 2 * 3)
                } finally {
                    CFRelease(sample)
                }
            }
            assertEquals(AVAssetReaderStatusCompleted, reader.status, reader.error?.localizedDescription)
            val padding = WebmOpusDemuxer.demux(opusWebmFixture()).discardSamples
            assertTrue(presentationFrames in 96_000L..96_000L + padding, "Unexpected decoder output duration $presentationEnd")
            assertTrue(samples >= presentationFrames * 2)
            assertTrue(sqrt(energy / samples) / Short.MAX_VALUE in 0.04..0.09)
        } finally {
            files.removeItemAtPath(path, null)
        }
    }
}
