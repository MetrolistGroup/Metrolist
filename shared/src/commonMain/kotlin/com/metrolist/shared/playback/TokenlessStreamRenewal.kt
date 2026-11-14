package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.WebPoTokenHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

object TokenlessStreamRenewal {
    const val RENEW_AFTER_MEDIA_MS = 45_000L
    internal const val BACKGROUND_RENEW_AFTER_MEDIA_MS = 20_000L
    internal const val RESOLVE_TIMEOUT_MS = 6_000L
    internal const val RETRY_DELAY_MS = 5_000L
    internal const val MAX_ATTEMPTS = 3

    // Renewing swaps to a tokenized stream; while web tokens are rejected, VISIONOS 0.1 is the stream that keeps playing.
    fun needsRenewal(resource: PlaybackResource): Boolean =
        resource.profileId.endsWith("__nopo") &&
            !(resource.profileId == WebPoTokenHealth.TOKENLESS_AUDIO_PROFILE && WebPoTokenHealth.tokensRejected()) &&
            resource.clientName != "local" && !resource.isLive

    private val tokenlessProfiles = PlaybackClientCatalog.manifests.map { "${it.id}__nopo" }.toSet()

    // Keep VISIONOS 0.1 reachable while web tokens are rejected: it is then the stream that plays through.
    private fun renewalExclusions(current: PlaybackResource): Set<String> =
        if (WebPoTokenHealth.tokensRejected()) {
            tokenlessProfiles - WebPoTokenHealth.TOKENLESS_AUDIO_PROFILE + current.profileId
        } else {
            tokenlessProfiles + current.profileId
        }

    fun recoveryExclusions(
        resource: PlaybackResource?,
        positionMs: Long,
    ): Set<String> =
        if (resource != null && needsRenewal(resource) && positionMs >= RENEW_AFTER_MEDIA_MS) {
            renewalExclusions(resource)
        } else {
            emptySet()
        }

    suspend fun observe(
        resources: StateFlow<PlaybackResource?>,
        positions: StateFlow<Long>,
        isActive: () -> Boolean,
        renew: suspend (PlaybackResource) -> Boolean,
    ) {
        resources.collectLatest { resource ->
            if (resource == null || !needsRenewal(resource)) return@collectLatest
            var previousPosition = positions.value
            var mediaProgress = 0L
            repeat(MAX_ATTEMPTS) { attempt ->
                positions.first { position ->
                    mediaProgress += (position - previousPosition).coerceAtLeast(0L)
                    previousPosition = position
                    val nearEnd = resource.durationSeconds?.takeIf { it > 0L }?.let { it * 1_000L - position <= 15_000L } == true
                    isActive() && !nearEnd &&
                        (mediaProgress >= BACKGROUND_RENEW_AFTER_MEDIA_MS || position >= BACKGROUND_RENEW_AFTER_MEDIA_MS)
                }
                if (renew(resource)) return@collectLatest
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
    }

    suspend fun renew(
        current: PlaybackResource,
        positionMs: () -> Long,
        isCurrent: () -> Boolean,
        resolve: suspend (Set<String>) -> PlaybackResource,
        replace: suspend (PlaybackResource) -> Boolean,
    ): Boolean {
        val excluded = renewalExclusions(current)
        if (!needsRenewal(current) || !isCurrent()) return false
        PlaybackEventLog.info(
            PlaybackEventKind.RECOVERY,
            "Proactive tokenless stream re-resolution started.",
            videoId = current.videoId,
            clientName = current.clientName,
            profileId = current.profileId,
            details = mapOf("positionMs" to positionMs().toString(), "selection" to "automatic"),
        )
        try {
            val fresh =
                supervisorScope {
                    val pending =
                        async {
                            val tokenResource =
                                try {
                                    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(excluded) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            tokenResource?.takeIf { it.videoId == current.videoId && !needsRenewal(it) }
                        }
                    val cancellation =
                        launch {
                            while (isCurrent()) delay(100L)
                            pending.cancel()
                        }
                    try {
                        pending.await()
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (isCurrent()) throw cancelled
                        null
                    } finally {
                        cancellation.cancel()
                    }
                }
            if (!isCurrent()) return false
            if (fresh != null) {
                PlaybackEventLog.info(
                    PlaybackEventKind.RECOVERY,
                    "Proactive stream renewal resolved a replacement.",
                    videoId = fresh.videoId,
                    clientName = fresh.clientName,
                    profileId = fresh.profileId,
                    details = mapOf("positionMs" to positionMs().toString(), "previousProfile" to current.profileId),
                )
            }
            if (fresh != null && !needsRenewal(fresh) && replace(fresh)) {
                PlaybackEventLog.info(
                    PlaybackEventKind.RECOVERY,
                    "Proactive stream renewal committed at the current position.",
                    videoId = fresh.videoId,
                    clientName = fresh.clientName,
                    profileId = fresh.profileId,
                    details = mapOf("positionMs" to positionMs().toString(), "previousProfile" to current.profileId),
                )
                return true
            }
            PlaybackEventLog.warn(
                PlaybackEventKind.RECOVERY,
                "Proactive stream renewal did not prepare a replacement; retaining current playback.",
                videoId = current.videoId,
                clientName = fresh?.clientName ?: current.clientName,
                profileId = fresh?.profileId ?: current.profileId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlaybackEventLog.warn(
                PlaybackEventKind.RECOVERY,
                "Proactive stream renewal failed; retaining current playback.",
                videoId = current.videoId,
                clientName = current.clientName,
                profileId = current.profileId,
                error = error,
            )
        }
        return false
    }
}
