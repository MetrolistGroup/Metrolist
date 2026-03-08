package com.metrolist.shared.ui.components

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.design.expressive.theme.ExpressiveMiniPlayerShape

internal val LocalPlayerSheetContainer = staticCompositionLocalOf<PlayerSheetContainerState?> { null }

internal class PlayerSheetContainerState {
    var coordinates: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)
    var style by mutableStateOf<PlayerSheetContainerStyle?>(null)
}

internal data class PlayerSheetContainerStyle(
    val color: Color,
    val cornerRadius: Dp,
    val expressive: Boolean,
    val elevation: Dp,
    val border: BorderStroke?,
) {
    fun shape(progress: Float): Shape {
        val radius = cornerRadius * (1f - progress.coerceIn(0f, 1f))
        return if (expressive) ExpressiveMiniPlayerShape(radius) else RoundedCornerShape(radius)
    }
}

@Composable
fun PlayerSheetSurface(
    modifier: Modifier = Modifier,
    color: Color,
    cornerRadius: Dp,
    elevation: Dp,
    border: BorderStroke? = null,
    expressive: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val container = LocalPlayerSheetContainer.current
    val style =
        remember(color, cornerRadius, expressive, elevation, border) {
            PlayerSheetContainerStyle(color, cornerRadius, expressive, elevation, border)
        }
    SideEffect { container?.style = style }
    val shape = remember(style) { style.shape(0f) }
    val surfaceModifier =
        modifier.then(if (onClick != null) Modifier.focusOutline(shape) else Modifier).then(
            if (container == null) {
                Modifier
            } else {
                Modifier.onGloballyPositioned { child ->
                    container.coordinates?.takeIf { it.isAttached }?.let { parent ->
                        container.bounds =
                            Rect(
                                parent.localPositionOf(child, Offset.Zero),
                                parent.localPositionOf(child, Offset(child.size.width.toFloat(), child.size.height.toFloat())),
                            )
                    }
                }
            },
        )
    val surfaceColor = if (container == null) color else Color.Transparent
    val surfaceElevation = if (container == null) elevation else 0.dp
    val surfaceBorder = if (container == null) border else null
    if (onClick == null) {
        Surface(
            modifier = surfaceModifier,
            shape = shape,
            color = surfaceColor,
            shadowElevation = surfaceElevation,
            border = surfaceBorder,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = shape,
            color = surfaceColor,
            shadowElevation = surfaceElevation,
            border = surfaceBorder,
            content = content,
        )
    }
}

internal fun playerSheetBounds(
    collapsed: Rect,
    expanded: Size,
    progress: Float,
): Rect = lerp(collapsed, Rect(Offset.Zero, expanded), progress.coerceIn(0f, 1f))

internal class PlayerSheetClipShape(
    private val bounds: Rect,
    private val style: PlayerSheetContainerStyle,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val rect = playerSheetBounds(bounds, size, progress)
        return when (val outline = style.shape(progress).createOutline(rect.size, layoutDirection, density)) {
            is Outline.Rectangle -> {
                Outline.Rectangle(outline.rect.translate(rect.topLeft))
            }

            is Outline.Rounded -> {
                Outline.Rounded(
                    with(outline.roundRect) {
                        RoundRect(rect, topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)
                    },
                )
            }

            is Outline.Generic -> {
                Outline.Generic(outline.path.apply { translate(rect.topLeft) })
            }
        }
    }
}

internal class PlayerSheetMotion(
    scheme: MotionScheme,
) {
    private val effects = TargetBasedAnimation(scheme.fastEffectsSpec(), Float.VectorConverter, 0f, 1f)

    private fun alpha(phase: Float): Float =
        effects.getValueFromNanos((phase.coerceIn(0f, 1f) * effects.durationNanos).toLong()).coerceIn(0f, 1f)

    fun expandedAlpha(progress: Float): Float = alpha((progress - 0.5f) * 2f)

    fun collapsedAlpha(progress: Float): Float = alpha(1f - progress * 2f)
}
