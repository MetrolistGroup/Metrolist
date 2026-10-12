package com.sydforum.musicplay.ui.screens.wrapped.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

enum class ShapeType {
    Circle, Rect, Line
}

private data class AnimatedElement(
    val shapeType: ShapeType,
    val initialX: Float,
    val initialY: Float,
    val targetX: Float,
    val targetY: Float,
    val size: Float, // radius for circle, width/height for rect, length multiplier for line
    val alpha: Float,
    val duration: Int
)

@Composable
internal fun AnimatedBackground(
    elementCount: Int = 20,
    shapeTypes: List<ShapeType> = listOf(ShapeType.Circle)
) {
    val random = remember { Random(System.currentTimeMillis()) }
    val elements = remember {
        List(elementCount) {
            val shapeType = shapeTypes.random(random)
            AnimatedElement(
                shapeType = shapeType,
                initialX = random.nextFloat(),
                initialY = random.nextFloat(),
                targetX = random.nextFloat(),
                targetY = random.nextFloat(),
                size = if (shapeType == ShapeType.Circle) random.nextFloat() * 15f + 5f else random.nextFloat() * 50f + 10f,
                alpha = random.nextFloat() * 0.3f + 0.1f,
                duration = random.nextInt(4000, 10000)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "animated_bg")
    val progressAnims = elements.map {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(it.duration, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "element_progress"
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        elements.forEachIndexed { index, element ->
            val progress = progressAnims[index].value
            val currentX = element.initialX + (element.targetX - element.initialX) * progress
            val currentY = element.initialY + (element.targetY - element.initialY) * progress

            when (element.shapeType) {
                ShapeType.Circle -> {
                    drawCircle(
                        color = Color.White.copy(alpha = element.alpha),
                        radius = element.size,
                        center = Offset(currentX * size.width, currentY * size.height)
                    )
                }
                ShapeType.Rect -> {
                    drawRect(
                        color = Color.White.copy(alpha = element.alpha),
                        topLeft = Offset(currentX * size.width, currentY * size.height),
                        size = Size(element.size, element.size)
                    )
                }
                ShapeType.Line -> {
                    val endX = currentX + (element.targetX - element.initialX) * 0.1f
                    val endY = currentY + (element.targetY - element.initialY) * 0.1f
                    drawLine(
                        color = Color.White.copy(alpha = element.alpha),
                        start = Offset(currentX * size.width, currentY * size.height),
                        end = Offset(endX * size.width, endY * size.height),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }
}
