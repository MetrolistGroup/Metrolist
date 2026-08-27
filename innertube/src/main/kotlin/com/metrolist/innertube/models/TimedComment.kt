package com.metrolist.innertube.models

/**
 * A user comment selected from YouTube's highlighted/top comments feed.
 * The existing name is retained for compatibility with the surrounding player feature.
 */
data class TimedComment(
    val id: String,
    val timestampMs: Long = 0L,
    val text: String,
    val avatarUrl: String?,
)
