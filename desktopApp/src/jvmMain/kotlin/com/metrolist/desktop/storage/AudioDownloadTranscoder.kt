package com.metrolist.desktop.storage

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import com.metrolist.shared.playback.ffmpeg.FFmpegLogCallback
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVOutputFormat
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open2
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

internal object AudioDownloadTranscoder {
    private const val AAC_BITRATE = 192_000L

    suspend fun transcodeToM4a(
        source: File,
        destination: File,
        song: SongItem,
        onProgress: (Float) -> Unit = {},
    ) {
        val context = currentCoroutineContext()
        context.ensureActive()
        FFmpegLogCallback.set()
        FFmpegLogCallback.setLevel(AV_LOG_WARNING)
        val temp = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp.m4a")
        val grabber = FFmpegFrameGrabber(source).apply { sampleFormat = AV_SAMPLE_FMT_FLTP }
        var grabberStarted = false
        var encoder: AacM4aEncoder? = null
        try {
            grabber.start()
            grabberStarted = true
            val sampleRate = grabber.sampleRate.takeIf { it > 0 } ?: throw IOException("Audio sample rate is unavailable")
            val channels = grabber.audioChannels.takeIf { it > 0 } ?: throw IOException("Audio channel count is unavailable")
            val durationMicros = grabber.getLengthInTime().takeIf { it > 0 }

            encoder = AacM4aEncoder(temp, sampleRate, channels, metadata(song))
            while (true) {
                context.ensureActive()
                val frame = grabber.grabSamples() ?: break
                val planes = frame.samples ?: continue
                encoder.write(Array(channels) { planes[it] as FloatBuffer })
                val progress = durationMicros?.let { (grabber.timestamp.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                onProgress(progress)
            }
            encoder.finish()
            encoder.close()
            encoder = null
            context.ensureActive()
            check(DownloadManager.promoteTempFile(temp, destination, minimumBytes = 1024)) {
                "Failed to finalize M4A download"
            }
            onProgress(1f)
        } finally {
            runCatching { encoder?.close() }
            if (grabberStarted) {
                runCatching { grabber.stop() }
            }
            runCatching { grabber.release() }
            temp.delete()
        }
    }

    private fun metadata(song: SongItem): Map<String, String> =
        buildMap {
            put("title", song.title)
            song.artists
                .joinToString(", ") { it.name }
                .takeIf(String::isNotBlank)
                ?.let { put("artist", it) }
            song.album
                ?.name
                ?.takeIf(String::isNotBlank)
                ?.let { put("album", it) }
            song.year?.let { put("date", it.toString()) }
            song.genre?.takeIf(String::isNotBlank)?.let { put("genre", it) }
            song.trackNumber?.let { track ->
                put("track", song.totalTrackCount?.let { "$track/$it" } ?: track.toString())
            }
            val origin = "https://music.youtube.com/watch?v=${song.id}"
            put("comment", origin)
            put("purl", origin)
        }

    private class AacM4aEncoder(
        file: File,
        sampleRate: Int,
        private val channels: Int,
        metadata: Map<String, String>,
    ) : AutoCloseable {
        private val format = AVFormatContext(null)
        private val codec: AVCodecContext
        private val stream: AVStream
        private val frame: AVFrame = av_frame_alloc()
        private val packet: AVPacket = av_packet_alloc()
        private val frameSize: Int

        private val pending = Array(channels) { FloatArray(0) }
        private var pendingCount = 0
        private var nextPts = 0L
        private var closed = false

        init {
            val encoder = avcodec_find_encoder_by_name("aac")
            codec = avcodec_alloc_context3(encoder)
            try {
                if (encoder == null || encoder.isNull) throw IOException("AAC encoder is unavailable")
                ffmpeg(avformat_alloc_output_context2(format, null as AVOutputFormat?, "ipod", file.path), "create the M4A muxer")
                codec.sample_fmt(AV_SAMPLE_FMT_FLTP)
                codec.sample_rate(sampleRate)
                codec.bit_rate(AAC_BITRATE)
                codec.time_base(av_make_q(1, sampleRate))
                av_channel_layout_default(codec.ch_layout(), channels)
                if (format.oformat().flags() and AVFMT_GLOBALHEADER != 0) {
                    codec.flags(codec.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
                }
                ffmpeg(avcodec_open2(codec, encoder, null as AVDictionary?), "open the AAC encoder")
                frameSize = codec.frame_size()
                stream = avformat_new_stream(format, null as AVCodec?) ?: throw IOException("Could not create the audio stream")
                stream.time_base(codec.time_base())
                ffmpeg(avcodec_parameters_from_context(stream.codecpar(), codec), "configure the audio stream")

                val tags = AVDictionary(null)
                metadata.forEach { (key, value) -> av_dict_set(tags, BytePointer(key, "UTF-8"), BytePointer(value, "UTF-8"), 0) }
                format.metadata(tags)

                val io = AVIOContext(null)
                ffmpeg(avio_open2(io, file.path, AVIO_FLAG_WRITE, null as AVIOInterruptCB?, null as AVDictionary?), "open ${file.name}")
                format.pb(io)
                ffmpeg(avformat_write_header(format, null as AVDictionary?), "write the M4A header")

                frame.format(AV_SAMPLE_FMT_FLTP)
                frame.sample_rate(sampleRate)
                av_channel_layout_copy(frame.ch_layout(), codec.ch_layout())
                frame.nb_samples(frameSize)
                ffmpeg(av_frame_get_buffer(frame, 0), "allocate an audio frame")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun write(planes: Array<FloatBuffer>) {
            val count = planes[0].remaining()
            for (channel in 0 until channels) {
                val buffer = pending[channel]
                val grown = if (buffer.size < pendingCount + count) buffer.copyOf(pendingCount + count) else buffer
                planes[channel].duplicate().get(grown, pendingCount, count)
                pending[channel] = grown
            }
            pendingCount += count
            var offset = 0
            while (pendingCount - offset >= frameSize) {
                encode(offset, frameSize)
                offset += frameSize
            }
            if (offset > 0) {
                for (channel in 0 until channels) {
                    pending[channel].copyInto(pending[channel], 0, offset, pendingCount)
                }
                pendingCount -= offset
            }
        }

        fun finish() {
            // The AAC encoder accepts a shorter final frame.
            if (pendingCount > 0) encode(0, pendingCount)
            pendingCount = 0
            drain(null)
            ffmpeg(av_write_trailer(format), "finish the M4A file")
        }

        private fun encode(
            offset: Int,
            samples: Int,
        ) {
            ffmpeg(av_frame_make_writable(frame), "reuse the audio frame")
            frame.nb_samples(samples)
            for (channel in 0 until channels) {
                FloatPointer(frame.data(channel)).put(pending[channel], offset, samples)
            }
            frame.pts(nextPts)
            nextPts += samples
            drain(frame)
        }

        private fun drain(input: AVFrame?) {
            ffmpeg(avcodec_send_frame(codec, input), "encode audio")
            while (true) {
                val result = avcodec_receive_packet(codec, packet)
                if (result == AVERROR_EAGAIN() || result == AVERROR_EOF) return
                ffmpeg(result, "receive an AAC packet")
                av_packet_rescale_ts(packet, codec.time_base(), stream.time_base())
                packet.stream_index(stream.index())
                ffmpeg(av_interleaved_write_frame(format, packet), "write an AAC packet")
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            av_packet_free(packet)
            av_frame_free(frame)
            avcodec_free_context(codec)
            if (!format.isNull) {
                format.pb()?.takeUnless { it.isNull }?.let { avio_closep(it) }
                avformat_free_context(format)
            }
        }

        private fun ffmpeg(
            result: Int,
            action: String,
        ) {
            if (result < 0) throw IOException("Could not $action (FFmpeg error $result)")
        }
    }
}
