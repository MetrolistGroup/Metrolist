package com.metrolist.shared.youtube.playback

/** Access under the token provider's mutex. Cancellation is never recorded as a failure. */
internal class TokenFailureCooldown(
    private val elapsedMs: () -> Long = ::playbackElapsedMs,
    private val networkState: () -> PlaybackNetworkState = { PlaybackNetworkStateStore.current },
) {
    private data class Scope(
        val visitorData: String?,
        val cookie: String?,
        val network: PlaybackNetworkState,
    )

    private var scope: Scope? = null
    private var untilMs = 0L
    private var backoffMs = 30_000L

    fun isCoolingDown(
        visitorData: String?,
        cookie: String?,
    ): Boolean {
        val current = Scope(visitorData, cookie, networkState())
        val previous = scope
        if (previous == null || previous.cookie != cookie || previous.network != current.network ||
            (visitorData != null && previous.visitorData != null && previous.visitorData != visitorData)
        ) {
            reset()
        }
        scope = current.copy(visitorData = visitorData ?: scope?.visitorData)
        return elapsedMs() < untilMs
    }

    fun recordFailure(
        visitorData: String?,
        cookie: String?,
    ) {
        if (scope?.network?.let { it != networkState() } == true) {
            reset()
            return
        }
        isCoolingDown(visitorData, cookie)
        untilMs = elapsedMs() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
    }

    fun reset() {
        scope = null
        untilMs = 0L
        backoffMs = 30_000L
    }
}
