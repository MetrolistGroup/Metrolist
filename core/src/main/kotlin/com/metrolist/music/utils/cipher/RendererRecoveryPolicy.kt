package com.metrolist.music.utils.cipher

/**
 * Pure (no Android deps) policy for recovering from WebView renderer deaths.
 *
 * A killed renderer under sustained memory pressure will typically be killed again while
 * re-parsing the ~2.8 MB player.js, so after [maxConsecutiveFailures] consecutive failures the
 * policy opens a backoff window during which [shouldAttempt] is false and callers skip the
 * doomed multi-second rebuild for that song.
 *
 * The window is deliberately SHORT and half-open: the non-WebView paths (NewPipe extractor,
 * fallback clients) are unreliable — the cipher WebView is the primary path — so we must get
 * back to retrying it quickly once memory pressure may have eased. Once the window expires, one
 * attempt is allowed. Success fully resets the policy; another failure re-arms the window
 * immediately.
 *
 * Callers pass a monotonic clock value (e.g. SystemClock.elapsedRealtime()) so the policy stays
 * JVM-testable.
 */
class RendererRecoveryPolicy(
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
) {
    var consecutiveFailures = 0
        private set

    private var backoffUntilMs = 0L

    /** Whether creating/using a WebView is currently worth attempting. */
    fun shouldAttempt(nowMs: Long): Boolean =
        consecutiveFailures < maxConsecutiveFailures || nowMs >= backoffUntilMs

    /** Record a renderer death (or renderer-gone-equivalent timeout). */
    fun onFailure(nowMs: Long) {
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) {
            backoffUntilMs = nowMs + backoffMs
        }
    }

    /** Record a successful WebView operation — fully resets the policy. */
    fun onSuccess() {
        consecutiveFailures = 0
        backoffUntilMs = 0L
    }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
        const val DEFAULT_BACKOFF_MS = 60_000L
    }
}
