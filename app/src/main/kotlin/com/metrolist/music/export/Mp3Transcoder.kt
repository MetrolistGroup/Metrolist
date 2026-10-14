/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * Decodes any audio track Android can decode (AAC, Opus, Vorbis...) and encodes it as MP3 with LAME.
 *
 * MP3 is a lossy format, so this is lossy -> lossy; the result cannot be better than the source.
 */
object Mp3Transcoder {
    const val BITRATE_KBPS = 192
    private const val CODEC_TIMEOUT_US = 10_000L

    // TAndroidLame keeps the encoder in native static state, so only one encode may run at a time.
    private val lameLock = Mutex()

    suspend fun transcode(
        input: File,
        output: OutputStream,
        tags: ExportTags,
        onProgress: (Float?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        lameLock.withLock {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var lame: AndroidLame? = null
            try {
                extractor.setDataSource(input.absolutePath)
                val trackIndex =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw ExportException(ExportError.UnsupportedSource)
                val inputFormat = extractor.getTrackFormat(trackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
                val durationUs =
                    if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
                extractor.selectTrack(trackIndex)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                output.write(Id3v2Writer.build(tags))

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var channels = 0
                var floatPcm = false
                var mp3Buffer = ByteArray(0)

                while (!outputDone) {
                    currentCoroutineContext().ensureActive()

                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            floatPcm =
                                format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            if (channels !in 1..2) throw ExportException(ExportError.UnsupportedSource)
                            lame =
                                createEncoder(sampleRate, channels)
                        }
                        outIndex >= 0 -> {
                            val encoder = lame
                            if (info.size > 0 && encoder != null) {
                                val buffer = codec.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val pcm = toShorts(buffer, floatPcm)
                                val frames = pcm.size / channels
                                val needed = (1.25 * frames + 7200).toInt()
                                if (mp3Buffer.size < needed) mp3Buffer = ByteArray(needed)
                                val written =
                                    if (channels == 2) {
                                        encoder.encodeBufferInterLeaved(pcm, frames, mp3Buffer)
                                    } else {
                                        encoder.encode(pcm, pcm, frames, mp3Buffer)
                                    }
                                if (written < 0) throw ExportException(ExportError.TranscodeFailed)
                                if (written > 0) output.write(mp3Buffer, 0, written)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }

                val encoder = lame ?: throw ExportException(ExportError.TranscodeFailed)
                if (mp3Buffer.size < 7200) mp3Buffer = ByteArray(7200)
                val tail = encoder.flush(mp3Buffer)
                if (tail > 0) output.write(mp3Buffer, 0, tail)
                output.flush()
                onProgress(1f)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExportException) {
                throw error
            } catch (error: UnsatisfiedLinkError) {
                throw ExportException(ExportError.Mp3Unavailable, error)
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw ExportException(ExportError.TranscodeFailed, error)
            } finally {
                runCatching { lame?.close() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }
    }

    private fun createEncoder(
        sampleRate: Int,
        channels: Int,
    ): AndroidLame =
        LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutChannels(channels)
            .setOutBitrate(BITRATE_KBPS)
            .setQuality(2)
            .setMode(if (channels == 1) LameBuilder.Mode.MONO else LameBuilder.Mode.JSTEREO)
            .setVbrMode(LameBuilder.VbrMode.VBR_OFF)
            .build()

    private fun toShorts(
        buffer: java.nio.ByteBuffer,
        floatPcm: Boolean,
    ): ShortArray {
        buffer.order(ByteOrder.nativeOrder())
        return if (floatPcm) {
            val floats = buffer.asFloatBuffer()
            ShortArray(floats.remaining()) { (floats.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        } else {
            val shorts = buffer.asShortBuffer()
            ShortArray(shorts.remaining()).also { shorts.get(it) }
        }
    }
}
