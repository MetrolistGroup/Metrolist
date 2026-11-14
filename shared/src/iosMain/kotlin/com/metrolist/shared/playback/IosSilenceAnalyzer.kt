package com.metrolist.shared.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
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
import platform.Foundation.NSURL
import kotlin.math.sqrt

private const val SILENCE_ANALYSIS_SAMPLE_RATE = 8_000
private const val SILENCE_ANALYSIS_WINDOW_SAMPLES = 800
private const val SILENCE_ANALYSIS_MAX_WINDOWS = 54_000

@OptIn(ExperimentalForeignApi::class)
internal suspend fun analyzeIosSilenceMap(fileUrl: String): List<SilenceInterval> =
    withContext(Dispatchers.Default) {
        val url = NSURL.URLWithString(fileUrl)?.takeIf { it.isFileURL() } ?: return@withContext emptyList()
        val asset = AVURLAsset(uRL = url, options = null)
        if (asset.tracksWithMediaType(AVMediaTypeAudio).isEmpty()) return@withContext emptyList()
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack ?: return@withContext emptyList()
        val reader = AVAssetReader(asset = asset, error = null)
        val output =
            AVAssetReaderTrackOutput(
                track = track,
                outputSettings =
                    mapOf(
                        "AVFormatIDKey" to kAudioFormatLinearPCM,
                        "AVSampleRateKey" to SILENCE_ANALYSIS_SAMPLE_RATE,
                        "AVNumberOfChannelsKey" to 1,
                        "AVLinearPCMBitDepthKey" to 16,
                        "AVLinearPCMIsBigEndianKey" to false,
                        "AVLinearPCMIsFloatKey" to false,
                        "AVLinearPCMIsNonInterleaved" to false,
                    ),
            )
        if (!reader.canAddOutput(output)) return@withContext emptyList()
        reader.addOutput(output)
        if (!reader.startReading()) return@withContext emptyList()

        val rmsWindows = ArrayList<Float>()
        var windowSquares = 0.0
        var windowSamples = 0
        try {
            while (reader.status == AVAssetReaderStatusReading) {
                currentCoroutineContext().ensureActive()
                val sampleBuffer = output.copyNextSampleBuffer() ?: break
                try {
                    val blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) ?: continue
                    val dataLength = CMBlockBufferGetDataLength(blockBuffer).toInt()
                    if (dataLength <= 0) continue
                    val bytes = ByteArray(dataLength)
                    val copyStatus =
                        bytes.usePinned { pinned ->
                            CMBlockBufferCopyDataBytes(blockBuffer, 0uL, dataLength.toULong(), pinned.addressOf(0))
                        }
                    if (copyStatus != 0) continue
                    for (index in 0 until dataLength / 2) {
                        val low = bytes[index * 2].toUByte().toInt()
                        val high = bytes[index * 2 + 1].toUByte().toInt()
                        val sample = ((low + high * 256).toShort().toInt() / 32768.0)
                        windowSquares += sample * sample
                        windowSamples++
                        if (windowSamples == SILENCE_ANALYSIS_WINDOW_SAMPLES) {
                            rmsWindows += sqrt(windowSquares / windowSamples).toFloat()
                            windowSquares = 0.0
                            windowSamples = 0
                            if (rmsWindows.size > SILENCE_ANALYSIS_MAX_WINDOWS) return@withContext emptyList()
                        }
                    }
                } finally {
                    CFRelease(sampleBuffer)
                }
            }
        } finally {
            reader.cancelReading()
        }
        if (windowSamples >= SILENCE_ANALYSIS_WINDOW_SAMPLES / 2) rmsWindows += sqrt(windowSquares / windowSamples).toFloat()
        detectSilenceIntervals(rmsWindows)
    }
