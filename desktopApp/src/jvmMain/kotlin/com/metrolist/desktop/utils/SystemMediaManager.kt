/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.utils.parseYouTubeUrl
import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

object SystemMediaManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var observerJob: Job? = null
    private var initialized = false
    var onQuit: (() -> Unit)? = null
    var onRaise: (() -> Unit)? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        observerJob =
            scope.launch(Dispatchers.Main) {
                val available =
                    withContext(Dispatchers.IO) {
                        mutex.withLock {
                            if (!MediaControlService.isAvailable()) return@withLock false
                            MediaControlService.configure(
                                dbusName = "org.mpris.MediaPlayer2.metrolist",
                                displayName = getString(Res.string.app_name),
                            )
                            MediaControlService.attach { event ->
                                scope.launch(Dispatchers.Main) { handle(event) }
                            }
                            true
                        }
                    }
                if (!available) {
                    initialized = false
                    return@launch
                }
                launch {
                    fun state(position: Long): MediaPlaybackState =
                        MediaPlaybackState(
                            status =
                                when {
                                    PlayerState.currentTrack == null -> MediaPlaybackStatus.STOPPED
                                    PlayerState.isPlaying -> MediaPlaybackStatus.PLAYING
                                    else -> MediaPlaybackStatus.PAUSED
                                },
                            positionMs = position,
                        )
                    val changes =
                        combine(
                            snapshotFlow { Triple(PlayerState.currentTrack, PlayerState.isPlaying, PlayerState.isPlaybackLoading) },
                            PlayerState.player.playbackSpeed,
                            PlayerState.player.playbackStateChanges,
                        ) { _, _, _ ->
                            state(PlayerState.currentMediaPositionMs())
                        }
                    systemMediaPlaybackUpdates(
                        merge(changes, PlayerState.mediaPositionDiscontinuities.map(::state)),
                        PlayerState.playbackPosition,
                        currentPositionMs = PlayerState::currentMediaPositionMs,
                    ).collect { playback ->
                        withContext(Dispatchers.IO) {
                            mutex.withLock { MediaControlService.setPlaybackState(playback) }
                        }
                    }
                }
                launch {
                    combine(
                        snapshotFlow { PlayerState.currentTrack to PlayerState.currentArtworkUrl },
                        PlayerState.playbackDuration,
                    ) { (track, artworkUrl), duration ->
                        val metadata =
                            track?.let {
                                MediaMetadata(
                                    title = it.title,
                                    artist = it.artists.joinToString(", ") { artist -> artist.displayName },
                                    album = it.album?.name.orEmpty(),
                                    coverUrl = artworkUrl ?: it.thumbnail.orEmpty(),
                                    duration = duration.takeIf { ms -> ms > 0L } ?: it.duration?.times(1000L),
                                )
                            }
                        track?.id to (metadata ?: MediaMetadata())
                    }.systemMediaMetadataUpdates()
                        .collect { metadata ->
                            withContext(Dispatchers.IO) {
                                mutex.withLock { MediaControlService.setMetadata(metadata) }
                            }
                        }
                }
                launch {
                    PlayerState.volumeState
                        .collect { volume ->
                            withContext(Dispatchers.IO) {
                                mutex.withLock { MediaControlService.setVolume(volume.toDouble()) }
                            }
                        }
                }
            }
    }

    private fun handle(event: MediaControlEvent) {
        val guest = PlayerState.isListenTogetherGuest()
        when (event) {
            MediaControlEvent.Play -> {
                PlayerState.play()
            }

            MediaControlEvent.Pause -> {
                PlayerState.pause()
            }

            MediaControlEvent.Toggle -> {
                PlayerState.togglePlay()
            }

            MediaControlEvent.Stop -> {
                PlayerState.stop()
            }

            MediaControlEvent.Next -> {
                if (!guest) PlayerState.skipNext(fromRemoteControl = true)
            }

            MediaControlEvent.Previous -> {
                if (!guest) PlayerState.skipPrevious(fromRemoteControl = true)
            }

            is MediaControlEvent.SeekBy -> {
                if (!guest) PlayerState.seekRelative(event.offsetMs)
            }

            is MediaControlEvent.SetPosition -> {
                if (!guest) PlayerState.seekToPosition(event.positionMs)
            }

            is MediaControlEvent.SetVolume -> {
                applySystemVolume(event.volume, PlayerState::setVolumeLevel)
            }

            is MediaControlEvent.OpenUri -> {
                if (!guest) DesktopUrlHandler.handleUrl(event.uri)
            }

            MediaControlEvent.Raise -> {
                onRaise?.invoke()
            }

            MediaControlEvent.Quit -> {
                onQuit?.invoke()
            }
        }
    }

    fun shutdown() {
        if (!initialized) return
        initialized = false
        onQuit = null
        onRaise = null
        observerJob?.cancel()
        observerJob = null
        scope.launch {
            mutex.withLock { MediaControlService.detach() }
        }
    }
}

internal fun applySystemVolume(
    requested: Double,
    setAppVolume: (Float) -> Unit,
) {
    if (!requested.isFinite()) return
    val volume = requested.coerceIn(0.0, 1.0).toFloat()
    setAppVolume(volume)
}
