package com.metrolist.android.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal fun exportAacM4a(
    source: File,
    target: File,
    metadata: AudioExportMetadata,
): Boolean =
    runCatching {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track =
                (0 until extractor.trackCount).first { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val writer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val outputTrack = writer.addTrack(format)
            writer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                check(size > 0 && size <= buffer.capacity()) { "Invalid AAC sample size" }
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted AAC sample" }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                writer.writeSampleData(outputTrack, buffer, info)
                samples++
                if (!extractor.advance()) break
            }
            check(samples > 0) { "No AAC samples to export" }
            writer.stop()
            writer.release()
            muxer = null
            tagMuxedM4a(target, metadata)
            true
        } finally {
            try {
                muxer?.release()
            } finally {
                extractor.release()
            }
        }
    }.getOrDefault(false)

// Only tag the unfragmented output of MediaMuxer; remuxing first also handles YouTube's fragmented M4A and raw AAC.
internal fun tagMuxedM4a(
    target: File,
    metadata: AudioExportMetadata,
) {
    val tags = m4aMetadataBox(metadata)
    RandomAccessFile(target, "rw").use { file ->
        val originalLength = file.length()
        var movieOffset = -1L
        var movieSize = 0L
        var movieHeaderSize = 0
        var offset = 0L
        while (offset < originalLength) {
            check(originalLength - offset >= 8) { "Truncated MP4 box" }
            file.seek(offset)
            val shortSize = file.readInt().toLong() and 0xffffffffL
            val type = file.readInt()
            val headerSize = if (shortSize == 1L) 16 else 8
            check(originalLength - offset >= headerSize) { "Truncated MP4 header" }
            val size =
                when (shortSize) {
                    0L -> originalLength - offset
                    1L -> file.readLong()
                    else -> shortSize
                }
            check(size >= headerSize && size <= originalLength - offset) { "Invalid MP4 box size" }
            check(type != 0x6d6f6f66) { "Fragmented MP4 must be remuxed before tagging" }
            if (type == 0x6d6f6f76) {
                check(movieOffset < 0) { "Multiple MP4 movie boxes" }
                movieOffset = offset
                movieSize = size
                movieHeaderSize = headerSize
            }
            if (shortSize == 0L) {
                check(size <= 0xffffffffL) { "MP4 box is too large" }
                file.seek(offset)
                file.writeInt(size.toInt())
            }
            offset += size
        }
        check(movieOffset >= 0) { "Missing MP4 movie box" }
        val payloadSize = movieSize - movieHeaderSize
        val taggedSize = 8L + payloadSize + tags.size
        check(taggedSize <= 0xffffffffL) { "MP4 movie box is too large" }
        // Leave media bytes at their original offsets so stco/co64 entries remain valid.
        val destination = if (movieOffset + movieSize == originalLength && movieHeaderSize == 8) movieOffset else originalLength
        if (destination != movieOffset) {
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (copied < payloadSize) {
                val count = minOf(buffer.size.toLong(), payloadSize - copied).toInt()
                file.seek(movieOffset + movieHeaderSize + copied)
                file.readFully(buffer, 0, count)
                file.seek(destination + 8 + copied)
                file.write(buffer, 0, count)
                copied += count
            }
            file.seek(movieOffset + 4)
            file.writeBytes("free")
        }
        file.seek(destination)
        file.writeInt(taggedSize.toInt())
        file.writeBytes("moov")
        file.seek(destination + 8 + payloadSize)
        file.write(tags)
    }
}

private fun m4aMetadataBox(metadata: AudioExportMetadata): ByteArray {
    fun textTag(
        type: String,
        value: String,
    ): ByteArray = mp4Box(type, mp4Box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value.encodeToByteArray()))

    val items =
        textTag("©nam", metadata.title) + textTag("©ART", metadata.artist) +
            (metadata.album?.takeIf(String::isNotBlank)?.let { textTag("©alb", it) } ?: byteArrayOf())
    val handler = mp4Box("hdlr", ByteArray(8) + "mdir".encodeToByteArray() + ByteArray(13))
    return mp4Box("udta", mp4Box("meta", ByteArray(4) + handler + mp4Box("ilst", items)))
}

private fun mp4Box(
    type: String,
    payload: ByteArray,
): ByteArray =
    ByteArrayOutputStream()
        .apply {
            DataOutputStream(this).apply {
                writeInt(8 + payload.size)
                write(type.toByteArray(Charsets.ISO_8859_1))
                write(payload)
            }
        }.toByteArray()
