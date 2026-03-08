/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Some UI stuff inside of this file are using YouLyPlus code under MIT code
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.LyricsUtils.lyricsPositionForOffset
import com.metrolist.shared.api.lyrics.LyricsUtils.lyricsSeekPositionForOffset
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.lyrics_curated_by
import com.metrolist.shared.generated.resources.lyrics_share_limit
import com.metrolist.shared.generated.resources.lyrics_share_selected_count
import com.metrolist.shared.generated.resources.lyrics_written_by
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.zoomedLyricsTextSize
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.expressiveEntrance
import com.metrolist.shared.ui.components.lyrics.words.FixedMotionDurationScale
import com.metrolist.shared.ui.components.lyrics.words.LocalLyricsAnimationVisible
import com.metrolist.shared.ui.components.lyrics.words.LyricsAnimationStyle
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.artworkVerticalProgressiveBlur
import com.metrolist.shared.ui.utils.fadingEdge
import com.metrolist.shared.utils.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val LYRICS_ANCHOR_RATIO = 0.5f
private const val MAX_PRE_CENTER_STAGGER_LINES = 4
private const val LYRICS_TEXT_MEASURER_CACHE_SIZE = 384
private const val PENDING_SEEK_TIMEOUT_MS = 650L
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp
private val LYRICS_KEEP_ALIVE_ZONE_BASE_DP = 350.dp
private const val LYRICS_PROGRESSIVE_BLUR_MAX_DP = 8f

private fun LyricsListItem.listKey(): String =
    when (this) {
        is LyricsListItem.Line -> "line_$index"
        is LyricsListItem.Indicator -> "ind_${afterLineIndex}_$gapStartMs"
        is LyricsListItem.Provider -> "prov_${name}_${songwriters.joinToString(",")}"
    }

private data class LyricsScrollTarget(
    val playbackListIndex: Int,
    val lastActiveGroupEndListIndex: Int,
    val activeIndices: List<Int>,
    val scrollDurationMs: Long,
    val scrollGapT: Float?,
    val isSeek: Boolean,
)

private data class TargetScrollSnapshot(
    val target: LyricsScrollTarget,
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val viewportHeight: Int,
    val viewportWidth: Int,
    val visibleItemsCount: Int,
    val anchorPx: Int,
    val keepAlivePx: Int,
)

private data class ReflowSnapshot(
    val target: LyricsScrollTarget,
    val contentSize: Int,
    val viewportHeight: Int,
    val reflowSignal: Long,
)

internal suspend fun animateLyricsItemAppearance(
    alpha: Animatable<Float, AnimationVector1D>,
    manualScrolling: Flow<Boolean>,
) {
    manualScrolling.collectLatest { manual ->
        withContext(FixedMotionDurationScale) {
            if (manual) {
                alpha.animateTo(1f, tween(250))
            } else if (alpha.value < 1f) {
                delay(100L.milliseconds)
                alpha.animateTo(1f, tween(200))
            }
        }
    }
}

@Composable
internal fun resolveLyricsTextColor(textColor: Color): Color =
    if (Settings.shared.lyricsNeutralColor) {
        if (textColor.luminance() > 0.5f) Color.White else Color.Black
    } else {
        lyricsPaletteTextColor(MaterialTheme.colorScheme, textColor.luminance() > 0.5f)
    }

@Composable
fun LyricsView(
    noLyricsText: String,
    translationProgress: LyricsTranslationProgress = LyricsTranslationProgress(),
    autoScrollText: String,
    lyricsProvidedBy: (String) -> String,
    lyrics: List<LyricsEntry>,
    songwriters: List<String> = emptyList(),
    lyricsWrittenBy: ((List<String>) -> String)? = null,
    agents: Map<String, AgentInfo> = emptyMap(),
    provider: String? = null,
    providerUrl: String? = null,
    lyricsMeta: MlxMeta? = null,
    lyricsCuratorUrl: String? = null,
    currentPosition: Long = 0L,
    currentPositionFlow: StateFlow<Long>? = null,
    positionReader: (() -> Long)? = null,
    playbackSpeed: Float = Settings.shared.playbackSpeed,
    timingOffsetMs: Int = 0,
    lyricsRetimed: Boolean = false,
    isPlaying: Boolean = false,
    onSeek: (Long, PlaybackSeekSource) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    selectionState: LyricsSelectionState? = null,
    onShareSelection: ((String) -> Unit)? = null,
    textSizeMultiplier: Float = 1.0f,
    textColor: Color = Color.White,
    onLyricsClosed: () -> Unit = {},
    songId: Any? = null,
    isCasting: Boolean = false,
    isVisible: Boolean = true,
    isLiquidBackground: Boolean = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.LIQUID,
) {
    val textColor = resolveLyricsTextColor(textColor)
    val translationShimmer = rememberTranslationShimmer(translationProgress.isTranslating && isVisible)
    val shimmerColor = MaterialTheme.colorScheme.primary
    val globalOffsetMs = Settings.shared.globalLyricsOffsetMs
    val effectiveOffsetMs = LyricsUtils.combinedLyricsOffsetMs(timingOffsetMs, globalOffsetMs)
    val isPlusLighter = isLiquidBackground && Settings.shared.lyricsPlusLighter
    val lyricsActiveByRealTiming = Settings.shared.lyricsActiveByRealTiming
    val selection = selectionState ?: remember(songId, lyrics) { LyricsSelectionState() }
    val selectedIndices = selection.selectedIndices
    val isSelectionMode = selection.isActive
    val onToggleSelection: (Int) -> Unit = selection::toggle
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSelectionMode) { if (isSelectionMode) focusRequester.requestFocus() }
    if (!isDesktop) NavigationBackHandler(enabled = isSelectionMode, onBack = selection::clear)
    val currentSongKey = songId?.toString()
    val isLrcSynced = remember(lyrics) { LyricsUtils.hasPlaybackTiming(lyrics) }
    var isAutoScrollEnabled by remember(currentSongKey, lyrics) { mutableStateOf(Settings.shared.lyricsAutoScroll) }
    var pendingSeekLineIndex by remember(currentSongKey, lyrics) { mutableStateOf<Int?>(null) }
    var isFirstPass by remember(songId, lyrics, effectiveOffsetMs) { mutableStateOf(true) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            isAutoScrollEnabled = Settings.shared.lyricsAutoScroll
            pendingSeekLineIndex = null
            isFirstPass = true
        }
    }
    val shouldFollowPlayback = isAutoScrollEnabled && isLrcSynced && !isSelectionMode
    val secondaryTextColor = remember(textColor) { textColor.copy(alpha = 0.7f) }
    val textMeasurer = rememberTextMeasurer(cacheSize = LYRICS_TEXT_MEASURER_CACHE_SIZE)

    val positionFlow =
        currentPositionFlow ?: remember(currentSongKey) { MutableStateFlow(currentPosition) }.also {
            it.value = currentPosition
            LaunchedEffect(currentPosition) { it.value = currentPosition }
        }

    DisposableEffect(Unit) {
        onDispose(onLyricsClosed)
    }

    val validSongwriters =
        remember(songwriters) {
            songwriters.filter { it.isNotBlank() }.distinct()
        }
    val lyricsAttribution =
        lyricsMeta
            ?.lyricsCurator
            ?.takeIf(String::isNotBlank)
            ?.let { curator ->
                val providerText = provider?.takeIf(String::isNotBlank)?.let(lyricsProvidedBy)
                val curatedByText = stringResource(Res.string.lyrics_curated_by, curator)
                buildAnnotatedString {
                    if (providerText != null) {
                        append(providerText)
                        val providerStart = providerText.lastIndexOf(provider.orEmpty())
                        if (providerStart >= 0 && providerUrl != null) {
                            addLink(
                                LinkAnnotation.Url(providerUrl, TextLinkStyles()),
                                providerStart,
                                providerStart + provider.orEmpty().length,
                            )
                        }
                        append(' ')
                    }
                    val curatedByStart = length
                    append(curatedByText)
                    val curatorOffset = curatedByText.indexOf(curator)
                    val curatorStart = if (curatorOffset >= 0) curatedByStart + curatorOffset else -1
                    if (curatorStart >= 0 && lyricsCuratorUrl != null) {
                        addLink(LinkAnnotation.Url(lyricsCuratorUrl, TextLinkStyles()), curatorStart, curatorStart + curator.length)
                    }
                }
            }

    val processedItems =
        remember(lyrics, provider, validSongwriters, lyricsActiveByRealTiming) {
            buildLyricsListItems(lyrics, provider, validSongwriters, activeByRealTiming = lyricsActiveByRealTiming)
        }

    val lyricsItemIndex =
        remember(processedItems, lyrics.size) {
            buildLyricsItemIndex(processedItems, lyrics.size)
        }

    val timelineItems =
        remember(processedItems, lyrics) {
            buildLyricsTimelineItems(processedItems, lyrics)
        }

    val lineEndTimesByIndex =
        remember(timelineItems, lyrics.size) {
            val out = LongArray(lyrics.size) { -1L }
            for (item in timelineItems) {
                if (!item.isGap && item.lineIndex in out.indices) {
                    out[item.lineIndex] = item.sourceEndTimeMs
                }
            }
            out
        }

    val animStyle =
        remember(Settings.shared.lyricsAnimationStyle) {
            LyricsAnimationStyle.fromString(Settings.shared.lyricsAnimationStyle)
        }
    val isWordByWord =
        remember(lyrics, isLrcSynced, animStyle, Settings.shared.lyricsRomanizationEnabled) {
            isLrcSynced &&
                animStyle != LyricsAnimationStyle.NONE &&
                lyrics.any { it.hasAnimatedWordTiming(Settings.shared.lyricsRomanizationEnabled) }
        }

    val clockEnabled = isVisible && lyrics.isNotEmpty() && isLrcSynced
    val deadlines =
        remember(timelineItems, lyrics, isWordByWord) {
            if (isWordByWord) emptyList() else buildLineLyricsDeadlines(timelineItems, lyrics)
        }
    val deadlinePosition =
        if (!isWordByWord) {
            rememberDeadlineLyricsPositionState(
                positionFlow = positionFlow,
                deadlines = deadlines,
                playbackSpeed = playbackSpeed,
                positionReader = positionReader,
                isPlaying = isPlaying,
                songId = currentSongKey,
                timingOffsetMs = effectiveOffsetMs,
                isCasting = isCasting,
                enabled = clockEnabled,
            )
        } else {
            null
        }
    val lyricsPositionState =
        if (deadlinePosition != null) {
            remember(deadlinePosition, effectiveOffsetMs) {
                derivedStateOf { lyricsPositionForOffset(deadlinePosition.value.positionMs, effectiveOffsetMs) }
            }
        } else {
            val smoothPosition =
                rememberSmoothLyricsPositionState(
                    positionFlow = positionFlow,
                    playbackSpeed = playbackSpeed,
                    positionReader = positionReader,
                    isPlaying = isPlaying,
                    songId = currentSongKey,
                    isCasting = isCasting,
                    enabled = clockEnabled,
                )
            remember(smoothPosition, effectiveOffsetMs) {
                derivedStateOf { lyricsPositionForOffset(smoothPosition.longValue, effectiveOffsetMs) }
            }
        }

    val stablePositionProvider: () -> Long =
        remember(lyricsPositionState) {
            { lyricsPositionState.value }
        }

    val syncEngine =
        remember(timelineItems, lyricsItemIndex.firstLineListIndex, songId, effectiveOffsetMs, lyricsActiveByRealTiming) {
            LyricsSyncEngine(timelineItems, lyricsItemIndex.firstLineListIndex, activeByRealTiming = lyricsActiveByRealTiming)
        }

    val lineAlignments =
        remember(lyrics, agents, Settings.shared.respectAgentPositioning, Settings.shared.lyricsTextPosition) {
            LyricsUtils.assignLineAlignments(
                lyrics,
                agents = agents,
                respectAgentPositioning = Settings.shared.respectAgentPositioning,
                lyricsTextPosition = Settings.shared.lyricsTextPosition,
            )
        }

    val hasMultipleSingers =
        remember(lineAlignments, agents, lyrics, Settings.shared.respectAgentPositioning) {
            Settings.shared.respectAgentPositioning &&
                (
                    lineAlignments.values.distinct().size > 1 ||
                        agents.size > 1 ||
                        lyrics.mapNotNull { it.agent }.distinct().size > 1
                )
        }

    val providerTextAlign =
        when (Settings.shared.lyricsTextPosition.uppercase()) {
            "LEFT" -> TextAlign.Start
            "RIGHT" -> TextAlign.End
            else -> TextAlign.Center
        }

    val syncSnapshotState =
        remember(
            lyricsPositionState,
            deadlinePosition,
            syncEngine,
            timelineItems,
            isLrcSynced,
            shouldFollowPlayback,
            isWordByWord,
            isCasting,
        ) {
            derivedStateOf(structuralEqualityPolicy()) {
                if (!isLrcSynced || timelineItems.isEmpty()) {
                    LyricsSyncSnapshot(
                        playbackListIndex = -1,
                        primaryLineIndex = -1,
                        lastActiveGroupEndListIndex = 0,
                        activeLineIndices = emptyList(),
                        scrollLookAheadMs = -DEFAULT_SUGGESTED_LINE_OFFSET_MS,
                        scrollDurationMs = calculateScrollDuration(calculateAppleScrollDuration(DEFAULT_APPLE_SCROLL_DURATION_MS)),
                        scrollGapT = null,
                        isSeek = false,
                    )
                } else {
                    syncEngine.update(
                        currentTimeMs = lyricsPositionState.value,
                        shouldFollowPlayback = shouldFollowPlayback,
                        isWordByWord = isWordByWord,
                        seekGeneration = if (isCasting) null else deadlinePosition?.value?.seekGeneration,
                    )
                }
            }
        }
    val syncSnapshot by syncSnapshotState
    val seekRequestSnapshot = remember(pendingSeekLineIndex) { Snapshot.withoutReadObservation { syncSnapshot } }
    LaunchedEffect(pendingSeekLineIndex, syncSnapshot) {
        val requestedLine = pendingSeekLineIndex ?: return@LaunchedEffect
        if (requestedLine in syncSnapshot.activeLineIndices || (syncSnapshot.isSeek && syncSnapshot != seekRequestSnapshot)) {
            pendingSeekLineIndex = null
            isFirstPass = false
        }
    }
    LaunchedEffect(pendingSeekLineIndex) {
        if (pendingSeekLineIndex != null) {
            delay(PENDING_SEEK_TIMEOUT_MS.milliseconds)
            pendingSeekLineIndex = null
        }
    }
    val highlightedIndicesList =
        remember(syncSnapshot.activeLineIndices, pendingSeekLineIndex, lyricsActiveByRealTiming) {
            val pending = pendingSeekLineIndex
            if (pending != null && !lyricsActiveByRealTiming) listOf(pending) else syncSnapshot.activeLineIndices
        }
    val highlightedLineIndices = remember(highlightedIndicesList) { highlightedIndicesList.toSet() }

    var lastActiveLineIndex by remember(songId, lyrics, effectiveOffsetMs) {
        val currentLyricsPos =
            Snapshot.withoutReadObservation {
                lyricsPositionForOffset(positionFlow.value.coerceAtLeast(0L), effectiveOffsetMs)
            }
        val initialIdx =
            if (!isLrcSynced || timelineItems.isEmpty()) {
                0
            } else {
                syncEngine
                    .peekInitialTarget(
                        currentTimeMs = currentLyricsPos,
                        shouldFollowPlayback = shouldFollowPlayback,
                        isWordByWord = isWordByWord,
                    ).primaryLineIndex
            }
        mutableIntStateOf(initialIdx.coerceAtLeast(0))
    }

    val scrollDurationMs: Long = syncSnapshot.scrollDurationMs
    val delayIncrementMs: Float =
        remember(scrollDurationMs) {
            calculateScrollDelayIncrement(scrollDurationMs)
        }

    val predictiveLineIndex = pendingSeekLineIndex ?: syncSnapshot.primaryLineIndex
    LaunchedEffect(predictiveLineIndex) {
        if (predictiveLineIndex >= 0) lastActiveLineIndex = predictiveLineIndex
    }

    val scrollTargetState =
        remember(
            syncSnapshotState,
            syncEngine,
            lyrics,
            isLrcSynced,
            isWordByWord,
            timelineItems,
            pendingSeekLineIndex,
            lyricsItemIndex,
        ) {
            derivedStateOf(structuralEqualityPolicy()) {
                val snap = syncSnapshotState.value
                val pending = pendingSeekLineIndex
                if (!isLrcSynced || timelineItems.isEmpty()) {
                    LyricsScrollTarget(
                        playbackListIndex = -1,
                        lastActiveGroupEndListIndex = 0,
                        activeIndices = emptyList(),
                        scrollDurationMs = SCROLL_LOOK_AHEAD_MIN_MS,
                        scrollGapT = null,
                        isSeek = false,
                    )
                } else if (pending != null) {
                    val pendingListIdx =
                        lyricsItemIndex.lineListIndices.getOrElse(pending) { snap.playbackListIndex }
                    val pendingSnap =
                        lyrics.getOrNull(pending)?.let {
                            syncEngine.peekInitialTarget(
                                currentTimeMs = it.time,
                                shouldFollowPlayback = true,
                                isWordByWord = isWordByWord,
                            )
                        }
                    LyricsScrollTarget(
                        playbackListIndex = pendingListIdx,
                        lastActiveGroupEndListIndex = pendingListIdx,
                        activeIndices = listOf(pending),
                        scrollDurationMs = pendingSnap?.scrollDurationMs ?: snap.scrollDurationMs,
                        scrollGapT = pendingSnap?.scrollGapT ?: snap.scrollGapT,
                        isSeek = true,
                    )
                } else {
                    LyricsScrollTarget(
                        playbackListIndex = snap.playbackListIndex,
                        lastActiveGroupEndListIndex = snap.lastActiveGroupEndListIndex,
                        activeIndices = snap.activeLineIndices,
                        scrollDurationMs = snap.scrollDurationMs,
                        scrollGapT = snap.scrollGapT,
                        isSeek = snap.isSeek,
                    )
                }
            }
        }

    val initialPlaybackListIndex =
        remember(songId, lyrics, effectiveOffsetMs) {
            val currentLyricsPos =
                Snapshot.withoutReadObservation {
                    lyricsPositionForOffset(positionFlow.value.coerceAtLeast(0L), effectiveOffsetMs)
                }
            if (!isLrcSynced || timelineItems.isEmpty()) {
                0
            } else {
                syncEngine
                    .peekInitialTarget(
                        currentTimeMs = currentLyricsPos,
                        shouldFollowPlayback = true,
                        isWordByWord = isWordByWord,
                    ).playbackListIndex
                    .coerceAtLeast(0)
            }
        }
    val lazyListState =
        key(currentSongKey, isLrcSynced) { rememberLazyListState(initialFirstVisibleItemIndex = initialPlaybackListIndex) }

    val activeProgrammaticScrolls = remember(currentSongKey) { mutableIntStateOf(0) }
    val scrollInCode = remember(activeProgrammaticScrolls) { derivedStateOf { activeProgrammaticScrolls.intValue > 0 } }
    val isDragged = key(currentSongKey) { lazyListState.interactionSource.collectIsDraggedAsState() }
    val isManualScrolling =
        remember(lazyListState, scrollInCode, isDragged) {
            derivedStateOf { isDragged.value || (lazyListState.isScrollInProgress && !scrollInCode.value) }
        }

    val manualScrollConnection =
        remember(currentSongKey, lyrics, isSelectionMode) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput &&
                        !isSelectionMode &&
                        !isFirstPass &&
                        !scrollInCode.value &&
                        abs(consumed.y) > 0.5f &&
                        (isDragged.value || isDesktop)
                    ) {
                        pendingSeekLineIndex = null
                        isAutoScrollEnabled = false
                    }
                    return Offset.Zero
                }
            }
        }

    val scrollShifter = remember(currentSongKey) { LyricsScrollShifter() }
    LaunchedEffect(scrollShifter) { scrollShifter.runClock() }
    val itemKeys = remember(processedItems) { Array(processedItems.size) { processedItems[it].listKey() } }
    val linesBefore = remember(processedItems) { buildLinesBefore(processedItems) }
    val latestLinesBefore by rememberUpdatedState(linesBefore)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (isSelectionMode && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        selection.clear()
                        true
                    } else {
                        false
                    }
                }.focusRequester(focusRequester)
                .focusable(enabled = isSelectionMode),
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val fadeTop = minOf(LYRICS_FADE_TOP_DP, maxHeight / 4)
        val fadeBottom = minOf(LYRICS_FADE_BOTTOM_DP, maxHeight / 4)
        val density = LocalDensity.current
        val activeLineGroupHeightPx by remember(lazyListState, scrollTargetState, lyricsItemIndex) {
            derivedStateOf(structuralEqualityPolicy()) {
                val target = scrollTargetState.value
                val activeListIndices =
                    target.activeIndices
                        .mapNotNull { lyricsItemIndex.lineListIndices.getOrNull(it) }
                        .ifEmpty { listOfNotNull(target.playbackListIndex.takeIf { it >= 0 }) }
                val activeItems =
                    lazyListState.layoutInfo.visibleItemsInfo.filter { it.index in activeListIndices }
                if (activeItems.isEmpty()) {
                    0
                } else {
                    activeItems.maxOf { it.offset + it.size } - activeItems.minOf { it.offset }
                }
            }
        }
        val anchorY = viewportHeightPx * LYRICS_ANCHOR_RATIO
        val activeLineHalfHeightPx = activeLineGroupHeightPx / 2f
        val topBlurEndPx =
            if (activeLineGroupHeightPx > 0) {
                anchorY - activeLineHalfHeightPx
            } else {
                with(density) { fadeTop.toPx() }
            }
        val bottomBlurStartPx =
            if (activeLineGroupHeightPx > 0) {
                anchorY + activeLineHalfHeightPx
            } else {
                viewportHeightPx - with(density) { fadeBottom.toPx() }
            }
        val targetBlurTopEndFraction =
            (topBlurEndPx / viewportHeightPx.coerceAtLeast(1f)).coerceIn(0f, 0.5f)
        val targetBlurBottomStartFraction =
            (bottomBlurStartPx / viewportHeightPx.coerceAtLeast(1f)).coerceIn(0.5f, 1f)
        val blurTopEndFraction by animateFloatAsState(
            targetValue = targetBlurTopEndFraction,
            animationSpec = tween(durationMillis = 350),
            label = "lyricsBlurTopEnd",
        )
        val blurBottomStartFraction by animateFloatAsState(
            targetValue = targetBlurBottomStartFraction,
            animationSpec = tween(durationMillis = 350),
            label = "lyricsBlurBottomStart",
        )
        val keepAliveZoneDp =
            (
                LYRICS_KEEP_ALIVE_ZONE_BASE_DP *
                    ((Settings.shared.lyricsTextSize * textSizeMultiplier) / 36f).coerceAtLeast(1f)
            ).coerceAtMost(with(density) { constraints.maxHeight.toDp() })
        val keepAliveZonePx = with(density) { keepAliveZoneDp.roundToPx() }

        val topPaddingDp = with(density) { anchorY.toDp() } + keepAliveZoneDp
        val bottomPaddingDp = with(density) { (viewportHeightPx - anchorY).toDp() } + keepAliveZoneDp
        val anchorPx = anchorY.toInt()

        val gapPx = with(density) { LYRICS_ITEM_GAP_DP.roundToPx() }
        val latestAnchorPx by rememberUpdatedState(anchorPx)
        val latestKeepAliveZonePx by rememberUpdatedState(keepAliveZonePx)
        val latestViewportHeightPx by rememberUpdatedState(viewportHeightPx)
        val latestGapPx by rememberUpdatedState(gapPx)

        LaunchedEffect(
            songId,
            lyrics,
            effectiveOffsetMs,
            isAutoScrollEnabled,
            isSelectionMode,
            isVisible,
        ) {
            if (!isVisible) return@LaunchedEffect
            var lastTargetIndex = -1
            var lastSettledTarget: LyricsScrollTarget? = null
            var lastViewportH = -1
            var lastViewportW = -1
            var lastAnchorPx = -1
            var lastKeepAlivePx = -1
            var lastPositionMs = lyricsPositionState.value
            var lastMark = TimeSource.Monotonic.markNow()

            fun blockScrollOffset(
                items: List<LazyListItemInfo>,
                target: LyricsScrollTarget,
                targetIndex: Int,
            ): Int? {
                var runStart = targetIndex
                var runEnd = targetIndex
                val activeLines = target.activeIndices
                val lineListIndices = lyricsItemIndex.lineListIndices
                val highlightSet = HashSet<Int>(maxOf(4, activeLines.size * 2))
                for (i in activeLines.indices) {
                    val li = lineListIndices.getOrElse(activeLines[i]) { -1 }
                    if (li >= 0) highlightSet.add(li)
                }
                if (activeLines.size > 1 && targetIndex in highlightSet) {
                    while ((runStart - 1) in highlightSet) runStart--
                    while ((runEnd + 1) in highlightSet) runEnd++
                }
                var runCount = 0
                var top = Int.MAX_VALUE
                var bottom = Int.MIN_VALUE
                var targetEffTop = 0
                var targetEffSize = 0
                var hasTarget = false
                var newestIndex = Int.MIN_VALUE
                var newestEffTop = 0
                var newestEffSize = 0
                var cumulativeBgDelta = 0
                for (i in items.indices) {
                    val item = items[i]
                    val shouldExpandBg = isSelectionMode || item.index in highlightSet
                    val bgDelta = scrollShifter.pendingBgDelta(item.index, shouldExpandBg)
                    val effTop = item.offset + cumulativeBgDelta
                    val effSize = (item.size + bgDelta).coerceAtLeast(0)
                    cumulativeBgDelta += bgDelta

                    if (item.index == targetIndex) {
                        hasTarget = true
                        targetEffTop = effTop
                        targetEffSize = effSize
                    }
                    if (item.index in runStart..runEnd) {
                        runCount++
                        if (effTop < top) top = effTop
                        if (effTop + effSize > bottom) bottom = effTop + effSize
                        if (item.index > newestIndex) {
                            newestIndex = item.index
                            newestEffTop = effTop
                            newestEffSize = effSize
                        }
                    }
                }
                val runBottom = bottom - latestGapPx
                val fits =
                    runCount > 0 && runCount == runEnd - runStart + 1 && runBottom - top <= latestViewportHeightPx
                val center =
                    if (fits) {
                        (top + runBottom) / 2
                    } else if (newestIndex != Int.MIN_VALUE) {
                        newestEffTop + (newestEffSize - latestGapPx) / 2
                    } else if (hasTarget) {
                        targetEffTop + (targetEffSize - latestGapPx) / 2
                    } else {
                        return null
                    }
                return center - (lazyListState.layoutInfo.viewportStartOffset + latestKeepAliveZonePx + latestAnchorPx)
            }

            launch {
                snapshotFlow {
                    if (isSelectionMode || !isAutoScrollEnabled || isManualScrolling.value) return@snapshotFlow null
                    val info = lazyListState.layoutInfo
                    ReflowSnapshot(
                        scrollTargetState.value,
                        info.visibleItemsInfo.sumOf { it.size },
                        info.viewportSize.height,
                        scrollShifter.reflowSignal,
                    )
                }.collectLatest { snap ->
                    if (snap == null) return@collectLatest
                    if (scrollInCode.value) snapshotFlow { scrollInCode.value }.first { !it }
                    val layoutInfo = lazyListState.layoutInfo
                    val viewportResizing =
                        layoutInfo.viewportSize.height != lastViewportH ||
                            layoutInfo.viewportSize.width != lastViewportW ||
                            latestAnchorPx != lastAnchorPx ||
                            latestKeepAliveZonePx != lastKeepAlivePx
                    val target = scrollTargetState.value
                    val targetIndex = target.playbackListIndex
                    if (viewportResizing ||
                        targetIndex < 0 ||
                        targetIndex != lastTargetIndex ||
                        target.activeIndices != lastSettledTarget?.activeIndices ||
                        scrollShifter.isStaging ||
                        isDragged.value ||
                        isManualScrolling.value
                    ) {
                        return@collectLatest
                    }
                    val items = layoutInfo.visibleItemsInfo
                    val offset = blockScrollOffset(items, target, targetIndex) ?: return@collectLatest
                    if (abs(offset) <= 0.5f) return@collectLatest
                    activeProgrammaticScrolls.intValue++
                    scrollShifter.isReflowing = true
                    try {
                        if (!scrollShifter.isRunning && !scrollShifter.isSuppressed) {
                            val scrollingDown = offset >= 0
                            val durationMs = target.scrollDurationMs
                            val zeta = resolveDynamicSpringZeta(target.scrollGapT)
                            scrollShifter.arm(
                                LyricsScrollStagger(
                                    scrollingDown = scrollingDown,
                                    referenceIndex = targetIndex,
                                    staggerAfterIndex = maxOf(targetIndex, target.lastActiveGroupEndListIndex),
                                    durationMs = durationMs,
                                    delayIncrementMs = 0f,
                                    linesBefore = latestLinesBefore,
                                    zeta = zeta,
                                ),
                            )
                        }
                        lazyListState.scrollBy(offset.toFloat())
                        scrollShifter.commitStaged()
                    } finally {
                        scrollShifter.isReflowing = false
                        activeProgrammaticScrolls.intValue = (activeProgrammaticScrolls.intValue - 1).coerceAtLeast(0)
                    }
                }
            }

            snapshotFlow {
                if (isSelectionMode || !isAutoScrollEnabled || isManualScrolling.value) return@snapshotFlow null
                val target = scrollTargetState.value
                val firstVisible = lazyListState.firstVisibleItemIndex
                val firstVisibleOffset = lazyListState.firstVisibleItemScrollOffset
                val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                val viewportWidth = lazyListState.layoutInfo.viewportSize.width
                val count = lazyListState.layoutInfo.visibleItemsInfo.size
                TargetScrollSnapshot(
                    target,
                    firstVisible,
                    firstVisibleOffset,
                    viewportHeight,
                    viewportWidth,
                    count,
                    latestAnchorPx,
                    latestKeepAliveZonePx,
                )
            }.collect { snapshot ->
                if (isSelectionMode || snapshot == null || !isAutoScrollEnabled || isManualScrolling.value) return@collect
                var target = snapshot.target
                var targetIndex = target.playbackListIndex
                if (targetIndex < 0 || lyrics.isEmpty()) return@collect
                val viewportChanged =
                    snapshot.viewportHeight != lastViewportH ||
                        snapshot.viewportWidth != lastViewportW ||
                        snapshot.anchorPx != lastAnchorPx ||
                        snapshot.keepAlivePx != lastKeepAlivePx
                val targetPositionChanged =
                    targetIndex != lastTargetIndex ||
                        target.activeIndices != lastSettledTarget?.activeIndices
                if (!targetPositionChanged && !viewportChanged && !isFirstPass) {
                    if (target.isSeek) {
                        val residual = blockScrollOffset(lazyListState.layoutInfo.visibleItemsInfo, target, targetIndex)
                        if (residual == null || abs(residual) <= 1) {
                            lastSettledTarget = target
                            return@collect
                        }
                    } else {
                        return@collect
                    }
                }
                val targetChanged = target != lastSettledTarget
                val resizeOnly = viewportChanged && !targetPositionChanged && !targetChanged && !isFirstPass
                if (!scrollInCode.value) {
                    try {
                        activeProgrammaticScrolls.intValue++

                        val initialItems = lazyListState.layoutInfo.visibleItemsInfo
                        val initiallyMeasured = initialItems.any { it.index == targetIndex }
                        val preStagingOffsetPx =
                            if (initiallyMeasured) {
                                blockScrollOffset(initialItems, target, targetIndex)?.toFloat()
                            } else {
                                null
                            }

                        if ((targetPositionChanged || targetChanged) && !isFirstPass && initiallyMeasured) {
                            scrollShifter.clearSuppression()
                            scrollShifter.beginStaging()
                            repeat(2) { withFrameNanos { } }
                            while (
                                scrollTargetState.value.playbackListIndex != target.playbackListIndex ||
                                scrollTargetState.value.activeIndices != target.activeIndices
                            ) {
                                target = scrollTargetState.value
                                targetIndex = target.playbackListIndex
                                repeat(2) { withFrameNanos { } }
                            }
                            target = scrollTargetState.value
                            targetIndex = target.playbackListIndex
                            lastSettledTarget = target
                            if (isManualScrolling.value || targetIndex < 0) {
                                scrollShifter.cancelStaging()
                                return@collect
                            }
                        } else if (targetChanged) {
                            lastSettledTarget = target
                        }

                        lastViewportH = lazyListState.layoutInfo.viewportSize.height
                        lastViewportW = lazyListState.layoutInfo.viewportSize.width
                        lastAnchorPx = latestAnchorPx
                        lastKeepAlivePx = latestKeepAliveZonePx

                        var items = lazyListState.layoutInfo.visibleItemsInfo
                        val wasUnmeasured = items.none { it.index == targetIndex }

                        val positionMs = lyricsPositionState.value
                        val positionDelta = positionMs - lastPositionMs
                        val elapsedMs = lastMark.elapsedNow().inWholeMilliseconds
                        val positionJumped =
                            target.isSeek ||
                                (abs(positionDelta) > 1000L && abs(positionDelta - elapsedMs * Settings.shared.playbackSpeed) > 1000L)
                        lastPositionMs = positionMs
                        lastMark = TimeSource.Monotonic.markNow()

                        if (wasUnmeasured) {
                            scrollShifter.cancelStaging()
                            scrollShifter.suppress(0L)
                            lazyListState.scrollToItem(targetIndex, 0)
                            repeat(2) { withFrameNanos { } }
                            items = lazyListState.layoutInfo.visibleItemsInfo
                            val centerOffset = blockScrollOffset(items, target, targetIndex) ?: 0
                            if (isFirstPass || items.isEmpty()) {
                                if (abs(centerOffset) > 0) {
                                    lazyListState.scrollBy(centerOffset.toFloat())
                                }
                            } else if (isAutoScrollEnabled && !isDragged.value) {
                                val leadPx =
                                    (if (targetIndex >= snapshot.firstVisibleIndex) 1f else -1f) *
                                        (latestViewportHeightPx * 0.35f)
                                lazyListState.scrollBy(centerOffset.toFloat() - leadPx)
                                scrollShifter.suppress(LYRICS_FORCED_SCROLL_MS.toLong())
                                withContext(FixedMotionDurationScale) {
                                    lazyListState.animateScrollBy(
                                        leadPx,
                                        tween(LYRICS_FORCED_SCROLL_MS, easing = LyPlusScrollEasing),
                                    )
                                }
                            }
                            items = lazyListState.layoutInfo.visibleItemsInfo
                        } else {
                            val scrollOffset = blockScrollOffset(items, target, targetIndex)
                            if (isAutoScrollEnabled && !isDragged.value && scrollOffset != null) {
                                val offsetPx = scrollOffset.toFloat()
                                when {
                                    isFirstPass -> {
                                        scrollShifter.cancelStaging()
                                        scrollShifter.suppress(0L)
                                        if (abs(scrollOffset) > 1) {
                                            lazyListState.scrollBy(offsetPx)
                                        }
                                    }

                                    resizeOnly -> {
                                        scrollShifter.clearSuppression()
                                        val durationMs = target.scrollDurationMs
                                        val stagger =
                                            LyricsScrollStagger(
                                                scrollingDown = offsetPx >= 0f,
                                                referenceIndex = targetIndex,
                                                staggerAfterIndex = maxOf(targetIndex, target.lastActiveGroupEndListIndex),
                                                durationMs = durationMs,
                                                delayIncrementMs = 0f,
                                                linesBefore = latestLinesBefore,
                                                zeta = SPRING_ZETA_DEFAULT,
                                            )
                                        scrollShifter.arm(stagger)
                                        if (abs(scrollOffset) > 1) {
                                            lazyListState.scrollBy(offsetPx)
                                        }
                                        scrollShifter.commitStaged()
                                    }

                                    abs(scrollOffset) > 1 || scrollShifter.hasStagedOffsets -> {
                                        scrollShifter.clearSuppression()
                                        val durationMs = if (target.isSeek) 380L else target.scrollDurationMs
                                        val zeta =
                                            if (positionJumped || abs(offsetPx) > latestViewportHeightPx * 0.5f) {
                                                SPRING_ZETA_TIGHT_GAP
                                            } else {
                                                resolveDynamicSpringZeta(target.scrollGapT)
                                            }
                                        val previousIndex =
                                            if (lastTargetIndex >= 0 && items.any { it.index == lastTargetIndex }) {
                                                lastTargetIndex
                                            } else {
                                                targetIndex
                                            }
                                        val scrollingDown =
                                            when {
                                                targetIndex > previousIndex -> true
                                                targetIndex < previousIndex -> false
                                                preStagingOffsetPx != null && abs(preStagingOffsetPx) > 1f -> preStagingOffsetPx >= 0f
                                                else -> offsetPx >= 0f
                                            }
                                        val visibleTopPx =
                                            lazyListState.layoutInfo.viewportStartOffset + latestKeepAliveZonePx
                                        val visibleBottomPx = visibleTopPx + latestViewportHeightPx.toInt()
                                        val firstVisibleLineIndex =
                                            items.firstOrNull { it.offset + it.size > visibleTopPx }?.index
                                                ?: snapshot.firstVisibleIndex
                                        val lastVisibleLineIndex =
                                            items.lastOrNull { it.offset < visibleBottomPx }?.index
                                                ?: items.lastOrNull()?.index
                                                ?: targetIndex
                                        val boundedFirstVisibleLineIndex =
                                            maxOf(firstVisibleLineIndex, targetIndex - MAX_PRE_CENTER_STAGGER_LINES)
                                        val boundedLastVisibleLineIndex =
                                            minOf(lastVisibleLineIndex, targetIndex + MAX_PRE_CENTER_STAGGER_LINES)
                                        val staggerReferenceIndex =
                                            if (target.isSeek) {
                                                targetIndex
                                            } else if (scrollingDown) {
                                                boundedFirstVisibleLineIndex
                                            } else {
                                                boundedLastVisibleLineIndex
                                            }
                                        val staggerAfterIndex =
                                            if (target.isSeek) {
                                                maxOf(targetIndex, target.lastActiveGroupEndListIndex)
                                            } else {
                                                boundedFirstVisibleLineIndex
                                            }
                                        val stagger =
                                            LyricsScrollStagger(
                                                scrollingDown = scrollingDown,
                                                referenceIndex = staggerReferenceIndex,
                                                staggerAfterIndex = staggerAfterIndex,
                                                durationMs = durationMs,
                                                delayIncrementMs = calculateScrollDelayIncrement(durationMs),
                                                linesBefore = latestLinesBefore,
                                                zeta = zeta,
                                                maxSteps = if (target.isSeek) 6 else 12,
                                            )
                                        scrollShifter.arm(stagger)
                                        if (abs(scrollOffset) > 0) {
                                            lazyListState.scrollBy(offsetPx)
                                        }
                                        scrollShifter.commitStaged()
                                    }

                                    else -> {
                                        scrollShifter.cancelStaging()
                                    }
                                }
                            } else {
                                scrollShifter.cancelStaging()
                            }
                        }

                        if (items.isNotEmpty() && lazyListState.layoutInfo.viewportSize.height > 0) {
                            if (isFirstPass && items.any { it.index == targetIndex }) {
                                repeat(2) { withFrameNanos { } }
                                val postFirstPassItems = lazyListState.layoutInfo.visibleItemsInfo
                                val postOffset = blockScrollOffset(postFirstPassItems, target, targetIndex)
                                if (postOffset != null && abs(postOffset) > 1) {
                                    scrollShifter.suppress(0L)
                                    lazyListState.scrollBy(postOffset.toFloat())
                                }
                            }
                            isFirstPass = false
                            lastTargetIndex = targetIndex
                        }
                    } catch (e: CancellationException) {
                        scrollShifter.cancelStaging()
                        throw e
                    } catch (_: Exception) {
                        scrollShifter.cancelStaging()
                    } finally {
                        activeProgrammaticScrolls.intValue = (activeProgrammaticScrolls.intValue - 1).coerceAtLeast(0)
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .lyricsTextSizePinch(!isSelectionMode && lyrics.isNotEmpty()),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading && lyrics.isEmpty() -> {
                    val lineHeight = with(density) { (Settings.shared.lyricsTextSize * textSizeMultiplier).sp.toDp() }
                    LyricsLoadingPlaceholder(
                        textColor = textColor,
                        textAlign = providerTextAlign,
                        lineHeight = lineHeight,
                        anchorTop = (with(density) { anchorY.toDp() } - lineHeight / 2).coerceAtLeast(0.dp),
                    )
                }

                lyrics.isEmpty() -> {
                    Text(
                        text = noLyricsText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = secondaryTextColor,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                else -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .expressiveEntrance()
                                .fadingEdge(
                                    top = fadeTop,
                                    bottom = fadeBottom,
                                    blendMode = if (isPlusLighter) BlendMode.Plus else BlendMode.SrcOver,
                                ).artworkVerticalProgressiveBlur(
                                    topStartFraction = 0f,
                                    topEndFraction = blurTopEndFraction,
                                    bottomStartFraction = blurBottomStartFraction,
                                    bottomEndFraction = 1f,
                                    maxRadius = LYRICS_PROGRESSIVE_BLUR_MAX_DP.dp,
                                    enabled =
                                        Settings.shared.lyricsProgressiveBlur &&
                                            isAutoScrollEnabled &&
                                            !isManualScrolling.value &&
                                            !isSelectionMode,
                                ),
                    ) {
                        if (!isLrcSynced) {
                            val plainScrollState = rememberLazyListState()
                            var lastHandledUnsyncedKey by rememberSaveable { mutableStateOf<String?>(null) }
                            LaunchedEffect(currentSongKey, lyrics) {
                                if (currentSongKey != null && lastHandledUnsyncedKey != currentSongKey) {
                                    plainScrollState.scrollToItem(0)
                                    lastHandledUnsyncedKey = currentSongKey
                                }
                            }
                            DragScrollableColumn(
                                state = plainScrollState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = fadeTop, bottom = fadeBottom),
                            ) {
                                itemsIndexed(lyrics, key = { index, _ -> index }) { index, entry ->
                                    Text(
                                        text = entry.text,
                                        color = textColor,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (onShareSelection != null && entry.text.isNotBlank()) {
                                                        Modifier
                                                            .combinedClickable(
                                                                onClick = { if (isSelectionMode) selection.toggle(index) },
                                                                onLongClick = { selection.select(index) },
                                                            ).semantics { selected = index in selectedIndices }
                                                    } else {
                                                        Modifier
                                                    },
                                                ).background(
                                                    if (index in selectedIndices) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                ).lyricsTranslationShimmer(
                                                    translationShimmer.takeIf { index in translationProgress.lines },
                                                    shimmerColor,
                                                ),
                                        textAlign = providerTextAlign,
                                    )
                                }
                                if (!provider.isNullOrEmpty() || validSongwriters.isNotEmpty()) {
                                    item(key = "provider") {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 16.dp),
                                        ) {
                                            if (validSongwriters.isNotEmpty()) {
                                                Text(
                                                    text = formatWrittenByText(validSongwriters, lyricsWrittenBy),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = secondaryTextColor,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textAlign = providerTextAlign,
                                                )
                                            }
                                            if (!provider.isNullOrEmpty()) {
                                                Text(
                                                    text = lyricsProvidedBy(provider),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = secondaryTextColor,
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = if (validSongwriters.isNotEmpty()) 4.dp else 0.dp),
                                                    textAlign = providerTextAlign,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            DragScrollableColumn(
                                state = lazyListState,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(manualScrollConnection)
                                        .layout { measurable, constraints ->
                                            val requestedHeight =
                                                (constraints.maxHeight.toLong() + keepAliveZonePx.toLong() * 2)
                                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                                    .toInt()
                                            val placeable =
                                                measurable.measure(
                                                    Constraints.fitPrioritizingWidth(
                                                        constraints.minWidth,
                                                        constraints.maxWidth,
                                                        constraints.minHeight,
                                                        requestedHeight,
                                                    ),
                                                )
                                            layout(constraints.maxWidth, constraints.maxHeight) {
                                                placeable.place(
                                                    0,
                                                    -minOf(keepAliveZonePx, (placeable.height - constraints.maxHeight) / 2),
                                                )
                                            }
                                        },
                                contentPadding =
                                    PaddingValues(
                                        top = topPaddingDp,
                                        bottom = bottomPaddingDp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                itemsIndexed(
                                    items = processedItems,
                                    key = { index, _ -> itemKeys[index] },
                                ) { index, listItem ->
                                    val itemKey = itemKeys[index]
                                    val itemAlpha =
                                        remember(itemKey) {
                                            Animatable(
                                                Snapshot.withoutReadObservation {
                                                    if (isSelectionMode || isManualScrolling.value || !isAutoScrollEnabled) 1f else 0f
                                                },
                                            )
                                        }
                                    LaunchedEffect(itemKey, isManualScrolling, isSelectionMode) {
                                        animateLyricsItemAppearance(
                                            itemAlpha,
                                            snapshotFlow { isSelectionMode || isManualScrolling.value || !isAutoScrollEnabled },
                                        )
                                    }
                                    val flip = remember(itemKey) { LyricsItemFlip() }
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .onPlaced { coordinates ->
                                                    val itemTop = coordinates.positionInParent().y - latestKeepAliveZonePx
                                                    if (!isSelectionMode && (isManualScrolling.value || !isAutoScrollEnabled)) {
                                                        scrollShifter.onPlacedWhileScrolling(
                                                            flip = flip,
                                                            index = index,
                                                            top = itemTop,
                                                        )
                                                    } else {
                                                        scrollShifter.onPlaced(
                                                            flip = flip,
                                                            index = index,
                                                            top = itemTop,
                                                            enabled = !isSelectionMode && isAutoScrollEnabled && !isManualScrolling.value,
                                                        )
                                                    }
                                                }.graphicsLayer {
                                                    val isActiveLine =
                                                        listItem is LyricsListItem.Line && listItem.index in highlightedLineIndices
                                                    alpha = if (isSelectionMode || isActiveLine) 1f else itemAlpha.value
                                                    translationY =
                                                        if (isSelectionMode) {
                                                            0f
                                                        } else {
                                                            scrollShifter.translationFor(flip)
                                                        }
                                                },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        when (listItem) {
                                            is LyricsListItem.Indicator -> {
                                                val inGap by remember(listItem, lyricsPositionState) {
                                                    derivedStateOf {
                                                        (lyricsPositionState.value + HIGHLIGHT_LOOK_AHEAD_MS) in
                                                            listItem.gapStartMs..listItem.gapEndMs
                                                    }
                                                }
                                                val isScrollTarget by remember(index, scrollTargetState) {
                                                    derivedStateOf { scrollTargetState.value.playbackListIndex == index }
                                                }
                                                val animateGapProgress by remember(listItem, lyricsPositionState) {
                                                    derivedStateOf {
                                                        lyricsPositionState.value >= listItem.gapStartMs - HIGHLIGHT_LOOK_AHEAD_MS &&
                                                            lyricsPositionState.value < listItem.gapEndMs
                                                    }
                                                }
                                                val gapPositionState =
                                                    if (!isWordByWord && animateGapProgress && !isSelectionMode) {
                                                        val smoothGapPosition =
                                                            rememberSmoothLyricsPositionState(
                                                                positionFlow = positionFlow,
                                                                playbackSpeed = playbackSpeed,
                                                                positionReader = positionReader,
                                                                isPlaying = isPlaying,
                                                                songId = currentSongKey,
                                                                isCasting = isCasting,
                                                                enabled = clockEnabled,
                                                            )
                                                        remember(smoothGapPosition, effectiveOffsetMs) {
                                                            derivedStateOf {
                                                                lyricsPositionForOffset(smoothGapPosition.longValue, effectiveOffsetMs)
                                                            }
                                                        }
                                                    } else {
                                                        lyricsPositionState
                                                    }
                                                IntervalIndicator(
                                                    gapStartMs = listItem.gapStartMs,
                                                    gapEndMs = listItem.gapEndMs,
                                                    currentPositionMs = {
                                                        gapPositionState.value.coerceIn(
                                                            listItem.gapStartMs,
                                                            listItem.gapEndMs,
                                                        )
                                                    },
                                                    visible = !isSelectionMode && inGap,
                                                    surfaceVisible = isVisible,
                                                    isPlaying = isPlaying,
                                                    animateClose = !isSelectionMode,
                                                    holdOpen = shouldFollowPlayback && isScrollTarget,
                                                    delayedClose = shouldFollowPlayback,
                                                    onLayoutShift = { delta ->
                                                        if (isManualScrolling.value || !isAutoScrollEnabled) {
                                                            scrollShifter.recordLayoutShift(index, delta)
                                                        }
                                                    },
                                                    color = textColor,
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .wrapContentWidth(Alignment.CenterHorizontally),
                                                )
                                            }

                                            is LyricsListItem.Line -> {
                                                val entry = listItem.entry
                                                val idx = listItem.index
                                                val isActive = highlightedLineIndices.contains(idx)
                                                val signedDistance =
                                                    if (highlightedIndicesList.isNotEmpty()) {
                                                        when {
                                                            idx < highlightedIndicesList.first() -> idx - highlightedIndicesList.first()
                                                            idx > highlightedIndicesList.last() -> idx - highlightedIndicesList.last()
                                                            else -> 0
                                                        }
                                                    } else if (predictiveLineIndex >= 0) {
                                                        idx - predictiveLineIndex
                                                    } else {
                                                        idx - lastActiveLineIndex
                                                    }
                                                val lineDistance = abs(signedDistance)
                                                val staggerDelayMs =
                                                    (lineDistance * delayIncrementMs).toLong().coerceAtMost(200L)
                                                val adjustedEndTimeMs =
                                                    lineEndTimesByIndex.getOrNull(idx)?.takeIf { it >= 0L }
                                                val isInteractive =
                                                    (!isSelectionMode && Settings.shared.lyricsClickToSeek) ||
                                                        (entry.text.isNotBlank() && (isSelectionMode || onShareSelection != null))

                                                val seekLine =
                                                    remember(
                                                        isSelectionMode,
                                                        idx,
                                                        entry.time,
                                                        entry.text,
                                                        effectiveOffsetMs,
                                                        timingOffsetMs,
                                                        globalOffsetMs,
                                                        lyricsRetimed,
                                                        provider,
                                                        index,
                                                        onSeek,
                                                        onToggleSelection,
                                                        onShareSelection,
                                                    ) {
                                                        {
                                                            if (isSelectionMode) {
                                                                if (entry.text.isNotBlank()) onToggleSelection(idx)
                                                            } else if (Settings.shared.lyricsClickToSeek) {
                                                                pendingSeekLineIndex = idx
                                                                if (Settings.shared.lyricsAutoScroll) {
                                                                    isAutoScrollEnabled = true
                                                                }
                                                                onSeek(
                                                                    lyricsSeekPositionForOffset(
                                                                        entry.time,
                                                                        effectiveOffsetMs,
                                                                    ),
                                                                    PlaybackSeekSource(
                                                                        "lyrics_tap",
                                                                        mapOf(
                                                                            "lineIndex" to idx.toString(),
                                                                            "listIndex" to index.toString(),
                                                                            "lineStartMs" to entry.time.toString(),
                                                                            "offsetMs" to effectiveOffsetMs.toString(),
                                                                            "songOffsetMs" to timingOffsetMs.toString(),
                                                                            "globalOffsetMs" to globalOffsetMs.toString(),
                                                                            "lyricsRetimed" to lyricsRetimed.toString(),
                                                                            "lyricsProvider" to provider.orEmpty(),
                                                                        ),
                                                                    ),
                                                                )
                                                            } else if (onShareSelection != null) {
                                                                selection.select(idx)
                                                            }
                                                        }
                                                    }

                                                Box(
                                                    modifier = Modifier.fillMaxWidth().padding(bottom = LYRICS_ITEM_GAP_DP),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CompositionLocalProvider(LocalLyricsAnimationVisible provides isVisible) {
                                                        InlineLyricsLine(
                                                            entry = entry,
                                                            isActive = isActive,
                                                            signedDistance = signedDistance,
                                                            distance = lineDistance,
                                                            staggerDelayMs = staggerDelayMs,
                                                            currentPosition = stablePositionProvider,
                                                            adjustedEndTimeMs = adjustedEndTimeMs,
                                                            isAutoScrollEnabled = isAutoScrollEnabled,
                                                            isLrcSynced = true,
                                                            onSeek = seekLine,
                                                            onLongClick =
                                                                if (onShareSelection != null &&
                                                                    entry.text.isNotBlank()
                                                                ) {
                                                                    ({ selection.select(idx) })
                                                                } else {
                                                                    null
                                                                },
                                                            isInteractive = isInteractive,
                                                            isSelected = selectedIndices.contains(idx),
                                                            isSelectionMode = isSelectionMode,
                                                            alignment = lineAlignments[idx] ?: TextAlign.Start,
                                                            hasMultipleSingers = hasMultipleSingers,
                                                            textSizeMultiplier = textSizeMultiplier,
                                                            textColor = textColor,
                                                            textMeasurer = textMeasurer,
                                                            isPlaying = isPlaying && isVisible,
                                                            flipShifter = scrollShifter,
                                                            flipIndex = index,
                                                            isPlusLighter = isPlusLighter,
                                                            translationShimmer =
                                                                translationShimmer.takeIf {
                                                                    idx in
                                                                        translationProgress.lines
                                                                },
                                                            backgroundTranslationShimmer =
                                                                translationShimmer.takeIf {
                                                                    idx in
                                                                        translationProgress.backgroundLines
                                                                },
                                                        )
                                                    }
                                                }
                                            }

                                            is LyricsListItem.Provider -> {
                                                Column(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp)
                                                            .padding(top = 8.dp, bottom = 32.dp),
                                                ) {
                                                    if (listItem.songwriters.isNotEmpty()) {
                                                        Text(
                                                            text =
                                                                formatWrittenByText(
                                                                    listItem.songwriters,
                                                                    lyricsWrittenBy,
                                                                ),
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = secondaryTextColor,
                                                            modifier = Modifier.fillMaxWidth(),
                                                            textAlign = providerTextAlign,
                                                        )
                                                    }
                                                    if (lyricsAttribution != null) {
                                                        Text(
                                                            text = lyricsAttribution,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = secondaryTextColor,
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(top = if (listItem.songwriters.isNotEmpty()) 4.dp else 0.dp),
                                                            textAlign = providerTextAlign,
                                                        )
                                                    } else if (!listItem.name.isNullOrEmpty()) {
                                                        val providerText = lyricsProvidedBy(listItem.name)
                                                        val providerStart = providerText.lastIndexOf(listItem.name)
                                                        val annotatedProviderText =
                                                            buildAnnotatedString {
                                                                append(providerText)
                                                                if (providerStart >= 0 && providerUrl != null) {
                                                                    addLink(
                                                                        LinkAnnotation.Url(providerUrl, TextLinkStyles()),
                                                                        providerStart,
                                                                        providerStart + listItem.name.length,
                                                                    )
                                                                }
                                                            }
                                                        Text(
                                                            text = annotatedProviderText,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = secondaryTextColor,
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(top = if (listItem.songwriters.isNotEmpty()) 4.dp else 0.dp),
                                                            textAlign = providerTextAlign,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isLoading && lyrics.isNotEmpty()) {
            ContainedLoadingIndicator(Modifier.align(Alignment.TopCenter).padding(16.dp).size(32.dp))
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (selection.limitReached) {
                        Text(
                            stringResource(Res.string.lyrics_share_limit, LYRICS_SHARE_LINE_LIMIT),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                Res.string.lyrics_share_selected_count,
                                selectedIndices.size,
                                LYRICS_SHARE_LINE_LIMIT,
                            ),
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        TextButton(onClick = selection::clear, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                        TextButton(
                            onClick = {
                                onShareSelection?.invoke(
                                    selectedLyricsText(
                                        lyrics,
                                        selectedIndices,
                                    ),
                                )
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(Res.string.share))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isAutoScrollEnabled && isLrcSynced && !isSelectionMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    pendingSeekLineIndex = null
                    isAutoScrollEnabled = true
                },
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(24.dp)),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = autoScrollText,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun formatWrittenByText(
    writers: List<String>,
    lyricsWrittenBy: ((List<String>) -> String)?,
): AnnotatedString {
    val writersText = writers.joinToString(", ")
    if (lyricsWrittenBy != null) {
        val custom = lyricsWrittenBy(writers)
        val colonIndex = custom.indexOf(':')
        return if (colonIndex >= 0 && colonIndex + 1 < custom.length) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(custom.substring(0, colonIndex + 1))
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                    append(custom.substring(colonIndex + 1))
                }
            }
        } else {
            AnnotatedString(custom)
        }
    }
    val placeholder = "\u0000"
    val template = stringResource(Res.string.lyrics_written_by, placeholder)
    val placeholderIndex = template.indexOf(placeholder)
    return if (placeholderIndex >= 0) {
        buildAnnotatedString {
            val prefix = template.substring(0, placeholderIndex)
            if (prefix.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(prefix)
                }
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                append(writersText)
            }
            val suffix = template.substring(placeholderIndex + placeholder.length)
            if (suffix.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(suffix)
                }
            }
        }
    } else {
        AnnotatedString(template)
    }
}

private fun Modifier.lyricsTextSizePinch(enabled: Boolean): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var textSize = Settings.shared.lyricsTextSize
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    pinching = true
                    textSize = zoomedLyricsTextSize(textSize, event.calculateZoom())
                    val rounded = textSize.roundToInt().toFloat()
                    if (rounded != Settings.shared.lyricsTextSize) Settings.shared.lyricsTextSize = rounded
                }
                if (pinching) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
