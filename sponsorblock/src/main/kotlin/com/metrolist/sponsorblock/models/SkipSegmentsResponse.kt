package com.metrolist.sponsorblock.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of the hash-prefix response. A prefix matches several videos, so
 * [videoId] has to be compared against the track we actually care about.
 */
@Serializable
data class VideoSegments(
    @SerialName("videoID") val videoId: String = "",
    val segments: List<ApiSegment> = emptyList(),
)

@Serializable
data class ApiSegment(
    val category: String = "",
    val actionType: String = ACTION_TYPE_SKIP,
    /** Start and end in fractional seconds. */
    val segment: List<Double> = emptyList(),
    @SerialName("UUID") val uuid: String = "",
    val locked: Int = 0,
    val votes: Int = 0,
) {
    /**
     * Converts to a [Segment], or null when the entry is unusable: an unknown
     * category, a non-skip action, a malformed range, or a segment the community
     * has voted down.
     */
    fun toSegment(): Segment? {
        if (actionType != ACTION_TYPE_SKIP) return null
        if (segment.size != 2) return null
        if (votes < 0) return null
        val resolved = SponsorBlockCategory.fromApiName(category) ?: return null

        val startMs = (segment[0] * 1000).toLong()
        val endMs = (segment[1] * 1000).toLong()
        if (endMs <= startMs) return null

        return Segment(category = resolved, startMs = startMs, endMs = endMs)
    }

    companion object {
        const val ACTION_TYPE_SKIP = "skip"
    }
}
