/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.component.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.shimmer

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    showGradient: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseModifier = modifier
        .shimmer()
        .graphicsLayer(alpha = 0.99f)

    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier = if (showGradient) {
            baseModifier.drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                    blendMode = BlendMode.DstIn,
                )
            }
        } else {
            baseModifier
        },
        content = content,
    )
}

val ShimmerTheme =
    defaultShimmerTheme.copy(
        animationSpec =
        infiniteRepeatable(
            animation =
            tween(
                durationMillis = 800,
                easing = LinearEasing,
                delayMillis = 250,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        shaderColors =
        listOf(
            Color.Unspecified.copy(alpha = 0.25f),
            Color.Unspecified.copy(alpha = 0.50f),
            Color.Unspecified.copy(alpha = 0.25f),
        ),
    )
