package com.metrolist.android.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.ceil

internal enum class GlassScreenEdge { Top, Bottom, Left, Right }

/** Move only the material rim outside the window; the reported surface bounds stay unchanged. */
internal fun Modifier.extendGlassPastScreenEdge(edge: GlassScreenEdge): Modifier = layout { measurable, constraints ->
    val vertical = edge == GlassScreenEdge.Top || edge == GlassScreenEdge.Bottom
    if (!(if (vertical) constraints.hasBoundedHeight else constraints.hasBoundedWidth)) {
        val child = measurable.measure(constraints)
        layout(child.width, child.height) { child.place(0, 0) }
    } else {
        // Include the highlight's stroke and soft halo, using the renderer's own rim width.
        val bleed = ceil(GlassRimWidth.toPx() * 3f).toInt()
        val child = measurable.measure(constraints.copy(
            minWidth = constraints.minWidth + if (vertical) 0 else bleed,
            maxWidth = constraints.maxWidth + if (vertical) 0 else bleed,
            minHeight = constraints.minHeight + if (vertical) bleed else 0,
            maxHeight = constraints.maxHeight + if (vertical) bleed else 0,
        ))
        layout((child.width - if (vertical) 0 else bleed).coerceAtLeast(0),
            (child.height - if (vertical) bleed else 0).coerceAtLeast(0)) {
            child.place(if (edge == GlassScreenEdge.Left) -bleed else 0,
                if (edge == GlassScreenEdge.Top) -bleed else 0)
        }
    }
}
