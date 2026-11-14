package com.metrolist.android.ui.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.api.artwork.youtubeCanvasClips
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

internal data class PreparedCanvasClips(
    val cached: CanvasCache.Entry?,
    val temporaryFile: File?,
    val mimeType: String,
    val durationMs: Long,
    val clips: List<YouTubeCanvasClip>,
)

internal suspend fun prepareCanvasClips(
    factory: DataSource.Factory,
    uri: String,
    clips: List<YouTubeCanvasClip>,
    directory: File,
    onSeek: (Long) -> Unit,
    cache: CanvasCache? = null,
    cacheKey: String? = null,
): PreparedCanvasClips {
    val cancellation = currentCoroutineContext()
    val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), factory)
    val output = File.createTempFile("canvas-clips-", ".media", directory)
    var muxer: MediaMuxer? = null
    var keepOutput = false
    try {
        extractor.setDataSource(uri.toUri(), 0L)
        val track =
            (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
                    true
            }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val videoDurationMs =
            format
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.div(1000)
                ?.takeIf { it > 0L }
        val selectedClips =
            if (clips.isEmpty() || (videoDurationMs != null && clips.any { it.endMs > videoDurationMs })) {
                youtubeCanvasClips(videoDurationMs)
            } else {
                clips
            }
        require(selectedClips.isNotEmpty()) { "Canvas video has no clip duration" }
        val webm = format.getString(MediaFormat.KEY_MIME) in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")
        val container = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val writer = MediaMuxer(output.absolutePath, container).also { muxer = it }
        if (format.containsKey(MediaFormat.KEY_ROTATION)) writer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
        val outputTrack = writer.addTrack(format)
        writer.start()
        var buffer = ByteBuffer.allocateDirect(0)
        val info = MediaCodec.BufferInfo()
        var timelineUs = 0L
        for (clip in selectedClips) {
            cancellation.ensureActive()
            require(clip.startMs >= 0 && clip.endMs > clip.startMs)
            onSeek(clip.startMs)
            extractor.seekTo(clip.startMs * 1000, MediaExtractorCompat.SEEK_TO_CLOSEST_SYNC)
            val startUs = extractor.sampleTime
            check(startUs >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "Canvas clip has no keyframe" }
            val endUs = startUs + (clip.endMs - clip.startMs) * 1000
            var lastUs = startUs
            var previousUs = startUs
            var count = 0
            while (extractor.sampleTime in startUs until endUs) {
                cancellation.ensureActive()
                val sampleTime = extractor.sampleTime
                val size = extractor.sampleSize
                check(size in 1..Int.MAX_VALUE.toLong()) { "Invalid Canvas sample size" }
                if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                check(read == size.toInt()) { "Incomplete Canvas sample" }
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted Canvas sample" }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, read, timelineUs + sampleTime - startUs, flags)
                writer.writeSampleData(outputTrack, buffer, info)
                if (sampleTime > lastUs) {
                    previousUs = lastUs
                    lastUs = sampleTime
                } else if (sampleTime > previousUs && sampleTime < lastUs) {
                    previousUs = sampleTime
                }
                count++
                if (!extractor.advance()) break
            }
            check(count > 0) { "Empty Canvas clip" }
            timelineUs += lastUs - startUs + ((lastUs - previousUs).takeIf { it > 0 } ?: (endUs - startUs))
        }
        writer.stop()
        writer.release()
        muxer = null
        cancellation.ensureActive()
        val mimeType = if (webm) "video/webm" else "video/mp4"
        val durationMs = timelineUs / 1000
        val cached = if (cache != null && cacheKey != null) cache.storeClips(cacheKey, output, mimeType, durationMs) else null
        keepOutput = cached == null
        return PreparedCanvasClips(cached, output.takeIf { keepOutput }, mimeType, durationMs, selectedClips)
    } finally {
        try {
            muxer?.release()
        } finally {
            extractor.release()
            if (!keepOutput) output.delete()
        }
    }
}
