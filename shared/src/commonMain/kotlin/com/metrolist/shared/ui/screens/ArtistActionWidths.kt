package com.metrolist.shared.ui.screens

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ArtistActionWidths(
    val subscribeWidth: Dp,
    val separation: Dp,
    val radioWidth: Dp,
    val shuffleWidth: Dp,
    val playWidth: Dp,
    val spacing: Dp,
    val labelRadio: Boolean,
)

internal fun Density.artistActionWidths(
    availableWidth: Int,
    subscribeLabelWidth: Int,
    radioLabelWidth: Int,
    showSubscribe: Boolean,
): ArtistActionWidths {
    // Budget whole pixels so independently rounded widths cannot trigger ButtonGroup overflow.
    val gapCount = if (showSubscribe) 4 else 2
    val spacing = minOf(4.dp.roundToPx(), availableWidth / gapCount)
    val contentWidth = availableWidth - spacing * gapCount
    val iconWidth = minOf(48.dp.roundToPx(), contentWidth / if (showSubscribe) 4 else 3)
    val minimumPlayWidth = minOf(64.dp.roundToPx(), contentWidth - iconWidth * if (showSubscribe) 3 else 2)
    val minimumPlaybackWidth = iconWidth * 2 + minimumPlayWidth
    val minimumSeparation = if (showSubscribe) 28.dp.roundToPx() else 0
    val subscribeWidth =
        if (showSubscribe) {
            minOf(
                subscribeLabelWidth + 24.dp.roundToPx(),
                (contentWidth - minimumPlaybackWidth - minimumSeparation).coerceAtLeast(iconWidth),
            )
        } else {
            0
        }
    val playbackWidth =
        contentWidth - subscribeWidth - minOf(minimumSeparation, contentWidth - subscribeWidth - minimumPlaybackWidth)
    val labeledRadioWidth = radioLabelWidth + 56.dp.roundToPx()
    val labelRadio = playbackWidth >= labeledRadioWidth * 2 + 16.dp.roundToPx() + iconWidth
    val radioWidth = if (labelRadio) labeledRadioWidth else iconWidth
    val playWidth = minOf(playbackWidth - radioWidth - iconWidth, maxOf(160.dp.roundToPx(), radioWidth + 16.dp.roundToPx()))
    val separation = if (showSubscribe) contentWidth - subscribeWidth - radioWidth - iconWidth - playWidth else 0
    return ArtistActionWidths(
        subscribeWidth = subscribeWidth.toDp(),
        separation = separation.toDp(),
        radioWidth = radioWidth.toDp(),
        shuffleWidth = iconWidth.toDp(),
        playWidth = playWidth.toDp(),
        spacing = spacing.toDp(),
        labelRadio = labelRadio,
    )
}
