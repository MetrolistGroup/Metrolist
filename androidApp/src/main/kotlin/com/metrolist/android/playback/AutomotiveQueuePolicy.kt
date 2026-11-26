package com.metrolist.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.deviceconnect.DcRepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.settings.AutomotiveSongPlaybackMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import timber.log.Timber

internal fun nextMediaSessionRepeatMode(mode: Int): Int =
    when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

internal fun canResolveMediaSessionItems(
    canBrowse: Boolean,
    isAssistant: Boolean,
    mediaItems: List<MediaItem>,
    entries: List<MusicQueueEntry>,
): Boolean {
    if (canBrowse || isAssistant) return true
    val item = mediaItems.singleOrNull() ?: return false
    return item.requestMetadata.searchQuery == null && automotiveQueueIndex(item.mediaId, entries) != null
}

internal suspend fun offlineAutomotiveQueue(
    queue: AutomotiveSongResolution,
    networkConnected: Boolean,
    isCached: suspend (String) -> Boolean,
): AutomotiveSongResolution? {
    if (networkConnected) return queue
    val playableIndices = queue.songs.indices.filter { isCached(queue.songs[it].id) }
    if (playableIndices.isEmpty()) return null
    val selected = playableIndices.firstOrNull { it >= queue.startIndex } ?: playableIndices.first()
    return queue.copy(
        songs = playableIndices.map(queue.songs::get),
        startIndex = playableIndices.indexOf(selected),
        retainsRequestedStartPosition = queue.retainsRequestedStartPosition && selected == queue.startIndex,
    )
}

// Selects inside the active queue so going offline never trims it to the downloaded songs.
internal suspend fun offlineQueueEntryIndex(
    entries: List<MusicQueueEntry>,
    startIndex: Int,
    isCached: suspend (String) -> Boolean,
): Int? {
    val playableIndices = entries.indices.filter { isCached(entries[it].song.id) }
    return playableIndices.firstOrNull { it >= startIndex } ?: playableIndices.firstOrNull()
}

internal suspend fun nextOfflineQueueIndex(
    songs: List<SongItem>,
    currentIndex: Int,
    repeatAll: Boolean,
    isPlayable: suspend (SongItem) -> Boolean,
): Int? {
    if (currentIndex !in songs.indices) return null
    val indices = (currentIndex + 1 until songs.size) + if (repeatAll) (0 until currentIndex).toList() else emptyList()
    return indices.firstOrNull { isPlayable(songs[it]) }
}

internal fun automotivePlaybackErrorCode(error: Throwable?): Int {
    val requiresLogin =
        generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
            .filterIsInstance<StreamResolveException>()
            .any { failure -> failure.diagnostics?.attempts?.any { it.outcome == "playability:LOGIN_REQUIRED" } == true }
    if (requiresLogin) return PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED
    return when (PlaybackFailureClassifier.fromThrowable(error)) {
        PlaybackFailureCategory.NETWORK -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        PlaybackFailureCategory.TIMEOUT -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        PlaybackFailureCategory.DECODER -> PlaybackException.ERROR_CODE_DECODING_FAILED
        PlaybackFailureCategory.UNAVAILABLE -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        else -> PlaybackException.ERROR_CODE_UNSPECIFIED
    }
}

internal suspend fun addAutomotiveSongToQueue(
    mediaId: String?,
    isPlaybackLocked: () -> Boolean,
    resolve: suspend (MediaItem) -> List<MediaItem>,
    append: (List<MediaItem>) -> Unit,
): SessionResult {
    if (isPlaybackLocked()) return SessionResult(SessionError.ERROR_PERMISSION_DENIED)
    if (mediaId == null || AutomotiveMediaIds.songId(mediaId).isNullOrBlank()) {
        return SessionResult(SessionError.ERROR_BAD_VALUE)
    }
    return try {
        val items = resolve(MediaItem.Builder().setMediaId(mediaId).build())
        when {
            isPlaybackLocked() -> {
                SessionResult(SessionError.ERROR_PERMISSION_DENIED)
            }

            items.isEmpty() -> {
                SessionResult(SessionError.ERROR_BAD_VALUE)
            }

            else -> {
                append(items)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Timber.e(error, "Automotive queue addition failed")
        SessionResult(SessionError.ERROR_IO)
    }
}

internal suspend fun resolveAutomotiveSearchSongQueue(
    item: MediaItem,
    library: AutomotiveMediaLibrary,
    mode: AutomotiveSongPlaybackMode,
): AutomotiveSongQueue? {
    val isSearchSong =
        AutomotiveMediaIds
            .browseSong(item.mediaId)
            ?.parentId
            ?.startsWith("auto:search:") == true

    if (isSearchSong && mode != AutomotiveSongPlaybackMode.QUEUE) {
        return library.resolveSong(item)?.let { song ->
            AutomotiveSongQueue(songs = listOf(song), startIndex = 0, songPlaybackMode = mode)
        } ?: library.resolveSongQueue(item)
    }
    return library.resolveSongQueue(item)
}

internal data class AutomotivePlaybackIntent(
    val selectedSongOnly: Boolean,
    val radioSeed: Boolean,
    val remoteRepeatMode: DcRepeatMode?,
)

internal fun automotivePlaybackIntent(mode: AutomotiveSongPlaybackMode?): AutomotivePlaybackIntent =
    AutomotivePlaybackIntent(
        selectedSongOnly = mode == AutomotiveSongPlaybackMode.SINGLE_SONG,
        radioSeed = mode == AutomotiveSongPlaybackMode.RADIO,
        remoteRepeatMode = DcRepeatMode.OFF.takeIf { mode == AutomotiveSongPlaybackMode.RADIO },
    )

private data class AutomotiveRadioSeedState(
    val request: QueuePlaybackRequest,
    val currentSongId: String?,
    val currentOriginalQueueItemId: String?,
    val playing: Boolean,
    val transitionPending: Boolean,
)

internal suspend fun awaitAutomotiveRadioSeed(
    song: SongItem,
    initialRequestId: Long,
    requests: SharedFlow<QueuePlaybackRequest>,
    currentSong: StateFlow<SongItem?>,
    isPlaying: Flow<Boolean>,
    transitionPending: StateFlow<Boolean>,
    latestRequestId: () -> Long,
    isCurrentSession: () -> Boolean,
    onAbandoned: () -> Unit,
    playMix: (SongItem) -> Unit,
) {
    var unrelatedSelection = false
    combine(requests, currentSong, isPlaying, transitionPending) { request, current, playing, pending ->
        AutomotiveRadioSeedState(request, current?.id, current?.originalQueueItemId, playing, pending)
    }.first { state ->
        when {
            state.request.requestId <= initialRequestId -> {
                false
            }

            state.request.song.id != song.id -> {
                unrelatedSelection = true
                true
            }

            else -> {
                (
                    state.currentSongId == song.id ||
                        state.currentOriginalQueueItemId == (song.originalQueueItemId ?: song.id)
                ) && state.playing && !state.transitionPending
            }
        }
    }
    // A seek or resume can supersede the seed request while it is still resolving;
    // follow the newest request instead of the one that started the wait.
    val newest = requests.replayCache.lastOrNull()?.takeIf { it.requestId > initialRequestId }
    val committed = currentSong.value
    if (newest == null || unrelatedSelection || newest.song.id != song.id ||
        newest.requestId != latestRequestId() || !isCurrentSession() || committed == null
    ) {
        onAbandoned()
        return
    }
    playMix(committed)
}
