package com.metrolist.android.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun encodeLyricsMp4(output: File, width: Int, height: Int, fps: Int, frameCount: Int, render: (Canvas, Int, Int) -> Unit) {
    require(width in 64..1280 && height in 64..1280 && width % 2 == 0 && height % 2 == 0)
    require(fps in 1..30 && frameCount in 2..fps*30)
    val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } && runCatching {
            val caps = info.getCapabilitiesForType("video/avc")
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) &&
                (caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true)
        }.getOrDefault(false)
    }
    var failure: Exception? = null
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        try { encodeWithCodec(candidate.name, output, width, height, fps, frameCount, render); return }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
    }
    throw IllegalStateException("No working AVC encoder available", failure)
}

private suspend fun encodeWithCodec(name: String, output: File, width: Int, height: Int, fps: Int, frameCount: Int, render: (Canvas, Int, Int) -> Unit) {
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var bitmap: Bitmap? = null
    var codecStarted = false
    var muxerStarted = false
    var complete = false
    try {
        val encoder = MediaCodec.createByCodecName(name).also { codec = it }
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start(); codecStarted = true
        val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val canvas = Canvas(frame)
        val pixels = IntArray(width*height)
        val info = MediaCodec.BufferInfo()
        var inputFrame = 0; var inputEos = false; var outputEos = false; var samples = 0; var track = -1
        var progressAt = SystemClock.elapsedRealtime()
        while (!outputEos) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime()-progressAt < 20_000) { "AVC encoder stopped making progress" }
            if (!inputEos) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (inputFrame < frameCount) {
                        render(canvas, inputFrame, frameCount)
                        currentCoroutineContext().ensureActive()
                        frame.getPixels(pixels, 0, width, 0, 0, width, height)
                        val image = checkNotNull(encoder.getInputImage(index)) { "Encoder has no flexible YUV input image" }
                        try {
                            check(image.width >= width && image.height >= height && image.planes.size == 3)
                            check(image.cropRect.left == 0 && image.cropRect.top == 0)
                            writeLyricsYuv420(pixels,width,height,image.planes.map { LyricsYuvPlane(it.buffer,it.rowStride,it.pixelStride) })
                        } finally { image.close() }
                        encoder.queueInputBuffer(index,0,width*height*3/2,lyricsFrameTimeUs(inputFrame,fps),0)
                        inputFrame++
                    } else {
                        encoder.queueInputBuffer(index,0,0,lyricsFrameTimeUs(frameCount,fps),MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    }
                    progressAt = SystemClock.elapsedRealtime()
                }
            }
            val outputIndex = encoder.dequeueOutputBuffer(info,10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = writer.addTrack(encoder.outputFormat); writer.start(); muxerStarted = true
                    progressAt = SystemClock.elapsedRealtime()
                }
                outputIndex >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val bytes = checkNotNull(encoder.getOutputBuffer(outputIndex))
                            bytes.position(info.offset); bytes.limit(info.offset+info.size)
                            writer.writeSampleData(track,bytes,info); samples++
                            check(output.length() <= 16L*1024*1024) { "Video exceeds export limit" }
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(outputIndex,false) }
                    progressAt = SystemClock.elapsedRealtime()
                }
            }
        }
        check(samples == frameCount && inputFrame == frameCount && muxerStarted) { "Incomplete video" }
        writer.stop(); muxerStarted = false
        check(output.length() in 1..16L*1024*1024)
        currentCoroutineContext().ensureActive()
        complete = true
    } finally {
        bitmap?.recycle()
        if (codecStarted) runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        if (!complete) output.delete()
    }
}
