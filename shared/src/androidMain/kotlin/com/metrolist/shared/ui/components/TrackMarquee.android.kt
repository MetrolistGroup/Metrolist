package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual fun Modifier.playbackMarquee(
    iterations: Int,
    initialDelayMillis: Int,
    velocity: Dp,
): Modifier = basicMarquee(iterations = iterations, initialDelayMillis = initialDelayMillis, velocity = velocity)

internal actual suspend fun Animatable<Float, AnimationVector1D>.animateMarquee(
    distance: Int,
    durationMillis: Int,
) {
    animateTo(distance.toFloat(), tween(durationMillis, easing = LinearEasing))
}
