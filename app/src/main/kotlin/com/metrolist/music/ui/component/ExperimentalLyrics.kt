/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import android.text.Layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AiSystemPromptKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DeeplFormalityKey
import com.metrolist.music.constants.LyricsClickKey
import com.metrolist.music.constants.LyricsRomanizeAsMainKey
import com.metrolist.music.constants.LyricsRomanizeCyrillicByLineKey
import com.metrolist.music.constants.LyricsRomanizeList
import com.metrolist.music.constants.LyricsTextPositionKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import com.metrolist.music.constants.OpenRouterDefaultModel
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.RespectAgentPositioningKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsResyncHelper
import com.metrolist.music.lyrics.LyricsTranslationHelper
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.screens.settings.defaultList
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.utils.ComposeToImage
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LyricsViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LYRICS_ANCHOR_RATIO = 0.35f
// A jump landing within this of the tapped line counts as the tap landing
// (covers ExoPlayer keyframe quantization); anything else is a scrub.
private const val TAP_TARGET_TOLERANCE_MS = 3000L
private const val LINE_CHANGE_ANIMATION_MS = 350
// After a scrub jump, index convergence within this window settles instantly
// instead of visibly gliding (tap jumps are exempt and keep gliding).
private const val SEEK_SETTLE_MS = 500L
// Brief redirect when the target moves far while a scroll is already in transit:
// smooth continuation instead of a murder-snap.
private const val REDIRECT_ANIMATION_MS = 200
private val LYRICS_ITEM_FALLBACK_HEIGHT_DP = 68.dp
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp

private sealed class ScrollCommand {
    data object Stop : ScrollCommand()
    data class Snap(val value: Float) : ScrollCommand()
    data class Animate(val value: Float, val durationMs: Int = 450) : ScrollCommand()
    data class Fling(val velocity: Float) : ScrollCommand()
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@SuppressLint("UnusedBoxWithConstraintsScope", "StringFormatInvalid")
@Composable
fun ExperimentalLyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    lyricsViewModel: LyricsViewModel = hiltViewModel()
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.CENTER)
    val changeLyrics by rememberPreference(LyricsClickKey, true)
    val romanizeLyricsList = rememberPreference(LyricsRomanizeList, "")
    val romanizeAsMain by rememberPreference(LyricsRomanizeAsMainKey, false)
    val romanizeCyrillicByLine by rememberPreference(LyricsRomanizeCyrillicByLineKey, false)
    val respectAgentPositioning by rememberPreference(RespectAgentPositioningKey, true)
    val showIntervalIndicator by rememberPreference(ShowIntervalIndicatorKey, true)
    
    // AI Translation Preferences
    val openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, OpenRouterDefaultBaseUrl)
    val openRouterModel by rememberPreference(OpenRouterModelKey, OpenRouterDefaultModel)
    val translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    val translateMode by rememberPreference(TranslateModeKey, "Literal")
    val deeplFormality by rememberPreference(DeeplFormalityKey, "default")
    val aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")
    
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val translationStatus by LyricsTranslationHelper.status.collectAsStateWithLifecycle()
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    var lastValidLyricsEntity by remember { mutableStateOf<com.metrolist.music.db.entities.LyricsEntity?>(null) }
    
    LaunchedEffect(currentLyricsEntity) {
        if (currentLyricsEntity != null) {
            lastValidLyricsEntity = currentLyricsEntity
        }
    }
    
    val lyricsEntity = remember(currentLyricsEntity, translationStatus) {
        if (currentLyricsEntity != null) {
            currentLyricsEntity
        } else if (translationStatus is LyricsTranslationHelper.TranslationStatus.Translating || translationStatus is LyricsTranslationHelper.TranslationStatus.Success) {
            lastValidLyricsEntity
        } else {
            null
        }
    }
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = remember(lyricsEntity) { lyricsEntity?.lyrics?.trim() }

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )

    val enabledLanguages = remember(romanizeLyricsList.value) {
        if (romanizeLyricsList.value.isEmpty()) {
            defaultList
        } else {
            romanizeLyricsList.value.split(",").map { entry ->
                val (lang, checked) = entry.split(":")
                Pair(lang, checked.toBoolean())
            }
        }.filter { it.second }.map { it.first }
    }

    val lines by lyricsViewModel.lines.collectAsStateWithLifecycle()
    val mergedLyricsList by lyricsViewModel.mergedLyricsList.collectAsStateWithLifecycle()

    LaunchedEffect(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator) {
        lyricsViewModel.processLyrics(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator)
    }

    val isSynced = remember(lyrics) { lyrics != null && com.metrolist.music.lyrics.LyricsUtils.isLineSynced(lyrics) }
    DisposableEffect(Unit) {
        LyricsTranslationHelper.setCompositionActive(true)
        onDispose {
            LyricsTranslationHelper.setCompositionActive(false)
            LyricsTranslationHelper.cancelTranslation()
        }
    }
    
    LaunchedEffect(lines, lyricsEntity, translateLanguage, translateMode) {
        if (lines.isNotEmpty() && lyricsEntity != null) {
            LyricsTranslationHelper.loadTranslationsFromDatabase(
                lyrics = lines,
                lyricsEntity = lyricsEntity,
                targetLanguage = translateLanguage,
                mode = translateMode
            )
        }
    }
    
    LaunchedEffect(
        showLyrics, 
        lines, 
        aiProvider, 
        openRouterApiKey, 
        deeplApiKey, 
        openRouterBaseUrl, 
        openRouterModel, 
        translateLanguage, 
        translateMode,
        deeplFormality,
        aiSystemPrompt,
        currentSong,
        database
    ) {
        LyricsTranslationHelper.manualTrigger.collectLatest {
            val effectiveApiKey = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
            if (showLyrics && lines.isNotEmpty() && effectiveApiKey.isNotBlank()) {
                LyricsTranslationHelper.translateLyrics(
                    lyrics = lines,
                    targetLanguage = translateLanguage,
                    apiKey = openRouterApiKey,
                    baseUrl = openRouterBaseUrl,
                    model = openRouterModel,
                    mode = translateMode,
                    scope = scope,
                    context = context,
                    provider = aiProvider,
                    deeplApiKey = deeplApiKey,
                    deeplFormality = deeplFormality,
                    useStreaming = true,
                    songId = currentSong?.id ?: "",
                    database = database,
                    systemPrompt = aiSystemPrompt,
                )
            } else if (effectiveApiKey.isBlank()) {
                Toast.makeText(context, context.getString(R.string.ai_api_key_required), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    LaunchedEffect(lines) {
        LyricsTranslationHelper.clearTranslationsTrigger.collectLatest {
            lines.forEach { it.translatedTextFlow.value = null }
        }
    }

    val expressiveAccent = when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.primary
        PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> Color.White
    }

    val currentPositionRef = remember {
        object {
            var position: Long = runCatching { playerConnection.player.currentPosition }.getOrDefault(0L)
        }
    }
    var activeLineIndices by remember(lines, currentSong?.id) {
        mutableStateOf(emptySet<Int>())
    }
    var visibleBackgroundLineIndices by remember(lines, currentSong?.id) {
        mutableStateOf(emptySet<Int>())
    }
    var activeIndicatorListIndex by remember(mergedLyricsList) {
        mutableStateOf<Int?>(null)
    }
    var showProgressDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    var isAutoScrollEnabled by rememberSaveable(isSynced) { mutableStateOf(isSynced) }
    val isLyricsProviderShown = lyricsEntity != null &&
        lyricsEntity.provider != "Unknown" &&
        lyricsEntity.provider != "Manual" &&
        !isSelectionModeActive

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    val maxSelectionLimit = 5
    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(context, context.getString(R.string.max_selection_limit, maxSelectionLimit), Toast.LENGTH_SHORT).show()
            showMaxSelectionToast = false
        }
    }

    val backgroundToMainMap = remember(lines) {
        val map = mutableMapOf<Int, Int>()
        for (i in lines.indices) {
            if (lines[i].isBackground) {
                val pairedMain = (i - 1 downTo 0).firstOrNull { !lines[it].isBackground } ?: -1
                if (pairedMain != -1) {
                    map[i] = pairedMain
                }
            }
        }
        map
    }

    // Seek generation counter: bumped by the frame loop whenever the playback position
    // jumps discontinuously (>2s between frames). NOTE: sliderPositionProvider() returns
    // the current position (non-null) in the lyrics-screen path, so provider nullness
    // must NOT be used as a seek signal.
    var positionEpoch by remember(lyrics) { mutableIntStateOf(0) }
    // Wall time of the last position discontinuity. Lets index convergence right
    // after a scrub settle instantly instead of visibly gliding there.
    var lastJumpTime by remember(lyrics) { mutableLongStateOf(0L) }
    // Pending tap-to-seek target (lyric timestamp). Set when a lyric line is tapped;
    // a position jump landing on it glides with a fixed duration, a jump anywhere
    // else (scrub) snaps instantly. Either/or, decided by position match — never by
    // timing. Cleared on consume or supersede; reset per lyrics.
    var pendingTapSeekMs by remember(lyrics) { mutableStateOf<Long?>(null) }
    // UNLIMITED (not CONFLATED) so a gesture Fling queued after drag Snaps can never be
    // overwritten in the buffer by a later auto/height Snap. Ordering drag -> fling is preserved.
    val scrollCommands = remember { Channel<ScrollCommand>(Channel.UNLIMITED) }
    var hasAutoPositioned by remember(lyrics) { mutableStateOf(false) }
    // Set by the frame loop after its first computation (even if the active set is
    // legitimately empty, e.g. mid-gap). Gates the very first snap so it goes
    // straight to the live spot instead of via the top.
    var loopWarmedUp by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(lyrics, lines, mergedLyricsList, backgroundToMainMap) {
        if (lyrics.isNullOrEmpty() || lines.isEmpty()) {
            activeLineIndices = emptySet()
            visibleBackgroundLineIndices = emptySet()
            activeIndicatorListIndex = null
            return@LaunchedEffect
        }
        
        var lastPlayerPos = runCatching { playerConnection.player.currentPosition }.getOrDefault(0L)
        var lastUpdateTime = System.currentTimeMillis()
        // Bridges micro-gaps between lines: when one line ends and the next starts
        // almost immediately, activeLineIndices is empty for a few frames, which would
        // make every line dim and then brighten again (flicker). Retain the previous
        // non-empty set briefly so fast transitions crossfade instead of dipping.
        var lastNonEmptyActive = emptySet<Int>()
        var lastNonEmptyTime = 0L
        val activeHoldMs = 700L
        var lastEffectivePosition = 0L
        
        while (isActive) {
            withFrameNanos { _ -> }
            val now = System.currentTimeMillis()
            val sliderPosition = sliderPositionProvider()
            
            val position = sliderPosition ?: run {
                val playerPos = playerConnection.player.currentPosition
                if (playerPos != lastPlayerPos) {
                    lastPlayerPos = playerPos
                    lastUpdateTime = now
                }
                val elapsed = now - lastUpdateTime
                lastPlayerPos + (if (playerConnection.player.isPlaying) elapsed else 0)
            }
            currentPositionRef.position = position
            val lyricsOffset = currentSong?.song?.lyricsOffset ?: 0
            val effectivePosition = position + lyricsOffset
            
            val newActiveIndices = if (isSynced) {
                val active = findActiveLineIndices(lines, effectivePosition).toMutableSet()
                for (i in active.toList()) {
                    val pairedMain = backgroundToMainMap[i]
                    if (pairedMain != null) {
                        active.add(pairedMain)
                    }
                }
                // A seek (or any large position jump) is a discontinuity: never hold the
                // pre-seek line, otherwise the view would first snap to the old line and
                // only correct itself ~700ms later. It also bumps positionEpoch so the
                // auto-scroll effect snaps exactly once to the new spot.
                val discontinuity = abs(effectivePosition - lastEffectivePosition) > 2000L
                if (discontinuity) {
                    lastJumpTime = now
                }
                lastEffectivePosition = effectivePosition
                if (discontinuity) {
                    positionEpoch++
                    lastNonEmptyActive = active.toSet()
                    lastNonEmptyTime = now
                    active
                } else if (active.isNotEmpty()) {
                    lastNonEmptyActive = active.toSet()
                    lastNonEmptyTime = now
                    active
                } else if (now - lastNonEmptyTime <= activeHoldMs && lastNonEmptyActive.isNotEmpty()) {
                    lastNonEmptyActive
                } else {
                    active
                }
            } else {
                lines.indices.toSet()
            }
            if (activeLineIndices != newActiveIndices) {
                activeLineIndices = newActiveIndices
            }

            if (isSynced) {
                val newBgVisible = mutableSetOf<Int>()
                for ((bgIndex, pairedMain) in backgroundToMainMap) {
                    val mainTime = lines[pairedMain].time
                    val bgTime = lines[bgIndex].time
                    val inGap = effectivePosition in mainTime..bgTime
                    if (newActiveIndices.contains(bgIndex) || newActiveIndices.contains(pairedMain) || inGap) {
                        newBgVisible.add(bgIndex)
                    }
                }
                if (visibleBackgroundLineIndices != newBgVisible) {
                    visibleBackgroundLineIndices = newBgVisible
                }
            }

            // A set containing only blank entries (e.g. the HEAD entry at song start)
            // has no visible line, so it counts as "no active line" for gap purposes.
            // This makes the interval indicator also show before the first lyric.
            val hasVisibleActive = newActiveIndices.any { lines.getOrNull(it)?.text?.isNotBlank() == true }
            val newIndicator = if (!hasVisibleActive) {
                mergedLyricsList.indexOfFirst { item ->
                    item is LyricsListItem.Indicator &&
                        effectivePosition >= item.gapStartMs &&
                        effectivePosition <= item.gapEndMs - 650L
                }.takeIf { it >= 0 }
            } else null
            if (activeIndicatorListIndex != newIndicator) {
                activeIndicatorListIndex = newIndicator
            }
            if (!loopWarmedUp) loopWarmedUp = true
        }
    }

    val viewConfiguration = LocalViewConfiguration.current

    val anchoredLineIndex by remember(lines, activeLineIndices) {
        derivedStateOf {
            activeLineIndices
                .filter { lines.getOrNull(it)?.isBackground == false }
                .maxOrNull() ?: activeLineIndices.maxOrNull() ?: 0
        }
    }

    val scrollTargetListIndex by remember(
        mergedLyricsList,
        activeLineIndices,
        anchoredLineIndex,
        activeIndicatorListIndex,
    ) {
        derivedStateOf {
            val activeLineListIndex = if (activeLineIndices.isEmpty()) {
                -1
            } else {
                mergedLyricsList.indexOfFirst {
                    it is LyricsListItem.Line && it.index == anchoredLineIndex
                }
            }

            if (activeLineListIndex >= 0) {
                activeLineListIndex
            } else {
                activeIndicatorListIndex
            }
        }
    }
    var activeListIndex by remember(lyrics) { mutableIntStateOf(0) }

    LaunchedEffect(scrollTargetListIndex, mergedLyricsList.lastIndex) {
        val targetListIndex = scrollTargetListIndex
        if (targetListIndex != null) {
            activeListIndex = targetListIndex
        } else if (mergedLyricsList.isNotEmpty()) {
            activeListIndex = activeListIndex.coerceIn(0, mergedLyricsList.lastIndex)
        }
    }

    DisposableEffect(showLyrics) {
        val activity = context as? Activity
        if (showLyrics) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().padding(bottom = 12.dp)
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = maxHeightPx * LYRICS_ANCHOR_RATIO
        val contentTop = with(density) { 56.dp.toPx() }
        val indicatorHeightPx = with(density) { 72.dp.toPx() }
        val lineHeightPx = with(density) { LYRICS_ITEM_FALLBACK_HEIGHT_DP.toPx() }
        val itemGapPx = with(density) { LYRICS_ITEM_GAP_DP.toPx() }
        val romanizeSong = currentSong?.romanizeLyrics == true

        // Eager exact heights: measured once with the real text engine (same styles and
        // width as LyricsLine) instead of starting from fallback estimates. Seeded
        // synchronously from the ViewModel cache on reopen, so positions are exact on the
        // very first frame. Later arrivals (translations) still patch entries live.
        val textMeasurer = rememberTextMeasurer()
        val mainFontFamily = MaterialTheme.typography.bodyLarge.fontFamily
        val tableKey = remember(
            lyrics?.hashCode() ?: 0, constraints.maxWidth, lyricsTextPosition, showIntervalIndicator,
            romanizeAsMain, enabledLanguages, romanizeSong, translateLanguage, translateMode,
        ) {
            "${lyrics?.hashCode() ?: 0}|${constraints.maxWidth}|$lyricsTextPosition|" +
                "$showIntervalIndicator|$romanizeAsMain|${enabledLanguages.hashCode()}|" +
                "$romanizeSong|$translateLanguage|$translateMode"
        }
        val itemHeights = remember(tableKey) {
            mutableStateMapOf<Int, Int>().also { map ->
                lyricsViewModel.heightTables[tableKey]?.let { cached ->
                    map.putAll(cached)
                    // Collapse bg/indicator entries on load: hidden is the common case
                    // and stays exact; visible ones are always near the anchor (hence
                    // composed) and regrow via live reports within a frame or two.
                    mergedLyricsList.forEachIndexed { i, item ->
                        when (item) {
                            is LyricsListItem.Indicator -> map[i] = 0
                            is LyricsListItem.Line ->
                                if (item.entry.isBackground) {
                                    map[i] = with(density) { (LYRICS_BG_TOP_PADDING + LYRICS_BG_BOTTOM_PADDING).roundToPx() }
                                }
                        }
                    }
                }
            }
        }
        LaunchedEffect(tableKey, mergedLyricsList) {
            if (mergedLyricsList.isNotEmpty() && lyricsViewModel.heightTables[tableKey] == null) {
                val table = precomputeLyricHeights(
                    items = mergedLyricsList,
                    measurer = textMeasurer,
                    density = density,
                    contentWidthPx = lyricContentWidthPx(constraints.maxWidth, density, lyricsTextPosition),
                    mainFontFamily = mainFontFamily,
                    textSizeSp = LYRICS_TEXT_SIZE_SP,
                    lineSpacing = LYRICS_LINE_SPACING,
                    romanizeAsMain = romanizeAsMain,
                    showRomanizedSub = romanizeSong && enabledLanguages.isNotEmpty(),
                    indicatorHeightPx = indicatorHeightPx,
                    itemGapPx = itemGapPx,
                    visibleBgLineIndices = visibleBackgroundLineIndices,
                    visibleIndicatorListIndex = activeIndicatorListIndex,
                )
                if (lyricsViewModel.heightTables.size > 3) lyricsViewModel.heightTables.clear()
                lyricsViewModel.heightTables[tableKey] = table.toMutableMap()
                // Live-measured entries (if any) win; the table only fills gaps.
                table.forEach { (index, height) -> itemHeights.putIfAbsent(index, height) }
            }
        }

        // Each item is positioned from the start of the song. The active line only changes
        // the viewport's offset, so playback never changes the layout of individual lines.
        val positions by remember(mergedLyricsList) {
            derivedStateOf {
                val n = mergedLyricsList.size
                val arr = FloatArray(n)
                var currentY = 0f
                for (i in 0 until n) {
                    arr[i] = currentY
                    val item = mergedLyricsList[i]
                    val height = itemHeights[i]?.toFloat()
                        ?: (if (item is LyricsListItem.Indicator) indicatorHeightPx else lineHeightPx)
                    val noGap = (item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator
                    currentY += height + if (noGap) 0f else itemGapPx
                }
                arr
            }
        }
        // Let the first and last entries reach the playback anchor instead of pinning
        // either edge of the list to the edge of the viewport.
        val firstAnchorOffset = contentTop - anchorY
        val lastAnchorOffset = remember(positions, contentTop, anchorY, mergedLyricsList.lastIndex) {
            derivedStateOf {
                val lastY = if (positions.isNotEmpty() && mergedLyricsList.isNotEmpty()) {
                    positions[mergedLyricsList.lastIndex]
                } else 0f
                contentTop + lastY - anchorY
            }
        }
        val scrollClampMin = remember(lastAnchorOffset, firstAnchorOffset) { 
            derivedStateOf { minOf(firstAnchorOffset, lastAnchorOffset.value) } 
        }
        val scrollClampMax = remember(lastAnchorOffset, firstAnchorOffset) { 
            derivedStateOf { maxOf(firstAnchorOffset, lastAnchorOffset.value) } 
        }

        val scrollOffset = remember { Animatable(0f) }

        LaunchedEffect(scrollCommands) {
            scrollCommands.receiveAsFlow().collectLatest { command ->
                when (command) {
                    is ScrollCommand.Stop -> scrollOffset.stop()
                    is ScrollCommand.Snap -> scrollOffset.snapTo(command.value)
                    is ScrollCommand.Animate -> scrollOffset.animateTo(command.value, tween(command.durationMs, easing = FastOutSlowInEasing))
                    is ScrollCommand.Fling -> scrollOffset.animateDecay(command.velocity, exponentialDecay())
                }
            }
        }

        val latestScrollLimits = rememberUpdatedState(scrollClampMin.value to scrollClampMax.value)
        val dragTargetOffsetRef = remember { object { var value: Float = 0f } }
        val latestAutoScrollEnabled = rememberUpdatedState(isAutoScrollEnabled)

        val onItemHeightChanged = rememberUpdatedState { index: Int, newHeight: Int ->
            val prevHeight = itemHeights[index]
            if (prevHeight == newHeight) return@rememberUpdatedState

            val fallbackHeight = if (mergedLyricsList.getOrNull(index) is LyricsListItem.Indicator) {
                indicatorHeightPx.roundToInt()
            } else {
                lineHeightPx.roundToInt()
            }
            val oldHeight = prevHeight ?: fallbackHeight
            val delta = (newHeight - oldHeight).toFloat()

            itemHeights[index] = newHeight
            // Write live values straight back to the cached table so the next open
            // seeds true heights (e.g. with translations), not pre-arrival ones.
            lyricsViewModel.heightTables[tableKey]?.let { tbl ->
                if (tbl[index] != newHeight) tbl[index] = newHeight
            }

            // In auto mode the autoScrollTarget already recomputes from the new positions,
            // so an extra Snap here would fight the auto animation (stutter). In manual
            // mode only compensate a settled offset; never cancel a running fling.
            if (delta != 0f && positions.isNotEmpty() && hasAutoPositioned &&
                !latestAutoScrollEnabled.value && !scrollOffset.isRunning
            ) {
                val currentAnchorY = scrollOffset.value - contentTop + anchorY
                val itemY = positions.getOrElse(index) { 0f }
                if (itemY < currentAnchorY) {
                    val newOffset = scrollOffset.value + delta
                    dragTargetOffsetRef.value += delta
                    scrollCommands.trySend(ScrollCommand.Snap(newOffset))
                }
            }
        }

        LaunchedEffect(scrollClampMin.value, scrollClampMax.value) {
            scrollOffset.updateBounds(scrollClampMin.value, scrollClampMax.value)
        }

        val autoScrollTarget = remember(
            positions,
            activeListIndex,
            scrollClampMin,
            scrollClampMax,
            contentTop,
            anchorY
        ) {
            derivedStateOf {
                if (positions.isEmpty() || activeListIndex !in positions.indices) {
                    null
                } else {
                    (positions[activeListIndex] + contentTop - anchorY)
                        .coerceIn(scrollClampMin.value, scrollClampMax.value)
                }
            }
        }

        val visibleRange by remember(positions, contentTop, maxHeightPx, mergedLyricsList.size) {
            derivedStateOf {
                if (positions.isEmpty() || mergedLyricsList.isEmpty()) {
                    IntRange.EMPTY
                } else {
                    val currentOffset = scrollOffset.value
                    // Generous prefetch in both directions so fast flings don't outrun
                    // composition (which reads as skipping/jumping).
                    val minListY = currentOffset - contentTop - (2f * maxHeightPx)
                    val maxListY = currentOffset - contentTop + (3f * maxHeightPx)
                    val start = findStartIndex(positions, minListY)
                    val end = findEndIndex(positions, maxListY)
                    start..end
                }
            }
        }

        // Scroll policy: the very first positioning snaps instantly (reopen shows the
        // exact spot with no animation). Tap-initiated jumps glide with the same fixed
        // duration as normal line changes, near or far. Scrub jumps snap instantly to
        // follow the finger. Same-index target moves are layout shifts (translation
        // arrival, clamp/rotation change), followed instantly and exactly.
        var lastAutoTargetIndex by remember(lyrics) { mutableIntStateOf(-1) }
        var lastHandledEpoch by remember(lyrics) { mutableIntStateOf(-1) }
        LaunchedEffect(autoScrollTarget.value, isAutoScrollEnabled, positionEpoch, loopWarmedUp) {
            val target = autoScrollTarget.value
            if (isAutoScrollEnabled && target != null) {
                if (!hasAutoPositioned) {
                    // Wait for the loop's first computation so the first snap goes
                    // straight to the live spot (scrollTargetListIndex is converged by
                    // then); the paint gate below holds the frames before it, so the
                    // opening frame already shows the right spot, never the top.
                    if (loopWarmedUp) {
                        val firstIdx = if (isSynced) scrollTargetListIndex else 0
                        val firstTarget = if (firstIdx != null && firstIdx in positions.indices) {
                            (positions[firstIdx] + contentTop - anchorY)
                                .coerceIn(scrollClampMin.value, scrollClampMax.value)
                        } else target
                        scrollCommands.send(ScrollCommand.Snap(firstTarget))
                        hasAutoPositioned = true
                        lastAutoTargetIndex = activeListIndex
                        lastHandledEpoch = positionEpoch
                    }
                } else if (positionEpoch != lastHandledEpoch) {
                    // Tap landing (jumped position matches the tapped line) glides with
                    // a fixed duration; anything else (scrub) snaps instantly and clears
                    // a stale tap. The flag survives a match so post-landing convergence
                    // in the far branch below keeps gliding instead of snapping.
                    val tapTarget = pendingTapSeekMs
                    val isTapLanding = tapTarget != null &&
                        abs(currentPositionRef.position - tapTarget) < TAP_TARGET_TOLERANCE_MS
                    lastAutoTargetIndex = activeListIndex
                    lastHandledEpoch = positionEpoch
                    if (isTapLanding) {
                        scrollCommands.send(ScrollCommand.Animate(target, LINE_CHANGE_ANIMATION_MS))
                    } else {
                        if (tapTarget != null) pendingTapSeekMs = null
                        scrollCommands.send(ScrollCommand.Snap(target))
                    }
                } else {
                    val indexDelta = if (lastAutoTargetIndex >= 0) {
                        abs(activeListIndex - lastAutoTargetIndex)
                    } else {
                        Int.MAX_VALUE
                    }
                    lastAutoTargetIndex = activeListIndex
                    if (indexDelta == 0) {
                        // Same line, target moved: the layout shifted under us (indicator
                        // show/hide, translation arrival, clamp/rotation change). Tiny
                        // shifts follow instantly (invisible). Bigger ones glide briefly:
                        // snapping here would murder an in-flight scroll animation (e.g.
                        // the tap glide) mid-way, so it gets redirected smoothly instead.
                        val d = abs(target - scrollOffset.value)
                        if (d > 0.5f) {
                            if (d < 24f) {
                                scrollCommands.send(ScrollCommand.Snap(target))
                            } else {
                                scrollCommands.send(ScrollCommand.Animate(target, REDIRECT_ANIMATION_MS))
                            }
                        }
                    } else if (indexDelta <= 2) {
                        scrollCommands.send(ScrollCommand.Animate(target, LINE_CHANGE_ANIMATION_MS))
                    } else {
                        // Far index jump: tap convergence keeps the fixed line-change
                        // duration (flag consumed here); scrub settling snaps instantly
                        // to follow the finger; anything else gets one smooth glide.
                        val tapPending = pendingTapSeekMs != null
                        val justJumped = System.currentTimeMillis() - lastJumpTime < SEEK_SETTLE_MS
                        if (tapPending) {
                            pendingTapSeekMs = null
                            scrollCommands.send(ScrollCommand.Animate(target, LINE_CHANGE_ANIMATION_MS))
                        } else if (justJumped) {
                            scrollCommands.send(ScrollCommand.Snap(target))
                        } else {
                            val distance = abs(target - scrollOffset.value)
                            val duration = (300 + distance * 0.12f).toInt().coerceIn(300, 750)
                            scrollCommands.send(ScrollCommand.Animate(target, duration))
                        }
                    }
                }
            }
        }

        val latestShowLyrics by rememberUpdatedState(showLyrics)
        val latestResyncLyrics by rememberUpdatedState(
            newValue = { isAutoScrollEnabled = true },
        )

        LaunchedEffect(Unit) {
            LyricsResyncHelper.resyncTrigger.collect {
                if (latestShowLyrics) {
                    latestResyncLyrics()
                }
            }
        }

        LyricsTranslationHeader(
            status = translationStatus,
            modifier = Modifier.zIndex(1f).padding(top = 56.dp)
        )

        if (lyrics == LYRICS_NOT_FOUND) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.lyrics_not_found), fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.alpha(0.5f))
            }
        } else if (lyrics == null && (translationStatus is LyricsTranslationHelper.TranslationStatus.Idle || translationStatus is LyricsTranslationHelper.TranslationStatus.Error)) {
             Column(modifier = Modifier.padding(top = 100.dp)) {
                 ShimmerHost { repeat(10) { Box(contentAlignment = when (lyricsTextPosition) {
                     LyricsPosition.LEFT -> Alignment.CenterStart; LyricsPosition.CENTER -> Alignment.Center; else -> Alignment.CenterEnd
                 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) { TextPlaceholder() } } }
             }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(top = LYRICS_FADE_TOP_DP, bottom = LYRICS_FADE_BOTTOM_DP)
                    .clipToBounds()
                    .nestedScroll(remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                return if (source == NestedScrollSource.UserInput && available.y != 0f) {
                                    Offset(0f, available.y)
                                } else {
                                    Offset.Zero
                                }
                            }
                        }
                    })
                    .pointerInput(Unit) {
                        coroutineScope {
                            while (isActive) {
                                val velocity = awaitPointerEventScope {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!isAutoScrollEnabled && scrollOffset.isRunning) {
                                        scrollCommands.trySend(ScrollCommand.Stop)
                                    }
                                    val tracker = VelocityTracker()
                                    tracker.addPointerInputChange(down)
                                    var dragging = false
                                    var accumulatedDrag = 0f
                                    var targetOffset = scrollOffset.value
                                    dragTargetOffsetRef.value = targetOffset
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUp()) break
                                        val delta = change.positionChange().y
                                        accumulatedDrag += delta
                                        if (!dragging && abs(accumulatedDrag) > viewConfiguration.touchSlop) {
                                            dragging = true
                                            isAutoScrollEnabled = false
                                            targetOffset = scrollOffset.value
                                            dragTargetOffsetRef.value = targetOffset
                                            scrollCommands.trySend(ScrollCommand.Stop)
                                        }
                                        if (dragging && delta != 0f) {
                                            val (minClamp, maxClamp) = latestScrollLimits.value
                                            targetOffset = (dragTargetOffsetRef.value - delta)
                                                .coerceIn(minClamp, maxClamp)
                                            dragTargetOffsetRef.value = targetOffset
                                            scrollCommands.trySend(ScrollCommand.Snap(targetOffset))
                                            tracker.addPointerInputChange(change)
                                            change.consume()
                                        }
                                    }
                                    if (dragging) -tracker.calculateVelocity().y else 0f
                                }
                                if (velocity != 0f) {
                                    scrollCommands.send(ScrollCommand.Fling(velocity))
                                }
                            }
                        }
                    }
            ) {
                // Paint gate: hold the list until the first snap lands so the opening
                // frame already shows the right spot (never the top). Lasts a frame or
                // two; unsynced lyrics (auto off) paint immediately at the top.
                if (hasAutoPositioned || !isAutoScrollEnabled) {
                if (isLyricsProviderShown) {
                    Text(
                        text = stringResource(R.string.lyrics_from_provider, lyricsEntity.provider),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { 
                                val y = contentTop - scrollOffset.value - with(density) { 32.dp.toPx() }
                                IntOffset(0, y.roundToInt()) 
                            }
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                for (listIndex in visibleRange) {
                    val listItem = mergedLyricsList[listIndex]
                    key(listItem) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .layout { m, c -> 
                                    val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                                    layout(p.width, 0) { p.place(0, 0) }
                                }
                                .offset {
                                    val y = contentTop + (positions.getOrElse(listIndex) { listIndex * lineHeightPx }) - scrollOffset.value
                                    IntOffset(0, y.roundToInt())
                                }
                        ) {
                            when (listItem) {
                                is LyricsListItem.Indicator -> {
                                    val visible = isAutoScrollEnabled && (listIndex == activeIndicatorListIndex)
                                    IntervalIndicator(
                                        gapStartMs = listItem.gapStartMs,
                                        gapEndMs = listItem.gapEndMs - 650L,
                                        currentPositionProvider = { currentPositionRef.position },
                                        visible = visible,
                                        color = expressiveAccent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { onItemHeightChanged.value(listIndex, it.height) }
                                            .padding(horizontal = 24.dp)
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                    )
                                }
                                is LyricsListItem.Line -> {
                                    val index = listItem.index
                                    val item = listItem.entry
                                    val isActiveLine = activeLineIndices.contains(index)
                                    val bgVisible = if (item.isBackground) visibleBackgroundLineIndices.contains(index) else false

                                    LyricsLine(
                                        index = index, item = item, isSynced = isSynced,
                                        isActiveLine = isActiveLine,
                                        bgVisible = bgVisible, isSelected = selectedIndices.contains(index),
                                        isSelectionModeActive = isSelectionModeActive,
                                        sliderPositionProvider = sliderPositionProvider,
                                        lyricsOffset = (currentSong?.song?.lyricsOffset ?: 0).toLong(),
                                        playerConnection = playerConnection, lyricsTextSize = LYRICS_TEXT_SIZE_SP, lyricsLineSpacing = LYRICS_LINE_SPACING,
                                        expressiveAccent = expressiveAccent, lyricsTextPosition = lyricsTextPosition,
                                        respectAgentPositioning = respectAgentPositioning, isAutoScrollEnabled = isAutoScrollEnabled,
                                        displayedCurrentLineIndex = if (isAutoScrollEnabled) anchoredLineIndex else index, romanizeAsMain = romanizeAsMain,
                                        enabledLanguages = enabledLanguages, romanizeLyrics = currentSong?.romanizeLyrics == true,
                                        onSizeChanged = { onItemHeightChanged.value(listIndex, it) },
                                        onClick = {
                                            if (isSelectionModeActive) {
                                                if (selectedIndices.contains(index)) {
                                                    selectedIndices.remove(index)
                                                    if (selectedIndices.isEmpty()) isSelectionModeActive = false
                                                } else if (selectedIndices.size < maxSelectionLimit) selectedIndices.add(index)
                                                else showMaxSelectionToast = true
                                            } else if (isSynced && changeLyrics && !isGuest) {
                                                if (item.time < playerConnection.player.duration + 30000L) {
                                                    // Records the tap target; the jump landing on it
                                                    // glides, a jump anywhere else snaps (see auto-scroll).
                                                    pendingTapSeekMs = item.time
                                                    playerConnection.seekTo((item.time - (currentSong?.song?.lyricsOffset ?: 0)).coerceAtLeast(0))
                                                }
                                                isAutoScrollEnabled = true
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionModeActive) {
                                                isSelectionModeActive = true
                                                selectedIndices.add(index)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        LyricsActionOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            isAutoScrollEnabled = isAutoScrollEnabled, isSynced = isSynced,
            isSelectionModeActive = isSelectionModeActive, anySelected = selectedIndices.isNotEmpty(),
            onSyncClick = latestResyncLyrics,
            onCancelSelection = { isSelectionModeActive = false; selectedIndices.clear() },
            onShareSelection = {
                val text = selectedIndices.sorted().mapNotNull { lines.getOrNull(it)?.text }.joinToString("\n")
                if (text.isNotBlank()) {
                    shareDialogData = Triple(text, mediaMetadata?.title ?: "", mediaMetadata?.artists?.joinToString { it.name } ?: "")
                    showShareDialog = true
                }
                isSelectionModeActive = false; selectedIndices.clear()
            }
        )
    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {}) {
            Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(32.dp)) { Text(stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait)) }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsShareDialog(
            txt = txt, title = title, arts = arts, songId = mediaMetadata?.id ?: "",
            onDismiss = { showShareDialog = false },
            onShareAsImage = {
                showShareDialog = false
                showColorPickerDialog = true
            }
        )
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsColorPickerDialog(
            txt = txt, title = title, arts = arts, thumbnailUrl = mediaMetadata?.thumbnailUrl,
            lyricsTextPosition = lyricsTextPosition,
            onDismiss = { showColorPickerDialog = false },
            onShare = { bgColor, textColor, secTextColor, style ->
                showColorPickerDialog = false
                showProgressDialog = true
                scope.launch {
                    try {
                        val image = ComposeToImage.createLyricsImage(
                            context, mediaMetadata?.thumbnailUrl, title, arts, txt,
                            (configuration.screenWidthDp * density.density).toInt(),
                            (configuration.screenHeightDp * density.density).toInt(),
                            bgColor.toArgb(),
                            when(style) {
                                LyricsBackgroundStyle.SOLID -> LyricsBackgroundStyle.SOLID
                                LyricsBackgroundStyle.BLUR -> LyricsBackgroundStyle.BLUR
                                LyricsBackgroundStyle.GRADIENT -> LyricsBackgroundStyle.GRADIENT
                            },
                            textColor.toArgb(), secTextColor.toArgb(),
                            when (lyricsTextPosition) {
                                LyricsPosition.LEFT -> Layout.Alignment.ALIGN_NORMAL
                                LyricsPosition.CENTER -> Layout.Alignment.ALIGN_CENTER
                                else -> Layout.Alignment.ALIGN_OPPOSITE
                            }
                        )
                        val uri = ComposeToImage.saveBitmapAsFile(context, image, "lyrics_${System.currentTimeMillis()}")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, context.getString(R.string.share_lyrics)))
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.failed_to_create_image, e.message), Toast.LENGTH_SHORT).show()
                    } finally {
                        showProgressDialog = false
                    }
                }
            }
        )
    }
}

private fun findStartIndex(positions: FloatArray, minListY: Float): Int {
    if (positions.isEmpty()) return 0
    var low = 0
    var high = positions.size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (positions[mid] >= minListY) {
            result = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return (result - 3).coerceAtLeast(0)
}

private fun findEndIndex(positions: FloatArray, maxListY: Float): Int {
    if (positions.isEmpty()) return 0
    var low = 0
    var high = positions.size - 1
    var result = positions.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (positions[mid] <= maxListY) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return (result + 3).coerceAtMost(positions.size - 1)
}
