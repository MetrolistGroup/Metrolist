package com.metrolist.shared.playback.sponsorblock

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

suspend fun observeSponsorBlockSkipping(
    currentSong: StateFlow<SongItem?>,
    currentPosition: StateFlow<Long>,
    duration: StateFlow<Long>,
    isPlaying: StateFlow<Boolean>,
    enabled: Flow<Boolean>,
    canSeek: () -> Boolean,
    seekTo: (Long) -> Unit,
    onSkipped: () -> Unit,
    client: SponsorBlockClient = SponsorBlockClient.shared,
) {
    combine(currentSong, enabled) { song, active -> song to active }
        .distinctUntilChanged()
        .collectLatest { (song, active) ->
            val segments = client.segments(song, active)
            if (segments.isEmpty()) return@collectLatest
            var pendingTarget: Long? = null
            combine(currentPosition, isPlaying) { position, playing -> position to playing }.collect { (position, playing) ->
                if (currentSong.value != song || !canSkipPodcastSegments(currentSong.value, active)) return@collect
                val target = segments.skipTarget(position, duration.value)
                if (target == null) pendingTarget = null
                if (playing && canSeek() && target != null && target != pendingTarget) {
                    pendingTarget = target
                    seekTo(target)
                    onSkipped()
                }
            }
        }
}
