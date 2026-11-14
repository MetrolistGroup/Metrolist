@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.utils.SkiaCacheCleaner
import com.metrolist.desktop.utils.saveImage
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close_fullscreen_player
import com.metrolist.shared.generated.resources.copy
import com.metrolist.shared.generated.resources.local
import com.metrolist.shared.generated.resources.lyrics_auto_scroll_btn
import com.metrolist.shared.generated.resources.lyrics_provided_by
import com.metrolist.shared.generated.resources.lyrics_share_filename
import com.metrolist.shared.generated.resources.lyrics_share_save_image
import com.metrolist.shared.generated.resources.no_lyrics_for_this_track
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.lyricsCuratorUrl
import com.metrolist.shared.model.lyricsProviderUrl
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.components.lyrics.LyricsSelectionState
import com.metrolist.shared.ui.components.lyrics.LyricsShareDialog
import com.metrolist.shared.ui.components.lyrics.LyricsView
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
fun FullscreenContentLayout(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    isWide: Boolean,
    toggleWindowFullscreen: () -> Unit,
    closeExpandedPlayer: () -> Unit,
) {
    val lyricsFraction by animateFloatAsState(
        targetValue = if (UiState.showLyricsDrawer) 1f else 0f,
        animationSpec = tween(450, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)),
        label = "lyricsFraction",
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val playerWidth = if (isWide) maxWidth / (1f + lyricsFraction) else maxWidth
        val scale = fullscreenPlayerScale(playerWidth, availableHeight)
        Box(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    PlayerContentColumn(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        availableHeight = availableHeight,
                        scale = scale,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 40.dp * scale * lyricsFraction),
                        toggleWindowFullscreen = toggleWindowFullscreen,
                    )

                    if (lyricsFraction > 0.001f) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(lyricsFraction)
                                    .fillMaxHeight()
                                    .clipToBounds()
                                    .graphicsLayer {
                                        alpha = (lyricsFraction * 1.5f).coerceIn(0f, 1f)
                                        translationX = (1f - lyricsFraction) * 120.dp.toPx()
                                    },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .padding(top = 78.dp * scale, bottom = 62.dp * scale, start = 50.dp * scale, end = 50.dp * scale)
                                        .width(700.dp * scale),
                            ) {
                                LyricsSection(textSizeMultiplier = 1.45f * scale.coerceAtLeast(0.8f))
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScrollWithDrag(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(24.dp))

                    IconButton(
                        onClick = closeExpandedPlayer,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .padding(end = 24.dp)
                                .size(48.dp),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Default.Close,
                            contentDescription = stringResource(Res.string.close_fullscreen_player),
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    PlayerCoreContent(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        availableHeight = (availableHeight - 144.dp).coerceAtLeast(0.dp),
                        scale = scale,
                        toggleWindowFullscreen = toggleWindowFullscreen,
                    )

                    Spacer(Modifier.height(24.dp))

                    AnimatedVisibility(
                        visible = UiState.showLyricsDrawer,
                        enter =
                            expandVertically(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                            ) + fadeIn(spring(dampingRatio = 0.8f, stiffness = 380f)),
                        exit =
                            shrinkVertically(
                                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f),
                            ) + fadeOut(spring(dampingRatio = 0.88f, stiffness = 450f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .heightIn(min = 200.dp, max = 400.dp),
                        ) {
                            LyricsSection()
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun LyricsSection(textSizeMultiplier: Float = 1.2f) {
    val colorScheme = MaterialTheme.colorScheme
    val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
    val lyricsTextColor by animateColorAsState(
        targetValue = if (isSolid) colorScheme.onSurface else Color.White,
        label = "LyricsSectionTextColor",
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            DesktopLyricsContent(
                textColor = lyricsTextColor,
                textSizeMultiplier = textSizeMultiplier,
                isVisible = UiState.showLyricsDrawer && !UiState.videoFullscreenObscuresPlayer,
            )
        }
        AnimatedVisibility(
            visible = LyricsState.showLyricsOffsetEditor,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LyricsOffsetEditor(
                offsetMs = LyricsState.currentLyricsOffsetMs,
                onOffsetChange = { offset ->
                    LyricsState.updateLyricsOffset(offset)
                },
                onDone = { LyricsState.showLyricsOffsetEditor = false },
                contentColor = lyricsTextColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DesktopLyricsContent(
    textColor: Color,
    textSizeMultiplier: Float = 1.0f,
    isVisible: Boolean = true,
) {
    val currentSongId = PlayerState.currentTrack?.id
    val isMatchingTrack = currentSongId != null && currentSongId == LyricsState.currentLyricsSongId
    val lyricsRaw = if (isMatchingTrack) LyricsState.currentLyrics ?: "" else ""
    val castState by PlayerState.castManager.state.collectAsState()
    val lyricsDoc = remember(lyricsRaw) { LyricsUtils.parseMlxDocument(lyricsRaw) }
    val enrichedLyrics = if (isMatchingTrack) LyricsState.currentLyricsEntries else emptyList()
    val lyrics =
        remember(lyricsDoc, enrichedLyrics, isMatchingTrack) {
            if (!isMatchingTrack) {
                emptyList()
            } else {
                enrichedLyrics.ifEmpty {
                    lyricsDoc?.lines ?: LyricsUtils.parseLyrics(lyricsRaw)
                }
            }
        }
    val selection = remember(currentSongId, lyrics) { LyricsSelectionState() }
    var selectedText by remember(currentSongId) { mutableStateOf<String?>(null) }
    val cancelSelection = remember(selection) { { selection.clear() } }
    DisposableEffect(selection.isActive) {
        if (selection.isActive) {
            LyricsState.cancelSelection?.takeIf { it !== cancelSelection }?.invoke()
            LyricsState.cancelSelection = cancelSelection
        }
        onDispose { if (LyricsState.cancelSelection === cancelSelection) LyricsState.cancelSelection = null }
    }
    val saveTitle = stringResource(Res.string.lyrics_share_save_image)
    val filename = stringResource(Res.string.lyrics_share_filename)
    val track = PlayerState.currentTrack
    selectedText?.let { text ->
        if (track != null) {
            LyricsShareDialog(
                text = text,
                songId = track.id,
                title = track.title,
                artist = track.artists.joinToString { it.name },
                artworkUrl = PlayerState.currentArtworkUrl,
                exportLabel = saveTitle,
                onExport = { saveImage(it, saveTitle, filename) },
                onShareText = AppState::copyToClipboard,
                onDismiss = { selectedText = null },
            )
        }
    }
    val lyricsProvidedByFmt = stringResource(Res.string.lyrics_provided_by)

    Box(modifier = Modifier.fillMaxSize()) {
        LyricsView(
            noLyricsText = stringResource(Res.string.no_lyrics_for_this_track),
            translationProgress = if (isMatchingTrack) LyricsState.translationProgress else LyricsTranslationProgress(),
            autoScrollText = stringResource(Res.string.lyrics_auto_scroll_btn),
            lyricsProvidedBy = { provider -> String.format(lyricsProvidedByFmt, provider) },
            providerUrl =
                if (isMatchingTrack) {
                    lyricsProviderUrl(LyricsState.currentLyricsProvider, lyricsDoc?.meta?.sourceId)
                } else {
                    null
                },
            lyricsMeta = if (isMatchingTrack) lyricsDoc?.meta else null,
            lyricsCuratorUrl =
                if (isMatchingTrack) {
                    lyricsCuratorUrl(LyricsState.currentLyricsProvider, lyricsDoc?.meta?.lyricsCuratorId)
                } else {
                    null
                },
            lyrics = lyrics,
            songwriters =
                if (isMatchingTrack) {
                    lyricsDoc?.song?.songwriters?.takeIf { it.isNotEmpty() } ?: LyricsState.currentLyricsSongwriters
                } else {
                    emptyList()
                },
            agents = if (isMatchingTrack) lyricsDoc?.agents ?: LyricsState.currentLyricsAgents else emptyMap(),
            provider =
                if (isMatchingTrack) {
                    if (isLocalLyricsProvider(LyricsState.currentLyricsProvider)) {
                        stringResource(Res.string.local)
                    } else {
                        LyricsState.currentLyricsProvider
                    }
                } else {
                    null
                },
            currentPositionFlow = PlayerState.playbackPosition,
            positionReader = PlayerState::currentMediaPositionMs,
            playbackSpeed =
                PlayerState.player.playbackSpeed
                    .collectAsState()
                    .value,
            timingOffsetMs = if (isMatchingTrack) LyricsState.currentLyricsOffsetMs else 0,
            isPlaying = PlayerState.isPlaying,
            isVisible = isVisible,
            lyricsRetimed = isMatchingTrack && LyricsState.mvLyricsRetimed,
            onSeek = { pos, source -> if (Settings.shared.lyricsClickToSeek) PlayerState.seekToPosition(pos, source) },
            isLoading = LyricsState.isLyricsLoading || (currentSongId != null && currentSongId != LyricsState.currentLyricsSongId),
            onLyricsClosed = { SkiaCacheCleaner.schedulePurge() },
            textSizeMultiplier = textSizeMultiplier,
            songId = PlayerState.currentTrack?.id,
            isCasting = castState.isConnected,
            selectionState = selection,
            onShareSelection = { selectedText = it },
            textColor = textColor,
        )
    }
}
