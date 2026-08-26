/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.core.R
import com.metrolist.music.constants.ThumbnailCornerRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun PlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    bars: Int = 3,
    barWidth: Dp = 4.dp,
    cornerRadius: Dp = ThumbnailCornerRadius,
) {
    val animatables =
        remember {
            List(bars) {
                Animatable(0.1f)
            }
        }

    LaunchedEffect(Unit) {
        delay(300)
        animatables.forEach { animatable ->
            launch {
                while (true) {
                    animatable.animateTo(Random.nextFloat() * 0.9f + 0.1f)
                    delay(50)
                }
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        animatables.forEach { animatable ->
            Canvas(
                modifier =
                Modifier
                    .fillMaxHeight()
                    .width(barWidth),
            ) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x = 0f, y = size.height * (1 - animatable.value)),
                    size = size.copy(height = animatable.value * size.height),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
        }
    }
}

@Composable
fun PlayingIndicatorBox(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    playWhenReady: Boolean,
    color: Color = Color.White,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            if (playWhenReady) {
                PlayingIndicator(
                    color = color,
                    modifier = Modifier.height(24.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = color,
                )
            }
        }
    }
}
