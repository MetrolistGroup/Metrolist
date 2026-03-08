package com.metrolist.shared.discord

sealed interface ReconnectAction {
    data class Resume(
        val sessionId: String,
        val seq: Int,
    ) : ReconnectAction

    data object ReIdentify : ReconnectAction

    data object RefreshAndReIdentify : ReconnectAction

    data object SurfaceFatal : ReconnectAction
}

internal class DiscordReconnectBackoff {
    private var attempt = 0

    fun nextDelayMs(rateLimited: Boolean): Long {
        attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT)
        return if (rateLimited) 60_000L else (1_000L * (1 shl (attempt - 1))).coerceAtMost(64_000L)
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_ATTEMPT = 7
    }
}

object DiscordReconnectStrategy {
    fun decide(
        closeCode: Int,
        hadSession: Boolean,
        seq: Int,
        sessionId: String?,
    ): ReconnectAction {
        val action =
            when (closeCode) {
                4000 -> {
                    if (hadSession && sessionId != null && seq > 0) {
                        ReconnectAction.Resume(sessionId, seq)
                    } else {
                        ReconnectAction.ReIdentify
                    }
                }

                4001, 4003, 4005, 4007, 4009 -> {
                    ReconnectAction.ReIdentify
                }

                4004 -> {
                    ReconnectAction.RefreshAndReIdentify
                }

                4010, 4011, 4012, 4013, 4014 -> {
                    ReconnectAction.SurfaceFatal
                }

                else -> {
                    ReconnectAction.ReIdentify
                }
            }
        return action
    }
}
