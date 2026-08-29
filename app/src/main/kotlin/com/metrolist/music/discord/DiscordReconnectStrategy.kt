package com.metrolist.music.discord

import timber.log.Timber

sealed interface ReconnectAction {
    data class Resume(val sessionId: String, val seq: Int) : ReconnectAction
    data object ReIdentify : ReconnectAction
    data object RefreshAndReIdentify : ReconnectAction
    data object SurfaceFatal : ReconnectAction
}

object DiscordReconnectStrategy {
    private const val TAG = "DiscordSvc"

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
}
