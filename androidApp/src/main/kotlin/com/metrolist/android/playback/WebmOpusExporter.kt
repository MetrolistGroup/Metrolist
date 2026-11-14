package com.metrolist.android.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

internal data class AudioExportMetadata(
    val title: String,
    val artist: String,
    val album: String?,
)

internal fun prepareStoredAudio(
    source: File,
    mimeType: String?,
    publicStorage: Boolean,
    metadata: AudioExportMetadata,
    exportAac: (File, File, AudioExportMetadata) -> Boolean = ::exportAacM4a,
    export: (File, File, AudioExportMetadata) -> Boolean = ::exportWebmOpus,
): Pair<File, String?> {
    if (!publicStorage) return source to mimeType
    val (extension, storedMimeType, exporter) =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/webm" -> Triple("opus", "audio/ogg", export)
            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> Triple("m4a", "audio/mp4", exportAac)
            else -> return source to mimeType
        }
    val target = File(source.parentFile, "${source.nameWithoutExtension}.$extension.tmp")
    try {
        check(exporter(source, target, metadata) && target.length() > 0L) { "Audio export failed" }
    } catch (error: Exception) {
        target.delete()
        throw error
    }
    source.delete()
    return target to storedMimeType
}

internal fun exportWebmOpus(
    source: File,
    target: File,
    metadata: AudioExportMetadata,
): Boolean =
    runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
                } ?: return false
            val format = extractor.getTrackFormat(trackIndex)
            val opusHead =
                format.getByteBuffer("csd-0")?.remainingBytes()?.takeIf { it.size >= 19 && it.startsWith(OPUS_HEAD) } ?: return false
            val preSkip = (opusHead[10].toInt() and 0xff) or ((opusHead[11].toInt() and 0xff) shl 8)
            val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            val maxPacketBytes = format.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024).coerceIn(4 * 1024, 1024 * 1024)
            val packetBuffer = ByteBuffer.allocate(maxPacketBytes)

            extractor.selectTrack(trackIndex)
            BufferedOutputStream(target.outputStream()).use { output ->
                val writer = OggPageWriter(output)
                writer.writePacket(opusHead, granulePosition = 0L, beginningOfStream = true)
                writer.writePacket(opusTagsPacket(metadata), granulePosition = 0L)

                var firstTimeUs = -1L
                var previousTimeUs = -1L
                while (true) {
                    packetBuffer.clear()
                    val packetSize = extractor.readSampleData(packetBuffer, 0)
                    if (packetSize < 0) break
                    val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    if (firstTimeUs < 0L) firstTimeUs = sampleTimeUs
                    val packet = ByteArray(packetSize)
                    packetBuffer.position(0)
                    packetBuffer.get(packet)
                    extractor.advance()
                    val nextTimeUs = extractor.sampleTime
                    val inferredDurationUs = (sampleTimeUs - previousTimeUs).takeIf { it > 0L } ?: DEFAULT_PACKET_DURATION_US
                    val endTimeUs =
                        when {
                            nextTimeUs >= sampleTimeUs -> nextTimeUs
                            durationUs > sampleTimeUs -> durationUs
                            else -> sampleTimeUs + inferredDurationUs
                        }
                    val granule = preSkip + ((endTimeUs - firstTimeUs).coerceAtLeast(0L) * OPUS_SAMPLE_RATE / 1_000_000L)
                    writer.writePacket(packet, granulePosition = granule, endOfStream = nextTimeUs < 0L)
                    previousTimeUs = sampleTimeUs
                }
                check(firstTimeUs >= 0L) { "No Opus samples to export" }
            }
            check(target.length() > 0L)
            true
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

internal fun opusTagsPacket(metadata: AudioExportMetadata): ByteArray {
    val vendor = "Metrolist".encodeToByteArray()
    val comments =
        buildList {
            add("TITLE=${metadata.title}")
            add("ARTIST=${metadata.artist}")
            metadata.album?.takeIf(String::isNotBlank)?.let { add("ALBUM=$it") }
        }.map(String::encodeToByteArray)
    return ByteArrayOutputStream()
        .apply {
            write(OPUS_TAGS)
            writeLittleEndian(vendor.size)
            write(vendor)
            writeLittleEndian(comments.size)
            comments.forEach { comment ->
                writeLittleEndian(comment.size)
                write(comment)
            }
        }.toByteArray()
}

private class OggPageWriter(
    private val output: OutputStream,
    private val serialNumber: Int = ThreadLocalRandom.current().nextInt(),
) {
    private var sequenceNumber = 0

    fun writePacket(
        packet: ByteArray,
        granulePosition: Long,
        beginningOfStream: Boolean = false,
        endOfStream: Boolean = false,
    ) {
        require(packet.size < MAX_OGG_PACKET_BYTES)
        val segmentCount = packet.size / 255 + 1
        val page =
            ByteArrayOutputStream()
                .apply {
                    write(OGG_CAPTURE)
                    write(0)
                    write((if (beginningOfStream) 2 else 0) or (if (endOfStream) 4 else 0))
                    writeLittleEndian(granulePosition)
                    writeLittleEndian(serialNumber)
                    writeLittleEndian(sequenceNumber++)
                    writeLittleEndian(0)
                    write(segmentCount)
                    repeat(segmentCount - 1) { write(255) }
                    write(packet.size % 255)
                    write(packet)
                }.toByteArray()
        val checksum = oggCrc(page)
        repeat(4) { index -> page[22 + index] = (checksum ushr (index * 8)).toByte() }
        output.write(page)
    }
}

internal fun oggCrc(bytes: ByteArray): Int {
    var crc = 0
    bytes.forEach { byte ->
        crc = crc xor ((byte.toInt() and 0xff) shl 24)
        repeat(8) {
            crc = if (crc < 0) (crc shl 1) xor OGG_CRC_POLYNOMIAL else crc shl 1
        }
    }
    return crc
}

private fun ByteArrayOutputStream.writeLittleEndian(value: Int) = repeat(Int.SIZE_BYTES) { index -> write(value ushr (index * 8)) }

private fun ByteArrayOutputStream.writeLittleEndian(value: Long) =
    repeat(Long.SIZE_BYTES) { index -> write((value ushr (index * 8)).toInt()) }

private fun ByteBuffer.remainingBytes(): ByteArray = ByteArray(remaining()).also(::get)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun MediaFormat.getLongOrDefault(
    key: String,
    default: Long,
): Long = if (containsKey(key)) getLong(key) else default

private fun MediaFormat.getIntegerOrDefault(
    key: String,
    default: Int,
): Int = if (containsKey(key)) getInteger(key) else default

private val OGG_CAPTURE = "OggS".encodeToByteArray()
private val OPUS_HEAD = "OpusHead".encodeToByteArray()
private val OPUS_TAGS = "OpusTags".encodeToByteArray()
private const val OGG_CRC_POLYNOMIAL = 0x04c11db7
private const val MAX_OGG_PACKET_BYTES = 255 * 255
private const val OPUS_SAMPLE_RATE = 48_000L
private const val DEFAULT_PACKET_DURATION_US = 20_000L
