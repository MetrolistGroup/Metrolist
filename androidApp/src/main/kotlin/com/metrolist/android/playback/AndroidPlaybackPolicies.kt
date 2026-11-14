package com.metrolist.android.playback

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.cast.AndroidMdnsCastDiscovery
import com.metrolist.android.constants.MediaSessionConstants
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PersistRadioQueueChip
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.android.models.playbackPersistenceJson
import com.metrolist.android.ui.GlobalPlaybackNetworkMonitor
import com.metrolist.android.widget.EXTRA_WIDGET_COMMAND_TOKEN
import com.metrolist.android.widget.MetrolistWidgetManager
import com.metrolist.android.widget.MusicWidgetReceiver
import com.metrolist.android.widget.WIDGET_COMMAND_TOKEN
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.sabr.SabrVideoVariant
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastMedia
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.cast.shouldHandleCastPlaybackEnd
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcSyncError
import com.metrolist.shared.discord.DiscordActivityBuilder
import com.metrolist.shared.discord.DiscordDefaults
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.discord.PresenceStatus
import com.metrolist.shared.discord.resolveDiscordButtonLabel
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToMusicVideo
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PlaybackCrossfadeState
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.playback.SleepTimerController
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.metrolist.shared.youtube.playback.toPlaybackLogDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.metrolist.shared.generated.resources.Res as SharedRes
import com.metrolist.shared.generated.resources.shuffle as sharedShuffle
import com.metrolist.shared.generated.resources.start_radio as sharedStartRadio

internal const val NEXT_TRACK_PRELOAD_DELAY_MS = 5_000L
internal const val NEXT_TRACK_PRELOAD_SETTLE_DELAY_MS = 500L
internal const val CROSSFADE_UPDATE_INTERVAL_MS = 16L

internal typealias AndroidCrossfadeState = PlaybackCrossfadeState<SongItem>

internal const val QUEUE_AUTO_LOAD_LIMIT = 2
internal const val REPORTING_TAG = "DeviceConnectReporting"
private const val ANDROID_AUTO_VIDEO_MAX_HEIGHT = 720

internal suspend fun releaseIdleTokenProvider(
    playbackRequested: Flow<Boolean>,
    playbackLoading: Flow<Boolean>,
    tokenProvider: TokenProvider,
) {
    try {
        combine(playbackRequested, playbackLoading) { requested, loading -> requested || loading }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (!active) {
                    delay(60.seconds)
                    tokenProvider.close()
                }
            }
    } finally {
        withContext(NonCancellable) { tokenProvider.close() }
    }
}

internal fun automaticCacheTracks(
    song: SongItem?,
    resource: PlaybackResource?,
    isPlaying: Boolean,
): List<SongItem> = listOfNotNull(song).takeIf { song?.id == resource?.videoId && isPlaying }.orEmpty()

internal data class AutoLoadMoreTrigger(
    val sessionId: Long,
    val currentIndex: Int,
    val queueSize: Int,
    val currentSongId: String?,
    val shouldLoadMore: Boolean,
)

internal fun shouldBootstrapQueueRecommendations(
    finiteQueue: Boolean,
    autoLoadMore: Boolean,
    hasMorePlaylistPages: Boolean,
): Boolean = !hasMorePlaylistPages && (!finiteQueue || autoLoadMore)

internal fun hasUpcomingQueuedTracks(
    queue: List<SongItem>,
    currentIndex: Int,
): Boolean {
    if (queue.isEmpty() || currentIndex !in queue.indices) return false
    for (i in (currentIndex + 1) until queue.size) {
        if (!queue[i].queueSourceTitle.isRecommendedQueueSource()) {
            return true
        }
    }
    return false
}

internal fun shouldAutoLoadMore(
    currentIndex: Int,
    queue: List<SongItem>,
    autoLoadMore: Boolean,
    repeatMode: Int,
): Boolean =
    autoLoadMore &&
        queue.isNotEmpty() &&
        currentIndex in queue.indices &&
        !hasUpcomingQueuedTracks(queue, currentIndex) &&
        repeatMode == Player.REPEAT_MODE_OFF

internal fun shouldPreloadNextTrack(
    playbackPositionMs: Long,
    nextVideoId: String?,
    repeatMode: Int,
    stopAfterCurrentSong: Boolean,
): Boolean =
    playbackPositionMs >= NEXT_TRACK_PRELOAD_DELAY_MS &&
        nextVideoId != null &&
        repeatMode != Player.REPEAT_MODE_ONE &&
        !stopAfterCurrentSong

internal fun shouldStartAndroidCrossfade(
    playbackPositionMs: Long,
    durationMs: Long,
    crossfadeDurationMs: Long,
    isPlaying: Boolean,
    crossfadeEnabled: Boolean,
    isCrossfading: Boolean,
    repeatMode: Int,
    isGapless: Boolean,
    stopAfterCurrentSong: Boolean,
): Boolean {
    if (!isPlaying || !crossfadeEnabled || isCrossfading || isGapless || stopAfterCurrentSong || durationMs <= 0L) return false
    if (repeatMode == Player.REPEAT_MODE_ONE || playbackPositionMs <= 500L) return false
    val remainingMs = durationMs - playbackPositionMs
    return remainingMs in 1L..crossfadeDurationMs
}

internal class CrossfadeCompletionGuard {
    private var playbackInstanceId: Long? = null
    private var completionConsumed = false
    val hasConsumedCompletion: Boolean get() = completionConsumed

    fun arm(playbackInstanceId: Long) {
        this.playbackInstanceId = playbackInstanceId
        completionConsumed = false
    }

    fun clear() {
        playbackInstanceId = null
        completionConsumed = false
    }

    fun consume(completedPlaybackInstanceId: Long?): Boolean {
        if (completedPlaybackInstanceId == null || completedPlaybackInstanceId != playbackInstanceId) return false
        playbackInstanceId = null
        completionConsumed = true
        return true
    }

    fun takeConsumedCompletion(): Boolean = completionConsumed.also { completionConsumed = false }

    fun discardConsumedCompletion() {
        completionConsumed = false
    }
}

internal fun repeatedQueueIndex(
    repeatMode: Int,
    currentIndex: Int,
    lastIndex: Int,
    hasMore: Boolean = false,
): Int? =
    when {
        currentIndex !in 0..lastIndex -> null
        repeatMode == Player.REPEAT_MODE_ONE -> currentIndex
        repeatMode == Player.REPEAT_MODE_ALL && currentIndex == lastIndex && !hasMore -> 0
        else -> null
    }

internal fun nextQueuedIndex(
    queueSize: Int,
    currentIndex: Int,
    repeatMode: Int,
    hasMore: Boolean = false,
): Int? =
    if (currentIndex !in 0 until queueSize) {
        null
    } else {
        (currentIndex + 1).takeIf { it < queueSize }
            ?: repeatedQueueIndex(repeatMode, currentIndex, queueSize - 1, hasMore)
    }

internal suspend fun <T> cachedAudioResource(
    wantVideo: Boolean,
    videoResolutionFailed: Boolean = false,
    load: suspend () -> T?,
): T? = if (wantVideo && !videoResolutionFailed) null else load()

internal fun shouldRequestQueueVideo(
    isVideoSong: Boolean,
    isVideoModeActive: Boolean? = null,
    isLocal: Boolean = false,
    isPodcast: Boolean = false,
    videoPlaybackEnabled: Boolean = true,
): Boolean {
    if (!videoPlaybackEnabled || isLocal) return false
    return isVideoModeActive ?: (isVideoSong && !isPodcast)
}

internal fun isQueuePlaybackModeCurrent(
    song: SongItem,
    requestedWantVideo: Boolean,
    currentVideoViewerVisible: Boolean,
    videoPlaybackEnabled: Boolean = true,
): Boolean =
    requestedWantVideo ==
        shouldRequestQueueVideo(
            isVideoSong = song.isVideoSong,
            isVideoModeActive = currentVideoViewerVisible,
            isLocal = song.isLocal || song.id.startsWith("local_"),
            isPodcast = song.isPodcast,
            videoPlaybackEnabled = videoPlaybackEnabled,
        )

internal fun shouldResolveMusicTrackCounterpart(
    sourceIsVideoSong: Boolean,
    wantVideo: Boolean,
): Boolean = sourceIsVideoSong && !wantVideo

internal enum class CastQueueReplacementResult {
    REMOTE,
    LOCAL,
    STALE,
}

internal class CastDisconnectSuppression {
    private var explicitDisconnectInProgress = false
    private var explicitDisconnectPending = false
    private var replacementPending = false

    fun beginExplicitDisconnect() {
        explicitDisconnectInProgress = true
        explicitDisconnectPending = false
    }

    fun finishExplicitDisconnect(disconnected: Boolean) {
        if (!explicitDisconnectInProgress) return
        explicitDisconnectInProgress = false
        explicitDisconnectPending = disconnected
    }

    fun beginReplacement() {
        replacementPending = true
    }

    fun cancelReplacement() {
        replacementPending = false
    }

    fun finishReplacement(disconnected: Boolean) {
        if (!replacementPending) return
        replacementPending = false
        if (disconnected) explicitDisconnectPending = true
    }

    fun onConnectionState(isConnected: Boolean): Boolean {
        if (isConnected) {
            if (!explicitDisconnectInProgress && !replacementPending) explicitDisconnectPending = false
            return false
        }
        val suppress = explicitDisconnectInProgress || explicitDisconnectPending || replacementPending
        explicitDisconnectInProgress = false
        explicitDisconnectPending = false
        replacementPending = false
        return suppress
    }
}

internal suspend fun routeCastQueueReplacement(
    suppression: CastDisconnectSuppression,
    isCurrentRequest: () -> Boolean,
    isConnected: () -> Boolean,
    replace: suspend () -> Boolean,
    disconnect: suspend () -> Unit,
    onFailure: (Exception) -> Unit = {},
): CastQueueReplacementResult {
    if (!isCurrentRequest()) return CastQueueReplacementResult.STALE
    suppression.beginReplacement()
    val replaced =
        try {
            replace()
        } catch (error: CancellationException) {
            suppression.cancelReplacement()
            throw error
        } catch (error: Exception) {
            onFailure(error)
            false
        }
    val connected = isConnected()
    suppression.finishReplacement(disconnected = !connected)
    if (!isCurrentRequest()) return CastQueueReplacementResult.STALE
    if (replaced && connected) return CastQueueReplacementResult.REMOTE
    if (connected) {
        suppression.beginExplicitDisconnect()
        try {
            disconnect()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            suppression.finishExplicitDisconnect(disconnected = !isConnected())
        }
    }
    return if (isCurrentRequest()) CastQueueReplacementResult.LOCAL else CastQueueReplacementResult.STALE
}

internal fun shouldActivateQueueVideoMode(
    videoRequested: Boolean,
    resolvedResourceHasVideo: Boolean,
): Boolean = videoRequested && resolvedResourceHasVideo

internal fun androidVideoMaxHeight(
    quality: MusicVideoQuality,
    resolvedMaxHeight: Int?,
): Int? =
    if (quality == MusicVideoQuality.AUTO) {
        resolvedMaxHeight?.coerceAtMost(ANDROID_AUTO_VIDEO_MAX_HEIGHT)
    } else {
        resolvedMaxHeight
    }

/** SABR pacing understates capacity; only stalls step down, to at most half the current bitrate. */
internal fun nextAutoSabrVideoVariant(
    variants: List<SabrVideoVariant>,
    currentHeight: Int,
    maxHeight: Int,
    bandwidthBps: Long?,
    audioBitrate: Int,
    stalled: Boolean,
    allowUpswitch: Boolean,
): SabrVideoVariant? {
    val usable = variants.filter { it.height <= maxHeight }
    val index = usable.indexOfFirst { it.height == currentHeight }
    if (index < 0) return null
    val current = usable[index]
    if (stalled) {
        val lower = usable.take(index)
        return lower.lastOrNull { it.bitrate <= current.bitrate / 2 } ?: lower.firstOrNull()
    }
    if (!allowUpswitch || bandwidthBps == null) return null
    return usable.getOrNull(index + 1)?.takeIf { (it.bitrate + audioBitrate) * SABR_UPSWITCH_HEADROOM <= bandwidthBps }
}

internal fun PlaybackResource.withSabrVideoVariant(variant: SabrVideoVariant): PlaybackResource =
    copy(
        sabrVideoBootstrap = checkNotNull(sabrVideoBootstrap).withVideoVariant(variant),
        videoItag = variant.format.itag,
        videoWidth = variant.width,
        videoHeight = variant.height,
        videoMimeType = variant.mimeType,
        videoCodecs = null,
        videoBitrate = variant.bitrate,
        videoContentLengthBytes = variant.contentLengthBytes,
    )

private const val SABR_UPSWITCH_HEADROOM = 1.5

internal fun SongItem.toAndroidContentHints(wantVideo: Boolean) =
    toContentHints().let { hints ->
        hints.copy(
            wantVideo = wantVideo && Settings.shared.musicVideoPlaybackEnabled,
            maxVideoHeight = androidVideoMaxHeight(Settings.shared.musicVideoQuality, hints.maxVideoHeight),
        )
    }

private const val AUTOMOTIVE_PLAYBACK_REQUEST_PREFIX = "auto:request:"

private fun MediaItem.withAutomotivePlaybackRequestGeneration(generation: Long): MediaItem =
    buildUpon()
        .setMediaId("$AUTOMOTIVE_PLAYBACK_REQUEST_PREFIX$generation:$mediaId")
        .build()

private fun MediaItem.automotivePlaybackRequestGeneration(): Long? =
    mediaId
        .takeIf { it.startsWith(AUTOMOTIVE_PLAYBACK_REQUEST_PREFIX) }
        ?.removePrefix(AUTOMOTIVE_PLAYBACK_REQUEST_PREFIX)
        ?.substringBefore(':')
        ?.toLongOrNull()

internal data class AutomotiveSongResolution(
    val songs: List<SongItem>,
    val startIndex: Int,
)

internal fun resolveAutomotiveSongRequest(
    requestedItemCount: Int,
    requestedStartIndex: Int,
    pinnedSongs: List<SongItem>?,
    singleItemQueue: AutomotiveSongQueue?,
    individuallyResolvedSongs: List<SongItem?>,
): AutomotiveSongResolution? {
    if (requestedItemCount == 1 && pinnedSongs != null) return AutomotiveSongResolution(pinnedSongs, 0)
    if (requestedItemCount == 1 && singleItemQueue != null) {
        return AutomotiveSongResolution(singleItemQueue.songs, singleItemQueue.startIndex)
    }
    if (individuallyResolvedSongs.size != requestedItemCount || individuallyResolvedSongs.any { it == null }) return null
    val songs = individuallyResolvedSongs.filterNotNull()
    if (songs.isEmpty()) return null
    return AutomotiveSongResolution(songs, requestedStartIndex.takeIf(songs.indices::contains) ?: 0)
}

internal sealed interface AutomotiveMediaItemsApplication {
    data object Reject : AutomotiveMediaItemsApplication

    data class Apply(
        val mediaItems: List<MediaItem>,
        val queueTitle: String?,
        val replacesQueue: Boolean,
        val shuffleModeEnabled: Boolean?,
        val playlistPage: PlaylistPage?,
    ) : AutomotiveMediaItemsApplication
}

class AutomotivePlaybackRequestGate internal constructor(
    private val queueReplacement: (
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
        shuffleModeEnabled: Boolean?,
        playlistPage: PlaylistPage?,
        replacement: () -> Unit,
    ) -> Unit =
        { _, _, _, _, _, replacement -> replacement() },
) {
    private data class PendingApplication(
        val generation: Long,
        val mediaItems: List<MediaItem>,
        val resolvedMediaIds: List<String>,
        val startIndex: Int,
        val startPositionMs: Long,
        val queueTitle: String?,
        val replacesQueue: Boolean,
        val shuffleModeEnabled: Boolean?,
        val playlistPage: PlaylistPage?,
    )

    private var generation = 0L
    private var pendingApplication: PendingApplication? = null

    @Synchronized
    internal fun cancelPendingRequests() {
        generation++
        pendingApplication = null
    }

    @Synchronized
    internal fun start(isGuestPlaybackLocked: Boolean): Long {
        val requestGeneration = ++generation
        pendingApplication = null
        if (isGuestPlaybackLocked) throw CancellationException()
        return requestGeneration
    }

    @Synchronized
    internal fun resolve(
        requestGeneration: Long,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        queueTitle: String? = null,
        replacesQueue: Boolean = true,
        shuffleModeEnabled: Boolean? = null,
        playlistPage: PlaylistPage? = null,
        canApply: Boolean = true,
    ): MediaSession.MediaItemsWithStartPosition {
        if (!canApply || requestGeneration != generation || mediaItems.isEmpty()) throw CancellationException()
        val originalItems = mediaItems.toList()
        val resolvedItems = originalItems.map { it.withAutomotivePlaybackRequestGeneration(requestGeneration) }
        pendingApplication =
            PendingApplication(
                generation = requestGeneration,
                mediaItems = originalItems,
                resolvedMediaIds = resolvedItems.map(MediaItem::mediaId),
                startIndex = startIndex,
                startPositionMs = normalizeMediaItemStartPosition(startPositionMs),
                queueTitle = queueTitle,
                replacesQueue = replacesQueue,
                shuffleModeEnabled = shuffleModeEnabled,
                playlistPage = playlistPage,
            )
        return MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
    }

    @Synchronized
    internal fun consume(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): AutomotiveMediaItemsApplication? {
        val requestGenerations = mediaItems.mapNotNull(MediaItem::automotivePlaybackRequestGeneration)
        if (requestGenerations.isEmpty()) {
            return if (mediaItems.any { it.mediaId.startsWith(AUTOMOTIVE_PLAYBACK_REQUEST_PREFIX) }) {
                AutomotiveMediaItemsApplication.Reject
            } else {
                null
            }
        }
        val requestGeneration = requestGenerations.distinct().singleOrNull() ?: return AutomotiveMediaItemsApplication.Reject
        val pending = pendingApplication
        if (
            requestGenerations.size != mediaItems.size ||
            pending?.generation != requestGeneration ||
            pending.resolvedMediaIds != mediaItems.map(MediaItem::mediaId) ||
            pending.startIndex != startIndex ||
            pending.startPositionMs != normalizeMediaItemStartPosition(startPositionMs)
        ) {
            return AutomotiveMediaItemsApplication.Reject
        }
        pendingApplication = null
        return AutomotiveMediaItemsApplication.Apply(
            pending.mediaItems,
            pending.queueTitle,
            pending.replacesQueue,
            pending.shuffleModeEnabled,
            pending.playlistPage,
        )
    }

    internal fun replaceQueue(
        application: AutomotiveMediaItemsApplication.Apply,
        songs: List<SongItem>,
        startIndex: Int,
        replacement: () -> Unit,
    ) {
        queueReplacement(songs, startIndex, application.queueTitle, application.shuffleModeEnabled, application.playlistPage, replacement)
    }
}

private fun normalizeMediaItemStartPosition(startPositionMs: Long): Long =
    startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

internal fun isCurrentRecommendationRequest(
    expectedSessionId: Long,
    expectedRequestGeneration: Long,
    currentSessionId: Long,
    currentRequestGeneration: Long,
): Boolean = expectedSessionId == currentSessionId && expectedRequestGeneration == currentRequestGeneration

@UnstableApi
internal val automotiveExtraPlayerCommands =
    setOf(
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    )

internal fun canBrowseAutomotiveLibrary(
    isTrusted: Boolean,
    isPackageNameVerified: Boolean,
    isAutoCompanion: Boolean,
): Boolean = isTrusted || (isPackageNameVerified && isAutoCompanion)

internal val externalTransportPlayerCommandCodes =
    setOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SET_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
    )

internal fun externalTransportPlayerCommands(): Player.Commands =
    Player.Commands
        .Builder()
        .addAllReadOnlyCommands()
        .apply { externalTransportPlayerCommandCodes.forEach(::add) }
        .build()

internal fun newQueueShuffleEnabled(
    requestedShuffle: Boolean?,
    currentShuffle: Boolean,
    persistentShuffle: Boolean,
): Boolean = requestedShuffle ?: (currentShuffle && persistentShuffle)

internal fun restoredShuffleMode(
    persistedShuffle: Boolean,
    rememberShuffleAndRepeat: Boolean,
): Boolean = persistedShuffle && rememberShuffleAndRepeat

internal fun filteredQueueSelection(
    songs: List<SongItem>,
    startIndex: Int,
    isBlocked: (SongItem) -> Boolean,
): Pair<List<SongItem>, Int>? {
    if (songs.isEmpty()) return null
    val queue = MusicQueue().apply { setQueue(songs, startIndex.coerceIn(songs.indices)) }
    val queued = queue.items.value
    queue.removeAt(queued.indices.filterTo(mutableSetOf()) { isBlocked(queued[it]) })
    return queue.items.value
        .takeIf { it.isNotEmpty() }
        ?.let { it to queue.currentIndex.value }
}

internal fun PersistPlayerState.restoredPlaybackState(
    rememberShuffleAndRepeat: Boolean,
    isBlocked: (SongItem) -> Boolean,
): Pair<PersistPlayerState, MusicQueueSnapshot> {
    val persistedQueue =
        queueSnapshot?.takeIf { it.displayedItems.isNotEmpty() }
            ?: MusicQueueSnapshot(queueSongs, queueSongs, currentMediaItemIndex, shuffleModeEnabled)
    val queue = MusicQueue().apply { restore(persistedQueue) }
    val displayed = queue.items.value
    val blockedIndices = displayed.indices.filterTo(mutableSetOf()) { isBlocked(displayed[it]) }
    val currentSurvives =
        persistedQueue.displayedItems.getOrNull(persistedQueue.currentIndex)?.let { !it.isUnavailable && !isBlocked(it) } == true
    queue.removeAt(blockedIndices)
    if (!rememberShuffleAndRepeat && queue.isShuffled) queue.toggleShuffle()
    val restoredQueue = queue.snapshot()
    return copy(currentPosition = currentPosition.takeIf { currentSurvives } ?: 0L) to restoredQueue
}

@UnstableApi
internal fun isGuestRestrictedPlayerCommand(command: Int): Boolean =
    when (command) {
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SPEED_AND_PITCH,
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_PREPARE,
        -> true

        else -> false
    }

internal fun automotivePlayerCommands(): Player.Commands =
    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        .buildUpon()
        .apply { automotiveExtraPlayerCommands.forEach(::add) }
        .build()

internal fun generatedQueueIndicesToRemoveForRepeat(
    queue: List<SongItem>,
    currentIndex: Int,
): List<Int> = queue.indices.filter { it != currentIndex && queue[it].queueSourceTitle.isRecommendationGeneratedQueueSource() }
