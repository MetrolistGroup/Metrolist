/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

fun Modifier.fadingEdge(
    left: Dp? = null,
    top: Dp? = null,
    right: Dp? = null,
    bottom: Dp? = null,
) = graphicsLayer(alpha = 0.99f)
    .drawWithContent {
        drawContent()
        if (top != null) {
            drawRect(
                brush =
                Brush.verticalGradient(
                    colors =
                    listOf(
                        Color.Transparent,
                        Color.Black,
                    ),
                    startY = 0f,
                    endY = top.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom != null) {
            drawRect(
                brush =
                Brush.verticalGradient(
                    colors =
                    listOf(
                        Color.Black,
                        Color.Transparent,
                    ),
                    startY = size.height - bottom.toPx(),
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (left != null) {
            drawRect(
                brush =
                Brush.horizontalGradient(
                    colors =
                    listOf(
                        Color.Black,
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = left.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (right != null) {
            drawRect(
                brush =
                Brush.horizontalGradient(
                    colors =
                    listOf(
                        Color.Transparent,
                        Color.Black,
                    ),
                    startX = size.width - right.toPx(),
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

fun Modifier.fadingEdge(
    horizontal: Dp? = null,
    vertical: Dp? = null,
) = fadingEdge(
    left = horizontal,
    right = horizontal,
    top = vertical,
    bottom = vertical,
)
