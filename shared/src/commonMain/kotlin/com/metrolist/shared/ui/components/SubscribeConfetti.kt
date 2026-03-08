package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

@Composable
internal fun SubscribeConfetti(
    successCount: Int,
    modifier: Modifier = Modifier,
) {
    val initialCount = remember { successCount }
    val progress = remember { Animatable(1f) }
    val velocities = remember { List(32) { Offset(Random.nextFloat() * 2f - 1f, -0.5f - Random.nextFloat()) } }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
    LaunchedEffect(successCount) {
        if (successCount > initialCount && (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1000, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        val time = progress.value
        if (time >= 1f) return@Canvas
        velocities.forEachIndexed { index, velocity ->
            val position = center + Offset(velocity.x * 100.dp.toPx() * time, (velocity.y * time + time * time) * 120.dp.toPx())
            rotate(index * 23f + time * 360f, position) {
                drawRect(
                    color = colors[index % colors.size],
                    topLeft = position,
                    size = Size(4.dp.toPx(), 7.dp.toPx()),
                    alpha = 1f - time,
                )
            }
        }
    }
}
