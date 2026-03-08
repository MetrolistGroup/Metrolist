/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

fun vintageOnButtonColor(buttonColor: Color): Color = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White

data class VintageClassicControlsText(
    val repeat: String,
    val previous: String,
    val play: String,
    val pause: String,
    val next: String,
    val like: String,
    val unlike: String,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VintageClassicControls(
    text: VintageClassicControlsText,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    isLiked: Boolean,
    onToggleRepeat: () -> Unit,
    onSkipPrevious: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    onSkipNext: (() -> Unit)?,
    onToggleLike: () -> Unit,
    contentColor: Color,
    buttonColor: Color,
    onButtonColor: Color,
    modifier: Modifier = Modifier,
    showLoadingIndicator: Boolean = true,
    buttonSize: Dp = 72.dp,
    playPauseFocusRequester: FocusRequester? = null,
) {
    val haptics = rememberMetrolistHaptics()
    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) buttonSize / 3 else buttonSize / 2,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "playPauseRoundness",
    )
    val iconColors =
        IconButtonDefaults.iconButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(alpha = 0.38f),
        )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onToggleRepeat,
                colors = iconColors,
                modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape).alpha(if (repeatMode == 0) 0.5f else 1f),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = if (repeatMode == 1) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                    contentDescription = text.repeat,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { onSkipPrevious?.invoke() },
                enabled = onSkipPrevious != null,
                colors = iconColors,
                modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.SkipPrevious,
                    contentDescription = text.previous,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val playPauseDescription = if (isPlaying) text.pause else text.play
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(buttonSize)
                    .clip(RoundedCornerShape(playPauseRoundness))
                    .background(buttonColor)
                    .tvFocusHighlight(RoundedCornerShape(playPauseRoundness), color = onButtonColor)
                    .then(
                        if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier,
                    ).semantics { contentDescription = playPauseDescription }
                    .clickable(role = Role.Button, onClick = {
                        haptics.toggle(!isPlaying)
                        onTogglePlay()
                    }),
        ) {
            if (isLoading && showLoadingIndicator) {
                val loadingText = stringResource(Res.string.loading)
                LoadingIndicator(
                    modifier = Modifier.size(36.dp).semantics { contentDescription = loadingText },
                    color = onButtonColor,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = onButtonColor,
                    modifier = Modifier.size(IconButtonDefaults.extraLargeIconSize.coerceAtMost(buttonSize)),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { onSkipNext?.invoke() },
                enabled = onSkipNext != null,
                colors = iconColors,
                modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.SkipNext,
                    contentDescription = text.next,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onToggleLike,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = if (isLiked) MaterialTheme.colorScheme.error else contentColor,
                    ),
                modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = if (isLiked) MetrolistIcons.Rounded.Favorite else MetrolistIcons.Rounded.FavoriteBorder,
                    contentDescription = if (isLiked) text.unlike else text.like,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
fun VintageClassicActionButtons(
    isLyricsVisible: Boolean,
    isLyricsExpanded: Boolean,
    shareDescription: String,
    expandDescription: String,
    collapseDescription: String,
    moreDescription: String,
    moreLyricsDescription: String,
    onToggleLyricsExpanded: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    onShareClick: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    buttonColor: Color,
    onButtonColor: Color,
    modifier: Modifier = Modifier,
    showExpandButton: Boolean = true,
) {
    val isTv = LocalIsTv.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        AnimatedContent(targetState = isLyricsVisible, label = "VintageFirstAction") { showLyrics ->
            if (showLyrics) {
                if (showExpandButton) {
                    VintageCircleButton(
                        icon = if (isLyricsExpanded) MetrolistIcons.Default.FullscreenExit else MetrolistIcons.Default.Fullscreen,
                        description = if (isLyricsExpanded) collapseDescription else expandDescription,
                        onClick = onToggleLyricsExpanded,
                        buttonColor = buttonColor,
                        onButtonColor = onButtonColor,
                    )
                }
            } else if (!isTv) {
                VintageCircleButton(
                    icon = MetrolistIcons.Default.Share,
                    description = shareDescription,
                    onClick = onShareClick,
                    buttonColor = buttonColor,
                    onButtonColor = onButtonColor,
                )
            }
        }

        AnimatedContent(targetState = isLyricsVisible, label = "VintageSecondAction") { showLyrics ->
            VintageCircleButton(
                icon = MetrolistIcons.Default.MoreHoriz,
                description = if (showLyrics) moreLyricsDescription else moreDescription,
                onClick = if (showLyrics) onShowLyricsMenu else onShowPlayerMenu,
                buttonColor = buttonColor,
                onButtonColor = onButtonColor,
            )
        }
    }
}

@Composable
private fun VintageCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    buttonColor: Color,
    onButtonColor: Color,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .navigationPopupSource()
                .clip(RoundedCornerShape(24.dp))
                .background(buttonColor)
                .tvFocusHighlight(CircleShape, color = onButtonColor)
                .semantics { contentDescription = description }
                .navigationPopupSource()
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onButtonColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

private data class VintageBottomBarSpec(
    val outerPadding: Dp,
    val itemPadding: Dp,
    val iconGap: Dp,
    val minSpacing: Dp,
    val showLabels: Boolean,
)

private val VintageBottomBarIconSize = 20.dp

@Composable
fun VintageClassicBottomBar(
    queueText: String,
    sleepTimerText: String,
    lyricsText: String,
    sleepTimerRemaining: String?,
    isSleepTimerEnabled: Boolean,
    onQueueClick: () -> Unit,
    onSleepTimerClick: (() -> Unit)?,
    sleepTimerDescription: String,
    onLyricsClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 30.dp,
    verticalPadding: Dp = 12.dp,
) {
    val textStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sleepTimerActive = isSleepTimerEnabled && sleepTimerRemaining != null
    val sleepLabel = if (sleepTimerActive) sleepTimerRemaining.orEmpty() else sleepTimerText

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val labels = listOfNotNull(queueText, sleepLabel.takeIf { onSleepTimerClick != null }, lyricsText)
        val textWidths =
            remember(labels, textStyle, density, textMeasurer) {
                labels.map { label ->
                    with(density) {
                        textMeasurer
                            .measure(text = label, style = textStyle, softWrap = false, maxLines = 1)
                            .size.width
                            .toDp()
                    }
                }
            }

        val specs =
            listOf(
                VintageBottomBarSpec(horizontalPadding, 12.dp, 6.dp, 8.dp, showLabels = true),
                VintageBottomBarSpec(12.dp, 6.dp, 4.dp, 4.dp, showLabels = true),
                VintageBottomBarSpec(16.dp, 0.dp, 0.dp, 0.dp, showLabels = false),
            )
        val spec =
            specs.firstOrNull { candidate ->
                !candidate.showLabels ||
                    run {
                        val items =
                            textWidths.fold(0.dp) { sum, textWidth ->
                                sum + candidate.itemPadding * 2 + VintageBottomBarIconSize + candidate.iconGap + textWidth
                            }
                        items + candidate.minSpacing * labels.lastIndex <= maxWidth - candidate.outerPadding * 2
                    }
            } ?: specs.last()

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spec.outerPadding, vertical = verticalPadding),
        ) {
            VintageBottomBarItem(
                icon = MetrolistIcons.AutoMirrored.Filled.QueueMusic,
                label = queueText,
                showLabel = spec.showLabels,
                spec = spec,
                contentColor = contentColor,
                onClick = onQueueClick,
            )
            if (onSleepTimerClick != null) {
                VintageBottomBarItem(
                    icon = MetrolistIcons.Default.Bedtime,
                    label = sleepLabel,
                    showLabel = spec.showLabels || sleepTimerActive,
                    spec = spec,
                    contentColor = contentColor,
                    onClick = onSleepTimerClick,
                    modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = sleepTimerDescription },
                )
            }
            VintageBottomBarItem(
                icon = MetrolistIcons.Default.Lyrics,
                label = lyricsText,
                showLabel = spec.showLabels,
                spec = spec,
                contentColor = contentColor,
                onClick = onLyricsClick,
            )
        }
    }
}

@Composable
private fun VintageBottomBarItem(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    spec: VintageBottomBarSpec,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showLabel) {
        TextButton(
            onClick = onClick,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = contentColor.copy(alpha = 0.38f),
                ),
            contentPadding =
                PaddingValues(
                    horizontal = if (spec.showLabels) spec.itemPadding else 6.dp,
                    vertical = 8.dp,
                ),
            modifier = modifier.tvFocusHighlight(CircleShape),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(VintageBottomBarIconSize))
            Spacer(modifier = Modifier.width(if (spec.showLabels) spec.iconGap else 4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            colors =
                IconButtonDefaults.iconButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = contentColor.copy(alpha = 0.38f),
                ),
            modifier = modifier.tvFocusHighlight(CircleShape),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun VintageAudioVideoModeSwitcher(
    songDescription: String,
    videoDescription: String,
    isVideoActive: Boolean,
    isResolving: Boolean,
    contentColor: Color,
    onSongClick: () -> Unit,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capsuleShape = RoundedCornerShape(50)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val selectedColor = contentColor
    val selectedIconColor = vintageOnButtonColor(contentColor)
    Row(
        modifier =
            modifier
                .width(96.dp)
                .height(36.dp)
                .clip(capsuleShape)
                .background(trackColor)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VintageAudioVideoModeItem(
            selected = !isVideoActive,
            enabled = !isResolving,
            description = songDescription,
            icon = if (isVideoActive) MetrolistIcons.Outlined.Headphones else MetrolistIcons.Filled.Headphones,
            showProgress = isResolving && isVideoActive,
            contentColor = contentColor,
            selectedContentColor = selectedIconColor,
            selectedColor = selectedColor,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = onSongClick,
        )
        VintageAudioVideoModeItem(
            selected = isVideoActive,
            enabled = !isResolving,
            description = videoDescription,
            icon = if (isVideoActive) MetrolistIcons.Filled.SmartDisplay else MetrolistIcons.Outlined.SmartDisplay,
            showProgress = isResolving && !isVideoActive,
            contentColor = contentColor,
            selectedContentColor = selectedIconColor,
            selectedColor = selectedColor,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = onVideoClick,
        )
    }
}

@Composable
private fun VintageAudioVideoModeItem(
    selected: Boolean,
    enabled: Boolean,
    description: String,
    icon: ImageVector,
    showProgress: Boolean,
    contentColor: Color,
    selectedContentColor: Color,
    selectedColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val iconTint = if (selected) selectedContentColor else contentColor
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(if (selected) selectedColor else Color.Transparent)
                .tvFocusHighlight(shape, color = iconTint)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (showProgress) {
            LoadingIndicator(modifier = Modifier.size(20.dp), color = iconTint)
        } else {
            Icon(imageVector = icon, contentDescription = description, tint = iconTint, modifier = Modifier.size(20.dp))
        }
    }
}
