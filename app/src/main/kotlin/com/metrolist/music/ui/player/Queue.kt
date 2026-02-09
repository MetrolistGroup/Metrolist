/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.music.LocalListenTogetherManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.navigation.NavController
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.QueueEditLockKey
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.move
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.ActionPromptDialog
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.menu.QueueMenu
import com.metrolist.music.ui.menu.SelectionMediaMetadataMenu
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Queue(
    state: BottomSheetState,
    playerBottomSheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    background: Color,
    onBackgroundColor: Color,
    TextBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    pureBlack: Boolean,
    showInlineLyrics: Boolean,
    playerBackground: PlayerBackgroundStyle = PlayerBackgroundStyle.DEFAULT,
    onToggleLyrics: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboard.current
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    // Listen Together state (reactive)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsState(initial = com.metrolist.music.listentogether.RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST

    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val currentFormat by playerConnection.currentFormat.collectAsState(initial = null)

    val selectedSongs = remember { mutableStateListOf<MediaMetadata>() }
    val selectedItems = remember { mutableStateListOf<Timeline.Window>() }

    // Cast state - safely access castConnectionHandler to prevent crashes during service lifecycle changes
    val castHandler = remember(playerConnection) {
        try {
            playerConnection.service.castConnectionHandler
        } catch (e: Exception) {
            null
        }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    var locked by rememberPreference(QueueEditLockKey, defaultValue = true)

    val (useNewPlayerDesign, onUseNewPlayerDesignChange) = rememberPreference(
        UseNewPlayerDesignKey,
        defaultValue = true
    )

    val snackbarHostState = remember { SnackbarHostState() }
    var dismissJob: Job? by remember { mutableStateOf(null) }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerValue by remember { mutableFloatStateOf(30f) }
    val sleepTimerEnabled = remember(
        playerConnection.service.sleepTimer.triggerTime,
        playerConnection.service.sleepTimer.pauseWhenSongEnd
    ) {
        playerConnection.service.sleepTimer.isActive
    }
    var sleepTimerTimeLeft by remember { mutableLongStateOf(0L) }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft = if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                    playerConnection.player.duration - playerConnection.player.currentPosition
                } else {
                    playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                }
                delay(1000L)
            }
        }
    }

    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Box(Modifier.fillMaxSize().background(Color.Unspecified))
        },
        collapsedContent = {
            if (useNewPlayerDesign) {
                // New design
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp, vertical = 12.dp)
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            ),
                        ),
                ) {
                    val buttonSize = 42.dp
                    val iconSize = 24.dp
                    val queueShape = RoundedCornerShape(
                        topStart = 50.dp, bottomStart = 50.dp,
                        topEnd = 3.dp, bottomEnd = 3.dp
                    )
                    val middleShape = RoundedCornerShape(3.dp)
                    val repeatShape = RoundedCornerShape(
                        topStart = 3.dp, bottomStart = 3.dp,
                        topEnd = 50.dp, bottomEnd = 50.dp
                    )

                    PlayerQueueButton(
                        icon = R.drawable.queue_music,
                        onClick = { state.expandSoft() },
                        isActive = false,
                        shape = queueShape,
                        modifier = Modifier.size(buttonSize),
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        iconSize = iconSize,
                        textBackgroundColor = TextBackgroundColor,
                        playerBackground = playerBackground
                    )

                    PlayerQueueButton(
                        icon = R.drawable.bedtime,
                        onClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        isActive = sleepTimerEnabled,
                        enabled = !isListenTogetherGuest,
                        shape = middleShape,
                        modifier = Modifier.size(buttonSize),
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        text = if (sleepTimerEnabled) makeTimeString(sleepTimerTimeLeft) else null,
                        iconSize = iconSize,
                        textBackgroundColor = TextBackgroundColor,
                        playerBackground = playerBackground
                    )

                    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
                    PlayerQueueButton(
                        icon = R.drawable.shuffle,
                        onClick = {
                            playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                        },
                        isActive = shuffleModeEnabled,
                        enabled = !isListenTogetherGuest,
                        shape = middleShape,
                        modifier = Modifier.size(buttonSize),
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        iconSize = iconSize,
                        textBackgroundColor = TextBackgroundColor,
                        playerBackground = playerBackground
                    )

                    PlayerQueueButton(
                        icon = R.drawable.lyrics,
                        onClick = { onToggleLyrics() },
                        isActive = showInlineLyrics,
                        shape = middleShape,
                        modifier = Modifier.size(buttonSize),
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        iconSize = iconSize,
                        textBackgroundColor = TextBackgroundColor,
                        playerBackground = playerBackground
                    )

                    PlayerQueueButton(
                        icon = when (repeatMode) {
                            Player.REPEAT_MODE_ALL -> R.drawable.repeat
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                            else -> R.drawable.repeat
                        },
                        onClick = {
                            playerConnection.player.toggleRepeatMode()
                        },
                        isActive = repeatMode != Player.REPEAT_MODE_OFF,
                        enabled = !isListenTogetherGuest,
                        shape = repeatShape,
                        modifier = Modifier.size(buttonSize),
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        iconSize = iconSize,
                        textBackgroundColor = TextBackgroundColor,
                        playerBackground = playerBackground
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .clip(CircleShape)
                            .background(textButtonColor)
                            .clickable {
                                menuState.show {
                                    PlayerMenu(
                                        mediaMetadata = mediaMetadata,
                                        navController = navController,
                                        playerBottomSheetState = playerBottomSheetState,
                                        onShowDetailsDialog = {
                                            mediaMetadata?.id?.let {
                                                bottomSheetPageState.show {
                                                    ShowMediaInfo(it)
                                                }
                                            }
                                        },
                                        onDismiss = menuState::dismiss
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.more_vert),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = iconButtonColor
                        )
                    }
                }
            } else {
                // Old design
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp, vertical = 12.dp)
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
                ) {
                    TextButton(
                            onClick = { state.expandSoft() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.queue_music),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = TextBackgroundColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(id = R.string.queue),
                                color = TextBackgroundColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                    }

                    TextButton(
                        enabled = !isListenTogetherGuest,
                        onClick = {
                            if (!isListenTogetherGuest) {
                                if (sleepTimerEnabled) {
                                    playerConnection.service.sleepTimer.clear()
                                } else {
                                    showSleepTimerDialog = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.bedtime),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = TextBackgroundColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            AnimatedContent(
                                label = "sleepTimer",
                                targetState = sleepTimerEnabled,
                            ) { enabled ->
                                if (enabled) {
                                    Text(
                                        text = makeTimeString(sleepTimerTimeLeft),
                                        color = TextBackgroundColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.basicMarquee()
                                    )
                                } else {
                                    Text(
                                        text = stringResource(id = R.string.sleep_timer),
                                        color = TextBackgroundColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.basicMarquee()
                                    )
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            onToggleLyrics()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.lyrics),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = TextBackgroundColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.lyrics),
                                color = TextBackgroundColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                    }
                }
            }

            if (showSleepTimerDialog) {
                ActionPromptDialog(
                    titleBar = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.sleep_timer),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    },
                    onDismiss = { showSleepTimerDialog = false },
                    onConfirm = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                    },
                    onCancel = {
                        showSleepTimerDialog = false
                    },
                    onReset = {
                        sleepTimerValue = 30f // Default value
                    },
                    content = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.minute,
                                    sleepTimerValue.roundToInt(),
                                    sleepTimerValue.roundToInt()
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )

                            Spacer(Modifier.height(16.dp))

                            Slider(
                                value = sleepTimerValue,
                                onValueChange = { sleepTimerValue = it },
                                valueRange = 5f..120f,
                                steps = (120 - 5) / 5 - 1,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    showSleepTimerDialog = false
                                    playerConnection.service.sleepTimer.start(-1)
                                }
                            ) {
                                Text(stringResource(R.string.end_of_song))
                            }
                        }
                    }
                )
            }
        },
    ) {
        val queueTitle by playerConnection.queueTitle.collectAsState()
        val queueWindows by playerConnection.queueWindows.collectAsState()
        val automix by playerConnection.service.automixItems.collectAsState()
        val mutableQueueWindows = remember { mutableStateListOf<Timeline.Window>() }
        val queueLength =
            remember(queueWindows) {
                queueWindows.sumOf { it.mediaItem.metadata!!.duration }
            }

        val coroutineScope = rememberCoroutineScope()

        val headerItems = 1
        val lazyListState = rememberLazyListState()
        var dragInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        val currentPlayingUid = remember(currentWindowIndex, queueWindows) {
            if (currentWindowIndex in queueWindows.indices) {
                queueWindows[currentWindowIndex].uid
            } else null
        }

        val reorderableState = rememberReorderableLazyListState(
            lazyListState = lazyListState,
            scrollThresholdPadding = WindowInsets.systemBars.add(
                WindowInsets(
                    top = ListItemHeight,
                    bottom = ListItemHeight
                )
            ).asPaddingValues()
        ) { from, to ->
            val currentDragInfo = dragInfo
            dragInfo = if (currentDragInfo == null) {
                from.index to to.index
            } else {
                currentDragInfo.first to to.index
            }

            val safeFrom = (from.index - headerItems).coerceIn(0, mutableQueueWindows.lastIndex)
            val safeTo = (to.index - headerItems).coerceIn(0, mutableQueueWindows.lastIndex)

            mutableQueueWindows.move(safeFrom, safeTo)
        }

        LaunchedEffect(reorderableState.isAnyItemDragging) {
            if (!reorderableState.isAnyItemDragging) {
                dragInfo?.let { (from, to) ->
                    val safeFrom = (from - headerItems).coerceIn(0, queueWindows.lastIndex)
                    val safeTo = (to - headerItems).coerceIn(0, queueWindows.lastIndex)

                    if (!playerConnection.player.shuffleModeEnabled) {
                        playerConnection.player.moveMediaItem(safeFrom, safeTo)
                    } else {
                        playerConnection.player.setShuffleOrder(
                            DefaultShuffleOrder(
                                queueWindows.map { it.firstPeriodIndex }
                                    .toMutableList()
                                    .move(safeFrom, safeTo)
                                    .toIntArray(),
                                System.currentTimeMillis()
                            )
                        )
                    }
                    dragInfo = null
                }
            }
        }

        LaunchedEffect(queueWindows) {
            mutableQueueWindows.apply {
                clear()
                addAll(queueWindows)
            }
        }

        LaunchedEffect(mutableQueueWindows) {
            if (currentWindowIndex != -1) {
                lazyListState.scrollToItem(currentWindowIndex)
            }
        }

        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(background),
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding =
                WindowInsets.systemBars
                    .add(
                        WindowInsets(
                            top = ListItemHeight + 8.dp,
                            bottom = ListItemHeight + 8.dp,
                        ),
                    ).asPaddingValues(),
                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection)
            ) {
                item(key = "queue_top_spacer") {
                    Spacer(
                        modifier =
                        Modifier
                            .animateContentSize()
                            .height(if (inSelectMode) 48.dp else 0.dp),
                    )
                }

                itemsIndexed(
                    items = mutableQueueWindows,
                    key = { _, item -> item.uid.hashCode() },
                ) { index, window ->
                    ReorderableItem(
                        state = reorderableState,
                        key = window.uid.hashCode(),
                    ) {
                        val currentItem by rememberUpdatedState(window)
                        val isActive = window.uid == currentPlayingUid
                        val dismissBoxState =
                            rememberSwipeToDismissBoxState(
                                positionalThreshold = { totalDistance -> totalDistance }
                            )

                        var processedDismiss by remember { mutableStateOf(false) }
                        LaunchedEffect(dismissBoxState.currentValue) {
                            val dv = dismissBoxState.currentValue
                            if (!processedDismiss && !isListenTogetherGuest && (
                                    dv == SwipeToDismissBoxValue.StartToEnd ||
                                    dv == SwipeToDismissBoxValue.EndToStart
                                )
                            ) {
                                processedDismiss = true
                                playerConnection.player.removeMediaItem(currentItem.firstPeriodIndex)
                                dismissJob?.cancel()
                                dismissJob = coroutineScope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = context.getString(
                                            R.string.removed_song_from_playlist,
                                            currentItem.mediaItem.metadata?.title,
                                        ),
                                        actionLabel = context.getString(R.string.undo),
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        playerConnection.player.addMediaItem(currentItem.mediaItem)
                                        playerConnection.player.moveMediaItem(
                                            mutableQueueWindows.size,
                                            currentItem.firstPeriodIndex,
                                        )
                                    }
                                }
                            }
                            if (dv == SwipeToDismissBoxValue.Settled) {
                                processedDismiss = false
                            }
                        }

                        val onCheckedChange: (Boolean) -> Unit = {
                            if (it) {
                                selection.add(window.mediaItem.mediaId)
                            } else {
                                selection.remove(window.mediaItem.mediaId)
                            }
                        }

                        val content: @Composable () -> Unit = {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.animateItem(),
                            ) {
                                MediaMetadataListItem(
                                    mediaMetadata = window.mediaItem.metadata!!,
                                    isSelected = false,
                                    isActive = isActive,
                                    isPlaying = isPlaying && isActive,
                                    trailingContent = {
                                        if (inSelectMode) {
                                            Checkbox(
                                                checked = window.mediaItem.mediaId in selection,
                                                onCheckedChange = onCheckedChange
                                            )
                                        } else {
                                            if (!isListenTogetherGuest) {
                                                IconButton(
                                                    onClick = {
                                                    menuState.show {
                                                        QueueMenu(
                                                            mediaMetadata = window.mediaItem.metadata!!,
                                                            navController = navController,
                                                            playerBottomSheetState = playerBottomSheetState,
                                                            onShowDetailsDialog = {
                                                                window.mediaItem.mediaId.let {
                                                                    bottomSheetPageState.show {
                                                                        ShowMediaInfo(it)
                                                                    }
                                                                }
                                                            },
                                                            onDismiss = menuState::dismiss,
                                                        )
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.more_vert),
                                                    contentDescription = null,
                                                )
                                            }
                                        }
                                            if (!locked && !isListenTogetherGuest) {
                                                IconButton(
                                                    onClick = { },
                                                    modifier = Modifier.draggableHandle()
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.drag_handle),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(background)
                                        .combinedClickable(
                                            onClick = {
                                                if (inSelectMode) {
                                                    onCheckedChange(window.mediaItem.mediaId !in selection)
                                                } else if (!isListenTogetherGuest) {
                                                    if (index == currentWindowIndex) {
                                                        if (isCasting) {
                                                            if (castIsPlaying) {
                                                                castHandler?.pause()
                                                            } else {
                                                                castHandler?.play()
                                                            }
                                                        } else {
                                                            playerConnection.togglePlayPause()
                                                        }
                                                    } else {
                                                        if (isCasting) {
                                                            val mediaId = window.mediaItem.mediaId
                                                            val navigated = castHandler?.navigateToMediaIfInQueue(mediaId) ?: false
                                                            if (!navigated) {
                                                                playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                                                            }
                                                        } else {
                                                            playerConnection.player.seekToDefaultPosition(
                                                                window.firstPeriodIndex,
                                                            )
                                                            playerConnection.player.playWhenReady = true
                                                        }
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (!inSelectMode) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    inSelectMode = true
                                                    onCheckedChange(true)
                                                }
                                            },
                                        ),
                                )
                            }
                        }

                        if (locked) {
                            content()
                        } else {
                            SwipeToDismissBox(
                                state = dismissBoxState,
                                backgroundContent = {},
                            ) {
                                content()
                            }
                        }
                    }
                }

                if (automix.isNotEmpty()) {
                    item(key = "automix_divider") {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                .animateItem(),
                        )

                        Text(
                            text = stringResource(R.string.similar_content),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }

                    itemsIndexed(
                        items = automix,
                        key = { _, it -> it.mediaId },
                    ) { index, item ->
                        Row(
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            MediaMetadataListItem(
                                mediaMetadata = item.metadata!!,
                                trailingContent = {
                                    if (!isListenTogetherGuest) {
                                        IconButton(
                                            onClick = {
                                                playerConnection.service.playNextAutomix(
                                                    item,
                                                    index,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.playlist_play),
                                                contentDescription = null,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                playerConnection.service.addToQueueAutomix(
                                                    item,
                                                    index,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.queue_music),
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            menuState.show {
                                                QueueMenu(
                                                    mediaMetadata = item.metadata!!,
                                                    navController = navController,
                                                    playerBottomSheetState = playerBottomSheetState,
                                                    onShowDetailsDialog = {
                                                        item.mediaId.let {
                                                            bottomSheetPageState.show {
                                                                ShowMediaInfo(it)
                                                            }
                                                        }
                                                    },
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    )
                                    .animateItem(),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier =
            Modifier
                .clickable (
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .background(
                    if (pureBlack) Color.Black
                    else MaterialTheme.colorScheme
                        .secondaryContainer
                        .copy(alpha = 0.90f),
                )
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                Modifier
                    .height(ListItemHeight)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = queueTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                AnimatedVisibility(
                    visible = !inSelectMode,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    Row {
                        IconButton(
                            onClick = { locked = !locked },
                            modifier = Modifier.padding(horizontal = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                contentDescription = null,
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.n_song,
                            queueWindows.size,
                            queueWindows.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        text = makeTimeString(queueLength * 1000L),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            AnimatedVisibility(
                visible = inSelectMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val selectedSongs = remember(selection.toList(), mutableQueueWindows) {
                    mutableQueueWindows.filter { it.mediaItem.mediaId in selection }
                        .mapNotNull { it.mediaItem.metadata }
                }
                val selectedItems = remember(selection.toList(), mutableQueueWindows) {
                    mutableQueueWindows.filter { it.mediaItem.mediaId in selection }
                }
                val count = selection.size
                Row(
                    modifier =
                    Modifier
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onExitSelectionMode,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = pluralStringResource(R.plurals.n_selected, count, count),
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        checked = count == mutableQueueWindows.size && count > 0,
                        onCheckedChange = {
                            if (count == mutableQueueWindows.size) {
                                selection.clear()
                            } else {
                                selection.clear()
                                mutableQueueWindows.forEach {
                                    selection.add(it.mediaItem.mediaId)
                                }
                            }
                        }
                    )
                    IconButton(
                        enabled = count > 0,
                        onClick = {
                            menuState.show {
                                SelectionMediaMetadataMenu(
                                    songSelection = selectedSongs,
                                    onDismiss = menuState::dismiss,
                                    clearAction = onExitSelectionMode,
                                    currentItems = selectedItems,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                            tint = LocalContentColor.current,
                        )
                    }
                }
            }
            if (pureBlack) {
                    HorizontalDivider()
            }
        }

        val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

        Box(
            modifier =
            Modifier
                .background(
                    if (pureBlack) Color.Black
                    else MaterialTheme.colorScheme
                        .secondaryContainer
                        .copy(alpha = 0.90f),
                )
                .fillMaxWidth()
                .height(
                    ListItemHeight +
                            WindowInsets.systemBars
                                .asPaddingValues()
                                .calculateBottomPadding(),
                )
                .align(Alignment.BottomCenter)
                .clickable {
                    state.collapseSoft()
                }
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(12.dp),
        ) {
            IconButton(
                enabled = !isListenTogetherGuest,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = {
                    coroutineScope
                        .launch {
                            lazyListState.animateScrollToItem(
                                if (playerConnection.player.shuffleModeEnabled) playerConnection.player.currentMediaItemIndex else 0,
                            )
                        }.invokeOnCompletion {
                            playerConnection.player.shuffleModeEnabled =
                                !playerConnection.player.shuffleModeEnabled
                        }
                },
            ) {
                val baseAlpha = if (shuffleModeEnabled) 1f else 0.5f
                val finalAlpha = if (!isListenTogetherGuest) baseAlpha else 0.3f
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = null,
                    modifier = Modifier.alpha(finalAlpha),
                )
            }

            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )

            IconButton(
                enabled = !isListenTogetherGuest,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = playerConnection.player::toggleRepeatMode,
            ) {
                val baseAlpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f
                val finalAlpha = if (!isListenTogetherGuest) baseAlpha else 0.3f
                Icon(
                    painter =
                    painterResource(
                        when (repeatMode) {
                            Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                            else -> throw IllegalStateException()
                        },
                    ),
                    contentDescription = null,
                    modifier = Modifier.alpha(finalAlpha),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
            Modifier
                .padding(
                    bottom =
                    ListItemHeight +
                            WindowInsets.systemBars
                                .asPaddingValues()
                                .calculateBottomPadding(),
                )
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlayerQueueButton(
    icon: Int,
    onClick: () -> Unit,
    isActive: Boolean,
    enabled: Boolean = true,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    text: String? = null,
    textButtonColor: Color,
    iconButtonColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    textBackgroundColor: Color,
    playerBackground: PlayerBackgroundStyle
) {
    val buttonModifier = Modifier
        .clip(shape)
        .clickable(enabled = enabled, onClick = onClick)

    val alphaFactor = if (enabled) 1f else 0.35f

    val appliedModifier = if (isActive) {
        modifier.then(buttonModifier.background(textButtonColor)).alpha(alphaFactor)
    } else {
        modifier.then(
            buttonModifier.border(
                width = 1.dp,
                color = textButtonColor.copy(alpha = 0.3f),
                shape = shape
            )
        ).alpha(alphaFactor)
    }

    Box(
        modifier = appliedModifier,
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = iconButtonColor.copy(alpha = if (enabled) 1f else 0.6f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
            )
        } else {
            val baseTint = if (isActive) {
                iconButtonColor
            } else {
                when (playerBackground) {
                    PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT ->
                        Color.White
                    PlayerBackgroundStyle.DEFAULT ->
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                }
            }
            val finalTint = if (enabled) baseTint else baseTint.copy(alpha = 0.5f)
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = finalTint
            )
        }
    }
}
