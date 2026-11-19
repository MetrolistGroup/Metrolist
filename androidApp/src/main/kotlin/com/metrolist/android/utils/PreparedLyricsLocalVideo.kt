package com.metrolist.android.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Owns a silent, decorative video loop. Close when the editor relinquishes ownership. */
class PreparedLyricsLocalVideo internal constructor(
    val durationMs: Long,
    private val frames: List<Bitmap>,
) : Closeable {
    private val lock = Any()
    private var ownerClosed = false
    private var references = 1

    val sampleCount: Int get() = frames.size

    fun acquireLease(): Lease = synchronized(lock) {
        check(!ownerClosed) { "Video session is closed" }
        references++
        Lease()
    }

    override fun close() = synchronized(lock) {
        if (!ownerClosed) {
            ownerClosed = true
            releaseReference()
        }
    }

    private fun releaseReference() {
        references--
        if (references == 0) frames.forEach(Bitmap::recycle)
    }

    /** Export and preview hold their own leases; owner close cannot invalidate existing leases. */
    inner class Lease internal constructor() : Closeable {
        private var closed = false

        /** Borrow only inside this non-suspending block; never retain or recycle the bitmap. */
        fun <T> withFrame(positionMs: Long, block: (Bitmap) -> T): T = synchronized(lock) {
            check(!closed) { "Video lease is closed" }
            references++
            try {
                block(frames[LyricsLocalVideoLimits.frameIndex(positionMs, durationMs)])
            } finally {
                releaseReference()
            }
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                releaseReference()
            }
        }
    }
}

private val localLyricsVideoPreparationMutex = Mutex()

/**
 * Reads one system-picker content URI. Does not request permissions or persist URI grants.
 * Native calls are blocking; cancellation checks between them are not a hard timeout.
 * Candidate supports unencrypted SDR AVC <=1080p on API27+. No source audio is exported.
 */
suspend fun prepareLyricsLocalVideo(context: Context, uri: Uri): PreparedLyricsLocalVideo {
    var delivery: PreparedLyricsLocalVideo? = null
    try {
        return localLyricsVideoPreparationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT < 27) throw UnsupportedOperationException("Scaled video decoding requires API 27")
                require(uri.scheme == "content") { "Choose a video using the system picker" }
                val cancellation = currentCoroutineContext()
                val directory = File(context.cacheDir, "lyrics_local_video")
                check(directory.isDirectory || directory.mkdirs())
                directory.listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith("selected_") && it.extension == "media" }
                    .forEach { it.delete() }
                val staged = File.createTempFile("selected_", ".media", directory)
                val frames = mutableListOf<Bitmap>()
                var handedOff = false
                try {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri)
                    require(mime == null || mime.startsWith("video/")) { "Selected content is not video" }
                    checkNotNull(resolver.openInputStream(uri)) { "Selected video is not readable" }.use { input ->
                        staged.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                cancellation.ensureActive()
                                val read = input.read(buffer)
                                cancellation.ensureActive()
                                if (read < 0) break
                                check(read > 0) { "Video provider returned an empty read" }
                                total = LyricsLocalVideoLimits.addWithinBudget(total, read.toLong(), LyricsLocalVideoLimits.MAX_SOURCE_BYTES)
                                output.write(buffer, 0, read)
                            }
                            check(total > 0) { "Selected video is empty" }
                        }
                    }
                    cancellation.ensureActive()
                    validateLocalVideo(staged)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(staged.absolutePath)
                        cancellation.ensureActive()
                        val duration = LyricsLocalVideoLimits.loopDuration(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                ?: error("Video duration is unavailable"),
                        )
                        var bytes = 0L
                        for (index in 0 until LyricsLocalVideoLimits.frameCount(duration)) {
                            cancellation.ensureActive()
                            val decoded = checkNotNull(retriever.getScaledFrameAtTime(
                                LyricsLocalVideoLimits.sampleTimeUs(index, duration),
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                LyricsLocalVideoLimits.MAX_SIDE,
                                LyricsLocalVideoLimits.MAX_SIDE,
                            )) { "Video frame could not be decoded" }
                            try {
                                cancellation.ensureActive()
                                check(decoded.width in 1..LyricsLocalVideoLimits.MAX_SIDE && decoded.height in 1..LyricsLocalVideoLimits.MAX_SIDE) {
                                    "Decoder exceeded requested dimensions"
                                }
                                // Decoded pixels are canonical. Never reapply metadata rotation in preview/export.
                                val frame = checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, false)) { "Video frame copy failed" }
                                try {
                                    bytes = LyricsLocalVideoLimits.addWithinBudget(bytes, frame.allocationByteCount.toLong(), LyricsLocalVideoLimits.MAX_FRAME_BYTES)
                                    frames += frame
                                } catch (error: Throwable) {
                                    frame.recycle()
                                    throw error
                                }
                            } finally {
                                decoded.recycle()
                            }
                        }
                        cancellation.ensureActive()
                        val result = PreparedLyricsLocalVideo(duration, frames.toList())
                        delivery = result
                        handedOff = true
                        result
                    } finally {
                        retriever.release()
                    }
                } finally {
                    if (!handedOff) frames.forEach(Bitmap::recycle)
                    staged.delete()
                }
            }
        }
    } catch (error: Throwable) {
        // Prompt cancellation can reject withContext delivery after successful preparation.
        delivery?.close()
        throw error
    }
}

private suspend fun validateLocalVideo(file: File) {
    val cancellation = currentCoroutineContext()
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        check(extractor.psshInfo.isNullOrEmpty()) { "Protected video is unsupported" }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track")
        val format = extractor.getTrackFormat(track)
        check(format.getString(MediaFormat.KEY_MIME) == "video/avc") { "Only AVC video is supported" }
        check(LyricsLocalVideoLimits.sourceDimensionsAllowed(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))) {
            "Video must be 1080p or smaller"
        }
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            check(transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG) { "HDR video is unsupported" }
        }
        extractor.selectTrack(track)
        var samples = 0
        while (extractor.sampleTime in 0 until LyricsLocalVideoLimits.MAX_LOOP_MS * 1000) {
            cancellation.ensureActive()
            check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted video is unsupported" }
            check(++samples <= 10_000) { "Video has too many samples" }
            if (!extractor.advance()) break
        }
        check(samples > 0) { "Video has no usable samples" }
    } finally {
        extractor.release()
    }
}
