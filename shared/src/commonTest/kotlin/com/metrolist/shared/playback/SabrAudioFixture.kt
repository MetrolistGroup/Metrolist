package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.youtube.playback.PlaybackResource

internal fun sabrAudioResource(
    size: Long? = null,
    itag: Int = 251,
    durationMs: Long = 2000,
): PlaybackResource {
    val mime = if (itag == 251) "audio/webm" else "audio/mp4"
    return PlaybackResource(
        "song",
        "sabr://audio",
        emptyMap(),
        null,
        null,
        contentLengthBytes = size,
        itag = itag,
        mimeType = mime,
        codecs = if (itag == 251) "opus" else "mp4a.40.2",
        profileId = "VISIONOS_SABR__nopo",
        requireBoundedRange = false,
        sabrBootstrap =
            SabrBootstrap(
                videoId = "song",
                serverAbrStreamingUrl = "https://rr1.googlevideo.com/videoplayback?sabr=1",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 101,
                clientVersion = "test",
                audioFormat = SabrFormatId(itag, 100),
                discardVideoFormat = SabrFormatId(160, 200),
                discardVideoHeight = 144,
                durationMs = durationMs,
                contentLengthBytes = size,
                mimeType = mime,
            ),
    )
}

internal fun sabrAudioResponse(
    audio: ByteArray,
    itag: Int = 251,
    durationMs: Long = 2000,
): ByteArray {
    fun segment(
        id: Int,
        data: ByteArray,
        init: Boolean,
    ): ByteArray {
        val header =
            field(1, id.toLong()) + field(3, itag.toLong()) + field(4, 100) +
                field(6, if (init) 0 else 32) + field(8, if (init) 1 else 0) + field(9, 0) +
                (if (init) byteArrayOf() else field(12, durationMs)) + field(14, data.size.toLong())
        return part(20, header) +
            data.asList().chunked(4096).fold(byteArrayOf()) { bytes, chunk ->
                bytes + part(21, byteArrayOf(id.toByte()) + chunk.toByteArray())
            } + part(22, byteArrayOf(id.toByte()))
    }
    val formatId = field(1, itag.toLong()) + field(2, 100)
    val initialization = varint(18) + varint(formatId.size.toLong()) + formatId + field(3, durationMs) + field(4, 0)
    return part(42, initialization) + segment(1, audio.copyOfRange(0, 32), true) +
        segment(2, audio.copyOfRange(32, audio.size), false) + part(62, byteArrayOf())
}

private fun field(
    number: Int,
    value: Long,
) = varint((number * 8).toLong()) + varint(value)

private fun varint(value: Long): ByteArray {
    var remaining = value
    val bytes = mutableListOf<Byte>()
    do {
        val next = (remaining and 127).toInt()
        remaining = remaining ushr 7
        bytes += (next or if (remaining > 0) 128 else 0).toByte()
    } while (remaining > 0)
    return bytes.toByteArray()
}

private fun part(
    type: Int,
    data: ByteArray,
): ByteArray {
    require(data.size < 16384)
    val length =
        if (data.size <
            128
        ) {
            byteArrayOf(data.size.toByte())
        } else {
            byteArrayOf(((data.size and 63) or 128).toByte(), (data.size ushr 6).toByte())
        }
    return byteArrayOf(type.toByte()) + length + data
}
