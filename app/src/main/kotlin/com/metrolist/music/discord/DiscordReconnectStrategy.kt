package com.metrolist.music.discord

import timber.log.Timber
import kotlin.math.abs
import kotlin.random.Random

sealed interface ReconnectAction {
    data class Resume(val sessionId: String, val seq: Int) : ReconnectAction
    data object ReIdentify : ReconnectAction
    data object RefreshAndReIdentify : ReconnectAction
    data object SurfaceFatal : ReconnectAction
}

object DiscordReconnectStrategy {
    private const val TAG = "DiscordSvc"
    private const val RECONNECT_BASE_DELAY_MS = 1000L
    private const val RECONNECT_MAX_DELAY_MS = 64_000L
    private const val MIN_RATE_LIMIT_DELAY_MS = 60_000L

    fun decide(
        closeCode: Int,
        hadSession: Boolean,
        seq: Int,
        sessionId: String?,
    ): ReconnectAction {
        val action = when (closeCode) {
            4000 -> if (hadSession && sessionId != null && seq > 0) {
                ReconnectAction.Resume(sessionId, seq)
            } else {
                ReconnectAction.ReIdentify
            }
            4001 -> ReconnectAction.ReIdentify
            4003 -> ReconnectAction.ReIdentify
            4004 -> ReconnectAction.RefreshAndReIdentify
            4005 -> ReconnectAction.ReIdentify
            4007 -> ReconnectAction.ReIdentify
            4009 -> ReconnectAction.ReIdentify
            4014 -> ReconnectAction.SurfaceFatal
            else -> ReconnectAction.ReIdentify
        }
        Timber.tag(TAG).d(
            "decide: closeCode=%d, hadSession=%s, seq=%d -> %s",
            closeCode, hadSession, seq, action::class.simpleName,
        )
        return action
    }

    fun backoffDelayMs(attempt: Int, closeCode: Int, reason: String): Long {
        if (closeCode == 429) {
            return parseRetryAfter(reason).coerceAtLeast(MIN_RATE_LIMIT_DELAY_MS)
        }
        val base = (RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        return applyJitter(base, 0.25)
    }

    private fun parseRetryAfter(reason: String): Long {
        val prefix = ";retry_after="
        val index = reason.indexOf(prefix)
        if (index < 0) return MIN_RATE_LIMIT_DELAY_MS
        val value = reason.substring(index + prefix.length).trim()
        val seconds = value.substringBefore(';').substringBefore(',').toDoubleOrNull()
        return if (seconds != null) {
            (seconds * 1000.0).toLong().coerceAtLeast(MIN_RATE_LIMIT_DELAY_MS)
        } else {
            MIN_RATE_LIMIT_DELAY_MS
        }
    }

    private fun applyJitter(intervalMs: Long, ratio: Double): Long {
        if (intervalMs <= 0L) return intervalMs
        val delta = (intervalMs * ratio).toLong()
        if (delta <= 0L) return intervalMs
        val offset = abs(Random.nextLong(delta + 1))
        val sign = if (Random.nextBoolean()) -1L else 1L
        return intervalMs + sign * offset
    }
}
