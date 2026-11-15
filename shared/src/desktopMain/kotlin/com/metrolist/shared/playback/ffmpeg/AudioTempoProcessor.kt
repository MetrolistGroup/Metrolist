package com.metrolist.shared.playback.ffmpeg

import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.global.avfilter.av_buffersink_get_frame
import org.bytedeco.ffmpeg.global.avfilter.av_buffersrc_add_frame
import org.bytedeco.ffmpeg.global.avfilter.avfilter_get_by_name
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_alloc
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_config
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_create_filter
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_free
import org.bytedeco.ffmpeg.global.avfilter.avfilter_link
import org.bytedeco.ffmpeg.global.avfilter.avfilter_process_command
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_d2q
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.javacpp.BytePointer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class AudioTempoProcessor(
    private val sampleRate: Int,
    private val channelCount: Int,
    speed: Float,
    pitch: Float = 1f,
) : AutoCloseable {
    private val graph = avfilter_graph_alloc()
    private val input = av_frame_alloc()
    private val output = av_frame_alloc()
    private val source = AVFilterContext(null)
    private val pitchRate = (sampleRate * pitch).roundToInt()
    private val initialTempoFactors = tempoFactors(speed, pitchRate)
    private val tempoFilters = initialTempoFactors.map { AVFilterContext(null) }
    private val sink = AVFilterContext(null)
    private var timestamp = 0L

    init {
        try {
            require(pitch.isFinite() && pitch in 0.5f..2f)
            graph.nb_threads(1)
            val processingFormat = AVFilterContext(null)
            val format = AVFilterContext(null)
            check(
                avfilter_graph_create_filter(
                    source,
                    avfilter_get_by_name("abuffer"),
                    "input",
                    "time_base=1/$sampleRate:sample_rate=$sampleRate:sample_fmt=s16:channel_layout=${channelCount}c",
                    null,
                    graph,
                ) >= 0,
            )
            check(
                avfilter_graph_create_filter(
                    processingFormat,
                    avfilter_get_by_name("aformat"),
                    "processing_format",
                    "sample_fmts=fltp",
                    null,
                    graph,
                ) >= 0,
            )
            check(avfilter_link(source, 0, processingFormat, 0) >= 0)
            val rate = AVFilterContext(null)
            val resample = AVFilterContext(null)
            var previous = processingFormat
            tempoFilters.forEachIndexed { index, filter ->
                check(
                    avfilter_graph_create_filter(
                        filter,
                        avfilter_get_by_name("atempo"),
                        "tempo_$index",
                        tempoExpression(initialTempoFactors[index]),
                        null,
                        graph,
                    ) >= 0,
                )
                check(avfilter_link(previous, 0, filter, 0) >= 0)
                previous = filter
            }
            check(avfilter_graph_create_filter(rate, avfilter_get_by_name("asetrate"), "pitch", pitchRate.toString(), null, graph) >= 0)
            check(
                avfilter_graph_create_filter(resample, avfilter_get_by_name("aresample"), "resample", sampleRate.toString(), null, graph) >=
                    0,
            )
            check(avfilter_graph_create_filter(format, avfilter_get_by_name("aformat"), "format", "sample_fmts=s16", null, graph) >= 0)
            check(avfilter_graph_create_filter(sink, avfilter_get_by_name("abuffersink"), "output", null as String?, null, graph) >= 0)
            check(avfilter_link(previous, 0, rate, 0) >= 0)
            check(avfilter_link(rate, 0, resample, 0) >= 0)
            check(avfilter_link(resample, 0, format, 0) >= 0)
            check(avfilter_link(format, 0, sink, 0) >= 0)
            check(avfilter_graph_config(graph, null) >= 0)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun setSpeed(speed: Float) {
        val factors = tempoFactors(speed, pitchRate)
        if (factors.size != tempoFilters.size) return
        tempoFilters.forEachIndexed { index, filter ->
            check(avfilter_process_command(filter, "tempo", tempoExpression(factors[index]), null as BytePointer?, 0, 0) >= 0)
        }
    }

    fun requiresRebuild(
        speed: Float,
        pitch: Float,
    ): Boolean {
        val requestedPitchRate = (sampleRate * pitch).roundToInt()
        return requestedPitchRate != pitchRate || tempoFactors(speed, requestedPitchRate).size != tempoFilters.size
    }

    // FFmpeg accepts rational expressions without depending on the native decimal separator.
    private fun tempoExpression(speed: Float): String {
        require(speed.isFinite() && speed > 0)
        return av_d2q(speed.toDouble(), Int.MAX_VALUE).use { "${it.num()}/${it.den()}" }
    }

    fun process(
        data: ByteArray?,
        write: (ByteArray) -> Unit,
    ) {
        if (data == null) {
            check(av_buffersrc_add_frame(source, null) >= 0)
        } else {
            require(data.size % (channelCount * Short.SIZE_BYTES) == 0)
            val samples = data.size / (channelCount * Short.SIZE_BYTES)
            input
                .format(AV_SAMPLE_FMT_S16)
                .sample_rate(sampleRate)
                .nb_samples(samples)
                .pts(timestamp)
            av_channel_layout_default(input.ch_layout(), channelCount)
            check(av_frame_get_buffer(input, 0) >= 0)
            input.data(0).put(data, 0, data.size)
            timestamp += samples
            check(av_buffersrc_add_frame(source, input) >= 0)
            av_frame_unref(input)
        }
        while (true) {
            val result = av_buffersink_get_frame(sink, output)
            if (result == AVERROR_EAGAIN() || result == AVERROR_EOF) break
            check(result >= 0) { "Audio tempo filter failed: $result" }
            try {
                val bytes = ByteArray(output.nb_samples() * channelCount * Short.SIZE_BYTES)
                output.data(0).get(bytes)
                write(bytes)
            } finally {
                av_frame_unref(output)
            }
        }
    }

    override fun close() {
        av_frame_free(input)
        av_frame_free(output)
        avfilter_graph_free(graph)
    }

    private fun tempoFactors(
        speed: Float,
        rate: Int,
    ): List<Float> {
        // Pitch-rate conversion changes duration by sampleRate/rate; omit identity and split ratios below 0.5.
        val factor = speed.toDouble() * sampleRate / rate
        if (abs(factor - 1.0) < TEMPO_IDENTITY_EPSILON) return emptyList()
        return if (factor < MIN_ATEMPO || factor > MAX_ATEMPO) {
            val stageFactor = sqrt(factor).toFloat()
            listOf(stageFactor, stageFactor)
        } else {
            listOf(factor.toFloat())
        }
    }
}

private const val TEMPO_IDENTITY_EPSILON = 1e-4
private const val MIN_ATEMPO = 0.5
private const val MAX_ATEMPO = 2.0
