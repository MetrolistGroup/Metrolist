package com.metrolist.shared.api.artwork

import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

internal const val MAX_ANIMATED_ARTWORK_VIDEO_BYTES = 25L * 1024L * 1024L
private const val MAX_ANIMATED_ARTWORK_SEGMENTS = 64

private val mapUriPattern = Regex("""URI="([^"]+)"""")
private val mapByteRangePattern = Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""")
private val segmentByteRangePattern = Regex("""^#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?$""")
private val keyMethodPattern = Regex("""METHOD=([^,\s]+)""")

internal data class AnimatedArtworkByteRange(
    val length: Long,
    val offset: Long,
) {
    val endExclusive: Long get() = offset + length
    val httpRangeHeader: String get() = "bytes=$offset-${endExclusive - 1L}"
}

internal data class AnimatedArtworkMediaSegment(
    val url: String,
    val byteRange: AnimatedArtworkByteRange? = null,
)

internal sealed interface AnimatedArtworkDownloadPlan {
    data class SingleFile(
        val url: String,
        val byteLength: Long? = null,
    ) : AnimatedArtworkDownloadPlan

    data class Segments(
        val segments: List<AnimatedArtworkMediaSegment>,
    ) : AnimatedArtworkDownloadPlan
}

internal fun parseAnimatedArtworkMediaPlaylist(
    playlistUrl: String,
    playlistBody: String,
): AnimatedArtworkDownloadPlan? {
    if (!isTrustedAnimatedArtworkUrl(playlistUrl)) return null

    var initSegment: AnimatedArtworkMediaSegment? = null
    val mediaSegments = mutableListOf<AnimatedArtworkMediaSegment>()
    var pendingRangeLength: Long? = null
    var pendingRangeOffset: Long? = null
    val nextOffsetByUrl = mutableMapOf<String, Long>()

    for (rawLine in playlistBody.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXT-X-KEY:")) {
            val method =
                keyMethodPattern
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.uppercase()
            if (method != "NONE") return null
            continue
        }
        if (line.startsWith("#EXT-X-MAP:")) {
            if (initSegment != null) return null
            val uri = mapUriPattern.find(line)?.groupValues?.get(1) ?: return null
            val resolved = resolveAnimatedArtworkPlaylistUrl(playlistUrl, uri) ?: return null
            if (!isTrustedAnimatedArtworkUrl(resolved) || !isSupportedMp4SegmentUrl(resolved)) return null
            val rangeMatch = mapByteRangePattern.find(line)
            val byteRange =
                if (rangeMatch != null) {
                    val length = rangeMatch.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
                    val offset = rangeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
                    if (offset < 0L || length > MAX_ANIMATED_ARTWORK_VIDEO_BYTES || offset > Long.MAX_VALUE - length) return null
                    AnimatedArtworkByteRange(length = length, offset = offset)
                } else {
                    null
                }
            initSegment = AnimatedArtworkMediaSegment(url = resolved, byteRange = byteRange)
            if (byteRange != null) nextOffsetByUrl[resolved] = byteRange.endExclusive
            continue
        }
        if (line.startsWith("#EXT-X-BYTERANGE:")) {
            val match = segmentByteRangePattern.matchEntire(line) ?: return null
            pendingRangeLength = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            pendingRangeOffset =
                match.groupValues[2]
                    .takeIf { it.isNotEmpty() }
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
            if (match.groupValues[2].isNotEmpty() && pendingRangeOffset == null) return null
            continue
        }
        if (line.startsWith("#")) continue

        if (mediaSegments.size >= MAX_ANIMATED_ARTWORK_SEGMENTS) return null
        val resolved = resolveAnimatedArtworkPlaylistUrl(playlistUrl, line) ?: return null
        if (!isTrustedAnimatedArtworkUrl(resolved) || !isSupportedMp4SegmentUrl(resolved)) return null

        val byteRange =
            if (pendingRangeLength != null) {
                val length = pendingRangeLength
                val offset = pendingRangeOffset ?: nextOffsetByUrl[resolved] ?: return null
                if (offset < 0L || length > MAX_ANIMATED_ARTWORK_VIDEO_BYTES || offset > Long.MAX_VALUE - length) return null
                AnimatedArtworkByteRange(length = length, offset = offset).also {
                    nextOffsetByUrl[resolved] = it.endExclusive
                }
            } else {
                null
            }
        pendingRangeLength = null
        pendingRangeOffset = null
        mediaSegments += AnimatedArtworkMediaSegment(url = resolved, byteRange = byteRange)
    }

    if (mediaSegments.isEmpty()) return null
    val allParts =
        buildList {
            initSegment?.let(::add)
            addAll(mediaSegments)
        }
    val declaredTotalBytes =
        allParts.fold(0L) { acc, part ->
            val len = part.byteRange?.length ?: 0L
            if (acc > MAX_ANIMATED_ARTWORK_VIDEO_BYTES - len) return null
            acc + len
        }
    if (declaredTotalBytes > MAX_ANIMATED_ARTWORK_VIDEO_BYTES) return null

    // Apple Music motion covers commonly pack init + fragments contiguously in one MP4 starting at byte 0.
    val firstUrl = allParts.first().url
    if (allParts.all { it.url == firstUrl && it.byteRange != null }) {
        var expectedOffset = 0L
        val contiguousFromZero =
            allParts.all { part ->
                val range = part.byteRange!!
                val matches = range.offset == expectedOffset
                expectedOffset = range.endExclusive
                matches
            }
        if (contiguousFromZero && expectedOffset in 1L..MAX_ANIMATED_ARTWORK_VIDEO_BYTES) {
            return AnimatedArtworkDownloadPlan.SingleFile(url = firstUrl, byteLength = expectedOffset)
        }
    }

    return AnimatedArtworkDownloadPlan.Segments(segments = allParts)
}

internal fun isSupportedMp4SegmentUrl(url: String): Boolean {
    val path =
        try {
            URLBuilder()
                .takeFrom(url)
                .build()
                .encodedPath
                .lowercase()
        } catch (_: Exception) {
            return false
        }
    return path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".m4v")
}

internal fun isValidAnimatedArtworkRangeResponse(
    statusCode: Int,
    contentRange: String?,
    expectedRange: AnimatedArtworkByteRange,
    allowFullResponseAtZero: Boolean,
): Boolean {
    if (statusCode == 206) {
        val header = contentRange?.trim() ?: return false
        val expectedPrefix = "bytes ${expectedRange.offset}-${expectedRange.endExclusive - 1L}/"
        if (!header.startsWith(expectedPrefix, ignoreCase = true)) return false
        val totalPart = header.substring(expectedPrefix.length)
        return totalPart == "*" || totalPart.toLongOrNull()?.let { it >= expectedRange.endExclusive } == true
    }
    return allowFullResponseAtZero && expectedRange.offset == 0L && statusCode == 200
}
