package com.metrolist.android.playback

private const val OUTPUT_RECOVERY_BACKOFF_MS = 60_000L
private const val PCM_OUTPUT_STALL_MS = 4_000L
private const val A2DP_NOT_PLAYING_MS = 5_000L
private const val OUTPUT_RECOVERY_PAUSE_MS = 1_000L

internal enum class AudioOutputUserAction { PAUSE, RESUME, SEEK }

internal data class AudioOutputSignal(
    val routeId: Int?,
    val bluetooth: Boolean,
    val stalled: Boolean = false,
    val initialRoute: Boolean = false,
    val a2dpAddress: String? = null,
    val pcmAccepted: Boolean = false,
)

internal data class AudioOutputRecoveryState(
    val routeId: Int? = null,
    val bluetooth: Boolean = false,
    val pending: Boolean = false,
    val attempted: Boolean = false,
    val lastAttemptMs: Long? = null,
    val a2dpNotPlayingSinceMs: Long? = null,
    val audioSessionId: Int? = null,
    val sessionSuspect: Boolean = false,
    val pausedAtMs: Long? = null,
    val userRecoveryReason: String? = null,
) {
    fun cancelUserRecovery(): AudioOutputRecoveryState = copy(pausedAtMs = null, userRecoveryReason = null, a2dpNotPlayingSinceMs = null)
}

internal data class AudioOutputRecoveryDecision(
    val state: AudioOutputRecoveryState,
    val rebuild: Boolean,
    val reason: String? = null,
)

internal fun decideAudioOutputRecovery(
    state: AudioOutputRecoveryState,
    signal: AudioOutputSignal?,
    eligible: Boolean,
    nowMs: Long,
    a2dpPlaying: Boolean? = null,
    audioSessionId: Int? = null,
    userAction: AudioOutputUserAction? = null,
    a2dpStateChanged: Boolean = false,
): AudioOutputRecoveryDecision {
    val session =
        if (audioSessionId != null && state.audioSessionId != audioSessionId) {
            state.copy(
                audioSessionId = audioSessionId,
                sessionSuspect = false,
                pausedAtMs = null,
                userRecoveryReason = null,
                a2dpNotPlayingSinceMs = null,
            )
        } else {
            state
        }
    val routeChanged =
        signal?.routeId != null && !signal.initialRoute && session.routeId != null && signal.routeId != session.routeId
    val routed =
        if (signal?.routeId == null) {
            session
        } else {
            session.copy(
                routeId = signal.routeId,
                bluetooth = signal.bluetooth,
                pending = if (routeChanged) signal.bluetooth else session.pending,
                attempted = if (routeChanged) false else session.attempted,
            )
        }
    val notPlayingSince =
        when {
            !routed.bluetooth || !eligible || a2dpPlaying != false || routeChanged -> null
            signal == null -> routed.a2dpNotPlayingSinceMs
            !signal.pcmAccepted -> null
            else -> routed.a2dpNotPlayingSinceMs ?: nowMs
        }
    val a2dpStalled = signal?.pcmAccepted == true && notPlayingSince != null && nowMs - notPlayingSince >= A2DP_NOT_PLAYING_MS
    val suspect =
        routed.sessionSuspect || (routeChanged && routed.bluetooth) ||
            (
                eligible && routed.bluetooth &&
                    (signal?.stalled == true || (a2dpPlaying == false && (signal?.pcmAccepted == true || a2dpStateChanged)))
            )
    val canRequestUserRecovery = routed.bluetooth && suspect
    val userRecoveryReason =
        when {
            !routed.bluetooth -> null

            userAction == AudioOutputUserAction.SEEK && canRequestUserRecovery -> "user_seek"

            userAction == AudioOutputUserAction.RESUME && canRequestUserRecovery &&
                routed.pausedAtMs != null && nowMs - routed.pausedAtMs >= OUTPUT_RECOVERY_PAUSE_MS -> "user_resume"

            else -> routed.userRecoveryReason
        }
    val reason =
        when {
            userRecoveryReason != null -> userRecoveryReason
            routed.pending -> "route_changed"
            signal?.stalled == true && signal.bluetooth -> "timestamp_stalled"
            a2dpStalled -> "a2dp_not_playing"
            else -> null
        }
    val rebuild =
        routed.bluetooth && reason != null && (!routed.attempted || userRecoveryReason != null) && eligible &&
            userAction != AudioOutputUserAction.PAUSE &&
            (routed.lastAttemptMs == null || nowMs - routed.lastAttemptMs >= OUTPUT_RECOVERY_BACKOFF_MS)
    return AudioOutputRecoveryDecision(
        routed.copy(
            pending = routed.pending && !rebuild,
            attempted = routed.attempted || rebuild,
            lastAttemptMs = if (rebuild) nowMs else routed.lastAttemptMs,
            a2dpNotPlayingSinceMs = if (rebuild) null else notPlayingSince,
            sessionSuspect = suspect,
            pausedAtMs =
                when (userAction) {
                    AudioOutputUserAction.PAUSE -> routed.pausedAtMs ?: nowMs
                    AudioOutputUserAction.RESUME -> null
                    else -> routed.pausedAtMs
                },
            userRecoveryReason = if (rebuild) null else userRecoveryReason,
        ),
        rebuild,
        reason = reason.takeIf { rebuild },
    )
}

internal class PcmOutputStallProbe {
    private var previousFrame: Long? = null
    private var unchangedSinceMs = 0L

    fun reset() {
        previousFrame = null
    }

    fun observe(
        nowMs: Long,
        timestampFrame: Long?,
        writtenFrames: Long,
        expectProgress: Boolean,
    ): Boolean {
        if (!expectProgress || timestampFrame == null || writtenFrames <= timestampFrame) {
            reset()
            return false
        }
        if (previousFrame != timestampFrame) unchangedSinceMs = nowMs
        previousFrame = timestampFrame
        return nowMs - unchangedSinceMs >= PCM_OUTPUT_STALL_MS
    }
}
