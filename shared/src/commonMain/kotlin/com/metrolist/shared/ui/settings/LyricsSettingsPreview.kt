@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.local
import com.metrolist.shared.generated.resources.lyrics_auto_scroll_btn
import com.metrolist.shared.generated.resources.lyrics_not_found
import com.metrolist.shared.generated.resources.lyrics_provided_by
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.repository.LYRICS_PREVIEW_DURATION_MS
import com.metrolist.shared.repository.LYRICS_PREVIEW_VIDEO_ID
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LyricsView
import com.metrolist.shared.ui.components.withPlaybackAnimationFrame
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun useCurrentLyricsForPreview(
    songId: String?,
    isPlaying: Boolean,
    lyrics: List<LyricsEntry>,
): Boolean = isPlaying && !songId.isNullOrBlank() && lyrics.any { it.text.isNotBlank() }

@Composable
fun PlayerLyricsSettingsPreview(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    isCasting: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(
        state.currentSong?.id,
        state.isPlaying,
        Settings.shared.lyricsProviderOrder,
        Settings.shared.lyricsEnabledProviders,
        Settings.shared.preferWordSyncedLyrics,
    ) {
        if (state.isPlaying) state.currentSong?.let { viewModel.loadLyrics(it) }
    }
    LyricsSettingsPreview(
        modifier = modifier,
        songId = state.currentSong?.id,
        currentLyrics = state.lyrics,
        currentAgents = state.lyricsAgents,
        currentSongwriters = state.lyricsSongwriters,
        currentProvider = state.lyricsProvider,
        currentPosition = viewModel.currentPosition,
        positionReader = viewModel::currentPositionMs,
        playbackSpeed = viewModel.playbackSpeed.collectAsState().value,
        timingOffsetMs = state.lyricsOffsetMs,
        lyricsRetimed = state.mvLyricsRetimed,
        isPlaying = state.isPlaying,
        isCasting = isCasting,
        onSeek = viewModel::seekToPosition,
    )
}

@Composable
fun LyricsSettingsPreview(
    modifier: Modifier = Modifier,
    songId: String? = null,
    currentLyrics: List<LyricsEntry> = emptyList(),
    currentAgents: Map<String, AgentInfo> = emptyMap(),
    currentSongwriters: List<String> = emptyList(),
    currentProvider: String? = null,
    currentPosition: StateFlow<Long>? = null,
    positionReader: (() -> Long)? = null,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    timingOffsetMs: Int = 0,
    lyricsRetimed: Boolean = false,
    isPlaying: Boolean = false,
    isCasting: Boolean = false,
    onSeek: (Long, PlaybackSeekSource) -> Unit = { _, _ -> },
) {
    val useCurrentLyrics = useCurrentLyricsForPreview(songId, isPlaying, currentLyrics)
    val repository = remember { KoinPlatform.getKoin().get<LyricsRepository>() }
    var result by remember { mutableStateOf<LyricsProviderResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, attempt, useCurrentLyrics) {
        if (useCurrentLyrics) return@LaunchedEffect
        loading = true
        try {
            result = repository.getPreviewLyrics()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result = null
        } finally {
            loading = false
        }
    }
    val document = remember(result) { result?.let { LyricsUtils.parseMlxDocument(it.lyrics) } }
    val lyrics = remember(result, document) { document?.lines ?: result?.let { LyricsUtils.parseLyrics(it.lyrics) }.orEmpty() }
    val duration = maxOf(LYRICS_PREVIEW_DURATION_MS, document?.song?.durationMs ?: 0L, lyrics.maxOfOrNull { it.endTime ?: it.time } ?: 0L)
    var seekPosition by remember(result) { mutableLongStateOf(lyrics.firstOrNull { it.text.isNotBlank() }?.time ?: 0L) }
    var position by remember(result) { mutableLongStateOf(seekPosition) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val frameInterval = 50.milliseconds
    var previewPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(result, seekPosition, duration, lifecycleState, frameInterval, useCurrentLyrics) {
        if (useCurrentLyrics || result == null || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        val started = TimeSource.Monotonic.markNow()
        previewPlaying = true
        try {
            do {
                withPlaybackAnimationFrame(frameInterval) {
                    position = (seekPosition + started.elapsedNow().inWholeMilliseconds).coerceAtMost(duration)
                }
            } while (position < duration)
        } finally {
            previewPlaying = false
        }
    }
    val noLyrics = stringResource(Res.string.lyrics_not_found)
    val autoScroll = stringResource(Res.string.lyrics_auto_scroll_btn)
    val provider = if (useCurrentLyrics) currentProvider else result?.providerName
    val displayedProvider = if (isLocalLyricsProvider(provider)) stringResource(Res.string.local) else provider
    val providerText = displayedProvider?.let { stringResource(Res.string.lyrics_provided_by, it) }.orEmpty()
    val loadingText = stringResource(Res.string.loading)
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Text(stringResource(Res.string.preview), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
            when {
                !useCurrentLyrics && loading -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = loadingText },
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                    ) {
                        repeat(3) { index ->
                            Box(
                                Modifier
                                    .fillMaxWidth(if (index == 1) 0.6f else 0.85f)
                                    .height(24.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                }

                !useCurrentLyrics && result == null -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(noLyrics)
                        TextButton(onClick = { attempt++ }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                    }
                }

                else -> {
                    key(
                        Settings.shared.lyricsAutoScroll,
                        Settings.shared.lyricsNeutralColor,
                        Settings.shared.lyricsAnimationStyle,
                        if (useCurrentLyrics) songId else LYRICS_PREVIEW_VIDEO_ID,
                    ) {
                        LyricsView(
                            noLyricsText = noLyrics,
                            autoScrollText = autoScroll,
                            lyricsProvidedBy = { providerText },
                            lyrics = if (useCurrentLyrics) currentLyrics else lyrics,
                            agents = if (useCurrentLyrics) currentAgents else document?.agents.orEmpty(),
                            songwriters = if (useCurrentLyrics) currentSongwriters else document?.song?.songwriters.orEmpty(),
                            provider = displayedProvider,
                            currentPosition = position,
                            currentPositionFlow = if (useCurrentLyrics) currentPosition else null,
                            positionReader = if (useCurrentLyrics) positionReader else null,
                            playbackSpeed = if (useCurrentLyrics) playbackSpeed else 1f,
                            timingOffsetMs = if (useCurrentLyrics) timingOffsetMs else 0,
                            isPlaying = if (useCurrentLyrics) isPlaying else previewPlaying,
                            isCasting = useCurrentLyrics && isCasting,
                            isVisible = lifecycleState == Lifecycle.State.RESUMED,
                            lyricsRetimed = useCurrentLyrics && lyricsRetimed,
                            onSeek = { positionMs, source ->
                                if (useCurrentLyrics) {
                                    if (Settings.shared.lyricsClickToSeek) onSeek(positionMs, source)
                                } else {
                                    seekPosition = positionMs
                                }
                            },
                            textColor = MaterialTheme.colorScheme.onSurface,
                            songId = if (useCurrentLyrics) songId else LYRICS_PREVIEW_VIDEO_ID,
                            modifier = Modifier.fillMaxSize(),
                            isLiquidBackground = false,
                        )
                    }
                }
            }
        }
    }
}
