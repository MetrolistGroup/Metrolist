package com.metrolist.shared.youtube.playback

import com.metrolist.shared.db.entities.FormatEntity

fun PlaybackResource.toCachedFormatEntity(
    id: String,
    mimeType: String?,
    contentLengthBytes: Long,
): FormatEntity? {
    val itag = itag ?: return null
    val storedMimeType = mimeType?.takeIf(String::isNotBlank) ?: return null
    val codecs = codecs?.takeIf(String::isNotBlank) ?: return null
    val bitrate = bitrate ?: return null
    return FormatEntity(
        id = id,
        itag = itag,
        mimeType = storedMimeType,
        codecs = codecs,
        bitrate = bitrate,
        sampleRate = sampleRate,
        contentLength = contentLengthBytes,
        loudnessDb = loudnessDb,
        perceptualLoudnessDb = perceptualLoudnessDb,
        playbackUrl = null,
    )
}

fun PlaybackResource.withCachedFormat(format: FormatEntity?): PlaybackResource =
    format?.let {
        copy(
            itag = it.itag,
            mimeType = it.mimeType,
            codecs = it.codecs,
            bitrate = it.bitrate,
            sampleRate = it.sampleRate,
            loudnessDb = it.loudnessDb,
            perceptualLoudnessDb = it.perceptualLoudnessDb,
            contentLengthBytes = it.contentLength,
        )
    } ?: this
