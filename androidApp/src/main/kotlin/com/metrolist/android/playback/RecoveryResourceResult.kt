package com.metrolist.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlaybackException
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class RecoveryResourceResult(
    val resource: PlaybackResource?,
    val budget: PlaybackRecoveryBudget,
    val failure: Throwable? = null,
)

internal suspend fun recoverPlaybackResource(
    streamResolver: StreamResolver,
    retryNetworkResource: Boolean,
    now: Instant,
    action: PlaybackRecoveryAction,
    currentResource: PlaybackResource?,
    videoId: String,
    hints: ContentHints,
    sourceSong: SongItem,
    budget: PlaybackRecoveryBudget,
    failure: Throwable? = null,
    positionMs: Long = 0L,
    excludedProfiles: Set<String> = emptySet(),
    category: PlaybackFailureCategory = PlaybackFailureClassifier.fromThrowable(failure),
): RecoveryResourceResult {
    val localSong = sourceSong.isLocal || videoId.startsWith("local_")
    val failedLocalResource = !localSong && currentResource?.url?.let { it.startsWith("content://") || it.startsWith("file:") } == true
    if (action == PlaybackRecoveryAction.REBUILD_RENDERER || (!failedLocalResource && action == PlaybackRecoveryAction.RETRY_SAME_URL)) {
        val resource =
            if (action == PlaybackRecoveryAction.REBUILD_RENDERER && currentResource?.hasVideo == true &&
                failure?.isVideoRendererFailure() == true
            ) {
                currentResource.copy(
                    videoUrl = null,
                    videoWidth = null,
                    videoHeight = null,
                    videoMimeType = null,
                    videoCodecs = null,
                    videoBitrate = null,
                    videoItag = null,
                    videoContentLengthBytes = null,
                    sabrVideoBootstrap = null,
                )
            } else {
                currentResource
            }
        return RecoveryResourceResult(resource, budget)
    }
    if (budget.requestsRemaining <= 0) return RecoveryResourceResult(null, budget)

    when (action) {
        PlaybackRecoveryAction.REFRESH_TOKEN -> streamResolver.invalidateAttestation()
        PlaybackRecoveryAction.RELOAD_PLAYER_CONFIG -> streamResolver.refreshPlayerConfigAfterStreamRejection()
        PlaybackRecoveryAction.INVALIDATE_NETWORK -> streamResolver.invalidateNetworkScope(PlaybackNetworkStateStore.current)
        else -> Unit
    }

    if (
        !failedLocalResource && action == PlaybackRecoveryAction.INVALIDATE_NETWORK && retryNetworkResource &&
        currentResource?.expiresAt?.let { it > now } == true
    ) {
        return RecoveryResourceResult(currentResource, budget)
    }

    val nextBudget = budget.consumeRequest()
    val excluded =
        excludedProfiles + PlaybackRecoveryPolicy.recoveryExclusions(category, currentResource, positionMs)
    val retryHints =
        if (action == PlaybackRecoveryAction.RETRY_CLIENT ||
            excluded.isNotEmpty()
        ) {
            hints.copy(playbackClientOverrideId = null)
        } else {
            hints
        }
    return try {
        val resource =
            withContext(Dispatchers.IO) {
                if (localSong) {
                    streamResolver.refresh(videoId, retryHints, playbackAudioQuality(), sourceSong = sourceSong)
                } else {
                    streamResolver.resolveRemote(
                        videoId,
                        retryHints,
                        playbackAudioQuality(),
                        sourceSong = sourceSong,
                        refresh = true,
                        excludedProfiles = excluded,
                    )
                }
            }
        RecoveryResourceResult(resource, nextBudget)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecoveryResourceResult(
            PlaybackRecoveryPolicy.retainCurrentResourceAfterFailedRefresh(action, currentResource.takeUnless { failedLocalResource }),
            nextBudget,
            e,
        )
    }
}

private fun Throwable.isVideoRendererFailure(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<ExoPlaybackException>()
        .any { it.type == ExoPlaybackException.TYPE_RENDERER && MimeTypes.isVideo(it.rendererFormat?.sampleMimeType) }
