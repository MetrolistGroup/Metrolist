package com.metrolist.shared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.discord_button1_text_default
import com.metrolist.shared.generated.resources.discord_button2_text_default
import com.metrolist.shared.generated.resources.discord_unknown_album
import com.metrolist.shared.generated.resources.unknown_artist
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.settings.IntegrationPlaybackState
import com.metrolist.shared.ui.settings.IosAppearanceProvider
import com.metrolist.shared.ui.settings.buildMobileDiscordPlaybackUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun IosDiscordPresenceEffect(
    playback: StateFlow<PlaybackUiState>,
    playbackSpeed: StateFlow<Float>,
) {
    IosAppearanceProvider {
        val latestPlayback = rememberUpdatedState(playback)
        val latestSpeed = rememberUpdatedState(playbackSpeed)
        val unknownArtist = rememberUpdatedState(stringResource(Res.string.unknown_artist))
        val unknownAlbum = rememberUpdatedState(stringResource(Res.string.discord_unknown_album))
        val button1 = rememberUpdatedState(stringResource(Res.string.discord_button1_text_default))
        val button2 = rememberUpdatedState(stringResource(Res.string.discord_button2_text_default))
        var foreground by remember {
            mutableStateOf(UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground)
        }
        DisposableEffect(Unit) {
            val center = NSNotificationCenter.defaultCenter
            val background =
                center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
                    foreground = false
                }
            val active =
                center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                    foreground = true
                }
            onDispose {
                center.removeObserver(background)
                center.removeObserver(active)
            }
        }
        LaunchedEffect(Unit) {
            maintainDiscordPlaybackSession(
                updates =
                    snapshotFlow { Triple(Settings.shared.enableDiscordIntegration, discordSettingsVisible, foreground) }
                        .flatMapLatest { (enabled, settingsVisible, _) ->
                            if (!enabled && !settingsVisible) {
                                flowOf(DiscordPlaybackUpdate(false, null, null))
                            } else {
                                snapshotFlow { latestPlayback.value to latestSpeed.value }
                                    .flatMapLatest { (states, speeds) -> combine(states, speeds) { state, speed -> state to speed } }
                                    .map { (state, speed) ->
                                        state.currentSong?.let { song ->
                                            IntegrationPlaybackState(
                                                song = song,
                                                positionMs = state.positionMs / 1_000L * 1_000L,
                                                durationMs = state.durationMs,
                                                isPlaying = state.isPlaying,
                                                playbackSpeed = speed,
                                            )
                                        }
                                    }.distinctUntilChanged()
                                    .flatMapLatest { playbackState ->
                                        snapshotFlow {
                                            val settings = Settings.shared
                                            buildMobileDiscordPlaybackUpdate(
                                                playback = playbackState,
                                                settingsVisible = settingsVisible,
                                                foreground = foreground,
                                                settings = settings,
                                                unknownArtist = unknownArtist.value,
                                                unknownAlbum = unknownAlbum.value,
                                                button1Default = button1.value,
                                                button2Default = button2.value,
                                            )
                                        }
                                    }
                            }
                        },
                connect = { DiscordRpcManager.init(GlobalYouTube.client) },
                disconnect = { DiscordRpcManager.destroy() },
                publish = { update ->
                    val activity = update.activity
                    if (activity == null) {
                        DiscordRpcManager.clear()
                    } else {
                        DiscordRpcManager.setActivity(activity, update.songId)
                    }
                },
            )
        }
    }
}
