package com.metrolist.android.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DividerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Restyles existing separators only; disabled areas retain their original native divider. */
@Composable
fun GlassHorizontalSeparator(
    modifier: Modifier = Modifier,
    area: GlassArea = GlassArea.Menus,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.color,
) {
    val settings = rememberGlassSettings()
    if (!settings.usesGlassSeparator(area)) {
        HorizontalDivider(modifier = modifier, thickness = thickness, color = color)
        return
    }
    val brush = glassEdgeLightBrush(settings, glassTint(settings))
    Canvas(modifier.fillMaxWidth().height(thickness)) { drawRect(brush) }
}

// Internal boundaries remain functional in GlassOnly; only the outer contour is suppressed.
internal fun GlassSettings.usesGlassSeparator(area: GlassArea): Boolean =
    enabled && mode(area) != GlassAreaMode.Disabled

/** Square's CapsuleDivider is 20 x 1 dp; draw it without consuming any action-cell width. */
@Composable
internal fun Modifier.glassActionGroupSeparators(
    settings: GlassSettings,
    area: GlassArea,
    cellWidth: Dp,
    progress: () -> Float = { 1f },
    transferred: () -> Boolean = { false },
    separatorBefore: Set<Int>? = null,
    fromPhysicalLeft: Boolean = false,
): Modifier {
    if (!settings.usesGlassSeparator(area)) return this
    // A contour gradient stretched along a hairline fades almost to nothing in
    // its middle. Keep the edge-light tint but give this short boundary one intensity.
    val color = glassEdgeLightColor(settings, glassTint(settings))
        .copy(alpha = settings.rim.coerceIn(0f, 1f) * .65f)
    return drawWithContent {
        drawContent()
        val mirrored = !fromPhysicalLeft && layoutDirection == LayoutDirection.Rtl
        if (!transferred()) {
            val height = glassActionSeparatorHeight(size.height, 20.dp.toPx())
            val alpha = progress().coerceIn(0f, 1f)
            val step = cellWidth.toPx()
            if (step > 0f && height > 0f && alpha > 0f) {
                var boundary = step
                var index = 1
                while (boundary < size.width) {
                    if (separatorBefore == null || index in separatorBefore) {
                        val width = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
                        val x = if (mirrored) size.width - boundary else boundary
                        val left = (x - width / 2f).roundToInt().toFloat()
                        val top = (size.height - height) / 2f
                        drawRoundRect(color, Offset(left, top), Size(width, height),
                            CornerRadius(width / 2f), alpha = alpha)
                    }
                    boundary += step
                    index++
                }
            }
        }
    }
}

internal fun glassActionSeparatorHeight(containerHeight: Float, referenceHeight: Float): Float =
    referenceHeight.coerceAtMost(containerHeight * .5f).coerceAtLeast(0f)
