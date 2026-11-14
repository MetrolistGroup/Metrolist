package com.metrolist.shared.youtube.playback

import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Remembers that YouTube recently rejected web PO tokens (SABR stuck in attestation pending, or 403 on a tokenized
 * direct URL). While set, playback prefers the tokenless VISIONOS 0.1 client, which keeps playing without a token.
 */
object WebPoTokenHealth {
    const val TOKENLESS_AUDIO_CLIENT = "VISIONOS_0_1"
    const val TOKENLESS_AUDIO_PROFILE = "VISIONOS_0_1__nopo"
    private const val REJECTION_TTL_MS = 30 * 60_000L

    @Volatile
    private var rejectedUntilMs = 0L

    fun recordRejection(nowMs: Long = Clock.System.now().toEpochMilliseconds()) {
        rejectedUntilMs = nowMs + REJECTION_TTL_MS
    }

    fun tokensRejected(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean = nowMs < rejectedUntilMs

    internal fun reset() {
        rejectedUntilMs = 0L
    }
}
