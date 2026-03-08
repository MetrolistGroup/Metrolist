package com.metrolist.android.playback

import com.metrolist.shared.api.SongPlayerInformation
import com.metrolist.shared.api.musicvideo.MusicTrackResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal const val ANDROID_MUSIC_TRACK_LOOKUP_TIMEOUT_MS = 10_000L

internal data class AndroidAudioTrackSelection(
    val song: SongItem,
    val positionMs: Long,
    val cachedAudio: PlaybackResource? = null,
)

internal suspend fun resolveIncomingPlaybackSong(
    videoId: String,
    fallbackArtist: String,
    loadMetadata: suspend (String) -> SongItem?,
    loadPlayerInformation: suspend (String) -> SongPlayerInformation?,
): SongItem {
    loadMetadata(videoId)?.let { return it }
    val info = loadPlayerInformation(videoId)
    return SongItem(
        id = videoId,
        title = info?.title ?: videoId,
        thumbnail = info?.thumbnail,
        artists = listOf(ArtistTiny(id = null, name = info?.artist ?: fallbackArtist)),
        album = null,
        duration = info?.durationSeconds,
        musicVideoType = info?.musicVideoType,
    )
}

internal suspend fun prefetchIncomingAudio(
    song: SongItem,
    wantVideo: Boolean,
    canPlayLocally: Boolean,
    resolve: suspend (SongItem) -> Unit,
) {
    if (!canPlayLocally || wantVideo || song.isUnavailable || song.canSwitchToAudioTrack) return
    try {
        resolve(song)
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (_: Exception) {
        // The playback service owns error presentation and recovery for this speculative request.
    }
}

internal fun shouldAwaitQueueReplacementReadiness(
    prepareInBackground: Boolean,
    replaceCurrentQueueItem: Boolean,
): Boolean = !prepareInBackground && replaceCurrentQueueItem

internal fun isCurrentPreparedQueueReplacement(
    requestIsCurrent: Boolean,
    playerIsCurrent: Boolean,
    expectedPlaybackInstanceId: Long,
    currentPlaybackInstanceId: Long,
): Boolean = requestIsCurrent && playerIsCurrent && expectedPlaybackInstanceId == currentPlaybackInstanceId

internal fun fallbackToOriginalAudioSelection(
    sourceSong: SongItem,
    selectedSong: SongItem,
    sourcePositionMs: Long,
    cachedAudio: PlaybackResource? = null,
): AndroidAudioTrackSelection? =
    sourceSong.takeIf { it.id != selectedSong.id }?.let {
        AndroidAudioTrackSelection(it, sourcePositionMs.coerceAtLeast(0L), cachedAudio?.takeIf { resource -> resource.videoId == it.id })
    }

internal fun canCommitAudioTrackSelection(
    sourceSongId: String,
    selectedSongId: String,
    deviceConnectActive: Boolean,
    listenTogetherGuestPlaybackLocked: Boolean,
): Boolean = sourceSongId == selectedSongId || (!deviceConnectActive && !listenTogetherGuestPlaybackLocked)

// A staged remote load prepares before the queue is replaced, so the old queue's current item is not the requested song.
internal fun selectionQueueSong(
    stagedQueueReplacement: Boolean,
    sourceSong: SongItem,
    currentQueueSong: () -> SongItem?,
): SongItem? = if (stagedQueueReplacement) sourceSong else currentQueueSong()

internal fun mergeSelectedQueueSong(
    selectedSong: SongItem,
    sourceSongAtSelection: SongItem,
    currentQueueSong: SongItem,
): SongItem {
    if (selectedSong.id == sourceSongAtSelection.id) return currentQueueSong
    val customTitle =
        if (sourceSongAtSelection.customTitle != currentQueueSong.customTitle) currentQueueSong.customTitle else selectedSong.customTitle
    val customArtist =
        if (sourceSongAtSelection.customArtist !=
            currentQueueSong.customArtist
        ) {
            currentQueueSong.customArtist
        } else {
            selectedSong.customArtist
        }
    return applyAudioTrackMetadata(
        song = selectedSong,
        queueSourceTitle = currentQueueSong.queueSourceTitle,
        customTitle = customTitle,
        customArtist = customArtist,
    )
}

private fun applyAudioTrackMetadata(
    song: SongItem,
    queueSourceTitle: String?,
    customTitle: String?,
    customArtist: String?,
): SongItem {
    val canonicalTitle = song.canonicalTitle ?: song.title
    val canonicalArtists = song.canonicalArtists ?: song.artists
    return song.copy(
        title = customTitle?.takeIf(String::isNotBlank) ?: canonicalTitle,
        artists =
            customArtist?.takeIf(String::isNotBlank)?.let { name ->
                listOf(canonicalArtists.firstOrNull()?.copy(name = name) ?: ArtistTiny(null, name))
            } ?: canonicalArtists,
        queueSourceTitle = queueSourceTitle,
        customTitle = customTitle,
        customArtist = customArtist,
        canonicalTitle = canonicalTitle,
        canonicalArtists = canonicalArtists,
    )
}

internal fun isCurrentQueuePlaybackTarget(
    expectedSessionId: Long,
    currentSessionId: Long,
    expectedRequestId: Long,
    currentRequestId: Long,
    expectedQueueSongId: String,
    currentQueueSongId: String?,
): Boolean = expectedSessionId == currentSessionId && expectedRequestId == currentRequestId && expectedQueueSongId == currentQueueSongId

internal suspend fun resolveAndroidAudioTrackSelection(
    sourceSong: SongItem,
    positionMs: Long,
    wantVideo: Boolean,
    cachedAudio: PlaybackResource? = null,
    allowCounterpartLookup: Boolean = true,
    isCounterpartAllowed: (SongItem) -> Boolean = { true },
    lookupTimeoutMs: Long = ANDROID_MUSIC_TRACK_LOOKUP_TIMEOUT_MS,
    resolveMusicTrack: suspend (SongItem) -> MusicTrackResult,
): AndroidAudioTrackSelection {
    val sourceSelection = AndroidAudioTrackSelection(sourceSong, positionMs.coerceAtLeast(0L), cachedAudio)
    if (wantVideo || !allowCounterpartLookup || !sourceSong.canSwitchToAudioTrack) {
        return sourceSelection
    }

    val counterpart =
        try {
            withTimeoutOrNull(lookupTimeoutMs) { resolveMusicTrack(sourceSong) } as? MusicTrackResult.Found
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return sourceSelection
    if (!isCounterpartAllowed(counterpart.song)) return sourceSelection

    val song =
        applyAudioTrackMetadata(
            song = counterpart.song,
            queueSourceTitle = sourceSong.queueSourceTitle,
            customTitle = sourceSong.customTitle ?: counterpart.song.customTitle,
            customArtist = sourceSong.customArtist ?: counterpart.song.customArtist,
        )
    val mappedPositionMs = counterpart.positionMap?.mapPosition(positionMs) ?: positionMs.coerceAtLeast(0L)
    val targetDurationMs = song.duration?.takeIf { it > 0L }?.times(1_000L)
    val targetPositionMs = targetDurationMs?.let { mappedPositionMs.coerceIn(0L, it - 1L) } ?: mappedPositionMs
    return AndroidAudioTrackSelection(song, targetPositionMs, cachedAudio?.takeIf { it.videoId == song.id })
}
