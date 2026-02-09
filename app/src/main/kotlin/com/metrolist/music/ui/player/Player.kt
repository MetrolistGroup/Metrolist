/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.KeepScreenOn
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerButtonsStyle
import com.metrolist.music.constants.PlayerButtonsStyleKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.QueuePeekHeight
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.constants.SliderStyleKey
import com.metrolist.music.constants.SquigglySliderKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.Lyrics
import com.metrolist.music.ui.component.PlayerSliderTrack
import com.metrolist.music.ui.component.ResizableIconButton
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.component.rememberBottomSheetState
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.ui.theme.PlayerSliderColors
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.ui.utils.ShowOffsetDialog
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.metrolist.music.ui.component.SquigglySlider
import kotlin.math.max
import kotlin.math.roundToInt
import com.metrolist.music.ui.component.Icon as MIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val (useNewPlayerDesign, onUseNewPlayerDesignChange) = rememberPreference(
        UseNewPlayerDesignKey,
        defaultValue = true
    )
    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) = rememberPreference(HidePlayerThumbnailKey, false)
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )
    val playerButtonsStyle by rememberEnumPreference(
        key = PlayerButtonsStyleKey,
        defaultValue = PlayerButtonsStyle.DEFAULT
    )

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    val shouldUseDarkButtonColors = remember(playerBackground, useDarkTheme) {
        when (playerBackground) {
            PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> true
            PlayerBackgroundStyle.DEFAULT -> useDarkTheme
        }
    }

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val isKeepScreenOn by rememberPreference(KeepScreenOn, false)
    val keepScreenOn = isPlaying && isKeepScreenOn

    DisposableEffect(playerBackground, state.isExpanded, useDarkTheme, keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (window != null && state.isExpanded) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            
            when (playerBackground) {
                PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                    insetsController.isAppearanceLightStatusBars = false
                }
                PlayerBackgroundStyle.DEFAULT -> {
                    insetsController.isAppearanceLightStatusBars = !useDarkTheme
                }
            }

            if (keepScreenOn && state.isExpanded)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !useDarkTheme
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    val onBackgroundColor = when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val useBlackBackground =
        remember(isSystemInDarkTheme, darkTheme, pureBlack) {
            val useDarkTheme =
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            useDarkTheme && pureBlack
        }

    val playbackState by playerConnection.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val automix by playerConnection.service.automixItems.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val isMuted by playerConnection.isMuted.collectAsState()

    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.DEFAULT)
    val squigglySlider by rememberPreference(SquigglySliderKey, defaultValue = false)
    
    // Listen Together state (reactive)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsState(initial = RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST
    
    // Cast state - safely access castConnectionHandler to prevent crashes during service lifecycle changes
    val castHandler = remember(playerConnection) {
        try {
            playerConnection.service.castConnectionHandler
        } catch (e: Exception) {
            null
        }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castPosition by castHandler?.castPosition?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val castDuration by castHandler?.castDuration?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
    
    // Use Cast state when casting, otherwise local player
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying

    // Use State objects for position/duration to pass to MiniPlayer without causing recomposition
    // These states persist across playback state changes to ensure continuous progress updates
    val positionState = remember { mutableLongStateOf(0L) }
    val durationState = remember { mutableLongStateOf(0L) }
    
    // Convenience accessors for local use
    var position by positionState
    var duration by durationState
    
    val effectivePosition by remember {
        derivedStateOf {
            if (isCasting) {
                castPosition
            } else {
                position
            }
        }
    }
    
    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }
    // Track when we last manually set position to avoid Cast overwriting it
    var lastManualSeekTime by remember { mutableLongStateOf(0L) }
    
    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    val gradientColorsCache = remember { mutableMapOf<String, List<Color>>() }

    if (!canSkipNext && automix.isNotEmpty()) {
        playerConnection.service.addToQueueAutomix(automix[0], 0)
    }

    val defaultGradientColors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()

    LaunchedEffect(mediaMetadata?.id, playerBackground) {
        if (playerBackground == PlayerBackgroundStyle.GRADIENT) {
            val currentMetadata = mediaMetadata
            if (currentMetadata != null && currentMetadata.thumbnailUrl != null) {
                val cachedColors = gradientColorsCache[currentMetadata.id]
                if (cachedColors != null) {
                    gradientColors = cachedColors
                    return@LaunchedEffect
                }
                withContext(Dispatchers.IO) {
                    val request = ImageRequest.Builder(context)
                        .data(currentMetadata.thumbnailUrl)
                        .size(100, 100)
                        .allowHardware(false)
                        .memoryCacheKey("gradient_${currentMetadata.id}")
                        .build()

                    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
                    if (result != null) {
                        val bitmap = result.image?.toBitmap()
                        if (bitmap != null) {
                            val palette = withContext(Dispatchers.Default) {
                                Palette.from(bitmap)
                                    .maximumColorCount(8)
                                    .resizeBitmapArea(100 * 100)
                                    .generate()
                            }
                            val extractedColors = PlayerColorExtractor.extractGradientColors(
                                palette = palette,
                                fallbackColor = fallbackColor
                            )
                            gradientColorsCache[currentMetadata.id] = extractedColors
                            withContext(Dispatchers.Main) { gradientColors = extractedColors }
                        }
                    }
                }
            }
        } else {
            gradientColors = emptyList()
        }
    }

    val TextBackgroundColor by animateColorAsState(
        targetValue = when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
            PlayerBackgroundStyle.BLUR -> Color.White
            PlayerBackgroundStyle.GRADIENT -> Color.White
        },
        label = "TextBackgroundColor"
    )

    val icBackgroundColor by animateColorAsState(
        targetValue = when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.surface
            PlayerBackgroundStyle.BLUR -> Color.Black
            PlayerBackgroundStyle.GRADIENT -> Color.Black
        },
        label = "icBackgroundColor"
    )

    val (textButtonColor, iconButtonColor) = when {
        playerBackground == PlayerBackgroundStyle.BLUR || 
        playerBackground == PlayerBackgroundStyle.GRADIENT -> {
            when (playerButtonsStyle) {
                PlayerButtonsStyle.DEFAULT -> Pair(Color.White, Color.Black)
                PlayerButtonsStyle.PRIMARY -> Pair(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimary
                )
                PlayerButtonsStyle.TERTIARY -> Pair(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onTertiary
                )
            }
        }
        else -> {
            when (playerButtonsStyle) {
                PlayerButtonsStyle.DEFAULT ->
                    if (useDarkTheme) Pair(Color.White, Color.Black)
                    else Pair(Color.Black, Color.White)
                PlayerButtonsStyle.PRIMARY -> Pair(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimary
                )
                PlayerButtonsStyle.TERTIARY -> Pair(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onTertiary
                )
            }
        }
    }

    // Separate colors for Previous/Next buttons in PRIMARY/TERTIARY modes
    val (sideButtonContainerColor, sideButtonContentColor) = when {
        playerBackground == PlayerBackgroundStyle.BLUR || 
        playerBackground == PlayerBackgroundStyle.GRADIENT -> {
            when (playerButtonsStyle) {
                PlayerButtonsStyle.DEFAULT -> Pair(
                    Color.White.copy(alpha = 0.2f), 
                    Color.White
                )
                PlayerButtonsStyle.PRIMARY -> Pair(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer
                )
                PlayerButtonsStyle.TERTIARY -> Pair(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        else -> {
            when (playerButtonsStyle) {
                PlayerButtonsStyle.DEFAULT -> Pair(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface
                )
                PlayerButtonsStyle.PRIMARY -> Pair(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer
                )
                PlayerButtonsStyle.TERTIARY -> Pair(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }

    val download by LocalDownloadUtil.current.getDownload(mediaMetadata?.id ?: "")
        .collectAsState(initial = null)

    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd
        ) {
            playerConnection.service.sleepTimer.isActive
        }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft =
                    if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                        playerConnection.player.duration - playerConnection.player.currentPosition
                    } else {
                        playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                    }
                delay(1000L)
            }
        }
    }

    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    var sleepTimerValue by remember {
        mutableFloatStateOf(30f)
    }
    if (showSleepTimerDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showSleepTimerDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.bedtime),
                    contentDescription = null
                )
            },
            title = { Text(stringResource(R.string.sleep_timer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSleepTimerDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.minute,
                            sleepTimerValue.roundToInt(),
                            sleepTimerValue.roundToInt()
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Slider(
                        value = sleepTimerValue,
                        onValueChange = { sleepTimerValue = it },
                        valueRange = 5f..120f,
                        steps = (120 - 5) / 5 - 1,
                    )

                    OutlinedIconButton(
                        onClick = {
                            showSleepTimerDialog = false
                            playerConnection.service.sleepTimer.start(-1)
                        },
                    ) {
                        Text(stringResource(R.string.end_of_song))
                    }
                }
            },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showInlineLyrics by rememberSaveable {
        mutableStateOf(false)
    }

    var isFullScreen by rememberSaveable {
        mutableStateOf(false)
    }

    // Position update - only for local playback
    // When casting, we use castPosition directly to avoid sync issues
    // Use isPlaying instead of playbackState to ensure continuous updates during playback
    LaunchedEffect(isPlaying, isCasting) {
        if (!isCasting && isPlaying) {
            while (isActive) {
                delay(100) // Update more frequently for smoother progress bar
                if (sliderPosition == null) { // Only update if user isn't dragging
                    position = playerConnection.player.currentPosition
                    duration = playerConnection.player.duration
                }
            }
        }
    }
    
    // Also update position when playback state changes (e.g., song change, seek)
    LaunchedEffect(playbackState, mediaMetadata?.id) {
        if (!isCasting) {
            position = playerConnection.player.currentPosition
            duration = playerConnection.player.duration
        }
    }
    
    // When casting, use Cast position/duration directly
    // But wait a bit after manual seeks to let Cast catch up
    LaunchedEffect(isCasting, castPosition, castDuration) {
        if (isCasting && sliderPosition == null) {
            val timeSinceManualSeek = System.currentTimeMillis() - lastManualSeekTime
            if (timeSinceManualSeek > 1500) {
                // Only update from Cast if we haven't manually seeked recently
                position = castPosition
                if (castDuration > 0) duration = castDuration
            }
        }
    }

    val dismissedBound = QueuePeekHeight + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val queueSheetState = rememberBottomSheetState(
        dismissedBound = dismissedBound,
        expandedBound = state.expandedBound,
        collapsedBound = dismissedBound + 1.dp,
        initialAnchor = 1
    )

    val bottomSheetBackgroundColor = when (playerBackground) {
        PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT ->
            MaterialTheme.colorScheme.surfaceContainer
        else ->
            if (useBlackBackground) Color.Black
            else MaterialTheme.colorScheme.surfaceContainer
    }

    val backgroundAlpha = state.progress.coerceIn(0f, 1f)

    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bottomSheetBackgroundColor)
            ) {
                when (playerBackground) {
                    PlayerBackgroundStyle.BLUR -> {
                        AnimatedContent(
                            targetState = mediaMetadata?.thumbnailUrl,
                            transitionSpec = {
                                fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))
                            },
                            label = "blurBackground"
                        ) { thumbnailUrl ->
                            if (thumbnailUrl != null) {
                                Box(modifier = Modifier.alpha(backgroundAlpha)) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(thumbnailUrl)
                                            .size(100, 100)
                                            .allowHardware(false)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .blur(if (useDarkTheme) 150.dp else 100.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.3f))
                                    )
                                }
                            }
                        }
                    }
                    PlayerBackgroundStyle.GRADIENT -> {
                        AnimatedContent(
                            targetState = gradientColors,
                            transitionSpec = {
                                fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))
                            },
                            label = "gradientBackground"
                        ) { colors ->
                            if (colors.isNotEmpty()) {
                                val gradientColorStops = if (colors.size >= 3) {
                                    arrayOf(
                                        0.0f to colors[0],
                                        0.5f to colors[1],
                                        1.0f to colors[2]
                                    )
                                } else {
                                    arrayOf(
                                        0.0f to colors[0],
                                        0.6f to colors[0].copy(alpha = 0.7f),
                                        1.0f to Color.Black
                                    )
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .alpha(backgroundAlpha)
                                        .background(Brush.verticalGradient(colorStops = gradientColorStops))
                                        .background(Color.Black.copy(alpha = 0.2f))
                                )
                            }
                        }
                    }
                    else -> {
                        PlayerBackgroundStyle.DEFAULT
                    }
                }
            }
        },
        onDismiss = {
            playerConnection.service.clearAutomix()
            playerConnection.player.stop()
            playerConnection.player.clearMediaItems()
        },
        collapsedContent = {
            MiniPlayer(
                positionState = positionState,
                durationState = durationState
            )
        },
    ) {
        val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
            val playPauseRoundness by animateDpAsState(
                targetValue = if (isPlaying) 24.dp else 36.dp,
                animationSpec = tween(durationMillis = 90, easing = LinearEasing),
                label = "playPauseRoundness",
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding),
            ) {
                AnimatedContent(
                    targetState = showInlineLyrics,
                    label = "ThumbnailAnimation"
                ) { showLyrics ->
                    if (showLyrics) {
                        Row {
                            if (hidePlayerThumbnail) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.small_icon),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(32.dp),
                                        tint = textButtonColor.copy(alpha = 0.7f)
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = mediaMetadata.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    AnimatedContent(
                        targetState = mediaMetadata.title,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "",
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = TextBackgroundColor,
                            modifier =
                            Modifier
                                .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                .combinedClickable(
                                    enabled = true,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        if (mediaMetadata.album != null) {
                                            navController.navigate("album/${mediaMetadata.album.id}")
                                            state.collapseSoft()
                                        }
                                    },
                                    onLongClick = {
                                        val clip = ClipData.newPlainText(context.getString(R.string.copied_title), title)
                                        clipboardManager.setPrimaryClip(clip)
                                        Toast
                                            .makeText(context, context.getString(R.string.copied_title), Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                )
                            ,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mediaMetadata.explicit) MIcon.Explicit()

                        if (mediaMetadata.artists.any { it.name.isNotBlank() }) {
                            val annotatedString = buildAnnotatedString {
                                mediaMetadata.artists.forEachIndexed { index, artist ->
                                    val tag = "artist_${artist.id.orEmpty()}"
                                    pushStringAnnotation(tag = tag, annotation = artist.id.orEmpty())
                                    withStyle(SpanStyle(color = TextBackgroundColor, fontSize = 16.sp)) {
                                        append(artist.name)
                                    }
                                    pop()
                                    if (index != mediaMetadata.artists.lastIndex) append(", ")
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                    .padding(end = 12.dp)
                            ) {
                                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                                var clickOffset by remember { mutableStateOf<Offset?>(null) }
                                Text(
                                    text = annotatedString,
                                    style = MaterialTheme.typography.titleMedium.copy(color = TextBackgroundColor),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { layoutResult = it },
                                    modifier = Modifier
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val tapPosition = event.changes.firstOrNull()?.position
                                                    if (tapPosition != null) {
                                                        clickOffset = tapPosition
                                                    }
                                                }
                                            }
                                        }
                                        .combinedClickable(
                                            enabled = true,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                            onClick = {
                                                val tapPosition = clickOffset
                                                val layout = layoutResult
                                                if (tapPosition != null && layout != null) {
                                                    val offset = layout.getOffsetForPosition(tapPosition)
                                                    annotatedString
                                                        .getStringAnnotations(offset, offset)
                                                        .firstOrNull()
                                                        ?.let { ann ->
                                                            val artistId = ann.item
                                                            if (artistId.isNotBlank()) {
                                                                navController.navigate("artist/$artistId")
                                                                state.collapseSoft()
                                                            }
                                                        }
                                                }
                                            },
                                            onLongClick = {
                                                val clip =
                                                    ClipData.newPlainText(
                                                        context.getString(R.string.copied_artist),
                                                        annotatedString
                                                    )
                                                clipboardManager.setPrimaryClip(clip)
                                                Toast
                                                    .makeText(
                                                        context,
                                                        context.getString(R.string.copied_artist),
                                                        Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            }
                                        )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (useNewPlayerDesign) {
                    val shareShape = RoundedCornerShape(
                        topStart = 50.dp, bottomStart = 50.dp,
                        topEnd = 3.dp, bottomEnd = 3.dp
                    )

                    val favShape = RoundedCornerShape(
                        topStart = 3.dp, bottomStart = 3.dp,
                        topEnd = 50.dp, bottomEnd = 50.dp
                    )

                    val middleShape = RoundedCornerShape(3.dp)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedContent(targetState = showInlineLyrics, label = "ShareButton") { showLyrics ->
                            if (showLyrics) {
                                FilledIconButton(
                                    onClick = { isFullScreen = !isFullScreen },
                                    shape = shareShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = textButtonColor,
                                        contentColor = iconButtonColor,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.fullscreen),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        val intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(intent, null))
                                    },
                                    shape = shareShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = textButtonColor,
                                        contentColor = iconButtonColor,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.share),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        AnimatedContent(targetState = showInlineLyrics, label = "LikeButton") { showLyrics ->
                            if (showLyrics) {
                                val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
                                FilledIconButton(
                                    onClick = {
                                        menuState.show {
                                            com.metrolist.music.ui.menu.LyricsMenu(
                                                lyricsProvider = { currentLyrics },
                                                songProvider = { currentSong?.song },
                                                mediaMetadataProvider = { mediaMetadata },
                                                onDismiss = menuState::dismiss,
                                                onShowOffsetDialog = {
                                                    bottomSheetPageState.show {
                                                        ShowOffsetDialog(
                                                            songProvider = { currentSong?.song }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    },
                                    shape = favShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = textButtonColor,
                                        contentColor = iconButtonColor,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_horiz),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = playerConnection::toggleLike,
                                    shape = favShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = textButtonColor,
                                        contentColor = iconButtonColor,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (currentSong?.song?.liked == true)
                                                R.drawable.favorite
                                            else R.drawable.favorite_border
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    AnimatedContent(targetState = showInlineLyrics, label = "ShareButton") { showLyrics ->
                        if (showLyrics) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(textButtonColor)
                                    .clickable { isFullScreen = !isFullScreen },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.fullscreen),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(textButtonColor)
                                    .clickable {
                                        val intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(intent, null))
                                    },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.size(12.dp))

                    AnimatedContent(targetState = showInlineLyrics, label = "LikeButton") { showLyrics ->
                        if (showLyrics) {
                            val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(textButtonColor)
                                    .clickable {
                                        menuState.show {
                                            com.metrolist.music.ui.menu.LyricsMenu(
                                                lyricsProvider = { currentLyrics },
                                                songProvider = { currentSong?.song },
                                                mediaMetadataProvider = { mediaMetadata },
                                                onDismiss = menuState::dismiss,
                                                onShowOffsetDialog = {
                                                    bottomSheetPageState.show {
                                                        ShowOffsetDialog(
                                                            songProvider = { currentSong?.song }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_horiz),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp),
                                )
                            }
                        } else {
                            PlayerMoreMenuButton(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                state = state,
                                textButtonColor = textButtonColor,
                                iconButtonColor = iconButtonColor,
                            )
                        }
                    }


                }
            }

            Spacer(Modifier.height(24.dp))

            when (sliderStyle) {
                SliderStyle.DEFAULT -> {
                    Slider(
                        value = (sliderPosition ?: effectivePosition).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            if (!isListenTogetherGuest) {
                                sliderPosition = it.toLong()
                            }
                        },
                        onValueChangeFinished = {
                            if (!isListenTogetherGuest) {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            }
                        },
                        enabled = !isListenTogetherGuest,
                        colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                    )
                }

                SliderStyle.WAVY -> {
                    if (squigglySlider) {
                        SquigglySlider(
                            value = (sliderPosition ?: effectivePosition).toFloat(),
                            valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                            onValueChange = {
                                sliderPosition = it.toLong()
                            },
                            onValueChangeFinished = {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            },
                            modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                            colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                            isPlaying = effectiveIsPlaying,
                        )
                    } else {
                        WavySlider(
                            value = (sliderPosition ?: effectivePosition).toFloat(),
                            valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                            onValueChange = {
                                sliderPosition = it.toLong()
                            },
                            onValueChangeFinished = {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            },
                            colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                            modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                            isPlaying = effectiveIsPlaying
                        )
                    }
                }

                SliderStyle.SLIM -> {
                    Slider(
                        value = (sliderPosition ?: effectivePosition).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            if (!isListenTogetherGuest) {
                                sliderPosition = it.toLong()
                            }
                        },
                        onValueChangeFinished = {
                            if (!isListenTogetherGuest) {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            }
                        },
                        enabled = !isListenTogetherGuest,
                        thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                        track = { sliderState ->
                            PlayerSliderTrack(
                                sliderState = sliderState,
                                colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme)
                            )
                        },
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding + 4.dp),
            ) {
                Text(
                    text = makeTimeString(sliderPosition ?: effectivePosition),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextBackgroundColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextBackgroundColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(
                visible = !isFullScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Column {
                    if (useNewPlayerDesign) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PlayerHorizontalPadding)
                        ) {
                            val backInteractionSource = remember { MutableInteractionSource() }
                            val nextInteractionSource = remember { MutableInteractionSource() }
                            val playPauseInteractionSource = remember { MutableInteractionSource() }

                            val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
                            val isBackPressed by backInteractionSource.collectIsPressedAsState()
                            val isNextPressed by nextInteractionSource.collectIsPressedAsState()

                            val playPauseWeight by animateFloatAsState(
                                targetValue = if (isPlayPausePressed) 1.9f else if (isBackPressed || isNextPressed) 1.1f else 1.3f,
                                animationSpec = spring(
                                    dampingRatio = 0.6f,
                                    stiffness = 500f
                                ),
                                label = "playPauseWeight"
                            )

                            val backButtonWeight by animateFloatAsState(
                                targetValue = if (isBackPressed) 0.65f else if (isPlayPausePressed) 0.35f else 0.45f,
                                animationSpec = spring(
                                    dampingRatio = 0.6f,
                                    stiffness = 500f
                                ),
                                label = "backButtonWeight"
                            )

                            val nextButtonWeight by animateFloatAsState(
                                targetValue = if (isNextPressed) 0.65f else if (isPlayPausePressed) 0.35f else 0.45f,
                                animationSpec = spring(
                                    dampingRatio = 0.6f,
                                    stiffness = 500f
                                ),
                                label = "nextButtonWeight"
                            )

                            FilledIconButton(
                                onClick = playerConnection::seekToPrevious,
                                enabled = canSkipPrevious && !isListenTogetherGuest,
                                shape = RoundedCornerShape(50),
                                interactionSource = backInteractionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = sideButtonContainerColor,
                                    contentColor = sideButtonContentColor,
                                ),
                                modifier = Modifier
                                    .height(68.dp)
                                    .weight(backButtonWeight)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledIconButton(
                                onClick = {
                                    if (isListenTogetherGuest) {
                                        playerConnection.toggleMute()
                                        return@FilledIconButton
                                    }
                                    if (isCasting) {
                                        if (castIsPlaying) {
                                            castHandler?.pause()
                                        } else {
                                            castHandler?.play()
                                        }
                                    } else if (playbackState == STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.togglePlayPause()
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                interactionSource = playPauseInteractionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = textButtonColor,
                                    contentColor = iconButtonColor,
                                ),
                                modifier = Modifier
                                    .height(68.dp)
                                    .weight(playPauseWeight)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (isListenTogetherGuest) {
                                                if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                                            } else {
                                                if (effectiveIsPlaying) R.drawable.pause else R.drawable.play
                                            }
                                        ),
                                        contentDescription = if (isListenTogetherGuest) {
                                            if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)
                                        } else {
                                            if (effectiveIsPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isListenTogetherGuest) {
                                            if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)
                                        } else {
                                            if (effectiveIsPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledIconButton(
                                onClick = playerConnection::seekToNext,
                                enabled = canSkipNext && !isListenTogetherGuest,
                                shape = RoundedCornerShape(50),
                                interactionSource = nextInteractionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = sideButtonContainerColor,
                                    contentColor = sideButtonContentColor,
                                ),
                                modifier = Modifier
                                    .height(68.dp)
                                    .weight(nextButtonWeight
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PlayerHorizontalPadding),
                        ) {
                            if (!isListenTogetherGuest) {
                                Box(modifier = Modifier.weight(1f)) {
                                    ResizableIconButton(
                                        icon = when (repeatMode) {
                                            Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                            else -> throw IllegalStateException()
                                        },
                                        color = TextBackgroundColor,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .padding(4.dp)
                                            .align(Alignment.Center),
                                        enabled = !isListenTogetherGuest,
                                        onClick = {
                                            playerConnection.player.toggleRepeatMode()
                                        }
                                    )
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = R.drawable.skip_previous,
                                        enabled = canSkipPrevious && !isListenTogetherGuest,
                                    color = TextBackgroundColor,
                                    modifier =
                                    Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center),
                                    onClick = playerConnection::seekToPrevious,
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Box(
                                modifier =
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(playPauseRoundness))
                                    .background(textButtonColor)
                                    .clickable {
                                        if (isListenTogetherGuest) {
                                            playerConnection.toggleMute()
                                            return@clickable
                                        }
                                        if (isCasting) {
                                            if (castIsPlaying) {
                                                castHandler?.pause()
                                            } else {
                                                castHandler?.play()
                                            }
                                        } else if (playbackState == STATE_ENDED) {
                                            playerConnection.player.seekTo(0, 0)
                                            playerConnection.player.playWhenReady = true
                                        } else {
                                            playerConnection.player.togglePlayPause()
                                        }
                                    },
                            ) {
                                Image(
                                    painter =
                                    painterResource(
                                        if (isListenTogetherGuest) {
                                            if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                                        } else if (playbackState ==
                                            STATE_ENDED
                                        ) {
                                            R.drawable.replay
                                        } else if (effectiveIsPlaying) {
                                            R.drawable.pause
                                        } else {
                                            R.drawable.play
                                        },
                                    ),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(iconButtonColor),
                                    modifier =
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(36.dp),
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = R.drawable.skip_next,
                                        enabled = canSkipNext && !isListenTogetherGuest,
                                    color = TextBackgroundColor,
                                    modifier =
                                    Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center),
                                    onClick = playerConnection::seekToNext,
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = if (currentSong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border,
                                    color = if (currentSong?.song?.liked == true) MaterialTheme.colorScheme.error else TextBackgroundColor,
                                    modifier =
                                    Modifier
                                        .size(32.dp)
                                        .padding(4.dp)
                                        .align(Alignment.Center),
                                    onClick = playerConnection::toggleLike,
                                )
                            }
                        }
                    }
                }
            }
        }

        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // Calculate vertical padding like OuterTune
                val density = LocalDensity.current
                val verticalPadding = max(
                    WindowInsets.systemBars.getTop(density),
                    WindowInsets.systemBars.getBottom(density)
                )
                val verticalPaddingDp = with(density) { verticalPadding.toDp() }
                val verticalWindowInsets = WindowInsets(left = 0.dp, top = verticalPaddingDp, right = 0.dp, bottom = verticalPaddingDp)
                
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).add(verticalWindowInsets)
                        )
                        .padding(bottom = 24.dp)
                        .fillMaxSize()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(state.preUpPostDownNestedScrollConnection)
                    ) {
                        // Remember lambdas to prevent unnecessary recomposition
                        val currentSliderPosition by rememberUpdatedState(sliderPosition)
                        val sliderPositionProvider = remember { { currentSliderPosition } }
                        val isExpandedProvider = remember(state) { { state.isExpanded } }
                        AnimatedContent(
                            targetState = showInlineLyrics,
                            label = "Lyrics",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { showLyrics ->
                            if (showLyrics) {
                                InlineLyricsView(
                                    mediaMetadata = mediaMetadata,
                                    showLyrics = showLyrics,
                                    positionProvider = { effectivePosition }
                                )
                            } else {
                                Thumbnail(
                                    sliderPositionProvider = sliderPositionProvider,
                                    modifier = Modifier.animateContentSize(),
                                    isPlayerExpanded = isExpandedProvider,
                                    isLandscape = true,
                                    isListenTogetherGuest = isListenTogetherGuest
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(if (showInlineLyrics) 0.65f else 1f, false)
                            .animateContentSize()
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                    ) {
                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                val bottomPadding by animateDpAsState(
                    targetValue = if (isFullScreen) 0.dp else queueSheetState.collapsedBound,
                    label = "bottomPadding"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                        .padding(bottom = bottomPadding)
                        .animateContentSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Remember lambdas to prevent unnecessary recomposition
                        val currentSliderPosition by rememberUpdatedState(sliderPosition)
                        val sliderPositionProvider = remember { { currentSliderPosition } }
                        val isExpandedProvider = remember(state) { { state.isExpanded } }
                        AnimatedContent(
                            targetState = showInlineLyrics,
                            label = "Lyrics",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { showLyrics ->
                            if (showLyrics) {
                                InlineLyricsView(
                                    mediaMetadata = mediaMetadata,
                                    showLyrics = showLyrics,
                                    positionProvider = { effectivePosition }
                                )
                            } else {
                                Thumbnail(
                                    sliderPositionProvider = sliderPositionProvider,
                                    modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                    isPlayerExpanded = isExpandedProvider,
                                    isListenTogetherGuest = isListenTogetherGuest
                                )
                            }
                        }
                    }

                    mediaMetadata?.let {
                        controlsContent(it)
                    }

                    Spacer(Modifier.height(30.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = !isFullScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Queue(
                state = queueSheetState,
                playerBottomSheetState = state,
            navController = navController,
            background =
            if (useBlackBackground) {
                Color.Black
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            onBackgroundColor = onBackgroundColor,
            TextBackgroundColor = TextBackgroundColor,
            textButtonColor = textButtonColor,
            iconButtonColor = iconButtonColor,
            pureBlack = pureBlack,
            showInlineLyrics = showInlineLyrics,
            playerBackground = playerBackground,
            onToggleLyrics = {
                showInlineLyrics = !showInlineLyrics
            },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLyricsView(
    mediaMetadata: MediaMetadata?,
    showLyrics: Boolean,
    positionProvider: () -> Long
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(mediaMetadata?.id, currentLyrics) {
        if (mediaMetadata != null && currentLyrics == null) {
            delay(500)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        com.metrolist.music.di.LyricsHelperEntryPoint::class.java
                    )
                    val lyricsHelper = entryPoint.lyricsHelper()
                    val fetchedLyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                    database.query {
                        upsert(LyricsEntity(mediaMetadata.id, fetchedLyricsWithProvider.lyrics, fetchedLyricsWithProvider.provider))
                    }
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }

    Box (
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            lyrics == null -> {
                ContainedLoadingIndicator()
            }
            lyrics == LyricsEntity.LYRICS_NOT_FOUND -> {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                val lyricsContent: @Composable () -> Unit = {
                    Lyrics(
                        sliderPositionProvider = positionProvider,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        showLyrics = showLyrics
                    )
                }
                ProvideTextStyle(
                    value = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                ) {
                    lyricsContent()
                }
            }
        }
    }
}


@Composable
fun MoreActionsButton(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    textButtonColor: Color,
    iconButtonColor: Color
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(textButtonColor)
            .clickable {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            mediaMetadata.id.let {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(it)
                                }
                            }
                        },
                        onDismiss = menuState::dismiss
                    )
                }
            }
    ) {
        Image(
            painter = painterResource(R.drawable.more_horiz),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconButtonColor)
        )
    }
}

@Composable
private fun PlayerMoreMenuButton(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    textButtonColor: Color,
    iconButtonColor: Color,
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    Box(
        contentAlignment = Alignment.Center,
        modifier =
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(textButtonColor)
            .clickable {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            mediaMetadata.id.let {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(it)
                                }
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            },
    ) {
        Image(
            painter = painterResource(R.drawable.more_horiz),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconButtonColor),
        )
    }
}
