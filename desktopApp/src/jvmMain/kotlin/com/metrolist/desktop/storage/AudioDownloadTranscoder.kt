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
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avformat.AVOutputFormat
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open2
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
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
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

internal object AudioDownloadTranscoder {
    private const val AAC_BITRATE = 192_000L

    internal class CopiedAudio(
        val file: File,
        val mimeType: String,
    )

    /**
     * Copies Opus into Ogg and AAC into M4A without decoding. Returns null when the source codec
     * has no lossless target, so the caller can transcode instead.
     */
    suspend fun streamCopy(
        source: File,
        song: SongItem,
        reserveDestination: (extension: String) -> File,
        onProgress: (Float) -> Unit = {},
    ): CopiedAudio? {
        val context = currentCoroutineContext()
        context.ensureActive()
        FFmpegLogCallback.set()
        FFmpegLogCallback.setLevel(AV_LOG_WARNING)
        val input = AVFormatContext(null)
        val output = AVFormatContext(null)
        var inputOpened = false
        var packet: AVPacket? = null
        var destination: File? = null
        var temp: File? = null
        try {
            ffmpeg(avformat_open_input(input, source.path, null as AVInputFormat?, null as AVDictionary?), "open ${source.name}")
            inputOpened = true
            ffmpeg(avformat_find_stream_info(input, null as PointerPointer<*>?), "read the audio stream")
            val inputIndex = av_find_best_stream(input, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            if (inputIndex < 0) return null
            val inputStream = input.streams(inputIndex)
            val (muxer, extension, mimeType) =
                when (inputStream.codecpar().codec_id()) {
                    AV_CODEC_ID_OPUS -> Triple("ogg", "opus", "audio/ogg")
                    AV_CODEC_ID_AAC -> Triple("ipod", "m4a", "audio/mp4")
                    else -> return null
                }
            val target = reserveDestination(extension).also { destination = it }
            val tempFile = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp.$extension").also { temp = it }
            ffmpeg(avformat_alloc_output_context2(output, null as AVOutputFormat?, muxer, tempFile.path), "create the $extension muxer")
            val outputStream = avformat_new_stream(output, null as AVCodec?) ?: throw IOException("Could not create the audio stream")
            ffmpeg(avcodec_parameters_copy(outputStream.codecpar(), inputStream.codecpar()), "copy the audio stream parameters")
            outputStream.codecpar().codec_tag(0)
            outputStream.time_base(inputStream.time_base())
            setMetadata(metadata(song)) { tags ->
                if (muxer == "ogg") outputStream.metadata(tags) else output.metadata(tags)
            }
            val io = AVIOContext(null)
            ffmpeg(avio_open2(io, tempFile.path, AVIO_FLAG_WRITE, null as AVIOInterruptCB?, null as AVDictionary?), "open ${tempFile.name}")
            output.pb(io)
            ffmpeg(avformat_write_header(output, null as AVDictionary?), "write the $extension header")

            val pkt =
                av_packet_alloc()?.takeUnless { it.isNull }?.also { packet = it } ?: throw IOException("Could not allocate an audio packet")
            val durationMicros = input.duration().takeIf { it > 0 }
            while (true) {
                context.ensureActive()
                val result = av_read_frame(input, pkt)
                if (result == AVERROR_EOF) break
                ffmpeg(result, "read an audio packet")
                if (pkt.stream_index() != inputIndex) {
                    av_packet_unref(pkt)
                    continue
                }
                durationMicros?.let { duration ->
                    pkt.pts().takeIf { it != AV_NOPTS_VALUE }?.let { pts ->
                        val timeBase = inputStream.time_base()
                        // Container duration is in AV_TIME_BASE units (microseconds).
                        val micros = pts * 1_000_000.0 * timeBase.num() / timeBase.den()
                        onProgress((micros / duration).toFloat().coerceIn(0f, 1f))
                    }
                }
                av_packet_rescale_ts(pkt, inputStream.time_base(), outputStream.time_base())
                pkt.stream_index(outputStream.index())
                pkt.pos(-1)
                ffmpeg(av_interleaved_write_frame(output, pkt), "write an audio packet")
            }
            ffmpeg(av_write_trailer(output), "finish the $extension file")
            // Close before promoting: Windows cannot move a file that is still open.
            avio_closep(output.pb())
            output.pb(null as AVIOContext?)
            context.ensureActive()
            check(DownloadManager.promoteTempFile(tempFile, target, minimumBytes = 1024)) { "Failed to finalize the $extension download" }
            onProgress(1f)
            destination = null
            return CopiedAudio(target, mimeType)
        } finally {
            av_packet_free(packet)
            if (!output.isNull) {
                output.pb()?.takeUnless { it.isNull }?.let { avio_closep(it) }
                avformat_free_context(output)
            }
            if (inputOpened) avformat_close_input(input)
            temp?.delete()
            destination?.delete()
        }
    }

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

    private fun setMetadata(
        metadata: Map<String, String>,
        assign: (AVDictionary) -> Unit,
    ) {
        val tags = AVDictionary(null)
        try {
            metadata.forEach { (key, value) ->
                BytePointer(key, "UTF-8").use { nativeKey ->
                    BytePointer(value, "UTF-8").use { nativeValue ->
                        ffmpeg(av_dict_set(tags, nativeKey, nativeValue, 0), "set $key metadata")
                    }
                }
            }
        } finally {
            // Format teardown also owns a partially populated dictionary.
            assign(tags)
        }
    }

    private fun ffmpeg(
        result: Int,
        action: String,
    ) {
        if (result < 0) throw IOException("Could not $action (FFmpeg error $result)")
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
        private var codec: AVCodecContext? = null
        private val stream: AVStream
        private var frame: AVFrame? = null
        private var packet: AVPacket? = null
        private val frameSize: Int

        private val pending = Array(channels) { FloatArray(0) }
        private var pendingCount = 0
        private var nextPts = 0L
        private var closed = false

        init {
            try {
                val encoder =
                    avcodec_find_encoder_by_name("aac")?.takeUnless { it.isNull }
                        ?: throw IOException("AAC encoder is unavailable")
                val frame =
                    av_frame_alloc()?.takeUnless { it.isNull }?.also { this.frame = it }
                        ?: throw IOException("Could not allocate an audio frame")
                packet =
                    av_packet_alloc()?.takeUnless { it.isNull }
                        ?: throw IOException("Could not allocate an audio packet")
                val codec =
                    avcodec_alloc_context3(encoder)?.takeUnless { it.isNull }?.also { this.codec = it }
                        ?: throw IOException("Could not allocate the AAC encoder")
                ffmpeg(avformat_alloc_output_context2(format, null as AVOutputFormat?, "ipod", file.path), "create the M4A muxer")
                codec.sample_fmt(AV_SAMPLE_FMT_FLTP)
                codec.sample_rate(sampleRate)
                codec.bit_rate(AAC_BITRATE)
                av_make_q(1, sampleRate).use { codec.time_base(it) }
                av_channel_layout_default(codec.ch_layout(), channels)
                if (format.oformat().flags() and AVFMT_GLOBALHEADER != 0) {
                    codec.flags(codec.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
                }
                ffmpeg(avcodec_open2(codec, encoder, null as AVDictionary?), "open the AAC encoder")
                frameSize = codec.frame_size()
                stream = avformat_new_stream(format, null as AVCodec?) ?: throw IOException("Could not create the audio stream")
                stream.time_base(codec.time_base())
                ffmpeg(avcodec_parameters_from_context(stream.codecpar(), codec), "configure the audio stream")

                setMetadata(metadata) { format.metadata(it) }

                val io = AVIOContext(null)
                ffmpeg(avio_open2(io, file.path, AVIO_FLAG_WRITE, null as AVIOInterruptCB?, null as AVDictionary?), "open ${file.name}")
                format.pb(io)
                ffmpeg(avformat_write_header(format, null as AVDictionary?), "write the M4A header")

                frame.format(AV_SAMPLE_FMT_FLTP)
                frame.sample_rate(sampleRate)
                ffmpeg(av_channel_layout_copy(frame.ch_layout(), codec.ch_layout()), "configure the audio frame layout")
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
            val frame = checkNotNull(frame)
            ffmpeg(av_frame_make_writable(frame), "reuse the audio frame")
            frame.nb_samples(samples)
            for (channel in 0 until channels) {
                FloatPointer(frame.data(channel)).use { it.put(pending[channel], offset, samples) }
            }
            frame.pts(nextPts)
            nextPts += samples
            drain(frame)
        }

        private fun drain(input: AVFrame?) {
            val codec = checkNotNull(codec)
            val packet = checkNotNull(packet)
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
            // Unlike av_frame_free and av_packet_free, avcodec_free_context dereferences a null argument.
            codec?.let { avcodec_free_context(it) }
            if (!format.isNull) {
                format.pb()?.takeUnless { it.isNull }?.let { avio_closep(it) }
                avformat_free_context(format)
            }
        }
    }
}
