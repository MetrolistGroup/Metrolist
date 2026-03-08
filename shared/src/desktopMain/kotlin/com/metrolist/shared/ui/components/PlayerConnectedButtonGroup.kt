@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cast
import com.metrolist.shared.generated.resources.close_miniplayer
import com.metrolist.shared.generated.resources.device_connect_title
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.fullscreen
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.open_miniplayer
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.subtitles
import com.metrolist.shared.generated.resources.video_viewer
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.currentAppLocaleList
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.text.NumberFormat
import java.util.Locale

@Composable
actual fun PlayerConnectedButtonGroup(
    onQueueClick: () -> Unit,
    onLyricsClick: (() -> Unit)?,
    isLyricsActive: Boolean,
    isQueueActive: Boolean,
    modifier: Modifier,
    compact: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onMiniplayerClick: (() -> Unit)?,
    showMiniplayer: Boolean,
    onFullscreenClick: (() -> Unit)?,
    isFullscreen: Boolean,
    onShuffleClick: (() -> Unit)?,
    isShuffled: Boolean,
    onRepeatClick: (() -> Unit)?,
    repeatMode: RepeatMode,
    onSleepTimerClick: (() -> Unit)?,
    isSleepTimerActive: Boolean,
    sleepTimerDisplay: String?,
    sleepTimerContentDescription: String?,
    onVideoClick: (() -> Unit)?,
    isVideoActive: Boolean,
    onSubtitleClick: (() -> Unit)?,
    isSubtitleActive: Boolean,
    onCastClick: (() -> Unit)?,
    isCasting: Boolean,
    onDeviceConnectClick: (() -> Unit)?,
    isDeviceConnectActive: Boolean,
    touchTarget: Dp,
    sectioned: Boolean,
) {
    val darkTheme = Settings.shared.getCurrentThemeIsDark()
    val height = if (compact) 36.dp else touchTarget
    val iconSize = if (compact) 20.dp else 24.dp
    val leftActions =
        listOfNotNull(
            PlayerControlAction(
                MetrolistIcons.AutoMirrored.Filled.QueueMusic,
                stringResource(Res.string.queue),
                onQueueClick,
                isQueueActive,
            ),
            onLyricsClick?.let {
                PlayerControlAction(
                    MetrolistIcons.Default.Lyrics,
                    stringResource(Res.string.lyrics),
                    it,
                    isLyricsActive,
                )
            },
            onShuffleClick?.let {
                PlayerControlAction(
                    MetrolistIcons.Rounded.Shuffle,
                    stringResource(Res.string.shuffle),
                    it,
                    isShuffled,
                )
            },
            onRepeatClick?.let {
                PlayerControlAction(
                    if (repeatMode ==
                        RepeatMode.ONE
                    ) {
                        MetrolistIcons.Rounded.RepeatOne
                    } else {
                        MetrolistIcons.Rounded.Repeat
                    },
                    repeatContentDescription(repeatMode),
                    it,
                    repeatMode != RepeatMode.OFF,
                )
            },
            onSleepTimerClick?.let {
                PlayerControlAction(
                    MetrolistIcons.Outlined.Bedtime,
                    sleepTimerContentDescription ?: stringResource(Res.string.sleep_timer),
                    it,
                    isSleepTimerActive,
                    text = sleepTimerDisplay,
                )
            },
        )
    val rightActions =
        listOfNotNull(
            onDeviceConnectClick?.let {
                PlayerControlAction(
                    MetrolistIcons.Outlined.Devices,
                    stringResource(Res.string.device_connect_title),
                    it,
                    isDeviceConnectActive,
                )
            },
            onCastClick
                ?.takeIf {
                    Settings.shared.isPlayerCastButtonVisible
                }?.let { PlayerControlAction(MetrolistIcons.Outlined.Cast, stringResource(Res.string.cast), it, isCasting) },
            onVideoClick?.takeIf { Settings.shared.showPlayerVideoButton }?.let {
                PlayerControlAction(
                    MetrolistIcons.Filled.Movie,
                    stringResource(Res.string.video_viewer),
                    it,
                    isVideoActive,
                )
            },
            onSubtitleClick?.let {
                PlayerControlAction(MetrolistIcons.Default.ClosedCaption, stringResource(Res.string.subtitles), it, isSubtitleActive)
            },
            onMiniplayerClick?.let {
                PlayerControlAction(
                    MetrolistIcons.Filled.PictureInPicture,
                    stringResource(if (showMiniplayer) Res.string.close_miniplayer else Res.string.open_miniplayer),
                    it,
                    showMiniplayer,
                )
            },
            onFullscreenClick?.let {
                PlayerControlAction(
                    if (isFullscreen) MetrolistIcons.Outlined.FullscreenExit else MetrolistIcons.Outlined.Fullscreen,
                    stringResource(if (isFullscreen) Res.string.exit_fullscreen else Res.string.fullscreen),
                    it,
                    isFullscreen,
                )
            },
        )
    val volumeLabel = stringResource(Res.string.volume)
    val volumeLocale = currentAppLocaleList()[0].toLanguageTag()
    val volumeFormat = remember(volumeLocale) { NumberFormat.getPercentInstance(Locale.forLanguageTag(volumeLocale)) }
    val volumeHover = remember { MutableInteractionSource() }
    val popupHover = remember { MutableInteractionSource() }
    val sliderInteraction = remember { MutableInteractionSource() }
    val hovered by volumeHover.collectIsHoveredAsState()
    val popupHovered by popupHover.collectIsHoveredAsState()
    val dragging by sliderInteraction.collectIsDraggedAsState()
    var keyboardVolume by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var previousVolume by remember { mutableStateOf(0.5f) }
    LaunchedEffect(hovered, popupHovered, dragging) {
        delay(if (hovered || popupHovered || dragging) 200 else 300)
        showVolume = hovered || popupHovered || dragging
    }
    val volumeAction =
        PlayerControlAction(
            when {
                volume > 0.66f -> MetrolistIcons.AutoMirrored.Filled.VolumeUp
                volume > 0f -> MetrolistIcons.AutoMirrored.Filled.VolumeDown
                else -> MetrolistIcons.AutoMirrored.Filled.VolumeOff
            },
            volumeLabel,
            {
                if (volume > 0f) {
                    previousVolume = volume
                    onVolumeChange(0f)
                } else {
                    onVolumeChange(previousVolume)
                }
            },
            false,
        )
    val sliderState = rememberSliderState(value = volume)
    sliderState.value = volume
    val volumeScrollModifier =
        Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
            val delta =
                event.changes
                    .firstOrNull()
                    ?.scrollDelta
                    ?.y ?: 0f
            onVolumeChange((volume - delta * 0.05f).coerceIn(0f, 1f))
            event.changes.forEach { it.consume() }
        }
    val filledTonalToggleButtonDefaults = FilledTonalToggleButtonDefaults.colors()
    val minimumTouchTarget = LocalMinimumInteractiveComponentSize.current.takeOrElse { 0.dp }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val groups =
            if (sectioned) {
                listOf(leftActions, listOf(volumeAction) + rightActions)
            } else {
                listOf(leftActions + volumeAction + rightActions)
            }
        groups.forEachIndexed { groupIndex, actions ->
            if (groupIndex > 0) Spacer(Modifier.weight(1f))
            var measuredWidth by remember(groupIndex, sectioned) { mutableIntStateOf(0) }
            var overflowWidth by remember(groupIndex, sectioned) { mutableIntStateOf(0) }
            val density = LocalDensity.current
            val spacingPx = with(density) { ButtonGroupDefaults.ConnectedSpaceBetween.toPx() }
            val itemWidthPx = with(density) { (height + 8.dp).coerceAtLeast(minimumTouchTarget).toPx() }
            val overflowWidthPx =
                if (overflowWidth > 0) {
                    overflowWidth.toFloat()
                } else {
                    with(density) { 40.dp.coerceAtLeast(minimumTouchTarget).toPx() }
                }
            val lastVisibleIndex =
                remember(measuredWidth, actions.size, itemWidthPx, spacingPx, overflowWidthPx) {
                    playerControlLastVisibleIndex(measuredWidth.toFloat(), actions.size, itemWidthPx, spacingPx, overflowWidthPx)
                }
            ButtonGroup(
                overflowIndicator = { menuState ->
                    ButtonGroupDefaults.OverflowIndicator(
                        menuState = menuState,
                        // Square, so it is a circle like the other icon-only buttons.
                        modifier = Modifier.size(height).onSizeChanged { size -> overflowWidth = size.width },
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor =
                                    if (menuState.isShowing) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                contentColor =
                                    if (menuState.isShowing) {
                                        MaterialTheme.colorScheme.onSecondary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                            ),
                    )
                },
                expandedRatio = 1f,
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .height(height)
                        .onSizeChanged { measuredWidth = it.width }
                        .then(if (sectioned) Modifier else Modifier.weight(1f, fill = false))
                        .playerControlBlend(),
            ) {
                actions.forEachIndexed { index, action ->
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val startsSegment = index == 0
                            val endsSegment = index == lastVisibleIndex
                            Box {
                                ToggleButton(
                                    checked = action.checked == true,
                                    interactionSource = interactionSource,
                                    onCheckedChange = { action.onClick() },
                                    enabled = action.enabled,
                                    shapes =
                                        when {
                                            startsSegment && endsSegment -> {
                                                ButtonGroupDefaults.connectedLeadingButtonShapes(
                                                    shape = CircleShape,
                                                    pressedShape = CircleShape,
                                                    checkedShape = CircleShape,
                                                )
                                            }

                                            startsSegment -> {
                                                ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            }

                                            endsSegment -> {
                                                ButtonGroupDefaults.connectedTrailingButtonShapes()
                                            }

                                            else -> {
                                                ButtonGroupDefaults.connectedMiddleButtonShapes()
                                            }
                                        }.let { it.copy(pressedShape = it.shape, checkedShape = it.shape) }.withStaticCorners(),
                                    colors =
                                        FilledTonalToggleButtonDefaults.colors(
                                            containerColor =
                                                filledTonalToggleButtonDefaults.containerColor.copy(
                                                    alpha = if (darkTheme) 0.5f else 1f,
                                                ),
                                        ),
                                    contentPadding = PaddingValues(horizontal = if (action.text != null) 8.dp else 0.dp),
                                    modifier =
                                        Modifier
                                            .navigationPopupSource()
                                            .height(height)
                                            .then(
                                                if (action.text !=
                                                    null
                                                ) {
                                                    Modifier.widthIn(min = height + 8.dp)
                                                } else {
                                                    Modifier.width(height + 8.dp)
                                                },
                                            ).then(
                                                if (action ===
                                                    volumeAction
                                                ) {
                                                    Modifier
                                                        .onPreviewKeyEvent { event ->
                                                            if (event.key in
                                                                listOf(Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.Spacebar)
                                                            ) {
                                                                if (event.type == KeyEventType.KeyDown) keyboardVolume = true
                                                                true
                                                            } else {
                                                                false
                                                            }
                                                        }.hoverable(
                                                            volumeHover,
                                                        ).then(volumeScrollModifier)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                ) {
                                    if (action.text !=
                                        null
                                    ) {
                                        Text(
                                            action.text,
                                            maxLines = 1,
                                            modifier =
                                                Modifier.semantics {
                                                    contentDescription =
                                                        action.contentDescription
                                                },
                                        )
                                    } else {
                                        Icon(action.icon, action.contentDescription, Modifier.size(iconSize))
                                    }
                                }
                                if (action === volumeAction) {
                                    // Fade and scale like the app's other popups instead of appearing at once.
                                    PopupVisibility(visible = showVolume || keyboardVolume) {
                                        Popup(
                                            popupPositionProvider = remember { VolumePopupPosition },
                                            onDismissRequest = {
                                                showVolume = false
                                                keyboardVolume = false
                                            },
                                            properties = PopupProperties(focusable = keyboardVolume),
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(20.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(0.75f),
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                                modifier =
                                                    Modifier
                                                        .popupMotion()
                                                        .hoverable(popupHover)
                                                        .padding(bottom = 8.dp)
                                                        .then(volumeScrollModifier),
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(12.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Text(
                                                        text = volumeFormat.format(sliderState.value.toDouble()),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        maxLines = 1,
                                                    )
                                                    VerticalSlider(
                                                        state = sliderState,
                                                        onValueChange = onVolumeChange,
                                                        topToBottom = false,
                                                        interactionSource = sliderInteraction,
                                                        modifier =
                                                            Modifier.height(200.dp).semantics {
                                                                contentDescription =
                                                                    volumeLabel
                                                            },
                                                        track = {
                                                            SliderDefaults.Track(
                                                                sliderState = it,
                                                                trackCornerSize = Dp.Unspecified,
                                                                modifier = Modifier.width(32.dp),
                                                            )
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        menuContent = { menu ->
                            val overflowIndex = (index - lastVisibleIndex - 1).coerceAtLeast(0)
                            val overflowCount = (actions.size - lastVisibleIndex - 1).coerceAtLeast(1)
                            val shape = MenuDefaults.itemShape(overflowIndex, overflowCount).shape
                            DropdownMenuItem(
                                shape = shape,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                        .background(MenuDefaults.groupStandardContainerColor, shape),
                                text = { Text(action.contentDescription) },
                                leadingIcon = { Icon(action.icon, null) },
                                trailingContent = {
                                    when {
                                        action.trailingText != null -> Text(action.trailingText)
                                        action.checked == true -> Icon(MetrolistIcons.Default.Check, null)
                                    }
                                },
                                onClick = {
                                    action.onClick()
                                    menu.dismiss()
                                },
                                enabled = action.enabled,
                            )
                        },
                    )
                }
            }
        }
    }
}

private object VolumePopupPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        IntOffset(
            (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(
                0,
                (windowSize.width - popupContentSize.width).coerceAtLeast(0),
            ),
            (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
        )
}
