/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.metrolist.android.ui.glass.backdrop.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import com.metrolist.android.ui.glass.backdrop.RuntimeShaderCacheImpl
import com.metrolist.android.ui.glass.backdrop.internal.ShapeProvider
import com.metrolist.android.ui.glass.backdrop.internal.MaterialDecoration
import com.metrolist.android.ui.glass.backdrop.internal.blur
import com.metrolist.android.ui.glass.backdrop.internal.clipOutline
import com.metrolist.android.ui.glass.backdrop.internal.setRuntimeShader
import com.metrolist.android.ui.glass.backdrop.isRuntimeShaderSupported
import kotlin.math.ceil

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: () -> Highlight?
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight)
    }

    override fun update(node: HighlightNode) {
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "highlight"
        properties["shapeProvider"] = shapeProvider
        properties["highlight"] = highlight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HighlightElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (highlight != other.highlight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        return result
    }
}

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: () -> Highlight?
) : DrawModifierNode, ObserverModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun ContentDrawScope.draw() {
        var decoration: MaterialDecoration? = null
        observeReads { decoration = prepareHighlight() }
        shapeProvider.materialLayers?.highlight = decoration
        drawContent()
        decoration?.draw(this)
    }

    override fun onObservedReadsChanged() = invalidateDraw()

    private fun DrawScope.prepareHighlight(): MaterialDecoration? {
        val highlight = highlight()
        if (highlight == null || highlight.width.value <= 0f) {
            return null
        }

        val highlightLayer = highlightLayer
        if (highlightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val safeSize =
                IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2
                )

            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            configurePaint(highlight)
            // Resolve density before recording: the reused recording scope must not read
            // its LayoutNodeDrawScope density while that same scope is being recorded.
            val bottomRimInset = paint.strokeWidth / 2f + highlight.blurRadius.toPx() * 3f

            highlightLayer.alpha = highlight.alpha
            highlightLayer.blendMode = highlight.style.blendMode
            highlightLayer.record(safeSize) {
                translate(1f, 1f) {
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.clipOutline(outline, clipPath)
                    if (highlight.excludeBottom) {
                        // Clip the rim's blurred tail too, without clipping the material or content.
                        canvas.clipRect(-bottomRimInset, (size.height - bottomRimInset).coerceAtLeast(0f),
                            size.width + bottomRimInset, size.height + bottomRimInset, ClipOp.Difference)
                    }
                    canvas.drawOutline(outline, paint)
                    canvas.restore()
                }
            }

            return MaterialDecoration(highlightLayer, highlight, Offset(-1f, -1f))
        }
        return null
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            highlightLayer = null
        }
        clipPath = null
        runtimeShaderCache.clear()
        shapeProvider.materialLayers?.highlight = null
    }

    private fun DrawScope.configurePaint(highlight: Highlight) {
        paint.color = highlight.style.color
        paint.strokeWidth = ceil(highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)) * 2f
        paint.blur(highlight.blurRadius.toPx())
        if (isRuntimeShaderSupported()) {
            val shader =
                with(highlight.style) {
                    createShader(
                        shape = shapeProvider.shape,
                        runtimeShaderCache = runtimeShaderCache
                    )
                }
            paint.setRuntimeShader(shader)
        }
    }
}
