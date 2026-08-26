/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * 
 * Material 3 Expressive Volume Slider
 * Based on M3 Expressive Slider specifications (Size M):
 * - Track height: 40dp
 * - Handle height: 52dp
 * - Handle width: 4dp
 * - Track corner radius: 12dp
 * - Inset icon size: 24dp
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.metrolist.music.core.R

/**
 * Material 3 Expressive Volume Slider dimensions (Size M)
 */
private object VolumeSliderDefaults {
    val TrackHeight: Dp = 40.dp
    val HandleHeight: Dp = 52.dp
    val HandleWidth: Dp = 4.dp
    val TrackCornerRadius: Dp = 12.dp
    val InsetIconSize: Dp = 24.dp
    val IconPadding: Dp = 10.dp
    val ThumbTrackGapSize: Dp = 6.dp
    val StopIndicatorRadius: Dp = 4.dp
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val interactionSource = remember { MutableInteractionSource() }

    val volumeOffIcon = painterResource(R.drawable.volume_off)
    val volumeMuteIcon = painterResource(R.drawable.volume_mute)
    val volumeDownIcon = painterResource(R.drawable.volume_down)
    val volumeUpIcon = painterResource(R.drawable.volume_up)

    val currentIcon = when {
        value <= 0f -> volumeOffIcon
        value < 0.33f -> volumeMuteIcon
        value < 0.66f -> volumeDownIcon
        else -> volumeUpIcon
    }

    val colors = SliderDefaults.colors(
        thumbColor = accentColor,
        activeTrackColor = accentColor,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    val stopIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = 0f..1f,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        track = { sliderState ->
            val iconSize = DpSize(VolumeSliderDefaults.InsetIconSize, VolumeSliderDefaults.InsetIconSize)
            val activeIconColor = colors.activeTickColor
            val inactiveIconColor = colors.inactiveTickColor

            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier
                    .height(VolumeSliderDefaults.TrackHeight)
                    .drawWithContent {
                        drawContent()
                        val yOffset = size.height / 2 - iconSize.toSize().height / 2
                        val fraction = value.coerceIn(0f, 1f)
                        val thumbGapPx = VolumeSliderDefaults.ThumbTrackGapSize.toPx()
                        val activeTrackEnd = size.width * fraction - thumbGapPx
                        val inactiveTrackStart = activeTrackEnd + thumbGapPx * 2
                        val activeTrackWidth = activeTrackEnd
                        val inactiveTrackWidth = size.width - inactiveTrackStart

                        drawVolumeIcon(
                            icon = currentIcon,
                            iconSize = iconSize,
                            iconPadding = VolumeSliderDefaults.IconPadding,
                            yOffset = yOffset,
                            activeTrackWidth = activeTrackWidth,
                            inactiveTrackStart = inactiveTrackStart,
                            inactiveTrackWidth = inactiveTrackWidth,
                            activeIconColor = activeIconColor,
                            inactiveIconColor = inactiveIconColor,
                            volumeOffIcon = volumeOffIcon
                        )
                    },
                colors = colors,
                enabled = enabled,
                thumbTrackGapSize = VolumeSliderDefaults.ThumbTrackGapSize,
                trackCornerSize = VolumeSliderDefaults.TrackCornerRadius,
                drawStopIndicator = if (value < 0.90f) { offset ->
                    drawCircle(
                        color = stopIndicatorColor,
                        radius = VolumeSliderDefaults.StopIndicatorRadius.toPx(),
                        center = offset
                    )
                } else null
            )
        }
    )
}

private fun DrawScope.drawVolumeIcon(
    icon: Painter,
    iconSize: DpSize,
    iconPadding: Dp,
    yOffset: Float,
    activeTrackWidth: Float,
    inactiveTrackStart: Float,
    inactiveTrackWidth: Float,
    activeIconColor: Color,
    inactiveIconColor: Color,
    volumeOffIcon: Painter
) {
    val iconSizePx = iconSize.toSize()
    val iconPaddingPx = iconPadding.toPx()
    val minSpaceForIcon = iconSizePx.width + iconPaddingPx * 2

    if (activeTrackWidth >= minSpaceForIcon) {
        translate(iconPaddingPx, yOffset) {
            with(icon) {
                draw(iconSizePx, colorFilter = ColorFilter.tint(activeIconColor))
            }
        }
    } else if (inactiveTrackWidth >= minSpaceForIcon) {
        translate(inactiveTrackStart + iconPaddingPx, yOffset) {
            with(volumeOffIcon) {
                draw(iconSizePx, colorFilter = ColorFilter.tint(inactiveIconColor))
            }
        }
    }
}
