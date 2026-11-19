package com.metrolist.android.ui.glass.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** References only decoration primitives, never the material's descendant display list. */
internal class MaterialLayers {
    var shadow: MaterialDecoration? = null
    var highlight: MaterialDecoration? = null
    var innerShadow: MaterialDecoration? = null
}

internal class MaterialDecoration(
    val layer: GraphicsLayer,
    val input: Any,
    val offset: Offset = Offset.Zero,
    private val outline: Outline? = null,
    private val clipPath: Path? = null,
) {
    fun draw(scope: DrawScope) = with(scope) {
        val canvas = drawContext.canvas
        canvas.save()
        try {
            outline?.let { canvas.clipOutline(it, clipPath) }
            translate(offset.x, offset.y) { drawLayer(layer) }
        } finally {
            canvas.restore()
        }
    }
}
