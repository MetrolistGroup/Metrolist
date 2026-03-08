package com.metrolist.shared.playback.ffmpeg

import okhttp3.HttpUrl

internal data class ArtworkSegment(
    val url: HttpUrl,
    val offset: Long? = null,
    val length: Long? = null,
)

internal fun artworkSegments(
    playlist: String,
    base: HttpUrl,
): List<ArtworkSegment>? {
    val lines = playlist.lineSequence().map(String::trim).toList()
    if (lines.firstOrNull() != "#EXTM3U" || "#EXT-X-ENDLIST" !in lines) return null
    val segments = mutableListOf<ArtworkSegment>()
    var range: String? = null
    var previous: ArtworkSegment? = null

    fun segment(
        uri: String,
        byteRange: String?,
    ): ArtworkSegment? {
        val url = base.resolve(uri) ?: return null
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
        if (byteRange == null) return ArtworkSegment(url)
        val values = byteRange.split('@')
        if (values.size > 2) return null
        val length = values[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val offset =
            if (values.size == 2) {
                values[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            } else {
                val last = previous?.takeIf { it.url == url } ?: return null
                val start = last.offset ?: return null
                val size = last.length ?: return null
                if (start > Long.MAX_VALUE - size) return null
                start + size
            }
        if (offset > Long.MAX_VALUE - length) return null
        return ArtworkSegment(url, offset, length)
    }
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-DISCONTINUITY") -> {
                return null
            }

            line.startsWith("#EXT-X-KEY:") && line != "#EXT-X-KEY:METHOD=NONE" -> {
                return null
            }

            line.startsWith("#EXT-X-MAP:") -> {
                if (segments.isNotEmpty()) return null
                val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return null
                val bytes = Regex("""BYTERANGE="([^"]+)"""").find(line)?.groupValues?.get(1)
                segments += segment(uri, bytes) ?: return null
            }

            line.startsWith("#EXT-X-BYTERANGE:") -> {
                range = line.substringAfter(':')
            }

            line.isNotEmpty() && !line.startsWith('#') -> {
                val next = segment(line, range) ?: return null
                segments += next
                previous = next
                range = null
                if (segments.size > 128) return null
            }
        }
    }
    return segments.takeIf { it.isNotEmpty() && range == null }
}
