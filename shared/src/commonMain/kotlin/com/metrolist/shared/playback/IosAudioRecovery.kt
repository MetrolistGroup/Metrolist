package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackResource

internal fun PlaybackResource.canRetireIosCaptureFor(
    replacement: PlaybackResource,
    failure: Throwable?,
): Boolean =
    PlaybackRecoveryPolicy.requiresFreshUrl(PlaybackFailureClassifier.fromThrowable(failure)) &&
        (networkUrl != replacement.networkUrl || headers != replacement.headers || sabrBootstrap != replacement.sabrBootstrap)

internal data class IosAudioCaptureFailure(
    val resource: PlaybackResource,
    val startPlaying: Boolean,
    val cause: Throwable? = null,
) {
    val category: PlaybackFailureCategory
        get() =
            PlaybackFailureClassifier.fromThrowable(cause).let {
                if (it == PlaybackFailureCategory.UNKNOWN && resource.isSabr) PlaybackFailureCategory.SABR_PROTOCOL else it
            }

    val shouldExcludeProfile: Boolean
        get() =
            category != PlaybackFailureCategory.URL_EXPIRED &&
                (resource.isSabr || category == PlaybackFailureCategory.CDN_FORBIDDEN)
}

internal class IosAudioRecovery {
    private var videoId: String? = null
    private var aac = false
    private val failedProfiles = mutableSetOf<String>()
    private var urlRefreshesRemaining = 2

    fun select(id: String) {
        if (videoId == id) return
        videoId = id
        aac = false
        failedProfiles.clear()
        urlRefreshesRemaining = 2
    }

    fun forceAac(id: String): Boolean = videoId == id && aac

    fun excludedProfiles(id: String): Set<String> = if (videoId == id) failedProfiles.toSet() else emptySet()

    fun retry(failure: IosAudioCaptureFailure): Boolean {
        if (videoId != failure.resource.videoId) return false
        return if (PlaybackRecoveryPolicy.requiresFreshUrl(failure.category) &&
            (!failure.resource.isSabr || failure.category == PlaybackFailureCategory.URL_EXPIRED)
        ) {
            if (urlRefreshesRemaining <= 0) return false
            if (failure.shouldExcludeProfile && !failedProfiles.add(failure.resource.profileId)) return false
            urlRefreshesRemaining--
            true
        } else if (failure.resource.isSabr) {
            failedProfiles.add(failure.resource.profileId)
        } else if (!aac) {
            aac = true
            true
        } else {
            false
        }
    }
}
