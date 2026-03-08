package com.metrolist.shared.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Uses Layout so parents can query intrinsic sizes, which BoxWithConstraints does not support. */
@Composable
fun FittingText(
    text: String,
    compactText: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Layout(
        content = {
            for (shown in listOf(text, compactText)) {
                Text(
                    text = shown,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier,
        measurePolicy =
            object : MeasurePolicy {
                override fun MeasureScope.measure(
                    measurables: List<Measurable>,
                    constraints: Constraints,
                ): MeasureResult {
                    val loose = constraints.copy(minWidth = 0, minHeight = 0)
                    val full = measurables[0].measure(loose.copy(maxWidth = Constraints.Infinity))
                    // Measure both in every pass: lookahead and main passes can pick different children.
                    val compact = measurables[1].measure(loose)
                    val placeable = if (full.width <= constraints.maxWidth) full else compact
                    return layout(
                        placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                        placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight),
                    ) { placeable.placeRelative(0, 0) }
                }

                override fun IntrinsicMeasureScope.minIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ) = measurables[1].minIntrinsicWidth(height)

                override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ) = measurables[0].maxIntrinsicWidth(height)

                override fun IntrinsicMeasureScope.minIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ) = measurables[0].minIntrinsicHeight(width)

                override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ) = measurables[0].maxIntrinsicHeight(width)
            },
    )
}
