/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

data class VintagePlayerControlsText(
    val previous: String,
    val play: String,
    val pause: String,
    val next: String,
)

/**
 * Vintage player controls inspired by Navidrome/Navic player UI.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VintagePlayerControls(
    text: VintagePlayerControlsText,
    isPlaying: Boolean,
    isLoading: Boolean,
    onSkipPrevious: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    onSkipNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sideButtonColor: Color = MaterialTheme.colorScheme.onSurface,
    buttonSize: Dp = 80.dp,
    iconSize: Dp = 64.dp,
    playButtonSize: Dp = buttonSize,
    playIconSize: Dp = iconSize,
    spacing: Dp = 28.dp,
    showLoadingIndicator: Boolean = true,
    isShuffled: Boolean = false,
    onShuffleClick: (() -> Unit)? = null,
    repeatMode: RepeatMode = RepeatMode.OFF,
    onRepeatClick: (() -> Unit)? = null,
    playPauseFocusRequester: FocusRequester? = null,
) {
    val hasSecondaryControls = onShuffleClick != null || onRepeatClick != null
    val haptic = rememberMetrolistHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPressed) {
        if (!isPressed) {
            if (scale.value != 1f) {
                scale.animateTo(
                    targetValue = 1.2f,
                    animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
                )
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
            }
        } else {
            scale.animateTo(0.95f)
        }
    }

    val hapticType =
        if (!isPlaying) {
            HapticEvent.TOGGLE_ON
        } else {
            HapticEvent.TOGGLE_OFF
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier =
                Modifier
                    .then(if (hasSecondaryControls) Modifier.widthIn(max = 600.dp).fillMaxWidth() else Modifier)
                    .padding(horizontal = 8.dp),
            horizontalArrangement = if (hasSecondaryControls) Arrangement.SpaceBetween else Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val primaryButtonModifier = if (hasSecondaryControls) Modifier.weight(1f, fill = false) else Modifier

            if (onShuffleClick != null) {
                IconToggleButton(
                    checked = isShuffled,
                    onCheckedChange = { onShuffleClick() },
                    enabled = enabled,
                    colors =
                        IconButtonDefaults.iconToggleButtonColors(
                            contentColor = sideButtonColor.copy(alpha = 0.6f),
                            checkedContentColor = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.tvFocusHighlight(CircleShape),
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Rounded.Shuffle,
                        contentDescription = stringResource(Res.string.shuffle),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            IconButton(
                onClick = { onSkipPrevious?.invoke() },
                enabled = enabled && onSkipPrevious != null,
                colors = IconButtonDefaults.iconButtonColors(contentColor = sideButtonColor),
                modifier = primaryButtonModifier.size(buttonSize).tvFocusHighlight(CircleShape),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.SkipPrevious,
                    contentDescription = text.previous,
                    modifier = Modifier.size(iconSize),
                )
            }

            IconButton(
                onClick = {
                    haptic.perform(hapticType)
                    onTogglePlay()
                },
                modifier =
                    primaryButtonModifier
                        .size(playButtonSize)
                        .scale(scale.value)
                        .tvFocusHighlight(CircleShape)
                        .then(
                            if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier,
                        ),
                enabled = enabled,
                interactionSource = interactionSource,
                shapes = IconButtonDefaults.shapes(),
            ) {
                if (isLoading && showLoadingIndicator) {
                    val loadingText = stringResource(Res.string.loading)
                    LoadingIndicator(
                        modifier =
                            Modifier
                                .size(
                                    (playIconSize * 0.6f).coerceIn(20.dp, 40.dp),
                                ).semantics { contentDescription = loadingText },
                        color = sideButtonColor,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) text.pause else text.play,
                        modifier = Modifier.size((playIconSize + 8.dp).coerceAtMost(playButtonSize)),
                        tint = sideButtonColor,
                    )
                }
            }

            IconButton(
                onClick = { onSkipNext?.invoke() },
                enabled = enabled && onSkipNext != null,
                colors = IconButtonDefaults.iconButtonColors(contentColor = sideButtonColor),
                modifier = primaryButtonModifier.size(buttonSize).tvFocusHighlight(CircleShape),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.SkipNext,
                    contentDescription = text.next,
                    modifier = Modifier.size(iconSize),
                )
            }

            if (onRepeatClick != null) {
                val repeatDescription = repeatContentDescription(repeatMode)
                IconButton(
                    onClick = onRepeatClick,
                    enabled = enabled,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor =
                                if (repeatMode != RepeatMode.OFF) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    sideButtonColor.copy(alpha = 0.6f)
                                },
                        ),
                    modifier = Modifier.tvFocusHighlight(CircleShape).semantics { stateDescription = repeatDescription },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector =
                            if (repeatMode == RepeatMode.ONE) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                        contentDescription = stringResource(Res.string.repeat),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
