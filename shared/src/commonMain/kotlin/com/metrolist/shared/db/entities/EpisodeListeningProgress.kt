package com.metrolist.shared.db.entities

data class EpisodeListeningProgress(
    val id: String,
    val duration: Int,
    val playbackPosition: Long?,
    val played: Boolean,
) {
    val hasStarted: Boolean get() = played || (playbackPosition ?: 0) > 0

    fun isComplete(durationSeconds: Long? = null): Boolean =
        isEpisodeComplete(playbackPosition, (durationSeconds?.takeIf { it > 0 } ?: duration.toLong()) * 1000L)

    fun fraction(durationSeconds: Long? = null): Float? {
        val seconds = durationSeconds?.takeIf { it > 0 } ?: duration.toLong()
        val position = playbackPosition?.takeIf { it > 0 } ?: return null
        if (seconds <= 0) return null
        return (position.toDouble() / (seconds.toDouble() * 1000)).toFloat().coerceIn(0f, 1f)
    }
}

fun isEpisodeComplete(
    positionMs: Long?,
    durationMs: Long,
): Boolean =
    positionMs != null && positionMs > 0L && durationMs > 0L &&
        durationMs - positionMs < 30_000L
